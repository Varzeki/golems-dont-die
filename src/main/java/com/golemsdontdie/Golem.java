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
		this.plane = itinerary.destination().getPlane();

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
				transportMemory.beginShoreLeaveOnArrival(tick);
				RoamPlanner planner = context.getPlanner();
				if (planner != null)
				{
					relocate(planner.snapToMesh(currentTile()));
				}
			}
		}
	}

	void advanceFar(RoamContext context, RoamPlanner planner)
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
			this.plane = itinerary.destination().getPlane();
			return;
		}

		WorldPoint at = itinerary == null ? currentTile() : itinerary.destination();
		this.fineX = at.getX() * TILE + TILE / 2;
		this.fineY = at.getY() * TILE + TILE / 2;
		this.plane = at.getPlane();

		itinerary = planner.plan(at, tick, random, transportMemory, context);
	}

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
						dwellRemaining = DWELL_MIN + random.nextInt(DWELL_SPREAD);
						return searched;
					}
					failedSearches = 0;
					continue;
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
						|| transportMemory.onCooldown(transport.getIndex(), context.getTick())
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
		return context.getTransports().hasOrigin(transport.getToX(), transport.getToY());
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

		if (transportMemory.onCooldown(transport.getIndex(), context.getTick())
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
		transportMemory.used(transport.getIndex(), transport, context.getTick());

		int animation = transport.animation();
		if (animation < 0 || animation == snapshot.getWalkAnimation())
		{
			relocate(transport.destination());
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
		int[] clips = transport.animations();
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
		int measuredTicks = MeasuredShortcuts.ticksFor(transport.getObjectId());
		int authored = transport.getDuration() * CYCLES_PER_TICK;
		int wanted = measuredTicks > 0 ? measuredTicks * CYCLES_PER_TICK
			: authored > 0 ? authored
			: total;
		wanted = Math.max(TRANSITION_MIN_CYCLES, wanted);

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
			new RoamBounds(context.getMemory(), plane, tileX, tileY), random));
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
		return walking ? snapshot.getWalkAnimation() : snapshot.getIdlePoseAnimation();
	}

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
		return transitionCycles > 0;
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
				float done = 1f - (transitionCycles / (float) transitionTotal);
				fineX = transitionFromX + Math.round((transitionToX - transitionFromX) * done);
				fineY = transitionFromY + Math.round((transitionToY - transitionFromY) * done);
			}
			return true;
		}

		transitionCycles = 0;
		transitionAnimation = -1;
		transitionGlide = false;
		phaseClips = new int[0];
		phaseCycles = new int[0];
		phase = 0;
		if (transitionDestination != null)
		{
			relocate(transitionDestination);
			transitionDestination = null;
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
		float done = 1f - (transitionCycles / (float) transitionTotal);
		// 4t(1-t) peaks at 1 when t is a half, and is zero at both ends.
		return Math.round(JUMP_ARC_HEIGHT * 4f * done * (1f - done));
	}
}
