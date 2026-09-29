package com.golemsdontdie;

import java.util.*;
import java.util.Deque;
import java.util.function.*;
import lombok.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import static com.golemsdontdie.RouteGeometry.span;

/**
 * One golem, simulated in world coordinates whether or not it is on screen.
 *
 * <p>A golem is a position, a heading and an intention; drawing it is a view attached only
 * while it is inside the loaded scene, so one that wanders off the edge keeps walking.
 * Positions are integer 128ths of a tile, the unit the client's local coordinates use;
 * floats would accumulate error over a golem that walks for hours.
 */
class Golem
{
	/** 128 units of position per tile, matching {@link net.runelite.api.coords.LocalPoint}. */
	static final int TILE = 128;

	/** Client cycles (20ms) per tile walked. One game tick, i.e. normal NPC walking pace. */
	private static final int CYCLES_PER_TILE = 30;

	/** Orientation units turned per cycle. 2048 is a full circle. */
	private static final int TURN_PER_CYCLE = 24;

	/** Angle units: 0 south, 512 west, 1024 north, 1536 east. */
	private static final int SOUTH = 0, WEST = 512, NORTH = 1024, EAST = 1536;

	/** Cycles a golem stands still between walks, before and after a random spread. */
	private static final int DWELL_MIN = 20;
	private static final int DWELL_SPREAD = 130;

	@Getter
	private final GolemSnapshot snapshot;

	/** Where it was made. The penned roam area is measured from here. */
	@Getter
	private final WorldPoint home;

	/**
	 * Which floor the golem is on. Fixed in the island-only plugin; mutable now because the
	 * transport network is mostly vertical — 2,262 of the 5,168 plain rows change plane.
	 * Only {@link #relocate} may change it, and never mid-step.
	 */
	@Getter
	private int plane;

	/**
	 * Seeded per golem so two made on the same tile do not walk in lockstep, and a restored
	 * golem carries on with its own gait.
	 */
	private final Random random;

	/** Position in 128ths of a tile, world-absolute. */
	@Getter
	private int fineX;
	@Getter
	private int fineY;

	@Getter
	private int orientation;
	private int targetOrientation;

	/** Remaining tiles of the current walk, as world tile pairs. */
	private final Deque<int[]> path = new ArrayDeque<>();

	/** The step in progress, interpolated between two tile centres. */
	private int stepFromX, stepFromY, stepToX, stepToY;
	private int stepElapsed;
	private boolean stepping;

	/** Cycles left standing still before choosing somewhere new to go. */
	private int dwellRemaining;

	/** Whether this golem used a path search on the current frame. */
	private boolean searched;

	/**
	 * What this golem has used recently, so it does not pace through the same door. Per
	 * golem rather than shared: two golems meeting at a ladder should both be able to climb.
	 */
	private final TransportMemory transportMemory = new TransportMemory();

	/**
	 * The route this golem is on while nobody can see it, or null if it is being simulated
	 * properly. Set when it drops out of range, cleared when it comes back; while set the
	 * golem is not stepped, and its position is whatever the route says at this tick.
	 */
	private Itinerary itinerary;

	/** The tier the golem was in last frame, so crossings can be acted on once. */
	@Getter
	private GolemTier tier = GolemTier.SCENE;

	// ---------------------------------------------------------------- watchdog

	/**
	 * Consecutive destination searches that found nowhere to go.
	 *
	 * <p>One failure is ordinary; a run of them means the golem cannot walk out of where it
	 * is, which is a death in all but name.
	 */
	private int failedSearches;

	/** Where the golem was when it last actually got somewhere, and when that was. */
	private int progressX;
	private int progressY;
	private int progressTick;

	/** Failed searches in a row before the golem is treated as stuck. */
	private static final int STUCK_SEARCHES = 12;

	/** Game ticks without meaningful movement before the golem is treated as stuck. */
	private static final int STUCK_TICKS = 500;

	/** How far the golem must get for it to count as having moved at all. */
	private static final int PROGRESS_TILES = 3;

	/**
	 * True if this golem has stopped getting anywhere and should be relocated. Two signals:
	 * failed searches catch a golem walled in on ground the map says is walkable, and no net
	 * movement catches searches that succeed but leave it shuffling between two tiles. Slow
	 * to fire, since standing still a few minutes is normal.
	 */
	boolean isStuck(int tick)
	{
		if (isDying() || inTransition())
		{
			return false;
		}

		// A golem on a route is travelling, not stuck; a passage holds it at the first
		// waypoint for the whole crossing, so the watchdog hauled golems off boats.
		if (itinerary != null && !itinerary.isFinished(tick))
		{
			return false;
		}
		if (failedSearches >= STUCK_SEARCHES)
		{
			return true;
		}

		int tileX = fineX / TILE;
		int tileY = fineY / TILE;
		if (progressTick == 0
			|| Math.abs(tileX - progressX) + Math.abs(tileY - progressY) >= PROGRESS_TILES)
		{
			progressX = tileX;
			progressY = tileY;
			progressTick = tick;
			return false;
		}
		return tick - progressTick > STUCK_TICKS;
	}

	/** When this golem may next sail, in real time; 0 if it may now. For the save. */
	long getShoreLeaveUntil()
	{
		return transportMemory.getShoreLeaveUntil();
	}

	void setShoreLeaveUntil(long until)
	{
		transportMemory.setShoreLeaveUntil(until);
	}

	/**
	 * True while the golem is drawn at a raft's helm on the open sea. Set as a crossing is
	 * followed in view, cleared when it ends or the golem moves.
	 */
	@Getter
	private boolean afloat;

	/** How far above the water a golem at the helm stands: on the deck, not in the sea. */
	private static final int DECK_HEIGHT = 30;

	/** Height to lift the golem by, in the client's height units: the deck, while afloat. */
	int deckLift()
	{
		return afloat ? DECK_HEIGHT : 0;
	}

	/** True while the golem is on a crossing: afloat, or waiting out a passage. */
	boolean isSailing(int tick)
	{
		return itinerary != null && itinerary.isVoyage() && !itinerary.isFinished(tick);
	}

	/**
	 * Where to write this golem down in a save: its landfall if it is on a crossing, since a
	 * golem restored onto water is moved to whatever land is nearest.
	 */
	WorldPoint saveTile()
	{
		// Aboard the player's ship it is at sea, and the quay it boarded from is where it goes if
		// the voyage ends without it stepping off.
		if (isAboard() && aboardFrom != null)
		{
			return aboardFrom;
		}
		return itinerary != null && itinerary.isVoyage() ? itinerary.destination() : currentTile();
	}

	/**
	 * Where a golem on a crossing has got to, or null if it is not on one. Its own tile is the
	 * port it is bound for — see {@link #saveTile} — so anything that wants the raft rather than
	 * the landfall, such as the world map, asks the crossing itself.
	 */
	WorldPoint seaPosition(int tick)
	{
		return isSailing(tick) ? itinerary.positionAt(tick) : null;
	}

	/** Clears the watchdog after the golem has been moved somewhere it can walk. */
	void noteUnstuck(int tick)
	{
		failedSearches = 0;
		progressTick = tick;
		progressX = fineX / TILE;
		progressY = fineY / TILE;
		eddyCycles = 0;
		reversed = false;
	}

	// ------------------------------------------------------- getting off a rock

	/** Cycles left spinning in place. The first and cheapest thing a grounded raft does. */
	private int eddyCycles;

	/** Whether this golem has already tried backing out of where it is. */
	private boolean reversed;

	/** Cycles a stuck raft turns on the spot before trying something with a cost. */
	private static final int EDDY_CYCLES = 100;

	/**
	 * Works a grounded raft free, cheapest first: spin on the spot; reverse along the stored
	 * waypoints; re-plan from where the boat is; snap to valid water. Only the snap is a
	 * cheat, and a grounded golem is unwatched almost by definition, since a watched one
	 * would be on live collision, not the shipped mesh.
	 *
	 * @return true if this handled it and nothing further should be done
	 */
	boolean workFree(RoamContext context, RoamPlanner planner)
	{
		int tick = context.getTick();

		if (eddyCycles > 0)
		{
			// Rung one is still running.
			eddyCycles = Math.max(0, eddyCycles - 1);
			targetOrientation = (orientation + 512) & 2047;
			return true;
		}

		// Not a route already through its transport: reversed, the walk back starts on the
		// far side of the ladder while the golem is at the top, and it would be put back.
		if (!reversed && itinerary != null && !(itinerary.transport() != null && itinerary.isFinished(tick)))
		{
			Itinerary back = itinerary.reversed(tick, tick);
			if (back != null)
			{
				reversed = true;
				itinerary = back;
				eddyCycles = EDDY_CYCLES;
				return true;
			}
		}

		// Rung three: a fresh plan from here. Only a golem nobody can see may be given a
		// route, because a roaming route is a straight line that ignores the map; handing a
		// visible golem one sent them sliding across the water. In view, a path search.
		if (tier == GolemTier.FAR)
		{
			Itinerary replanned = planner.plan(currentTile(), tick, random, transportMemory,
				context);
			if (replanned != null)
			{
				itinerary = replanned;
				noteUnstuck(tick);
				return true;
			}
		}
		else
		{
			chooseDestination(context);
			if (!path.isEmpty())
			{
				noteUnstuck(tick);
				return true;
			}
		}

		// Nothing left but rung four, which the caller owns because only it has the mesh.
		return false;
	}

	/**
	 * Cycles left of the crumble, or -1 if this golem is not dying. A retired golem plays
	 * the game's own death animation; vanishing on the spot would look like a bug.
	 */
	private int dyingCycles = -1;

	/** True once the crumble has been started. The golem stops walking and cannot path. */
	boolean isDying()
	{
		return dyingCycles >= 0;
	}

	/** True when the crumble has played out and the golem should be dropped. */
	boolean isCrumbled()
	{
		return dyingCycles == 0;
	}

	/**
	 * Begins the crumble. Idempotent, so asking twice does not restart it.
	 */
	void startDying()
	{
		if (dyingCycles < 0)
		{
			dyingCycles = GolemContent.GOLEM_DEATH_CYCLES;
			path.clear();
			stepping = false;
			walking = false;
		}
	}

	/** True while the golem is moving, which selects the walk animation over the idle. */
	@Getter
	private boolean walking;

	/**
	 * Set while the golems are celebrating something. A dancing golem stands where it is until it
	 * is over: it finishes the step it is on, then dances rather than looking for anywhere to go.
	 */
	@Getter
	@Setter
	private boolean dancing;

	/** The move it is on, or null before the first. */
	@Getter
	private GolemDance danceMove;

	/**
	 * The moves' own generator, seeded from the golem but separate from it: drawn from the golem's
	 * own, dancing would change where it walked afterwards, and two golems that danced the same
	 * number of moves would then walk in step.
	 */
	private Random danceRandom;

