package com.golemsdontdie;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;
import lombok.Getter;
import lombok.Setter;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * One golem, simulated in world coordinates whether or not it is on screen.
 *
 * <p>The separation is the point. A golem is a position, a heading and an intention;
 * drawing it is a view onto that, attached only while it happens to be inside the
 * loaded scene. So a golem that wanders off the edge of the scene keeps walking, and
 * wanders back on later, instead of the plugin having to decide what to do about the
 * edge of the world.
 *
 * <p>Positions are integer 128ths of a tile — the same unit the client's local
 * coordinates use. Floats would accumulate error over a golem that walks for hours,
 * and there is nothing here that fractions of a 128th would buy.
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
	 * Which floor the golem is on.
	 *
	 * <p>Fixed for the whole of the island-only plugin: a golem was made on the plinth
	 * and never left the ground. It is mutable now because the transport network is
	 * mostly vertical — 2,262 of the 5,168 plain transport rows change plane, and ladders
	 * and staircases alone are the largest group in the game. A golem that cannot change
	 * plane cannot use any of them.
	 *
	 * <p>Only {@link #relocate} may change it, and never mid-step.
	 */
	@Getter
	private int plane;

	/**
	 * Seeded per golem so that two golems made on the same tile do not walk in
	 * lockstep, and so a restored golem carries on with its own gait.
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
	 * What this golem has used recently, so it does not pace through the same door.
	 *
	 * <p>Per golem rather than shared: two golems meeting at a ladder should both be able
	 * to climb it.
	 */
	private final TransportMemory transportMemory = new TransportMemory();

	/**
	 * The route this golem is on while nobody can see it, or null if it is being
	 * simulated properly.
	 *
	 * <p>Set when the golem drops out of range and cleared when it comes back. While it is
	 * set the golem is not stepped at all: its position is whatever the route says it is
	 * at the current tick.
	 */
	private Itinerary itinerary;

	/** The tier the golem was in last frame, so crossings can be acted on once. */
	@Getter
	private GolemTier tier = GolemTier.SCENE;

	// ---------------------------------------------------------------- watchdog

	/**
	 * Consecutive destination searches that found nowhere to go.
	 *
	 * <p>One failure is ordinary — a golem in a corner picks a target behind a wall and
	 * tries again. A run of them means the golem is somewhere it cannot walk out of, which
	 * is the failure this plugin must never leave standing: the whole premise is that
	 * golems do not die, and a golem frozen in scenery for the rest of the session is a
	 * death that does not even have the decency to look like one.
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
	 * True if this golem has stopped getting anywhere and should be relocated.
	 *
	 * <p>Two independent signals, because they catch different failures. Repeated failed
	 * searches catch a golem walled in on ground the map says is walkable. No net movement
	 * catches the subtler case where searches succeed but the golem shuffles between two
	 * tiles forever — a path that is found and then immediately undone.
	 *
	 * <p>Deliberately slow to fire. A golem that stands still for a few minutes is normal
	 * behaviour, so the thresholds are set where a false positive costs a relocation
	 * nobody sees and a false negative costs a golem.
	 */
	boolean isStuck(int tick)
	{
		if (isDying() || inTransition())
		{
			return false;
		}

		// A golem on a route is not stuck, it is travelling — and a passage in particular
		// holds it at the first waypoint for the whole crossing, which looks exactly like
		// a golem that has stopped moving. Without this the watchdog would haul golems off
		// boats halfway across.
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

	/** True while the golem is on a crossing: afloat, or waiting out a passage. */
	boolean isSailing(int tick)
	{
		return itinerary != null && itinerary.isVoyage() && !itinerary.isFinished(tick);
	}

	/**
	 * Where to write this golem down in a save: its landfall if it is on a crossing.
	 *
	 * <p>Its current tile at sea is water, and a golem restored onto water is moved to the
	 * nearest land — which from mid-ocean is wherever that happens to be.
	 */
	WorldPoint saveTile()
	{
		return itinerary != null && itinerary.isVoyage() ? itinerary.destination() : currentTile();
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

	/** True while the golem is turning on the spot rather than going anywhere. */
	boolean isDrifting()
	{
		return eddyCycles > 0;
	}

	/**
	 * Works a grounded raft free, one rung at a time.
	 *
	 * <p>Four steps, cheapest and least visible first, which is the order that matters
	 * because the early ones are free and the last one is a cheat:
	 *
	 * <ol>
	 *   <li><b>Spin.</b> Orientation only — no position change, no risk, and it buys time
	 *       for the next rung while looking like a raft caught in an eddy.</li>
	 *   <li><b>Reverse.</b> Replay the route backwards. Free, since the waypoints are
	 *       already stored, and it escapes most dead-end inlets.</li>
	 *   <li><b>Re-plan.</b> A fresh route from where the boat actually is.</li>
	 *   <li><b>Snap.</b> Move to valid water. The only cheat, and invisible by
	 *       construction: a grounded golem is unwatched almost by definition, because a
	 *       watched one would be on live collision rather than the shipped mesh.</li>
	 * </ol>
	 *
	 * @return true if this handled the situation and nothing further should be done
	 */
	boolean workFree(RoamContext context, RoamPlanner planner)
	{
		int tick = context.getTick();

		if (eddyCycles > 0)
		{
			// Rung one is still running. Turning is all that happens.
			eddyCycles = Math.max(0, eddyCycles - 1);
			targetOrientation = (orientation + 512) & 2047;
			return true;
		}

		if (!reversed && itinerary != null)
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

		// Rung three: a fresh plan from here.
		//
		// Only a golem nobody can see may be given a route, because a roaming route is a
		// straight line that ignores the map. In view the equivalent is an ordinary path
		// search, which respects it — handing a visible golem a route was what sent them
		// sliding across the water.
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
	 * Cycles left of the crumble, or -1 if this golem is not dying.
	 *
	 * <p>A golem being retired plays the game's own death animation before it goes.
	 * Vanishing on the spot would look like a bug — and since the whole plugin exists
	 * to stop golems disappearing, one that disappears anyway should at least do it
	 * the way the game does.
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

	/** Set when the golem is inside the loaded scene and should be drawn. */
	@Getter
	@Setter
	private FakeGolem renderer;

	/**
	 * A name the player has given this golem, or null for none.
	 *
	 * <p>Bookkeeping for the side panel only. It is deliberately not used as the
	 * hover target: the world calls every one of them "Golem", and a nickname
	 * appearing on right-click would give the copy away.
	 */
	@Getter
	@Setter
	private String nickname;

	/**
	 * True while the golem is inside an instance — through the pew, in the Mad Angel's room.
	 *
	 * <p>Golems are simulated in an instance's template, which is ordinary world coordinates:
	 * the room behind the cathedral pew is a real, sealed place on the island, and the
	 * instance is a copy of it and everything around it. Coordinates alone could not say
	 * whether a golem standing there is in the instance, so golems in the sealed room were
	 * drawn from the cathedral, and every golem on the island near the cathedral was drawn
	 * inside the player's instance. A golem is in the instance from the moment a transport
	 * into one lands it until one out of it does.
	 */
	@Getter
	@Setter
	private boolean inInstance;

	/** What a transport in progress will make {@link #inInstance} when it lands: 1, 0, or -1 for no change. */
	private int landingInstance = -1;

	/** Applies a transport's instance change as the golem arrives. */
	private void landInInstance()
	{
		if (landingInstance >= 0)
		{
			inInstance = landingInstance == 1;
			landingInstance = -1;
		}
	}

	/** Applies the instance change of a transport already arrived through, for a golem out of view. */
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

	Golem(GolemSnapshot snapshot, WorldPoint home, long seed, int startFineX, int startFineY)
	{
		this.snapshot = snapshot;
		this.home = home;
		this.plane = home.getPlane();
		this.random = new Random(seed);
		this.id = seed;

		this.fineX = startFineX;
		this.fineY = startFineY;
		this.orientation = snapshot.getOrientation() & 2047;
		this.targetOrientation = this.orientation;
		this.dwellRemaining = DWELL_MIN + random.nextInt(DWELL_SPREAD);
	}

	/**
	 * Creates a golem standing on the centre of a tile.
	 *
	 * <p>Used when restoring a save, where only the tile was written down. Taking over
	 * from a live golem uses the other constructor with its exact sub-tile position —
	 * snapping to a tile centre there is precisely what made the first version look
	 * like the copy teleported.
	 */
	static Golem onTile(GolemSnapshot snapshot, WorldPoint home, long seed, WorldPoint at)
	{
		return new Golem(snapshot, home, seed,
			at.getX() * TILE + TILE / 2,
			at.getY() * TILE + TILE / 2);
	}

	/**
	 * True if a scene-local position is inside the loaded scene.
	 *
	 * <p>Replaces {@link net.runelite.api.coords.LocalPoint#isInScene()}, which is
	 * deprecated because it assumes every scene is the classic 104 tiles square.
	 * Asking the world view for its own size is correct for instances and for
	 * whatever sizes the client grows to support later.
	 */
	static boolean isInScene(WorldView wv, int localX, int localY)
	{
		return localX >= 0 && localY >= 0
			&& localX < wv.getSizeX() * TILE
			&& localY < wv.getSizeY() * TILE;
	}

	/** The tile the golem is standing on or walking out of. */
	WorldPoint currentTile()
	{
		return new WorldPoint(fineX / TILE, fineY / TILE, plane);
	}

	/**
	 * Moves the golem between tiers, doing the one-off work each crossing needs.
	 *
	 * <p>Called every frame with the tier the golem's position puts it in. Most frames it
	 * is the tier it was already in and this does nothing.
	 *
	 * <ul>
	 *   <li><b>Inward</b> — the route is resolved to a tile, that tile is snapped onto real
	 *       walkable ground, and the golem starts being stepped again. Snapping is what
	 *       makes the straight-line far routes safe: their inaccuracy is corrected at
	 *       exactly the moment it would otherwise become visible.</li>
	 *   <li><b>Outward</b> — the renderer is dropped and a route is planned. From here on
	 *       the golem costs nothing until someone comes back.</li>
	 * </ul>
	 *
	 * @return true if the golem changed tier
	 */
	boolean setTier(GolemTier now, RoamContext context, RoamPlanner planner)
	{
		if (now == tier)
		{
			return false;
		}

		boolean wasFar = tier == GolemTier.FAR;
		tier = now;

		if (now == GolemTier.FAR)
		{
			itinerary = planner.plan(currentTile(), context.getTick(), random, transportMemory, context);
			return true;
		}

		if (wasFar)
		{
			// A crossing keeps going. It was searched properly over the ocean mesh, every
			// waypoint is real water, and the golem is drawn on a boat — so a player who
			// sails out to meet one finds it exactly where the route says, still sailing.
			if (itinerary != null && itinerary.isVoyage()
				&& !itinerary.isFinished(context.getTick()))
			{
				return true;
			}

			if (itinerary != null && itinerary.isFinished(context.getTick()) && itinerary.transport() != null)
			{
				noteInstance(itinerary.transport());
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
	 * Advances a golem nobody can see, which is almost always nothing at all.
	 *
	 * <p>The only work is replanning when a route runs out — once every minute or so per
	 * golem, and nothing in between. A thousand golems crossing the world between them
	 * generate a handful of straight-line plans a second.
	 */
	/**
	 * Moves the golem along its route, and tidies up when the route runs out.
	 *
	 * <p>Position comes from the route rather than being stepped toward it, so this is the
	 * same mechanism whether anyone is watching or not. What differs is the ending: a
	 * route that finishes in view is cleared so the golem goes back to walking, where a
	 * far golem just gets another route.
	 */
	private void followItinerary(int cycles, RoamContext context)
	{
		int tick = context.getTick();

		// Sub-tile, interpolated along the route by distance. Reading the waypoint index
		// instead made a golem jump a whole straightened leg at a time.
		int[] at = itinerary.fineAt(tick);

		// Face along the leg being travelled, which is the route's own answer and does not
		// depend on having moved since last frame.
		int[] heading = itinerary.headingAt(tick);
		if (heading[0] != 0 || heading[1] != 0)
		{
			targetOrientation = headingFor(heading[0], heading[1]);
		}
		turnToward(cycles);

		this.fineX = at[0];
		this.fineY = at[1];
		this.plane = itinerary.planeAt(tick);

		// A crossing shows the walk cycle: the golem is standing on a moving boat, and a
		// golem playing its idle while the sea goes past reads as scenery.
		walking = itinerary.isVoyage() && !itinerary.isFinished(tick);

		if (itinerary.isFinished(tick) && tier != GolemTier.FAR)
		{
			boolean wasVoyage = itinerary.isVoyage();
			itinerary = null;
			walking = false;
			dwellRemaining = DWELL_MIN + random.nextInt(DWELL_SPREAD);

			// A voyage ends at a mooring, and a mooring is water. Left there the golem
			// would be standing on the sea with nowhere to walk, so it is put ashore —
			// which is what stepping off a boat is.
			if (wasVoyage)
			{
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
	 * @param mayPlan false once this frame's planning is spent. A golem whose route has run
	 *                out then stands where it arrived and plans on a later frame — a frame's
	 *                delay nobody can see, where a thousand golems arriving together could
	 *                otherwise all search in the same one.
	 * @return true if a route was planned
	 */
	boolean advanceFar(RoamContext context, RoamPlanner planner, boolean mayPlan)
	{
		int tick = context.getTick();

		if (itinerary != null && !itinerary.isFinished(tick))
		{
			// Read the route's position rather than stepping toward it. This is a binary
			// search and a lerp — the "costs nothing" claim is about there being no search,
			// no allocation and no accumulated drift, not about avoiding arithmetic.
			// Keeping the position live is what lets the tier check notice a golem sailing
			// toward the player, and what stops a golem mid-ocean saving the port it left
			// rather than where it actually is.
			int[] at = itinerary.fineAt(tick);
			this.fineX = at[0];
			this.fineY = at[1];
			this.plane = itinerary.planeAt(tick);
			return false;
		}

		if (itinerary != null && itinerary.transport() != null)
		{
			noteInstance(itinerary.transport());
		}
		WorldPoint at = itinerary == null ? currentTile() : itinerary.destination();
		this.fineX = at.getX() * TILE + TILE / 2;
		this.fineY = at.getY() * TILE + TILE / 2;
		this.plane = at.getPlane();
		if (!mayPlan)
		{
			return false;
		}

		itinerary = planner.plan(at, tick, random, transportMemory, context);
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

	/** Plans in a row that found nowhere to go, which lengthens the pause before the next. */
	private int farFailures;

	/**
	 * Puts the golem down somewhere else entirely — the far end of a ladder, a dock it
	 * has just sailed to, or a valid tile after it was found somewhere it cannot stand.
	 *
	 * <p>Everything mid-flight has to go. The queued path was found on the old plane and
	 * would walk the golem through the new floor; a half-finished step would interpolate
	 * it across the map. Both are cleared here rather than left for the caller to
	 * remember, because forgetting either produces a golem that slides through scenery
	 * and the cause is a long way from the symptom.
	 *
	 * <p>Identity is untouched: same name, same id, same seed, same gait. A golem is
	 * never replaced to solve a navigation problem.
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

		// The route goes too. Being put somewhere means forgetting where you were going —
		// a kept itinerary would resolve against the new position next frame and drag the
		// golem straight back, which would make a rescue look like a rubber band.
		itinerary = null;

		// Arriving somewhere new is a moment to stand and look around, and it keeps a
		// relocated golem from immediately pathing back out of a doorway it just used.
		dwellRemaining = DWELL_MIN + random.nextInt(DWELL_SPREAD);
	}

	/**
	 * Advances the simulation.
	 *
	 * @param cycles client cycles (20ms each) since the last advance
	 * @param bounds where it may walk, re-derived each time so that flipping the free
	 *               roam setting takes effect on the golem's next decision
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

		// Mid-obstacle: the golem is playing a climb or a squeeze and must not walk,
		// turn or path until it has finished and landed.
		if (advanceTransition(cycles))
		{
			return false;
		}

		// A route carries the golem only if it is a crossing.
		//
		// Roaming routes are straight lines drawn without consulting the map at all — that
		// is the whole point of them, and it is safe only while nobody can see. Following
		// one in view is what put golems sliding through the sea and the cliffs with no
		// walk animation, so a roaming route found here is discarded rather than followed:
		// this golem is being simulated properly now and does not need it.
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

		// Movement is resolved a step at a time rather than by advancing a distance,
		// so a large `cycles` — after a stutter, or a tab-out — carries the golem
		// through several whole tiles instead of teleporting it past a wall.
		int remaining = cycles;
		while (remaining > 0)
		{
			if (!stepping)
			{
				if (path.isEmpty())
				{
					walking = false;
					dwellRemaining -= remaining;
					if (dwellRemaining > 0)
					{
						return searched;
					}
					// Hopping off a stepping stone is not a search and must not wait on the
					// search budget. A golem mid-crossing has nowhere to walk, so being
					// told to try again next frame leaves it standing in a river until
					// the watchdog comes for it — which is how golems ended up being
					// rescued off stones they had only just landed on.
					if (takeQueuedTransport(context) || considerTransport(context))
					{
						return searched;
					}
					// Standing where it cannot walk off, and nothing fresh to take: go back the
					// way it came rather than wait here for the watchdog.
					if (!context.getMemory().isKnownWalkable(fineX / TILE, fineY / TILE, plane)
						&& takeWayOut(context, true))
					{
						return searched;
					}

					if (!context.isMayPath())
					{
						// This frame's search budget is spent. Stand still and try again
						// next frame rather than joining a stampede of path searches.
						return searched;
					}
					// Overshoot is discarded: a golem that dawdled a few cycles too long
					// is not worth carrying an error forward for.
					remaining = 0;
					searched = true;
					chooseDestination(context);
					if (path.isEmpty())
					{
						// Nowhere to go — usually a golem boxed in by scenery. Wait and
						// try again rather than spinning on it. Counted, because the
						// difference between a golem that is briefly hemmed in and one
						// that is permanently trapped is only how many times this happens.
						failedSearches++;
						// Twice in a row with nowhere to walk is a dead end, not bad luck — but on
						// ground it can stand on, only a fresh way out is taken. Going back the
						// way it came from a room is how golems ping-ponged up and down the
						// tower ladder a hundred and sixty times: the room at the bottom was shut
						// in, so was the floor at the top, and each sent them to the other.
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

				// Checked again at the moment of stepping, not only when the path was planned.
				// A door that was open when the golem chose its route can be shut by the time
				// it gets there, and a path is a promise about the past. A climb is exempt: it
				// walks up a cliff face that was never walkable to begin with.
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
				// gets to look at this golem. Leaving the gait on would have it walking
				// around the world in a climbing pose, and rolling for a new transport
				// mid-obstacle would abandon the one it is halfway up.
				if (gaitWalk != -1 && path.isEmpty())
				{
					endClimb();
					walking = false;
					dwellRemaining = DWELL_MIN + random.nextInt(DWELL_SPREAD);
					return searched;
				}

				// A tile has just been entered, which is the one moment a transport roll
				// may happen. Rolling per frame instead would make the behaviour depend on
				// framerate, and would mean a golem that loitered anywhere near a ladder
				// eventually always took it.
				// Arriving at a shortcut the golem walked here to use comes first: it has
				// already decided, and rolling again would make it stand on the spot it
				// deliberately walked to and think better of it.
				if (takeQueuedTransport(context) || considerBoarding(context)
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
	 * How far from a transport's origin a golem will consider using it, in tiles.
	 *
	 * <p>Three, not one. Requiring the golem to land exactly on the origin tile would mean
	 * it almost never used anything: only 3.2% of walkable ground is within three tiles of
	 * a transport origin in the first place, so insisting on an exact hit makes the
	 * network effectively invisible.
	 */
	private static final int TRANSPORT_REACH = 3;

	/**
	 * Chance of taking a transport the golem is standing on. Falls off with distance.
	 *
	 * <p>Deliberately well under certainty. A forced "use what you are next to" rule turns
	 * a ladder in a corridor into a wall, because the golem takes it every single time it
	 * passes and can never walk down the corridor. A failed roll just means the golem
	 * carries on and gets another chance if it comes back.
	 */
	private static final float TRANSPORT_CHANCE = 0.3f;

	/**
	 * Rolls for a voyage, for a golem standing at a dock where the player can see it.
	 *
	 * <p>Sailing used to be planned only while a golem was out of range, which kept the
	 * ocean search off the frame but meant nobody ever saw a golem leave — boats appeared
	 * and vanished at the edge of what was loaded. Now that crossings are cached per pair
	 * of ports and new searches are rationed to one a frame, a golem in view can board
	 * like any other: it walks to the dock, and if the route is known it goes.
	 *
	 * <p>If the route has never been searched and the frame's budget is spent, the golem
	 * simply does not sail this time, which from outside is a golem that stayed put.
	 *
	 * @return true if the golem set off and this frame's movement should stop
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
		itinerary = crossing;
		return true;
	}

	/**
	 * Rolls for a transport near the tile just entered, and takes one if it wins.
	 *
	 * @return true if the golem used something and this frame's movement should stop
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

		// True when the golem is somewhere it cannot walk out of but the network can:
		// halfway across a river, standing on a stone. The next hop then happens without
		// a roll, because there is nothing else it could do.
		boolean stranded = !context.getMemory().isKnownWalkable(tileX, tileY, plane);

		// Scanned as a box around the tile rather than by asking for neighbours, because
		// the index is by exact origin tile and most tiles have nothing on them at all —
		// so this is 49 cheap map lookups that almost always all miss.
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

					// Standing somewhere that cannot be walked off — mid-crossing, on a
					// stepping stone — means the next hop is not a choice. Rolling for it
					// would leave the golem stranded on a rock in a river until the
					// watchdog came for it.
					if (!stranded)
					{
						// Adjacent is the full chance, three tiles away a quarter of it.
						float chance = TRANSPORT_CHANCE
							* (TRANSPORT_REACH + 1 - distance) / (TRANSPORT_REACH + 1);
						if (random.nextFloat() >= chance)
						{
							continue;
						}
					}

					// Only ever taken from the tile it actually starts on.
					//
					// The search radius exists so a golem notices a shortcut it is walking
					// past, not so it can use one from across the clearing. An earlier
					// version let a stranded golem take any transport in range regardless
					// of where that transport began, which is how golems on the extra
					// slippery stone ended up turning sideways and hopping nowhere: the
					// row they took started on the far bank, so its direction was
					// meaningless and its destination was the tile they already stood on.
					if (tileX != transport.getFromX() || tileY != transport.getFromY())
					{
						if (stranded)
						{
							// Mid-crossing there is nowhere to walk to, so a row starting
							// somewhere else is no use — wait for one that starts here.
							continue;
						}
						if (walkTo(transport.getFromX(), transport.getFromY(), context))
						{
							queuedTransport = transport;
							return true;
						}
						continue;
					}

					take(transport, context);
					return true;
				}
			}
		}
		return false;
	}

	/** The shortest wait before going back, however small the space beyond. About fifteen seconds. */
	private static final int MIN_COOLDOWN_TICKS = 25;

	/**
	 * How long before a golem may go back the way a transport just took it, scaled by how
	 * much room there is on the far side.
	 *
	 * <p>The full wait is right for open ground: a golem through a door into a town should
	 * explore it rather than turn round. Behind the stile is a pen of a few dozen tiles, and
	 * two minutes there was long enough for the golems arriving to outnumber the ones
	 * leaving — it filled until it could not be drawn. The wait shrinks with the space, down
	 * to a floor that still stops a golem stepping straight back.
	 *
	 * <p>Unwalkable landings keep the full wait. A stepping stone is not a small space, it is
	 * the middle of a crossing, and shortening its cooldown would send golems back to the
	 * bank they just left.
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
		float share = Math.min(1f, context.enclosedArea(x, y, toPlane) / (float) RoamContext.OPEN_AREA);
		return Math.max(MIN_COOLDOWN_TICKS, Math.round(TransportMemory.COOLDOWN_TICKS * share));
	}

	/**
	 * Leaves a dead end by whatever starts here, even the way the golem just came.
	 *
	 * <p>The cooldown on a transport's reverse exists to stop a golem pacing through a door
	 * for the fun of it. At the top of a ladder with no floor to walk on, that same cooldown
	 * was the only thing keeping the golem there — it could not wander, could not go back
	 * down, and stood re-planning until the watchdog lifted it to the plinth. A fresh way
	 * out is still preferred; the way back is the fallback, not the choice.
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
		note(context, "dead end: taking the way back");
		take(back, context);
		return true;
	}

	/**
	 * True if the far end of a transport is ground the golem could actually walk on.
	 *
	 * <p>The transport table covers the whole game; the passability map, for now, covers
	 * Wyrmscraig. A golem that took a row leading somewhere unmapped would arrive on
	 * ground the pathfinder cannot route out of and stand there for the rest of its life —
	 * a stranding with no visible cause, and one the player could not fix.
	 *
	 * <p>So the network is allowed to be larger than the map, and this is the seam between
	 * them. As the shipped mesh grows, more of the table simply becomes reachable without
	 * anything here changing.
	 */
	private boolean arrivesSomewhereKnown(GolemTransport transport, RoamContext context)
	{
		if (context.getMemory().isKnownWalkable(
			transport.getToX(), transport.getToY(), transport.getToPlane()))
		{
			return true;
		}

		// A stepping stone is blocked ground — you cannot walk onto one, which is why
		// crossing is a transport in the first place. But a stone is somewhere you can
		// legitimately stand, and the proof is that another transport starts there. So a
		// destination that is itself a transport origin is accepted even though the map
		// says nothing may stand on it: the network is asserting otherwise, and the golem
		// can always hop off again.
		//
		// But only if that transport goes on somewhere. A stone whose only way off is back to
		// the stone the golem came from is not the middle of a crossing, it is a dead end with
		// a hop at each side: the extra-slippery crossing lands on tiles the game blocks, and
		// golems hopped back and forth across it forty times each, never getting off.
		//
		// And "goes on somewhere" means eventually reaches ground, followed hop by hop: one hop
		// ahead passed every stone of a crossing that never reached a bank. See
		// TransportNetwork.leadsToGround.
		return context.getTransports().leadsToGround(transport.getToX(), transport.getToY(), transport.getToPlane(),
			transport.getFromX(), transport.getFromY(), transport.getFromPlane(), TransportNetwork.CHAIN_HOPS,
			context.getAbilities()::canUse, context.getMemory()::isKnownWalkable);
	}

	/**
	 * A transport the golem is walking toward and will use on arrival.
	 *
	 * <p>Held rather than taken immediately so the golem walks up to the shortcut and uses
	 * it from the tile it starts on, the way a player does.
	 */
	private GolemTransport queuedTransport;

	/**
	 * Paths the golem to a nearby tile.
	 *
	 * @return true if a route was found and the golem is now walking it
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
	 * Takes the queued transport once the golem has arrived at its starting tile.
	 *
	 * @return true if the golem used it and this frame's movement should stop
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
			// Still on the way. If the path has run out without arriving, the plan is
			// stale — drop it rather than hold out for a tile the golem is no longer
			// heading to.
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

	/**
	 * Uses a transport.
	 *
	 * <p>Anything with an animation is played before the golem arrives: it stops, climbs
	 * or jumps or squeezes, and only then appears at the far end. Arriving instantly would
	 * work but would look like a teleport, which is the one thing a golem that has just
	 * walked to a ladder should not do.
	 *
	 * <p>Doors and teleport-like rows have no animation and land immediately. That is
	 * correct rather than a shortcut — a door animates itself, and a golem pausing in a
	 * doorway to play a clip that does not exist would be worse than walking through.
	 */
	private void take(GolemTransport transport, RoamContext context)
	{
		transportMemory.used(transport, context.getTick(), cooldownFor(transport, context));
		// On landing, not now: a golem climbing into the pew is still in the cathedral while it climbs.
		landingInstance = transport.entersInstance() ? 1 : transport.leavesInstance() ? 0 : -1;

		// Logged before anything else can return. A row with no animation used to relocate
		// the golem above this line, so every staircase a golem took left no trace and the
		// journal read as though golems never used staircases at all.
		note(context, "transport obj=" + transport.getObjectId()
			+ " to=" + transport.getToX() + "," + transport.getToY() + ","
			+ transport.getToPlane()
			+ " arch=" + transport.getArchetype()
			+ " dur=" + transport.getDuration());

		// A recording beats anything reconstructed, and that includes the silent case. A
		// staircase plays nothing but still has a shape — the player stands on it for a
		// moment and then is gone — and relocating on the spot skipped straight past it.
		//
		// A plane change can be performed from a recording as long as the recording barely
		// moves: a ladder animates and then puts you on another floor, so its value is in
		// the timing. A recording that does travel cannot cross planes, because its offsets
		// would slide the golem across a floor it is no longer on.
		MotionCurve recorded = context.getKnowledge() == null ? null
			: context.getKnowledge().curveFor(transport);
		boolean samePlane = transport.getFromPlane() == transport.getToPlane();
		if (recorded != null && !recorded.isEmpty() && (samePlane || recorded.reach() < TILE))
		{
			beginRecordedMotion(transport, recorded);
			note(context, "performing " + recorded);
			return;
		}

		int animation = transport.animation();
		if (animation < 0 || animation == snapshot.getWalkAnimation())
		{
			note(context, "relocated without animation");
			relocate(transport.destination());
			landInInstance();
			return;
		}

		// Stop where it is and play the clip. The path is dropped now rather than on
		// landing so the golem does not keep walking under its own animation.
		path.clear();
		stepping = false;
		walking = false;
		transitionAnimation = animation;
		// The whole clip set, in order — mount, cross, dismount — rather than one clip
		// standing in for all three. Each phase runs for exactly as long as the clip does,
		// asked of the client, so a rock climb takes as long as climbing and a hop takes
		// as long as hopping.
		//
		// A fixed length made a cliff take the same two thirds of a second as stepping
		// through a door, which is why golems scaled shortcuts faster than a player can.
		// What the player was seen doing beats what was shipped, which beats the guess the
		// archetype makes from the menu text.
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

		// How long the obstacle takes, in order of how much the answer is worth.
		//
		// A measured tick count wins outright. After that comes the duration authored on
		// this row, and only then the clip's own length.
		//
		// The middle term is the one that matters, and it used to be last. A clip length
		// is per obstacle *type* — every rock climb in the game plays 4435, so ranking the
		// clip above the row gave all of them the same duration. The tables know better
		// than that: they author eight to fifteen ticks across the fifteen climbing-rock
		// rows, and three to six across the forty-seven stiles. Throwing that away is
		// precisely the "one size fits all" that made shortcuts across the game run at the
		// wrong rate long after the hops had been fixed.
		//
		// The clip stays as the fallback rather than the answer. Where both exist they
		// agree closely — a cathedral door measured one tick against a 30-cycle clip, the
		// cave three ticks against 96 — so little is lost by preferring the row, and what
		// is gained is that two climbs of different heights take different lengths of time.
		int measuredTicks = context.getKnowledge() == null
			? MeasuredShortcuts.ticksFor(transport.getObjectId())
			: context.getKnowledge().ticksFor(transport);
		int authored = transport.getDuration() * CYCLES_PER_TICK;
		// What decides whether this is walked or performed is how long it takes and how far
		// it goes — not whether its clip loops.
		//
		// The loop test was wrong in both directions and measurement proved it:
		// human_climbing (737) is a one-shot and yet is the walk animation of a climb,
		// while hole_squeeze (3835) loops and is not walked at all. Loop-ness is a property
		// of the clip, not of the movement.
		//
		// Duration does separate them. Where the cache states an obstacle's time, stepping
		// stones read one or two ticks and log balances and slopes read six to eight — the
		// short ones are a single action, the long ones are several tiles of walking.
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

		note(context, "clips=" + java.util.Arrays.toString(clips) + " total=" + total
			+ " wanted=" + wanted + " measured=" + measuredTicks + " authored=" + authored
			+ " obstacleTicks=" + obstacleTicks + " stretchable=" + stretchable
			+ " delay=" + (context.getKnowledge() == null ? -1
				: context.getKnowledge().moveDelayFor(transport))
			+ " span=" + (context.getKnowledge() == null ? -1
				: context.getKnowledge().moveSpanFor(transport))
			+ " at=" + (fineX / TILE) + "," + (fineY / TILE));

		// Several ticks over several tiles is a walk, whatever the clip does.
		if (obstacleTicks >= WALKED_TICKS && total > 0)
		{
			if (beginClimb(transport, clips, context))
			{
				note(context, "climbing as a walk");
				return;
			}
			note(context, "walk refused: plane or span");
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

		// A hop or a vault carries the golem across and is drawn doing so. A ladder or a
		// cave mouth does not: those end up somewhere else entirely, often on another
		// plane thousands of tiles away, and interpolating toward that would send the
		// golem sliding across the world.
		int span = Math.abs(transport.getToX() - transport.getFromX())
			+ Math.abs(transport.getToY() - transport.getFromY());
		transitionGlide = transport.getFromPlane() == transport.getToPlane() && span <= 8;

		// Movement is whole game ticks — the server moves you over a number of them — while
		// the clip is whatever length it was drawn at. The measured hop covers its gap in
		// one tick under a clip of nearly one and a third.
		// The measured window wins: how long the golem stands still, then how long it
		// moves. Both come from watching the player and are the structure a traversal has
		// that a single duration cannot express.
		int learnedDelay = context.getKnowledge() == null ? 0
			: context.getKnowledge().moveDelayFor(transport);
		int learnedSpan = context.getKnowledge() == null ? 0
			: context.getKnowledge().moveSpanFor(transport);

		if (learnedSpan > 0)
		{
			// Never longer than the clip.
			//
			// A chained crossing recorded a 72-cycle span for a 38-cycle hop, and a window
			// wider than the transition meant the golem only ever completed part of its
			// glide before the transition ended and dropped it at the far side. That is the
			// jump-in-place-then-teleport: it was gliding, just never far enough to see.
			// Which direction the player last crossed decided whether the figure was sane,
			// which is why it changed depending on the way they went.
			int moveCycles = Math.max(1, Math.min(learnedSpan, total));

			// The movement has to finish with the animation, and the observed delay is
			// otherwise taken as given.
			//
			// It is right, and an earlier version of this distrusted it and clamped it to
			// the first half of the clip — which broke the cathedral door. That door
			// measures a 30-cycle delay against a 30-cycle clip, because the player plays
			// the whole animation and *then* teleports, arriving in a standing pose. Forced
			// to halfway, the golem teleported mid-animation instead.
			//
			// What actually caused the double jump was the tail, not the delay. A hop's
			// window ran to cycle 45 against a 38-cycle clip, and a one-shot clip that
			// completes inside a longer transition is nulled by the animation controller and
			// then set again by the renderer — restarting the jump while the golem was still
			// in the air. Ending the movement with the clip removes the overrun and the
			// restart together.
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

			// Face the way it is going, and get there instantly — a golem that jumps
			// sideways because it had not finished turning looks worse than one that
			// snaps round before leaving the ground.
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
	 * A transport whose scenery should be animated, claimed once by the plugin.
	 *
	 * <p>Handed over rather than acted on here because the golem knows nothing about
	 * models or the scene — it decides that a plank should lower, and the plugin, which
	 * owns the renderers, decides whether anyone is close enough to see it.
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
	 * Walks the golem through an obstacle wearing the obstacle's gait.
	 *
	 * <p>This is how the game does it: the climb is ordinary tile-by-tile movement with the
	 * player's walk and idle animations swapped for climbing ones. The loop clip is the
	 * walk cycle, so it repeats once per tile of its own accord and needs no stretching —
	 * a three-tile scramble takes three ticks and an eight-tile one takes eight, without
	 * anybody choosing a duration.
	 *
	 * <p>Only for obstacles that stay on one plane and cover ground the golem can be drawn
	 * crossing. A ladder goes somewhere else entirely and is still a transition.
	 *
	 * @return true if the golem is now climbing, false to fall back to a played clip
	 */
	private boolean beginClimb(GolemTransport transport, int[] clips, RoamContext context)
	{
		if (transport.getFromPlane() != transport.getToPlane())
		{
			return false;
		}

		int span = Math.max(Math.abs(transport.getToX() - fineX / TILE),
			Math.abs(transport.getToY() - fineY / TILE));
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
	 * Ticks above which a traversal is walked rather than performed.
	 *
	 * <p>Four. The cache's own durations cluster either side of it — one and two ticks for
	 * stepping stones and broken walls, six to eight for log balances, slopes and crevices
	 * — and nothing in the sample sits on the boundary.
	 */
	private static final int WALKED_TICKS = 4;

	/**
	 * Where to draw the golem relative to where it is, in fine units, and on which plane.
	 *
	 * <p>Zero everywhere but an instance. A golem that has gone into a boss room is simulated
	 * at the room's template — the fixed place in the world the instance is copied from,
	 * which is the only address the room keeps between visits — and the player standing in
	 * the instance sees that same template somewhere else entirely. The plugin works out the
	 * difference each frame and the golem is drawn there.
	 */
	private int drawOffsetX;
	private int drawOffsetY;
	private int drawPlane = -1;

	void setDrawOffset(int x, int y, int plane)
	{
		drawOffsetX = x;
		drawOffsetY = y;
		drawPlane = plane;
	}

	int getDrawFineX()
	{
		return fineX + drawOffsetX;
	}

	int getDrawFineY()
	{
		return fineY + drawOffsetY;
	}

	int getDrawPlane()
	{
		return drawPlane >= 0 ? drawPlane : plane;
	}

	/** Steps begun so far, so each can be checked exactly once from outside. */
	private int stepSerial;
	private boolean stepClimbing;

	int getStepSerial()
	{
		return stepSerial;
	}

	/** The most recent step as {fromX, fromY, toX, toY} in tiles. */
	int[] lastStep()
	{
		return new int[]{stepFromX / TILE, stepFromY / TILE, stepToX / TILE, stepToY / TILE};
	}

	/** True if the most recent step was part of a climb, which crosses unwalkable ground by design. */
	boolean lastStepClimbing()
	{
		return stepClimbing;
	}

	private void beginStep(int[] tile)
	{
		stepSerial++;
		stepClimbing = gaitWalk != -1;
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
	 * Picks somewhere new to walk.
	 *
	 * <p>The roam bounds are built here rather than passed in. They describe a window
	 * around wherever the golem currently is, so they are only meaningful at the moment
	 * of choosing — and building one per golem per frame, as the caller used to, meant
	 * hundreds of throwaway objects a frame to serve the handful of golems that were
	 * actually choosing anything.
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
	 * nearest cardinal.
	 *
	 * <p>This used to pick whichever of the four cardinals the movement was most like,
	 * which was exact while the pathfinder only stepped north, south, east and west. Once
	 * diagonals were added it became wrong for half of all steps: a golem walking
	 * north-east faced north and crabbed sideways across the ground, and one walking
	 * south-west faced south and appeared to moonwalk.
	 *
	 * <p>Angles run 0 south, 512 west, 1024 north, 1536 east, so north is the zero of the
	 * arc-tangent and east is a quarter turn on from it. Computing it gives the diagonals
	 * their own headings for free, and any future movement that is not on an eight-point
	 * compass will come out right without this needing to change again.
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
	 * Performs a traversal exactly as it was observed.
	 *
	 * <p>No delay, span, stretch or walked-versus-glided decision is taken. The recording
	 * holds the position at every other cycle and the animation at every point it changed,
	 * and both are simply read back.
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
		//
		// These used to be worked out from the golem's *tile* while the offsets were added
		// to its exact position, so whatever fraction of a tile it happened to be standing
		// off centre was added on top of the distance. The golem slid past the far side by
		// that much and the arrival snapped it back — which is precisely what it looked
		// like. Tiles and fine units cannot be mixed halfway through a calculation.
		motionFrame = recorded.frameAt(0);
		motionFromX = fineX;
		motionFromY = fineY;

		float dx = transport.getToX() * TILE + TILE / 2f - fineX;
		float dy = transport.getToY() * TILE + TILE / 2f - fineY;
		float needed = (float) Math.sqrt(dx * dx + dy * dy);
		motionAxisX = needed < 1f ? 0f : dx / needed;
		motionAxisY = needed < 1f ? 1f : dy / needed;

		// The recording covered whatever distance the player covered; this golem may have
		// a different one to cross. Scale the forward part so it arrives exactly.
		int reach = recorded.reach();
		motionScale = reach <= TILE / 2 || needed < 1f ? 1f : needed / reach;

		// Face the way it is about to go, immediately. A golem still turning as a recording
		// starts performs the whole thing at the wrong angle.
		//
		// Turned by however the player was facing relative to the way they went. Climbing
		// down the rockslide the player faces the rock and moves away from it; a golem that
		// faced its direction of travel climbed down outwards, off the cliff. Only for a
		// traversal that goes somewhere — a ladder has no direction to be relative to.
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
	 * Which frame the recording says the animation should be on, or -1.
	 *
	 * <p>Read by the renderer so the animation and the movement share one clock. Without
	 * it the clip advances at the rate the client happens to be drawing at while the
	 * position advances on the simulation's cycle count, and the two drift apart — which
	 * looks like the animation skipping even when the recording is perfect.
	 */
	@Getter
	private int motionFrame = -1;

	/** Reports a decision to the journal, if one is listening. */
	private void note(RoamContext context, String what)
	{
		if (context != null && context.getJournal() != null)
		{
			context.getJournal().accept(this, what);
		}
	}

	/**
	 * Everything about this golem's current state, for the developer journal.
	 *
	 * <p>Deliberately one flat line per golem per tick. Working out why a golem did
	 * something has meant reading its position, its animation, its path and its intention
	 * together, and reconstructing those from separate debug lines after the fact is what
	 * has made several of these bugs take three attempts instead of one.
	 */
	String debugState()
	{
		return "tile=" + (fineX / TILE) + "," + (fineY / TILE) + "," + plane
			+ " fine=" + fineX + "," + fineY
			+ " orient=" + orientation + "/" + targetOrientation
			+ " anim=" + currentPoseAnimation()
			+ " tier=" + tier
			+ (walking ? " walking" : "")
			+ (stepping ? " stepping" : "")
			+ (transitionCycles > 0 ? " transit=" + transitionCycles + "/" + transitionTotal
				+ (transitionGlide ? " glide=" + transitionGlideCycles : "") : "")
			+ (gaitWalk != -1 ? " climbing gait=" + gaitWalk + "/" + gaitIdle : "")
			+ (motion != null ? " performing" : "")
			+ " path=" + path.size()
			+ (itinerary != null ? " itinerary" : "")
			+ (dwellRemaining > 0 ? " dwell=" + dwellRemaining : "");
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
		if (gaitWalk != -1)
		{
			return walking ? gaitWalk : gaitIdle;
		}
		return walking ? snapshot.getWalkAnimation() : snapshot.getIdlePoseAnimation();
	}

	/**
	 * The gait a golem climbs with, replacing its own for the length of the obstacle.
	 *
	 * <p>A long climb is not a long animation. The game does not play a climbing clip for
	 * eight ticks; it swaps the player's base animation set — the idle and the walk — and
	 * then walks them up the cliff a tile at a time, so the walk cycle happens to be a
	 * climb. That is why {@code human_climbing_loop} loops at all: it is a walk animation,
	 * and walk animations repeat once per tile.
	 *
	 * <p>Modelling it as a clip stretched across a duration was wrong in a way that could
	 * not be tuned out. Too short and the golem finished climbing in a fraction of a
	 * second; too long and it restarted the animation halfway up while sliding. Both were
	 * symptoms of animating something that should have been walking.
	 *
	 * <p>-1 when the golem is using its own gait, which is nearly always.
	 */
	private int gaitWalk = -1;
	private int gaitIdle = -1;

	/** Played once on arrival, or -1. The step-off at the top of a climb. */
	private int gaitFinish = -1;

	/** Its length, measured when the climb began while the models were to hand. */
	private int gaitFinishCycles;

	// ------------------------------------------------------- using a transport

	/**
	 * The clip playing while the golem climbs, jumps or squeezes through something.
	 *
	 * <p>All of these are the game's own human animations, which the golem can play
	 * because it shares the human rig — see {@link GolemContent#GOLEM_FRAMEMAP}. Nothing
	 * is authored and nothing is deformed; this is the same shared-{@code Animation}
	 * machinery the walk cycle already uses.
	 */
	private int transitionAnimation = -1;

	/**
	 * Cycles of the transition the golem is actually moving for.
	 *
	 * <p>Movement and animation are separate and do not last the same length of time. A
	 * stepping-stone hop was measured at 20ms resolution: the player covers the gap in
	 * <b>30 cycles — exactly one game tick — while the clip runs for 38</b>, so the last
	 * eight animate in place as they settle.
	 *
	 * <p>Gliding across the whole clip instead, which is what this used to do, means the
	 * golem is still drifting when it should have landed and never holds the end of the
	 * animation. That is the part of a hop that looks wrong without being obviously wrong.
	 */
	private int transitionGlideCycles;

	/**
	 * Cycles the golem holds still at the start of a traversal before moving.
	 *
	 * <p>Measured, not assumed. Four consecutive basalt hops each held for 33 cycles and
	 * then crossed in 12 — and the game's own script for the same kind of obstacle uses a
	 * 12-cycle movement window inside a longer action, which is the same shape arrived at
	 * from the other direction.
	 *
	 * <p>This is the part of a traversal that was never recorded. Without it the golem
	 * begins sliding on the animation's first frame, which no amount of adjusting the total
	 * duration can disguise.
	 */
	private int transitionGlideDelay;

	/**
	 * The recorded motion being performed, or null when none was available.
	 *
	 * <p>While this is set nothing about the traversal is being calculated. Position comes
	 * from the recording and so does the animation, which is the whole point: the shapes
	 * that kept fighting each other — a hop's wind-up, a climb's steady rise, a door's
	 * animate-then-teleport — are all just different recordings.
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
	 * Shortest a transport may take, in client cycles.
	 *
	 * <p>A floor rather than the duration itself. Every transport now runs for as long as
	 * its own table row says it does — a fixed length made a rock climb take the same two
	 * thirds of a second as stepping through a door, which is why golems were scaling
	 * shortcuts visibly faster than a player can.
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
		// A climb is walked rather than played, so it has no transition cycles at all —
		// but it is every bit as much "mid-obstacle" as a hop is. Without this the
		// watchdog sees a golem standing on a cliff face, calls that unwalkable ground,
		// and relocates it off the rocks halfway up.
		return transitionCycles > 0 || gaitWalk != -1;
	}

	/**
	 * Advances an in-progress transition, landing the golem when it completes.
	 *
	 * @return true if the golem is still mid-transition and should not move
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

			// Between clips — before the first starts, or after the last has ended — the
			// golem is itself: idle, or walking if the recording is moving it. This used to
			// keep whatever was last set, which before the first clip was nothing at all, so
			// golems stood in their bare model waiting to hop; and after the stile's climb
			// they stepped off still frozen in its final frame.
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

		// Step through the clip set as each phase runs out, so the golem takes hold,
		// hauls itself up and steps off rather than looping one clip for the whole
		// obstacle.
		phaseRemaining -= cycles;
		while (phaseRemaining <= 0 && phase + 1 < phaseClips.length)
		{
			phase++;
			phaseRemaining += phaseCycles[phase];
			transitionAnimation = phaseClips[phase];
		}

		if (transitionCycles > 0)
		{
			// Carry the golem across while the clip plays, rather than holding it still
			// and putting it down at the far end. A jump that starts and finishes with
			// the golem in two places and nothing in between reads as a teleport with an
			// animation attached, which is exactly what it was.
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
			relocate(transitionDestination);
			transitionDestination = null;
			landInInstance();
		}
		return false;
	}

	/** Where the glide started and ends, in the same 128ths of a tile as everything else. */
	private int transitionFromX;
	private int transitionFromY;
	private int transitionToX;
	private int transitionToY;

	/** Whether this transition moves the golem, or is played on the spot. */
	private boolean transitionGlide;

	/**
	 * How high the golem is lifted mid-jump, in client units at the peak.
	 *
	 * <p>A stepping-stone hop is an arc, not a slide. Without this the golem crosses the
	 * water at ground level looking like it is being dragged.
	 */
	private static final int JUMP_ARC_HEIGHT = 26;

	/**
	 * Extra height for the drawn model this frame, or zero.
	 *
	 * <p>Read by {@link FakeGolem} and added to the terrain height. A parabola across the
	 * transition, so the golem leaves the ground, peaks halfway and lands.
	 */
	int jumpArc()
	{
		if (!transitionGlide || transitionCycles <= 0)
		{
			return 0;
		}

		// You can only be in the air while you are moving.
		//
		// This used to arc across the whole transition, which was harmless while movement
		// filled it and wrong the moment it did not. A cathedral door holds still for
		// thirty cycles and then teleports, so the golem rose into the air and hung there
		// for the entire animation without going anywhere.
		int window = transitionGlideCycles > 0 ? transitionGlideCycles : transitionTotal;
		int elapsed = transitionTotal - transitionCycles - transitionGlideDelay;
		if (elapsed <= 0 || elapsed >= window)
		{
			return 0;
		}

		// And only for a leap. A door's single tile is a step through a doorway; lifting
		// the golem off the ground for it is not a jump, it is a hover.
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
