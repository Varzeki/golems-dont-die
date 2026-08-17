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

	@Getter
	private final int plane;

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
	 * Advances the simulation.
	 *
	 * @param cycles client cycles (20ms each) since the last advance
	 * @param bounds where it may walk, re-derived each time so that flipping the free
	 *               roam setting takes effect on the golem's next decision
	 */
	boolean advance(int cycles, IslandMemory memory, GolemPathfinder pathfinder, boolean mayPath)
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
					if (!mayPath)
					{
						// This frame's search budget is spent. Stand still and try again
						// next frame rather than joining a stampede of path searches.
						return searched;
					}
					// Overshoot is discarded: a golem that dawdled a few cycles too long
					// is not worth carrying an error forward for.
					remaining = 0;
					searched = true;
					chooseDestination(memory, pathfinder);
					if (path.isEmpty())
					{
						// Nowhere to go — usually a golem boxed in by scenery. Wait and
						// try again rather than spinning on it.
						dwellRemaining = DWELL_MIN + random.nextInt(DWELL_SPREAD);
						return searched;
					}
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
	private void chooseDestination(IslandMemory memory, GolemPathfinder pathfinder)
	{
		int tileX = fineX / TILE;
		int tileY = fineY / TILE;
		path.clear();
		path.addAll(pathfinder.wanderPath(tileX, tileY, plane,
			new RoamBounds(memory, plane, tileX, tileY), random));
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

	private static int headingFor(int dx, int dy)
	{
		if (Math.abs(dx) >= Math.abs(dy))
		{
			return dx >= 0 ? EAST : WEST;
		}
		return dy >= 0 ? NORTH : SOUTH;
	}

	/** The animation this golem should be playing right now. -1 if it has none. */
	int currentPoseAnimation()
	{
		if (isDying())
		{
			return GolemContent.GOLEM_DEATH_ANIMATION;
		}
		return walking ? snapshot.getWalkAnimation() : snapshot.getIdlePoseAnimation();
	}
}