	/** Set when a move wants something drawn beside the golem, such as the air guitar's guitar. */
	private boolean propPending;

	/** The tick a golem dancing of its own accord stops; 0 when it is not. See GolemTrait. */
	private int partyUntil;

	/** The tick a golem waving at the player puts its hand down; 0 when it is not. */
	private int greetUntil;

	/** Set when the golem is inside the loaded scene and should be drawn. */
	@Getter
	@Setter
	private FakeGolem renderer;

	/**
	 * A name the player has given this golem, or null for none.
	 *
	 * <p>Bookkeeping for the side panel: the world calls every one of them "Golem", so a
	 * nickname on right-click gives the copy away.
	 */
	@Getter
	@Setter
	private String nickname;

	/**
	 * Starred by the player, which keeps it at the top of the sidebar. Set on the client thread and
	 * read by the sidebar's own, to draw the star.
	 */
	@Getter
	@Setter
	private volatile boolean favourite;

	/**
	 * Which golem this was to be crafted, counting from one: what an ordinal name is made of. 0 until
	 * it is numbered, which a golem is as it joins the roster. Read by the sidebar's thread for the
	 * name, so volatile like the star.
	 */
	@Getter
	@Setter
	private volatile int craftNumber;

	/**
	 * True while the golem is inside an instance — through the pew, in the Mad Angel's room.
	 * Golems are simulated in the instance's template, ordinary world coordinates, so
	 * coordinates alone cannot say who is inside; without this, golems near the cathedral
	 * were drawn inside the player's instance.
	 */
	@Getter
	@Setter
	private boolean inInstance;

	/** What a transport will make {@link #inInstance} on landing: 1, 0, or -1 for no change. */
	private int landingInstance = -1;

	private void landInInstance()
	{
		if (landingInstance >= 0)
		{
			inInstance = landingInstance == 1;
			landingInstance = -1;
		}
	}

	/** The same, for a golem out of view that has already arrived through a transport. */
	private void noteInstance(GolemTransport transport)
	{
		if (transport.entersInstance())
		{
			inInstance = true;
		}
		else if (transport.leavesInstance())
		{
			inInstance = false;
		}
	}

	/** Stable identity for the side panel and the save file, assigned on creation. */
	@Getter
	private final long id;

	/** What this golem is like, drawn from its seed and kept for life. See GolemTrait. */
	@Getter
	private int traits;

	/** What it has done since it was made. See GolemHistory. */
	@Getter
	private final GolemHistory history = new GolemHistory();

	Golem(GolemSnapshot snapshot, WorldPoint home, long seed, int startFineX, int startFineY)
	{
		this.snapshot = snapshot;
		this.home = home;
		this.plane = home.getPlane();
		this.random = new Random(seed);
		this.id = seed;
		this.traits = GolemTrait.of(seed);
		transportMemory.setTraits(traits);

		this.fineX = startFineX;
		this.fineY = startFineY;
		this.orientation = snapshot.getOrientation() & 2047;
		this.targetOrientation = this.orientation;
		this.dwellRemaining = DWELL_MIN + random.nextInt(DWELL_SPREAD);
	}

	/**
	 * Creates a golem on the centre of a tile, for restoring a save where only the tile was
	 * written down. Taking over from a live golem uses the other constructor with its exact
	 * sub-tile position; snapping to a tile centre there made the copy look teleported.
	 */
	static Golem onTile(GolemSnapshot snapshot, WorldPoint home, long seed, WorldPoint at)
	{
		return new Golem(snapshot, home, seed,
			at.getX() * TILE + TILE / 2,
			at.getY() * TILE + TILE / 2);
	}

	/**
	 * True if a scene-local position is inside the loaded scene. Replaces
	 * {@link net.runelite.api.coords.LocalPoint#isInScene()}, deprecated for assuming every
	 * scene is 104 tiles square; the world view's own size is correct for instances too.
	 */
	static boolean isInScene(WorldView wv, int localX, int localY)
	{
		return localX >= 0 && localY >= 0
			&& localX < wv.getSizeX() * TILE
			&& localY < wv.getSizeY() * TILE;
	}

	/**
	 * Stands the golem at the quayside for a while instead of casting off, while a crew is made
	 * up around it. The crossing it had planned is given up — the crew's is planned when the crew
	 * is, and a golem let go without one plans afresh — so the tally gives that one back too.
	 */
	void waitAshore(int tick, int ticks, WorldPoint quayside)
	{
		if (itinerary != null && itinerary.isVoyage())
		{
			history.unsailed();
		}
		path.clear();
		walking = false;
		relocate(quayside);
		itinerary = RoamPlanner.stayPut(quayside, tick, ticks);
	}

	/**
	 * Turns the golem to a heading at once, without turning through it: a golem on a boat is
	 * carried round with the boat rather than steering itself.
	 */
	void faceAs(int heading)
	{
		orientation = heading & 2047;
		targetOrientation = orientation;
	}

	/** Takes the crossing its crew has planned. Counted, as any other voyage is. */
	void boardCrossing(Itinerary crossing)
	{
		path.clear();
		walking = false;
		adopt(crossing);
	}

	TransportMemory getTransportMemory()
	{
		return transportMemory;
	}

	/** Puts back the traits a golem was saved with, which win over the ones its seed deals now. */
	void restoreTraits(int saved)
	{
		traits = saved;
		transportMemory.setTraits(saved);
	}

	/**
	 * Takes on a plan, and keeps the tally with it: a voyage is only ever known about here, the
	 * planner having worked it out for a golem it was handed the memory of and not the golem.
	 */
	private void adopt(Itinerary plan)
	{
		itinerary = plan;
		if (plan != null && plan.isVoyage())
		{
			history.sailed();
		}
	}

	/** The tile the golem is standing on or walking out of. */
	WorldPoint currentTile()
	{
		return new WorldPoint(fineX / TILE, fineY / TILE, plane);
	}

	/**
	 * Moves the golem between tiers, doing the one-off work each crossing needs.
	 *
	 * <p>Called every frame; most frames the tier is unchanged. Inward, the route resolves to
	 * a tile snapped onto real walkable ground — which is what makes the straight-line far
	 * routes safe, correcting them exactly when the error would show. Outward, the renderer
	 * is dropped and a route is planned.
	 *
	 * @return true if the golem changed tier
	 */
	/** The tick this golem is next looked at while it is out of view. See {@link #scheduleFarCheck}. */
	private int nextFarCheck = Integer.MIN_VALUE;

	boolean farCheckDue(int tick)
	{
		return tick >= nextFarCheck;
	}

	/**
	 * Decides when a golem out of view is next looked at. Its position is a function of
	 * time, so nothing need happen between the moments something could change: the route
	 * running out, or the player coming near. Each golem takes its own tick in the cycle,
	 * spread by its id, so a few thousand share the work.
	 */
	void scheduleFarCheck(int tick, int interval)
	{
		if (itinerary == null || itinerary.isFinished(tick))
		{
			nextFarCheck = tick;
			return;
		}
		int slot = (int) ((id ^ (id >>> 32)) & 0x7fffffff) % interval;
		int next = tick + interval - (tick + slot) % interval;
		nextFarCheck = Math.min(next, itinerary.getStartTick() + itinerary.getDuration());
	}

	/**
	 * Puts a golem out of view where its route says it is now, before its tier is judged.
	 * Looked at only every few ticks, its last position can be a few ticks old.
	 */
	void catchUpFar(int tick)
	{
		if (itinerary != null && !itinerary.isFinished(tick))
		{
			this.fineX = itinerary.fineXAt(tick);
			this.fineY = itinerary.fineYAt(tick);
			this.plane = itinerary.planeAt(tick);
		}
	}

	boolean setTier(GolemTier now, RoamContext context, RoamPlanner planner)
	{
		if (now == tier)
		{
			return false;
		}

		boolean wasFar = tier == GolemTier.FAR;
		tier = now;
		// Out of view a crossing is not drawn, so there is nothing to trace.
		if (now != GolemTier.SCENE)
		{
			tracing = null;
		}

		if (now == GolemTier.FAR)
		{
			// Walking to a shortcut or a dock is something only a golem in view does.
			queuedTransport = null;
			queuedDock = null;
			// A crossing keeps going, out of view as into it. Planning afresh from the open
			// sea found nowhere to walk, so the golem idled on the water for good.
			if (itinerary != null && itinerary.isVoyage() && !itinerary.isFinished(context.getTick()))
			{
				return true;
			}
			adopt(planner.plan(currentTile(), context.getTick(), random, transportMemory, context));
			return true;
		}

		if (wasFar)
		{
			// A crossing keeps going: it was searched over the ocean mesh, so a player who
			// sails out to meet one finds it where the route says, still sailing.
			if (itinerary != null && itinerary.isVoyage()
				&& !itinerary.isFinished(context.getTick()))
			{
				return true;
			}

			if (itinerary != null && itinerary.isFinished(context.getTick()) && itinerary.transport() != null)
			{
				noteInstance(itinerary.transport());
				history.tookTransport(itinerary.transport());
			}
			WorldPoint resolved = itinerary != null
				? itinerary.positionAt(context.getTick())
				: currentTile();
			relocate(planner.snapToMesh(resolved));
			itinerary = null;
		}
		return true;
	}

	/**
	 * Moves the golem along its route, and tidies up when the route runs out. Position comes
	 * from the route rather than being stepped toward it, so the mechanism is the same
	 * watched or not; only the ending differs.
	 */
	private void followItinerary(int cycles, RoamContext context)
	{
		int tick = context.getTick();

		// Sub-tile, interpolated along the route by distance. Reading the waypoint index
		// instead made a golem jump a whole straightened leg at a time.
		int[] at = itinerary.fineAt(tick, context.getTickFraction());

		// Face along the leg being travelled: the route's own answer, not dependent on
		// having moved since last frame.
		int facing = itinerary.facingAt(tick, context.getTickFraction());
		if (facing >= 0)
		{
			// At sea the raft's own heading, which already turns at a raft's pace. The
			// walking turn on top of it lagged the raft round every bend.
			orientation = facing;
			targetOrientation = facing;
		}
		else
		{
			int[] heading = itinerary.headingAt(tick);
			if (heading[0] != 0 || heading[1] != 0)
			{
				targetOrientation = headingFor(heading[0], heading[1]);
			}
			turnToward(cycles);
		}

		this.fineX = at[0];
		this.fineY = at[1];
		this.plane = itinerary.planeAt(tick);

		// At sea the golem holds the helm: it played its walk the whole crossing, which read
		// as a golem running on the spot in a boat. A passage from the cave dock is not sea.
		walking = false;
		afloat = itinerary.isSailed() && !itinerary.isFinished(tick);

		if (itinerary.isFinished(tick) && tier != GolemTier.FAR)
		{
			boolean wasVoyage = itinerary.isVoyage();
			itinerary = null;
			walking = false;
			afloat = false;
			dwellRemaining = DWELL_MIN + random.nextInt(DWELL_SPREAD);

			// A voyage ends at a mooring, which is water: left there the golem would stand
				// on the sea with nowhere to walk, so it is put ashore.
			if (wasVoyage)
			{
				history.cameAshore();
				transportMemory.beginShoreLeaveOnArrival(tick, tick, random);
				RoamPlanner planner = context.getPlanner();
				if (planner != null)
				{
					relocate(planner.snapToMesh(currentTile()));
				}
			}
		}
	}

