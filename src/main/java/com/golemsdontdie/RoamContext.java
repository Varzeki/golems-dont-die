package com.golemsdontdie;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/**
 * The world as one golem sees it while deciding what to do next.
 *
 * <p>{@link Golem#advance} needs the passability map, the pathfinder, the transport
 * network, what the player has unlocked, and the current tick. Passing five collaborators
 * through a per-frame call — once per golem, and there can be a thousand — is the kind of
 * signature that grows another argument every phase and is miserable to read by the third.
 *
 * <p>One instance, owned by the plugin and reused for every golem in the frame. It holds
 * no per-golem state: everything specific to a golem lives on the golem.
 */
@Getter
@RequiredArgsConstructor
final class RoamContext
{
	private final IslandMemory memory;
	private final GolemPathfinder pathfinder;
	private final TransportNetwork transports;
	private final GolemAbilities abilities;

	/**
	 * What is known about how each obstacle is traversed.
	 *
	 * <p>Set rather than passed to the constructor so that a golem mid-frame always sees
	 * the current state: the player can unlock an obstacle at any moment by using it, and
	 * a copy taken at start-up would go stale the first time they did.
	 */
	@Setter
	private ObstacleKnowledge knowledge;

	/**
	 * Where every obstacle is, and how long the cache says it takes.
	 *
	 * <p>Wanted here for the duration rather than the positions: it is the only source of
	 * a per-obstacle traversal time that is neither measured nor inferred from a median.
	 */
	@Setter
	private ObstacleIndex obstacles;

	/**
	 * Where a golem reports what it just decided, or null when nobody is listening.
	 *
	 * <p>Only wired up while the developer journal is on. A golem's behaviour is the
	 * product of a transport roll, a knowledge lookup and a pacing decision, and seeing the
	 * outcome without the reasoning has meant guessing at which of the three went wrong.
	 */
	@Setter
	private java.util.function.BiConsumer<Golem, String> journal;

	/**
	 * The route planner, so a golem in view can consider sailing.
	 *
	 * <p>Set after construction rather than injected, because the planner needs the same
	 * singletons this context carries and wiring it both ways in the constructor would be a
	 * cycle for no benefit.
	 */
	@Setter
	private RoamPlanner planner;

	/**
	 * The shared model and animation cache, so a golem can ask how long a clip runs for.
	 *
	 * <p>Set after construction for the same reason the planner is.
	 */
	@Setter
	private GolemModelFactory models;

	/**
	 * The client's tick counter, used for cooldowns.
	 *
	 * <p>Set once per frame rather than read per golem. Ticks rather than wall clock so
	 * that cooldowns advance with the game and pause with it.
	 */
	@Setter
	private int tick;

	/**
	 * Whether this golem may spend one of the frame's path searches.
	 *
	 * <p>Set per golem by the plugin as it works down the roster, so the budget is shared
	 * across all of them rather than each golem searching whenever it feels like it.
	 */
	@Setter
	private boolean mayPath;

	/**
	 * Whether an ocean search may still be run this frame.
	 *
	 * <p>Crossings are cached per pair of ports, so in a settled session almost every
	 * voyage is a map lookup. The exception is the first golem to sail a given route,
	 * which pays for an A* across a 2.4-million-tile sea — far too much to run twice in
	 * one frame, and enough to be worth rationing even once.
	 *
	 * <p>A golem refused a search simply does not sail this frame and considers it again
	 * on its next decision, which is indistinguishable from it having chosen to stay.
	 */
	@Setter
	private boolean maySearchSea;

	/** Set by the planner when it spends the frame's sea search. */
	void spendSeaSearch()
	{
		maySearchSea = false;
	}

	/**
	 * Golems standing on each tile this frame, keyed by {@link #tileKey}. Null until the
	 * plugin fills it.
	 */
	@Setter
	private java.util.Map<Long, Integer> occupancy;

	/**
	 * Golems on a tile beyond which another should not choose to go there.
	 *
	 * <p>The game draws only so many things on one tile, and a pen with three hundred golems
	 * in it had them flashing in and out as the client picked a different few each frame.
	 * Keeping them from piling up in the first place is better than choosing which to hide.
	 */
	static final int CROWD = 3;

	boolean crowded(int x, int y, int plane)
	{
		return occupancy != null && occupancy.getOrDefault(tileKey(x, y, plane), 0) >= CROWD;
	}

	static long tileKey(int x, int y, int plane)
	{
		return ((long) plane << 40) | ((long) (x & 0xFFFFF) << 20) | (y & 0xFFFFF);
	}

	/** A space this many walkable tiles or larger is open ground. */
	static final int OPEN_AREA = 400;

	/** How long a measured area is trusted, in ticks, before doors may have changed it. */
	private static final int AREA_REFRESH_TICKS = 500;

	private final java.util.Map<Long, int[]> areas = new java.util.HashMap<>();

	/** Walkable tiles reachable from here, up to {@link #OPEN_AREA}, measured once in a while. */
	int enclosedArea(int x, int y, int plane)
	{
		long key = tileKey(x, y, plane);
		int[] cached = areas.get(key);
		if (cached != null && tick - cached[1] < AREA_REFRESH_TICKS)
		{
			return cached[0];
		}
		int size = pathfinder.areaSize(x, y, plane, OPEN_AREA);
		areas.put(key, new int[]{size, tick});
		return size;
	}
}