	/**
	 * Advances a golem nobody can see, almost always nothing at all: the only work is
	 * replanning when a route runs out, once a minute or so per golem.
	 *
	 * @param mayPlan false once this frame's planning is spent, so a golem whose route ran
	 *                out stands where it arrived and plans on a later frame rather than a
	 *                thousand of them searching in the same one.
	 * @return true if a route was planned
	 */
	boolean advanceFar(RoamContext context, RoamPlanner planner, boolean mayPlan)
	{
		// Nobody sees a golem out of view crumble, so it is simply done. The crumble only
		// counted down in view, and a golem told to go while out of it stayed in the roster,
		// still walking, for the rest of the session.
		if (isDying())
		{
			dyingCycles = 0;
			return false;
		}

		int tick = context.getTick();

		if (itinerary != null && !itinerary.isFinished(tick))
		{
			// Read the route's position rather than stepping toward it: a binary search and
			// a lerp, so "costs nothing" means no search, no allocation and no drift. Live
			// position lets the tier check notice a golem sailing toward the player, and
			// stops one mid-ocean saving the port it left. Cached per tick.
			this.fineX = itinerary.fineXAt(tick);
			this.fineY = itinerary.fineYAt(tick);
			this.plane = itinerary.planeAt(tick);
			return false;
		}

		if (itinerary != null && itinerary.transport() != null)
		{
			noteInstance(itinerary.transport());
			history.tookTransport(itinerary.transport());
		}
		else if (itinerary != null && itinerary.isVoyage())
		{
			// Out of view, a crossing ends here rather than at a landing anyone watched: the journal
			// hears it came ashore all the same, or it would say the golem walked there.
			history.cameAshore();
		}
		WorldPoint at = itinerary == null ? currentTile() : itinerary.destination();
		this.fineX = at.getX() * TILE + TILE / 2;
		this.fineY = at.getY() * TILE + TILE / 2;
		this.plane = at.getPlane();
		if (!mayPlan)
		{
			return false;
		}

		adopt(planner.plan(at, tick, random, transportMemory, context));
		if (itinerary == null)
		{
			// Nowhere found: stand a moment before looking again. See RoamPlanner.idle.
			itinerary = RoamPlanner.idle(at, tick, random, farFailures++);
		}
		else
		{
			farFailures = 0;
		}
		return true;
	}

	/** Plans in a row that found nowhere to go; lengthens the pause before the next. */
	private int farFailures;

	/**
	 * Puts the golem down somewhere else entirely — the far end of a ladder, a dock it has
	 * just sailed to, or a valid tile after it was found somewhere it cannot stand.
	 *
	 * <p>Everything mid-flight has to go: a queued path found on the old plane would walk
	 * the golem through the new floor, and a half-finished step would interpolate it across
	 * the map. Identity is untouched.
	 */
	void relocate(WorldPoint to)
	{
		this.plane = to.getPlane();
		this.fineX = to.getX() * TILE + TILE / 2;
		this.fineY = to.getY() * TILE + TILE / 2;

		path.clear();
		gaitWalk = -1;
		gaitIdle = -1;
		gaitFinish = -1;
		motion = null;
		stepping = false;
		stepElapsed = 0;
		walking = false;
		afloat = false;

		// The route goes too: a kept itinerary would resolve against the new position next
		// frame and drag the golem straight back, making a rescue look like a rubber band.
		itinerary = null;
		queuedTransport = null;
		queuedDock = null;
		transportMemory.clearPending();
		// A crossing interrupted by being put somewhere else is not one worth recording.
		tracing = null;

		// Arriving somewhere new is a moment to stand and look around, and it keeps a golem
		// from immediately pathing back out of a doorway it just used.
		dwellRemaining = DWELL_MIN + random.nextInt(DWELL_SPREAD);
	}

	/**
	 * Advances the simulation.
	 *
	 * @param cycles client cycles (20ms each) since the last advance
	 * @param bounds where it may walk, re-derived each time so flipping the free roam
	 *               setting takes effect on the golem's next decision
	 */
	boolean advance(int cycles, RoamContext context)
	{
		if (cycles <= 0)
		{
			return false;
		}
		this.searched = false;

		if (isDying())
		{
			// Stands where it is and crumbles. No turning, no walking, no pathing.
			dyingCycles = Math.max(0, dyingCycles - cycles);
			return false;
		}

		sampleTrace(cycles);

		// Mid-obstacle: playing a climb or a squeeze, so it must not walk, turn or path
		// until it has finished and landed.
		if (advanceTransition(cycles))
		{
			return false;
		}

		// A route carries the golem only if it is a crossing. Roaming routes are straight
		// lines drawn without consulting the map, safe only while nobody can see: following
		// one in view put golems sliding through the sea and the cliffs, so one found here
		// is discarded.
		if (itinerary != null)
		{
			if (itinerary.isVoyage())
			{
				followItinerary(cycles, context);
				return false;
			}
			itinerary = null;
		}

		turnToward(cycles);

		// Movement is resolved a step at a time rather than by advancing a distance, so a
		// large `cycles` — after a stutter or a tab-out — carries the golem through several
		// whole tiles instead of teleporting it past a wall.
		int remaining = cycles;
		while (remaining > 0)
		{
			if (!stepping)
			{
				// Dancing, or waving at the player: it finishes the step it is on and drops the
				// rest of the walk. A leg is up to fifty tiles, so a golem that only stopped at the
				// end of one would still be walking when the celebration was over.
				boolean stopping = dancing || isGreeting(context.getTick());
				if (stopping && !path.isEmpty())
				{
					path.clear();
				}
				if (path.isEmpty())
				{
					walking = false;
					// And it goes nowhere else until it is done: not even a shortcut under its nose.
					if (stopping)
					{
						return searched;
					}
					dwellRemaining -= remaining;
					if (dwellRemaining > 0)
					{
						return searched;
					}
					// Hopping off a stepping stone is not a search and must not wait on the
					// budget: a golem mid-crossing has nowhere to walk, so being told to
					// try next frame left it in a river until the watchdog came.
					if (takeQueuedTransport(context) || takeQueuedDock(context) || considerTransport(context))
					{
						return searched;
					}
					// Standing where it cannot walk off, nothing fresh to take: go back the
					// way it came rather than wait for the watchdog.
					if (!context.getMemory().isKnownWalkable(fineX / TILE, fineY / TILE, plane)
						&& takeWayOut(context, true))
					{
						return searched;
					}

					if (!context.isMayPath())
					{
						// This frame's search budget is spent: stand still rather than join
						// a stampede of path searches.
						return searched;
					}
					// Overshoot is discarded rather than carried forward.
					remaining = 0;
					searched = true;
					// Somewhere it knows no way out of: back the way it came in, now and then.
					if (headHome(context))
					{
						return searched;
					}
					// Sometimes the dock nearby, otherwise anywhere. One search a frame is
					// the budget, so a dock it could not reach is left for a pause.
					boolean toDock = headForDock(context);
					if (toDock && path.isEmpty())
					{
						dwellRemaining = DWELL_MIN + random.nextInt(DWELL_SPREAD);
						return searched;
					}
					if (!toDock)
					{
						chooseDestination(context);
					}
					if (path.isEmpty())
					{
						// Nowhere to go — usually a golem boxed in by scenery. Counted,
						// because the difference between briefly hemmed in and
						// permanently trapped is only how often this happens.
						failedSearches++;
						// Twice in a row with nowhere to walk is a dead end, not bad luck —
						// but on ground it can stand on, only a fresh way out is taken.
						// Going back is how golems ping-ponged up and down the tower ladder
						// a hundred and sixty times, each shut-in floor sending them back.
						if (failedSearches >= 2 && takeWayOut(context, false))
						{
							failedSearches = 0;
							return searched;
						}
						dwellRemaining = DWELL_MIN + random.nextInt(DWELL_SPREAD);
						return searched;
					}
					failedSearches = 0;
					continue;
				}

				// Checked again at the moment of stepping, not only when the path was planned:
				// a door open when the golem chose its route can be shut by the time it gets
				// there. A climb is exempt, walking up a cliff never walkable to begin with.
				int[] next = path.peek();
				int fromX = fineX / TILE;
				int fromY = fineY / TILE;
				int stepX = next[0] - fromX;
				int stepY = next[1] - fromY;
				if (gaitWalk == -1 && Math.abs(stepX) <= 1 && Math.abs(stepY) <= 1
					&& (stepX != 0 || stepY != 0)
					&& !context.getMemory().canStep(fromX, fromY, plane, stepX, stepY))
				{
					path.clear();
					walking = false;
					dwellRemaining = DWELL_MIN;
					return searched;
				}
				beginStep(path.poll());
			}

			int left = CYCLES_PER_TILE - stepElapsed;
			if (remaining < left)
			{
				stepElapsed += remaining;
				remaining = 0;
			}
			else
			{
				remaining -= left;
				stepElapsed = CYCLES_PER_TILE;
			}

			interpolateStep();

			if (stepElapsed >= CYCLES_PER_TILE)
			{
				stepping = false;

				// A climb ends the moment its last tile is reached, before anything else
				// looks at this golem: leaving the gait on would walk it around the world
				// in a climbing pose, and rolling again would abandon the obstacle.
				if (gaitWalk != -1 && path.isEmpty())
				{
					finishTrace();
					endClimb();
					// Walked across, it has landed like any other crossing, instance change
					// included. Only animated crossings applied that, so a golem that
					// climbed the pew was drawn on the wrong side of it.
					landInInstance();
					walking = false;
					dwellRemaining = DWELL_MIN + random.nextInt(DWELL_SPREAD);
					return searched;
				}

				// A tile has just been entered, the one moment a transport roll may happen:
				// rolling per frame would tie the behaviour to framerate, and a golem
				// loitering near a ladder would eventually always take it. A shortcut it
				// walked here to use comes first, having already decided.
				if (takeQueuedTransport(context) || takeQueuedDock(context) || considerBoarding(context)
					|| considerTransport(context))
				{
					return searched;
				}

				if (path.isEmpty())
				{
					walking = false;
					dwellRemaining = DWELL_MIN + random.nextInt(DWELL_SPREAD);
					return searched;
				}
			}
		}
		return searched;
	}

	/**
	 * How far from a transport's origin a golem will consider using it, in tiles. Three, not
	 * one: only 3.2% of walkable ground is within three tiles of a transport origin at all,
	 * so insisting on an exact hit makes the network effectively invisible.
	 */
	private static final int TRANSPORT_REACH = 3;

	/**
	 * Chance of taking a transport the golem is standing on, falling off with distance.
	 * Deliberately well under certainty: a forced "use what you are next to" rule turns a
	 * ladder in a corridor into a wall. A failed roll just means it carries on.
	 */
	private static final float TRANSPORT_CHANCE = 0.3f;

	/**
	 * Rolls for a voyage, for a golem standing at a dock where the player can see it.
	 * Sailing was once planned only out of range, so boats appeared and vanished at the edge
	 * of what was loaded; with crossings cached per pair of ports and searches rationed to
	 * one a frame, a golem in view goes if the route is known and otherwise stays put.
	 *
	 * @return true if the golem set off, stopping this frame's movement
	 */
	private boolean considerBoarding(RoamContext context)
	{
		RoamPlanner planner = context.getPlanner();
		if (planner == null)
		{
			return false;
		}

		Itinerary crossing = planner.planVoyage(currentTile(), context.getTick(),
			random, transportMemory, context);
		if (crossing == null)
		{
			return false;
		}

		path.clear();
		stepping = false;
		adopt(crossing);
		return true;
	}

	/**
	 * Rolls for a transport near the tile just entered, and takes one if it wins.
	 *
	 * @return true if it used something, stopping this frame's movement
	 */
	private boolean considerTransport(RoamContext context)
	{
		TransportNetwork network = context.getTransports();
		if (network == null || network.size() == 0)
		{
			return false;
		}

		int tileX = fineX / TILE;
		int tileY = fineY / TILE;

		// True when the golem cannot walk out of where it is but the network can: halfway
		// across a river, on a stone. The next hop then happens without a roll.
		boolean stranded = !context.getMemory().isKnownWalkable(tileX, tileY, plane)
			&& (network.isStone(tileX, tileY, plane) || !walkableBeside(tileX, tileY, context));

		// Mid-crossing, the hop back is a last resort. A stone mid-river starts a hop both
		// ways, and taking whichever was found first had golems turn round on the middle
		// stone and hop back where they began.
		GolemTransport wayBack = null;

		// Scanned as a box rather than by asking for neighbours, because the index is by
		// exact origin tile and most tiles have nothing on them: 49 cheap lookups that
		// almost always all miss.
		for (int dx = -TRANSPORT_REACH; dx <= TRANSPORT_REACH; dx++)
		{
			for (int dy = -TRANSPORT_REACH; dy <= TRANSPORT_REACH; dy++)
			{
				int x = tileX + dx;
				int y = tileY + dy;
				if (!network.hasOrigin(x, y))
				{
					continue;
				}

				int distance = Math.abs(dx) + Math.abs(dy);
				if (distance > TRANSPORT_REACH)
				{
					continue;
				}

				for (GolemTransport transport : network.from(x, y))
				{
					if (transport.getFromPlane() != plane
						|| transportMemory.onCooldown(transport, context.getTick())
						|| context.crowded(transport.getToX(), transport.getToY(), transport.getToPlane())
						|| !arrivesSomewhereKnown(transport, context)
						|| !context.getAbilities().canUse(transport))
					{
						continue;
					}

					// Somewhere that cannot be walked off — mid-crossing, on a stepping
					// stone — the next hop is not a choice: rolling for it would strand
					// the golem on a rock until the watchdog came.
					if (!stranded)
					{
						// Not straight after the last one, unless looking for a way out of a
						// crowd or out of a pen. See TransportMemory.TRANSPORT_REST_TICKS.
						if (transportMemory.restingFromTransports(context.getTick())
							&& !context.crowdedRegion(tileX, tileY, plane)
							&& context.enclosedArea(tileX, tileY, plane) >= PENNED_TILES)
						{
							continue;
						}
						// Adjacent is the full chance, three tiles away a quarter of it, and
						// more or less again by where it leads. See RoamContext.appeal.
						float chance = TRANSPORT_CHANCE
							* (TRANSPORT_REACH + 1 - distance) / (TRANSPORT_REACH + 1)
							* context.appeal(tileX, tileY, plane,
								transport.getToX(), transport.getToY(), transport.getToPlane(), transportMemory)
							* context.desire(transportMemory, tileX, tileY, transport.getToX(), transport.getToY())
							* transportMemory.tasteFor(transport);
						if (random.nextFloat() >= chance)
						{
							continue;
						}
					}

					// Only ever taken from the tile it actually starts on: the radius exists
					// so a golem notices a shortcut it walks past, not so it can use one
					// from across the clearing. Letting a stranded golem take any row in
					// range had golems on the extra slippery stone hop nowhere, the row
					// having started on the far bank.
					if (tileX != transport.getFromX() || tileY != transport.getFromY())
					{
						if (stranded)
						{
							// Mid-crossing there is nowhere to walk to, so a row starting
							// elsewhere is no use: wait for one that starts here.
							continue;
						}
						if (walkTo(transport.getFromX(), transport.getFromY(), context))
						{
							queuedTransport = transport;
							return true;
						}
						continue;
					}

					if (stranded && reversesLastHop(transport, context.getTick()))
					{
						if (wayBack == null)
						{
							wayBack = transport;
						}
						continue;
					}

					take(transport, context);
					return true;
				}
			}
		}
		if (wayBack != null)
		{
			// Nothing onward from this stone: back is still better than standing in the river.
			take(wayBack, context);
			return true;
		}
		return false;
	}

	/**
	 * True if a tile beside this one is ground the golem knows it can walk. A golem on a
	 * fairy ring is not stranded the way one on a stone is. See RoamPlanner.plan.
	 */
	private boolean walkableBeside(int tileX, int tileY, RoamContext context)
	{
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				if ((dx != 0 || dy != 0) && context.getMemory().isKnownWalkable(tileX + dx, tileY + dy, plane))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Shortest wait before going back, however small the space beyond: about 15 seconds. */
	private static final int MIN_COOLDOWN_TICKS = 25;

	/** And in a pen, five seconds: there is nowhere else to go, so there is nothing to wait for. */
	private static final int PENNED_COOLDOWN_TICKS = 8;

	/**
	 * Walkable tiles around a golem below which it counts as penned in.
	 *
	 * <p>A golem that hops a stile into a paddock has nowhere to walk off its three minutes of
	 * shore leave from shortcuts, so it paced the paddock until something else moved it. Penned,
	 * it may take the stile straight back.
	 */
	static final int PENNED_TILES = 80;

	/**
	 * How long before a golem may go back the way a transport just took it, scaled by the
	 * room on the far side. The full wait suits open ground; behind the stile is a pen of a
	 * few dozen tiles, where two minutes let arrivals outnumber departures until it could
	 * not be drawn. Unwalkable landings keep the full wait, being crossings rather than
	 * small spaces.
	 */
	private int cooldownFor(GolemTransport transport, RoamContext context)
	{
		int x = transport.getToX();
		int y = transport.getToY();
		int toPlane = transport.getToPlane();
		if (!context.getMemory().isKnownWalkable(x, y, toPlane))
		{
			return TransportMemory.COOLDOWN_TICKS;
		}
		// The smaller of the two ends: a golem in a pen is as stuck as one that hopped into it,
		// and the way back out is the only way there is.
		int room = Math.min(context.enclosedArea(x, y, toPlane),
			context.enclosedArea(fineX / TILE, fineY / TILE, plane));
		float share = Math.min(1f, room / (float) RoamContext.OPEN_AREA);
		int floor = room < PENNED_TILES ? PENNED_COOLDOWN_TICKS : MIN_COOLDOWN_TICKS;
		return Math.max(floor, Math.round(TransportMemory.COOLDOWN_TICKS * share));
	}

	/**
	 * Leaves a dead end by whatever starts here, even the way the golem just came. The
	 * reverse cooldown stops a golem pacing through a door, but at the top of a ladder with
	 * no floor to walk on it was the only thing keeping the golem there, re-planning until
	 * the watchdog lifted it. A fresh way out is preferred; the way back is the fallback.
	 *
	 * @return true if a transport was taken
	 */
	private boolean takeWayOut(RoamContext context, boolean evenBack)
	{
		TransportNetwork network = context.getTransports();
		if (network == null)
		{
			return false;
		}
		GolemTransport back = null;
		for (GolemTransport transport : network.from(fineX / TILE, fineY / TILE))
		{
			if (transport.getFromPlane() != plane
				|| !arrivesSomewhereKnown(transport, context)
				|| !context.getAbilities().canUse(transport))
			{
				continue;
			}
			if (!transportMemory.onCooldown(transport, context.getTick()))
			{
				take(transport, context);
				return true;
			}
			if (back == null)
			{
				back = transport;
			}
		}
		if (back == null || !evenBack)
		{
			return false;
		}
		take(back, context);
		return true;
	}

	/**
	 * True if the far end of a transport is ground the golem could actually walk on.
	 *
	 * <p>The transport table covers the whole game; the passability map, for now, covers
	 * Wyrmscraig. A row leading somewhere unmapped would land a golem on ground the
	 * pathfinder cannot route out of. The network is allowed to be larger than the map, and
	 * this is the seam: as the mesh grows, more of the table becomes reachable.
	 */
	private boolean arrivesSomewhereKnown(GolemTransport transport, RoamContext context)
	{
		if (context.getMemory().isKnownWalkable(
			transport.getToX(), transport.getToY(), transport.getToPlane()))
		{
			return true;
		}

		// A stepping stone is blocked ground — that is why crossing it is a transport — but
		// it is somewhere you can stand, the proof being that another transport starts
		// there. So a transport origin is accepted as a destination, but only if it goes on
		// somewhere: golems hopped back and forth across the extra-slippery crossing forty
		// times each. "Goes on" means eventually reaches ground, followed hop by hop, since
		// one hop ahead passed every stone of a crossing that never reached a bank.
		return context.getTransports().leadsToGround(transport.getToX(), transport.getToY(), transport.getToPlane(),
			transport.getFromX(), transport.getFromY(), transport.getFromPlane(), TransportNetwork.CHAIN_HOPS,
			context.getAbilities()::canUse, context.getMemory()::isKnownWalkable);
	}

	/**
	 * A transport the golem is walking toward and will use on arrival. Held rather than
	 * taken at once so the golem uses it from the tile it starts on, as a player does.
	 */
	private GolemTransport queuedTransport;

	/** A dock the golem is walking up to, to consider sailing from. See dockToVisit. */
	private SailingDocks.Dock queuedDock;

	/** How long it stands between asking whether the crossing is ready, in client cycles. */
	private static final int DOCK_WAIT_CYCLES = 20;

	/**
	 * Sets off for a dock nearby, if the golem chooses to.
	 *
	 * @return true if it is on its way, with a path to the quayside
	 */
	private boolean headForDock(RoamContext context)
	{
		RoamPlanner planner = context.getPlanner();
		if (planner == null)
		{
			return false;
		}
		SailingDocks.Dock dock = planner.dockToVisit(currentTile(), transportMemory, context, random);
		if (dock == null)
		{
			return false;
		}
		// True once it has searched, found or not. See the caller.
		if (walkTo(dock.getShore().getX(), dock.getShore().getY(), context))
		{
			queuedDock = dock;
		}
		return true;
	}

	/**
	 * Rolls to sail once the golem has walked up to the dock it chose.
	 *
	 * @return true if it set sail, stopping this frame's movement
	 */
	private boolean takeQueuedDock(RoamContext context)
	{
		if (queuedDock == null)
		{
			return false;
		}
		RoamPlanner planner = context.getPlanner();
		WorldPoint here = currentTile();
		if (planner == null || !planner.atDock(here, queuedDock))
		{
			// Still on the way; or the path ran out short of it, and the plan is stale.
			if (planner == null || path.isEmpty() && !stepping)
			{
				queuedDock = null;
			}
			return false;
		}
		queuedDock = null;

		Itinerary crossing = planner.planVoyageAtDock(here, context.getTick(), random, transportMemory, context);
		if (crossing == null)
		{
			if (transportMemory.getPendingPort() >= 0)
			{
				// Decided to go and the crossing is still being worked out: wait at the dock
				// rather than walk away. How long is TransportMemory's business.
				queuedDock = planner.dockAt(here);
				dwellRemaining = DOCK_WAIT_CYCLES;
				return queuedDock != null;
			}
			// Thought better of it, or no crossing to be had: it wanders off, and may
			// come back.
			return false;
		}
		path.clear();
		stepping = false;
		adopt(crossing);
		return true;
	}

	/**
	 * Paths the golem to a nearby tile.
	 *
	 * @return true if a route was found
	 */
	private boolean walkTo(int goalX, int goalY, RoamContext context)
	{
		int tileX = fineX / TILE;
		int tileY = fineY / TILE;

		path.clear();
		path.addAll(context.getPathfinder().findPath(tileX, tileY, plane, goalX, goalY,
			new RoamBounds(context.getMemory(), plane, tileX, tileY)));
		return !path.isEmpty();
	}

	/**
	 * Heads back out the way it came in, if it is somewhere it knows no other way out of.
	 * See RoamPlanner.wayHome.
	 *
	 * @return true if it set off, or went
	 */
	private boolean headHome(RoamContext context)
	{
		RoamPlanner planner = context.getPlanner();
		if (planner == null || random.nextFloat() >= RoamPlanner.HOMEWARD_CHANCE)
		{
			return false;
		}
		int tileX = fineX / TILE;
		int tileY = fineY / TILE;
		GolemTransport home = planner.wayHome(tileX, tileY, plane, context.getTick(), transportMemory, random);
		if (home == null)
		{
			return false;
		}
		if (tileX == home.getFromX() && tileY == home.getFromY())
		{
			take(home, context);
			return true;
		}
		if (walkTo(home.getFromX(), home.getFromY(), context))
		{
			queuedTransport = home;
		}
		else
		{
			// Searched and found no way: that was this frame's search. Look again after a pause.
			dwellRemaining = DWELL_MIN + random.nextInt(DWELL_SPREAD);
		}
		return true;
	}

	/**
	 * Takes the queued transport once the golem has arrived at its starting tile.
	 *
	 * @return true if it was used, stopping this frame's movement
	 */
	private boolean takeQueuedTransport(RoamContext context)
	{
		if (queuedTransport == null)
		{
			return false;
		}

		int tileX = fineX / TILE;
		int tileY = fineY / TILE;
		if (tileX != queuedTransport.getFromX() || tileY != queuedTransport.getFromY())
		{
			// Still on the way. If the path ran out without arriving, the plan is stale:
			// drop it rather than hold out for a tile the golem is no longer heading to.
			if (path.isEmpty() && !stepping)
			{
				queuedTransport = null;
			}
			return false;
		}

		GolemTransport transport = queuedTransport;
		queuedTransport = null;

		if (transportMemory.onCooldown(transport, context.getTick())
			|| !context.getAbilities().canUse(transport))
		{
			return false;
		}

		take(transport, context);
		return true;
	}

	/** Which way, and when, the golem last used a transport on one plane. See reversesLastHop. */
	private int lastHopX;
	private int lastHopY;
	private int lastHopTick = Integer.MIN_VALUE;

	/** How long after a hop the next one is still part of the same crossing, in ticks. */
	private static final int CROSSING_TICKS = 20;

	/** True if this transport would take the golem back against the hop it has just made. */
	private boolean reversesLastHop(GolemTransport transport, int tick)
	{
		if (tick - lastHopTick > CROSSING_TICKS || transport.getFromPlane() != transport.getToPlane())
		{
			return false;
		}
		int dx = transport.getToX() - transport.getFromX();
		int dy = transport.getToY() - transport.getFromY();
		return dx * lastHopX + dy * lastHopY < 0;
	}

	/**
	 * Uses a transport.
	 *
	 * <p>Anything with an animation is played before the golem arrives, since arriving
	 * instantly would look like a teleport. Doors and teleport-like rows have no animation
	 * and land immediately, which is correct rather than a shortcut: a door animates itself.
	 */
	private void take(GolemTransport transport, RoamContext context)
	{
		if (transport.getFromPlane() == transport.getToPlane())
		{
			lastHopX = transport.getToX() - transport.getFromX();
			lastHopY = transport.getToY() - transport.getFromY();
			lastHopTick = context.getTick();
		}
		transportMemory.used(transport, context.getTick(), cooldownFor(transport, context));
		history.tookTransport(transport);
		// On landing, not now: a golem climbing into the pew is still in the cathedral.
		landingInstance = transport.entersInstance() ? 1 : transport.leavesInstance() ? 0 : -1;

		// A recording beats anything reconstructed, the silent case included: a staircase
		// plays nothing but still has a shape. A plane change may be performed from one
		// only if it barely moves, since a travelling recording would slide the golem
		// across a floor it has left.
		MotionCurve recorded = context.getKnowledge() == null ? null
			: context.getKnowledge().curveFor(transport);
		boolean samePlane = transport.getFromPlane() == transport.getToPlane();
		if (recorded != null && !recorded.isEmpty() && (samePlane || recorded.reach() < TILE))
		{
			beginTrace(transport, context);
			beginRecordedMotion(transport, recorded);
			return;
		}

		int animation = transport.animation();
		if (animation < 0 || animation == snapshot.getWalkAnimation())
		{
			relocate(transport.destination());
			landInInstance();
			return;
		}

		beginTrace(transport, context);

		// Stop where it is and play the clip. The path is dropped now rather than on
		// landing so the golem does not keep walking under its own animation.
		path.clear();
		stepping = false;
		walking = false;
		transitionAnimation = animation;
		// The whole clip set, in order — mount, cross, dismount — each phase running exactly
		// as long as its clip does; a fixed length made a cliff take the same two thirds of
		// a second as a doorway. What the player was seen doing beats what was shipped,
		// which beats the archetype's guess from menu text.
		int[] clips = context.getKnowledge() == null
			? transport.animations()
			: context.getKnowledge().clipsFor(transport);
		phaseClips = clips;
		phaseCycles = new int[clips.length];

		int total = 0;
		for (int i = 0; i < clips.length; i++)
		{
			int length = context.getModels() == null ? 0
				: context.getModels().animationCycles(clips[i]);
			phaseCycles[i] = Math.max(1, length);
			total += phaseCycles[i];
		}

		// How long the obstacle takes, in order of how much the answer is worth: a measured
		// tick count, then this row's authored duration, then the clip's own length. The
		// middle term used to be last, and it matters: a clip length is per obstacle *type*
		// — every rock climb plays 4435 — so ranking it above the row gave them all one
		// duration, where the tables author eight to fifteen ticks across the climbing-rock
		// rows and three to six across the stiles.
		int measuredTicks = context.getKnowledge() == null
			? MeasuredShortcuts.ticksFor(transport.getObjectId())
			: context.getKnowledge().ticksFor(transport);
		int authored = transport.getDuration() * CYCLES_PER_TICK;
		// Whether this is walked or performed follows from how long it takes and how far it
		// goes, not whether its clip loops: human_climbing (737) is a one-shot and yet is
		// the walk animation of a climb, while hole_squeeze (3835) loops and is not walked.
		// Duration does separate them — stepping stones read one or two ticks, log balances
		// and slopes six to eight.
		int obstacleTicks = measuredTicks > 0 ? measuredTicks
			: context.getObstacles() != null
				&& context.getObstacles().ticksFor(transport.getObjectId()) > 0
				? context.getObstacles().ticksFor(transport.getObjectId())
				: transport.getDuration();

		// Whether a clip may be stretched is still the clip's own business — a one-shot
		// restarted halfway through looks broken however long the obstacle takes.
		int stretchIndex = phaseCycles.length > 2 ? 1 : phaseCycles.length - 1;
		boolean stretchable = total == 0 || stretchIndex < 0
			|| context.getModels() == null
			|| context.getModels().loops(clips[stretchIndex]);

		int wanted;
		if (!stretchable)
		{
			wanted = total;
		}
		else
		{
			wanted = measuredTicks > 0 ? measuredTicks * CYCLES_PER_TICK
				: authored > 0 ? authored
				: total;
		}
		wanted = Math.max(TRANSITION_MIN_CYCLES, wanted);


		// Several ticks over several tiles is a walk, whatever the clip does.
		if (obstacleTicks >= WALKED_TICKS && total > 0)
		{
			if (beginClimb(transport, clips, context))
			{
				return;
			}
		}

		if (phaseCycles.length > 0 && total != wanted)
		{
			int loop = phaseCycles.length > 2 ? 1 : phaseCycles.length - 1;
			phaseCycles[loop] = Math.max(1, phaseCycles[loop] + (wanted - total));

			total = 0;
			for (int length : phaseCycles)
			{
				total += length;
			}
		}

		phase = 0;
		transitionAnimation = clips.length > 0 ? clips[0] : animation;
		transitionTotal = total;
		transitionCycles = total;
		phaseRemaining = phaseCycles.length > 0 ? phaseCycles[0] : total;
		transitionDestination = transport.destination();

		// A hop or a vault carries the golem across and is drawn doing so. A ladder or cave
		// mouth ends up elsewhere, often another plane thousands of tiles away, where
		// interpolating would slide the golem across the world.
		int span = Math.abs(transport.getToX() - transport.getFromX())
			+ Math.abs(transport.getToY() - transport.getFromY());
		transitionGlide = transport.getFromPlane() == transport.getToPlane() && span <= 8;

		// Movement is whole game ticks while the clip is whatever length it was drawn at:
		// the measured hop covers its gap in one tick under a clip of nearly one and a
		// third. The measured window wins — how long it stands still, then how long it
		// moves — which a single duration cannot express.
		int learnedDelay = context.getKnowledge() == null ? 0
			: context.getKnowledge().moveDelayFor(transport);
		int learnedSpan = context.getKnowledge() == null ? 0
			: context.getKnowledge().moveSpanFor(transport);

		if (learnedSpan > 0)
		{
			// Never longer than the clip. A chained crossing recorded a 72-cycle span for a
			// 38-cycle hop, and a window wider than the transition left the golem partway
			// through its glide when it was dropped at the far side — the
			// jump-in-place-then-teleport.
			int moveCycles = Math.max(1, Math.min(learnedSpan, total));

			// The movement has to finish with the animation; the observed delay is otherwise
			// taken as given. Clamping it to the first half of the clip broke the cathedral
			// door, which measures a 30-cycle delay against a 30-cycle clip because the
			// player plays the whole animation and *then* teleports. The double jump came
			// from the tail instead: a one-shot clip completing inside a longer transition
			// is nulled by the animation controller and set again by the renderer.
			int latest = Math.max(0, total - moveCycles);
			transitionGlideDelay = Math.min(learnedDelay, latest);
			transitionGlideCycles = moveCycles;
			transitionCycles = total;
			transitionTotal = transitionCycles;
		}
		else
		{
			int movementTicks = Math.max(1, measuredTicks > 0 ? measuredTicks
				: transport.getDuration() > 0 ? transport.getDuration() : 1);
			transitionGlideDelay = 0;
			transitionGlideCycles = Math.min(total, movementTicks * CYCLES_PER_TICK);
		}

		if (transitionGlide)
		{
			transitionFromX = fineX;
			transitionFromY = fineY;
			transitionToX = transport.getToX() * TILE + TILE / 2;
			transitionToY = transport.getToY() * TILE + TILE / 2;

			// Face the way it is going, instantly: a golem that jumps sideways because it
			// had not finished turning looks worse than one that snaps round first.
			orientation = headingFor(transitionToX - transitionFromX,
				transitionToY - transitionFromY);
			targetOrientation = orientation;
		}

		if (transport.wantsPropAnimation())
		{
			pendingProp = transport;
		}
	}

	/**
	 * A transport whose scenery should be animated, claimed once by the plugin. The golem
	 * knows nothing about models or the scene: it decides a plank should lower, and the
	 * plugin decides whether anyone can see it.
	 */
	private GolemTransport pendingProp;

	/** Takes the pending prop request, if any. Returns null once it has been claimed. */
	GolemTransport claimPendingProp()
	{
		GolemTransport claimed = pendingProp;
		pendingProp = null;
		return claimed;
	}

	/**
	 * Walks the golem through an obstacle wearing the obstacle's gait, as the game does:
	 * tile-by-tile movement with the walk and idle animations swapped for climbing ones.
	 * The loop clip is the walk cycle, so a three-tile scramble takes three ticks with
	 * nobody choosing a duration. Only for obstacles on one plane over ground the golem can
	 * be drawn crossing.
	 *
	 * @return true if the golem is now climbing, false to fall back to a played clip
	 */
	private boolean beginClimb(GolemTransport transport, int[] clips, RoamContext context)
	{
		if (transport.getFromPlane() != transport.getToPlane())
		{
			return false;
		}

		int span = span(transport.getToX() - fineX / TILE, transport.getToY() - fineY / TILE);
		if (span < 1 || span > CLIMB_MAX_TILES)
		{
			return false;
		}

		path.clear();
		int x = fineX / TILE;
		int y = fineY / TILE;
		while (x != transport.getToX() || y != transport.getToY())
		{
			x += Integer.signum(transport.getToX() - x);
			y += Integer.signum(transport.getToY() - y);
			path.add(new int[]{x, y});
		}

		// Three clips is ready, loop, merge — the idle, the walk, and a one-shot on
		// arrival. Fewer than three and the one clip is the walk.
		gaitWalk = clips.length > 2 ? clips[1] : clips[clips.length - 1];
		gaitIdle = clips.length > 2 ? clips[0] : gaitWalk;
		gaitFinish = clips.length > 2 ? clips[2] : -1;
		gaitFinishCycles = gaitFinish == -1 || context.getModels() == null ? 0
			: context.getModels().animationCycles(gaitFinish);

		transitionCycles = 0;
		transitionAnimation = -1;
		beginStep(path.poll());
		return true;
	}

	/** Clears the climbing gait, playing the step-off if the obstacle had one. */
	private void endClimb()
	{
		gaitWalk = -1;
		gaitIdle = -1;

		if (gaitFinish != -1)
		{
			transitionAnimation = gaitFinish;
			transitionTotal = Math.max(TRANSITION_MIN_CYCLES, gaitFinishCycles);
			transitionCycles = transitionTotal;
			phaseClips = new int[0];
			phaseCycles = new int[0];
			phaseRemaining = transitionTotal;
			transitionGlide = false;
			transitionDestination = null;
			gaitFinish = -1;
		}
	}

	/** Tiles a golem will climb across rather than be carried over. */
	private static final int CLIMB_MAX_TILES = 12;

	/**
	 * Ticks above which a traversal is walked rather than performed. Four: the cache's own
	 * durations cluster either side — one or two for stepping stones and broken walls, six
	 * to eight for log balances and slopes — with nothing on it.
	 */
	private static final int WALKED_TICKS = 4;

	/**
	 * Where to draw the golem relative to where it is, in fine units, and on which plane.
	 * Zero everywhere but an instance, where the golem is simulated at the room's template —
	 * the only address it keeps between visits — and the player inside sees it elsewhere.
	 */
	private int drawOffsetX;
	private int drawOffsetY;
	private int drawPlane = -1;

	/**
	 * Where this golem stands on its crew's boat, in model units: across to starboard, and forward
	 * of the helm. The helm's own berth is 0,0. A crew shares one crossing, so every golem in it is
	 * at the same place by the route's reckoning and would stand in one heap without this.
	 */
	private int deckAcross;
	private int deckAlong;

	/** Set while this golem is one of a crew rather than alone on a raft. */
	@Getter
	private boolean crewed;

	/** Takes a berth on a boat. See GolemCrew, which hands them out. */
	void board(int across, int along)
	{
		deckAcross = across;
		deckAlong = along;
		crewed = true;
	}

	/** Steps off, back to sailing alone. */
	void disembark()
	{
		deckAcross = 0;
		deckAlong = 0;
		crewed = false;
	}

	/**
	 * The way the golem is drawn facing.
	 *
	 * <p>A golem at the helm faces the way the boat is going. Everyone else looks out over the
	 * side they are standing on, which is what people on a boat do and what keeps a crew from
	 * reading as a rank of soldiers.
	 */
	int drawOrientation()
	{
		if (isAboard())
		{
			// Out over the rail, in the ship's own frame: the client turns the ship.
			return deckFacing;
		}
		if (!crewed || deckAcross == 0)
		{
			return orientation;
		}
		// Right of a golem facing south is west, which is 512 further round.
		return orientation + (deckAcross > 0 ? 512 : -512) & 2047;
	}

	// ------------------------------------------------------------------ aboard the player's ship

	/** The world view of the player's ship this golem stands on, or -1 ashore. See GolemShipmates. */
	@Getter
	private int aboardView = -1;

	/** Where on the deck, in the ship's own local units, the floor it is on, and the way it faces. */
	@Getter
	private int deckX;
	@Getter
	private int deckY;
	private int deckPlane;
	private int deckFacing;

	/** The quay it stepped aboard from, which it goes back to if the voyage ends without it. */
	@Getter
	private WorldPoint aboardFrom;

	boolean isAboard()
	{
		return aboardView >= 0;
	}

	/**
	 * Steps onto the player's ship, at a place on its deck. Whatever the golem was about is dropped:
	 * it goes where the ship goes now.
	 */
	void boardShip(int view, int x, int y, int plane, int facing)
	{
		aboardFrom = currentTile();
		// Everything a walk or a route leaves behind, cleared the way a rescue clears it.
		relocate(aboardFrom);
		aboardView = view;
		deckX = x;
		deckY = y;
		deckPlane = plane;
		deckFacing = facing;
	}

	/** Keeps the golem's own position at the ship's place in the main world, in fine units. */
	void followShip(int fineX, int fineY, int plane)
	{
		this.fineX = fineX;
		this.fineY = fineY;
		this.plane = plane;
	}

	/** Steps off the ship onto the given tile, and remembers having sailed there. */
	void leaveShip(WorldPoint at, int tick)
	{
		WorldPoint ashore = at == null ? currentTile() : at;
		// Sailed, if it came ashore somewhere else. Stepping straight back off at the quay it
		// boarded from, or being sent back there, is not a voyage for the journal.
		boolean sailed = aboardFrom == null || regionOf(ashore) != regionOf(aboardFrom);
		aboardView = -1;
		relocate(ashore);
		if (sailed)
		{
			history.cameAshore();
		}
		noteUnstuck(tick);
	}

	private static int regionOf(WorldPoint at)
	{
		return (at.getX() >> 6) << 8 | at.getY() >> 6;
	}

	/**
	 * Where this golem is drawn, in whichever world it is drawn in: the ship's own if it is aboard,
	 * the scene otherwise. Null if that world is not loaded, or the golem is outside the scene.
	 */
	LocalPoint drawnPoint(Client client)
	{
		if (isAboard())
		{
			WorldView ship = client.getWorldView(aboardView);
			return ship == null ? null : new LocalPoint(deckX, deckY, ship);
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return null;
		}
		int localX = getDrawFineX() - wv.getBaseX() * TILE;
		int localY = getDrawFineY() - wv.getBaseY() * TILE;
		return isInScene(wv, localX, localY) ? new LocalPoint(localX, localY, wv) : null;
	}

	/** The floor this golem is drawn on, in the world it is drawn in. */
	int drawnLevel()
	{
		return isAboard() ? deckPlane : getDrawPlane();
	}

	void setDrawOffset(int x, int y, int plane)
	{
		drawOffsetX = x;
		drawOffsetY = y;
		drawPlane = plane;
	}

	int getDrawFineX()
	{
		return fineX + drawOffsetX + deckOffset(true);
	}

	int getDrawFineY()
	{
		return fineY + drawOffsetY + deckOffset(false);
	}

	/**
	 * How far a berth lies from the boat's helm, along the world's axes. Model units and the
	 * golem's own hundred-and-twenty-eighths of a tile are the same size, so the deck's numbers
	 * need no scaling — only turning, to wherever the boat is pointing.
	 */
	private int deckOffset(boolean acrossX)
	{
		if (!crewed || deckAcross == 0 && deckAlong == 0)
		{
			return 0;
		}
		double facing = orientation * Math.PI / 1024;
		double sin = Math.sin(facing);
		double cos = Math.cos(facing);
		// Forward is where the boat points; starboard is a quarter turn right of it.
		return (int) Math.round(acrossX
			? deckAlong * -sin + deckAcross * -cos
			: deckAlong * -cos + deckAcross * sin);
	}

	int getDrawPlane()
	{
		return drawPlane >= 0 ? drawPlane : plane;
	}
	private void beginStep(int[] tile)
	{
		stepFromX = fineX;
		stepFromY = fineY;
		stepToX = tile[0] * TILE + TILE / 2;
		stepToY = tile[1] * TILE + TILE / 2;
		stepElapsed = 0;
		stepping = true;
		walking = true;
		targetOrientation = headingFor(stepToX - stepFromX, stepToY - stepFromY);
	}

	private void interpolateStep()
	{
		fineX = stepFromX + (stepToX - stepFromX) * stepElapsed / CYCLES_PER_TILE;
		fineY = stepFromY + (stepToY - stepFromY) * stepElapsed / CYCLES_PER_TILE;
	}

	/**
	 * Picks somewhere new to walk. The roam bounds are built here rather than passed in:
	 * they describe a window around where the golem is, so building one per golem per frame
	 * meant hundreds of throwaway objects for the few golems actually choosing anything.
	 */
	private void chooseDestination(RoamContext context)
	{
		int tileX = fineX / TILE;
		int tileY = fineY / TILE;
		path.clear();
		path.addAll(context.getPathfinder().wanderPath(tileX, tileY, plane,
			new RoamBounds(context.getMemory(), plane, tileX, tileY,
				(x, y) -> context.crowded(x, y, plane)), random));
	}

	/** Eases the heading toward where the golem is walking, the short way round. */
	private void turnToward(int cycles)
	{
		if (orientation == targetOrientation)
		{
			return;
		}
		int delta = ((targetOrientation - orientation + 1024) & 2047) - 1024;
		int maxTurn = TURN_PER_CYCLE * cycles;
		if (Math.abs(delta) <= maxTurn)
		{
			orientation = targetOrientation;
		}
		else
		{
			orientation = (orientation + Integer.signum(delta) * maxTurn) & 2047;
		}
	}

	/**
	 * The orientation for a direction of travel, to the nearest angle rather than the
	 * nearest cardinal. Picking the nearest cardinal was exact while the pathfinder only
	 * stepped north, south, east and west; once diagonals were added a golem walking
	 * north-east faced north and crabbed sideways.
	 *
	 * <p>Angles run 0 south, 512 west, 1024 north, 1536 east, so north is the zero of the
	 * arc-tangent and east a quarter turn on.
	 */
	private static int headingFor(int dx, int dy)
	{
		if (dx == 0 && dy == 0)
		{
			return NORTH;
		}
		return (int) (1024 + Math.round(Math.atan2(dx, dy) / Math.PI * 1024)) & 2047;
	}

	/**
	 * Performs a traversal exactly as it was observed. No delay, span, stretch or
	 * walked-versus-glided decision is taken: the recording holds the position at every
	 * other cycle and the animation at every change, and both are read back.
	 */
	private void beginRecordedMotion(GolemTransport transport, MotionCurve recorded)
	{
		path.clear();
		stepping = false;
		walking = false;
		gaitWalk = -1;
		gaitIdle = -1;
		gaitFinish = -1;

		motion = recorded;
		transitionTotal = Math.max(1, recorded.cycles());
		transitionCycles = transitionTotal;
		transitionGlide = false;
		transitionGlideDelay = 0;
		transitionGlideCycles = 0;
		phaseClips = new int[0];
		phaseCycles = new int[0];
		phase = 0;
		transitionDestination = transport.destination();
		int opening = recorded.animationAt(0);
		transitionAnimation = opening != -1 ? opening : snapshot.getIdlePoseAnimation();

		// The axis and the distance, both in fine units from exactly where the golem is.
		// These used to come from its *tile* while the offsets were added to its exact
		// position, so the fraction of a tile it stood off centre was added to the distance
		// and the golem slid past the far side before the arrival snapped it back.
		motionFrame = recorded.frameAt(0);
		motionFromX = fineX;
		motionFromY = fineY;

		float dx = transport.getToX() * TILE + TILE / 2f - fineX;
		float dy = transport.getToY() * TILE + TILE / 2f - fineY;
		float needed = (float) Math.sqrt(dx * dx + dy * dy);
		motionAxisX = needed < 1f ? 0f : dx / needed;
		motionAxisY = needed < 1f ? 1f : dy / needed;

		// The recording covered the player's distance; this golem may have a different one.
		// Scale the forward part so it arrives exactly.
		int reach = recorded.reach();
		motionScale = reach <= TILE / 2 || needed < 1f ? 1f : needed / reach;

		// Face the way it is about to go, immediately: a golem still turning as a recording
		// starts performs the whole thing at the wrong angle. Turned by the player's facing
		// relative to their travel — on the rockslide they face the rock and move away from
		// it, and a golem facing its travel climbed off the cliff. Only where the traversal
		// goes somewhere; a ladder has no direction to be relative to.
		int heading = headingFor(Math.round(dx), Math.round(dy));
		targetOrientation = recorded.hasFacing() && needed >= TILE / 2f
			? (heading + recorded.facing()) & 2047
			: heading;
		orientation = targetOrientation;
	}

	/** Where the recording started from, in fine units. */
	private int motionFromX;
	private int motionFromY;

	/** Forward scaling, so a recording made from one tile away still lands correctly. */
	private float motionScale = 1f;

	/**
	 * Which frame the recording says the animation should be on, or -1. Read by the renderer
	 * so animation and movement share one clock; without it the clip advances at the draw
	 * rate while the position advances on cycle count, and the drift looks like skipping.
	 */
	@Getter
	private int motionFrame = -1;

	/**
	 * Starts this golem dancing where it stands, for its own reasons rather than a celebration:
	 * the life of the party, with a crowd around it. No fireworks — those are for the occasions.
	 */
	void startParty(int untilTick)
	{
		partyUntil = untilTick;
		nextDanceMove();
	}

	boolean isPartying(int tick)
	{
		return tick < partyUntil && partyUntil - tick <= MOST_MOMENT_TICKS;
	}

	/**
	 * Longest a wave, a look or a dance may still have to run, in ticks. Every one is a few seconds;
	 * a deadline further off than this was set against a clock that has since gone backwards, and
	 * believing it would hold the golem still for as long as the old clock had been running.
	 */
	private static final int MOST_MOMENT_TICKS = 30;

	/**
	 * Forgets anything the golem was in the middle of for the player's benefit — a wave, a look, a
	 * dance of its own — on a log out or a world hop, where the moment is over whatever the clock
	 * says.
	 */
	void forgetMoments()
	{
		greetUntil = 0;
		partyUntil = 0;
		wavedAtGolem = -1;
	}

	/** Gives up waiting at a quay for a crew, and plans for itself again. */
	void stopWaiting()
	{
		relocate(currentTile());
	}

	/**
	 * Turns the golem to face something and waves at it, for as long as the wave takes.
	 *
	 * @param dx how far east the thing being waved at is
	 * @param dy how far north it is
	 */
	void greet(int untilTick, int dx, int dy)
	{
		attend(untilTick, dx, dy, GolemContent.ANIM_EMOTE_WAVE);
	}

	/** The tick this golem last waved at another golem, or -1 if it never has. */
	private int wavedAtGolem = -1;

	/**
	 * Whether it has been long enough since this golem last waved at another. Also true if the
	 * tick has gone backwards, which is the counter starting again after a world hop.
	 */
	boolean mayWaveAtGolem(int tick, int cooldown)
	{
		return wavedAtGolem < 0 || tick < wavedAtGolem || tick - wavedAtGolem >= cooldown;
	}

	/** Waves at another golem where it stands, and remembers having done so. */
	void waveAtGolem(int untilTick, int dx, int dy, int tick)
	{
		greet(untilTick, dx, dy);
		wavedAtGolem = tick;
	}

	/** Stops and looks at something, playing nothing: a golem that finds the player interesting. */
	void watch(int untilTick, int dx, int dy)
	{
		attend(untilTick, dx, dy, -1);
	}

	/** Stops, looks, and copies what it saw. The animation is the player's own. */
	void mimic(int untilTick, int dx, int dy, int animation)
	{
		attend(untilTick, dx, dy, animation);
	}

	/**
	 * Turns to face something and holds there, playing a clip or nothing at all.
	 *
	 * @param animation what to play while it does, or -1 to stand and look
	 */
	private void attend(int untilTick, int dx, int dy, int animation)
	{
		greetUntil = untilTick;
		greetAnimation = animation;
		// headingFor, because an orientation worked out by hand faced the golem the other way.
		targetOrientation = headingFor(dx, dy);
	}

	/** What the golem plays while it is attending to something; -1 to stand and look. */
	private int greetAnimation = GolemContent.ANIM_EMOTE_WAVE;

	boolean isGreeting(int tick)
	{
		return tick < greetUntil && greetUntil - tick <= MOST_MOMENT_TICKS;
	}

	void nextDanceMove()
	{
		if (danceRandom == null)
		{
			danceRandom = new Random(id * 0x2545F4914F6CDD1DL);
		}
		danceMove = GolemDance.random(danceRandom);
		propPending = danceMove.getSpotanim() != -1;
	}

	/**
	 * Whether the move just started needs something drawn beside the golem, clearing the ask.
	 * Called once per move by whoever can draw it; see GolemsDontDiePlugin.spawnDanceProp.
	 */
	boolean takeDanceProp()
	{
		boolean wanted = propPending;
		propPending = false;
		return wanted;
	}

	/**
	 * The tick as the last frame saw it, so the pose can be asked about without one: the renderer
	 * asks what to play from its own callback, which has no tick to hand.
	 */
	private int tickNow;

	void setTickNow(int tick)
	{
		tickNow = tick;
	}

	/** The animation this golem should be playing right now. -1 if it has none. */
	int currentPoseAnimation()
	{
		if (isDying())
		{
			return GolemContent.GOLEM_DEATH_ANIMATION;
		}
		if (transitionCycles > 0)
		{
			return transitionAnimation;
		}
		if (afloat)
		{
			return GolemContent.ANIM_GOLEM_HELM;
		}
		if (gaitWalk != -1)
		{
			return walking ? gaitWalk : gaitIdle;
		}
		// Attending to the player — a wave, a copied emote, or simply looking — which stops for
		// nothing except the things above: a golem halfway up a ladder has its hands full. A
		// golem that is only watching plays nothing and falls through to standing still.
		if (!walking && isGreeting(tickNow) && greetAnimation != -1)
		{
			return greetAnimation;
		}
		// Last of all, and never over anything else: a golem climbing, crossing or at the helm has
		// a pose already, and one halfway through a recorded motion is having its frames set by
		// hand. Dancing is what a golem does when it is doing nothing.
		if ((dancing || isPartying(tickNow)) && !walking)
		{
			if (danceMove == null)
			{
				nextDanceMove();
			}
			return danceMove.getAnimationId();
		}
		return walking ? snapshot.getWalkAnimation() : snapshot.getIdlePoseAnimation();
	}

	/**
	 * The gait a golem climbs with, replacing its own for the length of the obstacle; -1
	 * when it uses its own, which is nearly always. A long climb is not a long animation:
	 * the game swaps the base animation set and walks the player up the cliff a tile at a
	 * time, which is why {@code human_climbing_loop} loops — it is a walk animation, and
	 * walk animations repeat once per tile.
	 */
	private int gaitWalk = -1;
	private int gaitIdle = -1;

	/** Played once on arrival, or -1. The step-off at the top of a climb. */
	private int gaitFinish = -1;

	/** Its length, measured when the climb began while the models were to hand. */
	private int gaitFinishCycles;

	// ------------------------------------------------------- using a transport

	/**
	 * The clip playing while the golem climbs, jumps or squeezes through something. All are
	 * the game's own human animations, playable because the golem shares the human rig —
	 * see {@link GolemContent#GOLEM_FRAMEMAP}. Nothing is authored or deformed.
	 */
	private int transitionAnimation = -1;

	/**
	 * Cycles of the transition the golem is actually moving for. Movement and animation do
	 * not last the same time: a stepping-stone hop covers the gap in <b>30 cycles, one game
	 * tick, while the clip runs for 38</b>. Gliding across the whole clip leaves the golem
	 * drifting when it should have landed.
	 */
	private int transitionGlideCycles;

	/**
	 * Cycles the golem holds still at the start of a traversal before moving. Measured, not
	 * assumed: four consecutive basalt hops each held 33 cycles and then crossed in 12, and
	 * the game's own script uses a 12-cycle window inside a longer action. Without it the
	 * golem slides on the animation's first frame.
	 */
	private int transitionGlideDelay;

	/**
	 * The recorded motion being performed, or null when none was available. While it is set
	 * nothing is calculated: position and animation both come from the recording, so a hop's
	 * wind-up, a climb's rise and a door's animate-then-teleport are all just recordings.
	 */
	private MotionCurve motion;

	/** The unit vector toward this traversal's destination, for orienting the recording. */
	private float motionAxisX;
	private float motionAxisY;

	/** Scratch for the curve's offset, to avoid allocating one every cycle per golem. */
	private final int[] motionOffset = new int[2];

	/** Cycles left of the transition, after which the golem arrives at the far end. */
	private int transitionCycles;

	/** Where the golem lands once the transition finishes. */
	private WorldPoint transitionDestination;

	/**
	 * Shortest a transport may take, in client cycles. A floor rather than the duration
	 * itself: every transport runs for as long as its own table row says, where a fixed
	 * length made a rock climb take the same two thirds of a second as a doorway.
	 */
	private static final int TRANSITION_MIN_CYCLES = 20;

	/** Client cycles in one game tick, which is the unit the tables give durations in. */
	private static final int CYCLES_PER_TICK = 30;

	/** How long the transition in progress runs for, so the glide and arc can be paced. */
	private int transitionTotal = TRANSITION_MIN_CYCLES;

	/** The clip set being played, its per-phase lengths, and where in it the golem is. */
	private int[] phaseClips = new int[0];
	private int[] phaseCycles = new int[0];
	private int phase;
	private int phaseRemaining;

	/** True while the golem is mid-obstacle and should not be walking or pathing. */
	boolean inTransition()
	{
		// A climb is walked rather than played, so it has no transition cycles, but it is as
		// much mid-obstacle as a hop: without this the watchdog called a golem on a cliff
		// face unwalkable ground and relocated it halfway up.
		return transitionCycles > 0 || gaitWalk != -1;
	}

	/**
	 * Advances an in-progress transition, landing the golem when it completes.
	 *
	 * @return true while still mid-transition, when the golem must not move
	 */
	private boolean advanceTransition(int cycles)
	{
		if (transitionCycles <= 0)
		{
			return false;
		}

		transitionCycles -= cycles;

		if (motion != null)
		{
			int elapsed = transitionTotal - transitionCycles;
			int beforeX = fineX;
			int beforeY = fineY;
			motion.offsetAt(elapsed, motionAxisX, motionAxisY, motionScale, motionOffset);
			fineX = motionFromX + motionOffset[0];
			fineY = motionFromY + motionOffset[1];

			// Between clips — before the first starts, or after the last ends — the golem is
			// itself: idle, or walking if the recording is moving it. Keeping the last value
			// left golems in their bare model waiting to hop, and frozen after the stile.
			int playing = motion.animationAt(elapsed);
			transitionAnimation = playing != -1 ? playing
				: fineX != beforeX || fineY != beforeY ? snapshot.getWalkAnimation()
				: snapshot.getIdlePoseAnimation();
			motionFrame = motion.frameAt(elapsed);

			if (transitionCycles > 0)
			{
				return true;
			}
			motion = null;
			motionFrame = -1;
		}

		// Step through the clip set as each phase runs out, so the golem takes hold, hauls
		// itself up and steps off rather than looping one clip for the whole obstacle.
		phaseRemaining -= cycles;
		while (phaseRemaining <= 0 && phase + 1 < phaseClips.length)
		{
			phase++;
			phaseRemaining += phaseCycles[phase];
			transitionAnimation = phaseClips[phase];
		}

		if (transitionCycles > 0)
		{
			// Carry the golem across while the clip plays: a jump with the golem in two
			// places and nothing in between reads as a teleport with an animation on it.
			if (transitionGlide)
			{
				// Against the movement window, not the whole transition, so the golem
				// arrives when it should and holds still for the rest of the clip.
				int window = transitionGlideCycles > 0 ? transitionGlideCycles : transitionTotal;
				int elapsed = transitionTotal - transitionCycles - transitionGlideDelay;
				float done = elapsed <= 0 ? 0f : Math.min(1f, elapsed / (float) window);
				fineX = transitionFromX + Math.round((transitionToX - transitionFromX) * done);
				fineY = transitionFromY + Math.round((transitionToY - transitionFromY) * done);
			}
			return true;
		}

		transitionCycles = 0;
		transitionAnimation = -1;
		transitionGlide = false;
		motion = null;
		phaseClips = new int[0];
		phaseCycles = new int[0];
		phase = 0;
		if (transitionDestination != null)
		{
			finishTrace();
			relocate(transitionDestination);
			transitionDestination = null;
			landInInstance();
		}
		return false;
	}

	/** Frames kept of one traced crossing: ten seconds of them at fifty a second. */
	private static final int TRACE_SAMPLES = 500;

	/** Longest crossing traced, in tiles. Further is a teleport, with no path to get wrong. */
	private static final int TRACE_REACH = 8;

	/** The crossing being traced, or null. See GolemTraversal. */
	private GolemTransport tracing;
	private Consumer<GolemTraversal> traceSink;
	private int traceStartX;
	private int traceStartY;
	private int traceElapsed;
	private java.util.List<int[]> traceSamples;

	/** Starts tracing a crossing, if it is one worth tracing and somebody keeps the record. */
	private void beginTrace(GolemTransport transport, RoamContext context)
	{
		tracing = null;
		if (tier != GolemTier.SCENE || context.getTraversals() == null
			|| transport.getFromPlane() != transport.getToPlane()
			|| span(transport.getToX() - transport.getFromX(), transport.getToY() - transport.getFromY()) > TRACE_REACH)
		{
			return;
		}
		tracing = transport;
		traceSink = context.getTraversals();
		traceStartX = fineX;
		traceStartY = fineY;
		traceElapsed = 0;
		traceSamples = new ArrayList<>();
		traceSamples.add(new int[]{0, fineX, fineY});
	}

	private void sampleTrace(int cycles)
	{
		if (tracing == null)
		{
			return;
		}
		traceElapsed += cycles;
		if (traceSamples.size() < TRACE_SAMPLES)
		{
			traceSamples.add(new int[]{traceElapsed, fineX, fineY});
		}
	}

	/** Hands the finished crossing over, from where the golem is before it lands on the tile. */
	private void finishTrace()
	{
		if (tracing == null)
		{
			return;
		}
		if (traceSamples.size() < TRACE_SAMPLES)
		{
			traceSamples.add(new int[]{traceElapsed, fineX, fineY});
		}
		GolemTraversal done = new GolemTraversal(tracing, traceStartX, traceStartY, fineX, fineY, traceSamples);
		tracing = null;
		traceSamples = null;
		traceSink.accept(done);
	}

	/** Where the glide started and ends, in the same 128ths of a tile as everything else. */
	private int transitionFromX;
	private int transitionFromY;
	private int transitionToX;
	private int transitionToY;

	/** Whether this transition moves the golem, or is played on the spot. */
	private boolean transitionGlide;

	/**
	 * How high the golem is lifted mid-jump, in client units at the peak. A stepping-stone
	 * hop is an arc, not a slide: without this the golem crosses the water at ground level
	 * looking dragged.
	 */
	private static final int JUMP_ARC_HEIGHT = 26;

	/**
	 * Extra height for the drawn model this frame, or zero. Read by {@link FakeGolem} and
	 * added to the terrain height: a parabola across the transition, so the golem leaves
	 * the ground, peaks halfway and lands.
	 */
	int jumpArc()
	{
		if (!transitionGlide || transitionCycles <= 0)
		{
			return 0;
		}

		// You can only be in the air while you are moving. Arcing across the whole
		// transition made the golem rise and hang there for the cathedral door, which holds
		// still for thirty cycles and then teleports.
		int window = transitionGlideCycles > 0 ? transitionGlideCycles : transitionTotal;
		int elapsed = transitionTotal - transitionCycles - transitionGlideDelay;
		if (elapsed <= 0 || elapsed >= window)
		{
			return 0;
		}

		// And only for a leap. A door's single tile is a step through a doorway; lifting the
		// golem off the ground for it is a hover, not a jump.
		if (Math.abs(transitionToX - transitionFromX)
			+ Math.abs(transitionToY - transitionFromY) <= TILE)
		{
			return 0;
		}

		float done = elapsed / (float) window;
		// 4t(1-t) peaks at 1 when t is a half, and is zero at both ends.
		return Math.round(JUMP_ARC_HEIGHT * 4f * done * (1f - done));
	}
}
