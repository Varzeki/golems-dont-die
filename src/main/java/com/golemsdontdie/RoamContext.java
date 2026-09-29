package com.golemsdontdie;

import java.util.*;
import java.util.function.*;
import lombok.*;

/**
 * The world as one golem sees it while deciding what to do next: passability, pathfinder,
 * transports, unlocks and the current tick, rather than five collaborators in a signature
 * called once per golem per frame. One instance, reused for every golem in the frame; it holds
 * no per-golem state.
 */
@Getter
@RequiredArgsConstructor
final class RoamContext
{
	private final IslandMemory memory;
	private final GolemPathfinder pathfinder;
	private final TransportNetwork transports;
	private final GolemAbilities abilities;

	/** How each obstacle is traversed. Set, not injected, so a golem sees the latest unlocks. */
	@Setter
	private ObstacleKnowledge knowledge;

	/** Where every obstacle is; wanted here for its duration, the only per-obstacle traversal
	 * time neither measured nor inferred from a median. */
	@Setter
	private ObstacleIndex obstacles;

	/**
	 * Asked about a golem that is about to cast off alone: true if it has been kept at the quayside
	 * to wait for a crew instead. See GolemCrews.offer. Null in tools with no crews.
	 */
	@Setter
	private BiPredicate<Golem, RoamContext> muster;

	/** Where every golem is, by region, so golems spread out. Null in tools that do not count. */
	@Setter
	private GolemCensus census;

	/** Which places are cold, hot and home, for the golems that care. Null in tools that do not. */
	@Setter
	private GolemClimate climates;

	/** Where a golem in view reports each crossing, for the obstacle record; null if none. */
	@Setter
	private Consumer<GolemTraversal> traversals;

	/** How willing a golem is to enter this tile's region: 1 unless crowded. */
	float roominess(int x, int y, int plane)
	{
		return census == null ? 1f : census.roominess(x, y, plane);
	}

	/**
	 * As {@link #roominess}, as one golem sees it: a sociable golem does not mind a crowd at all, and
	 * a crowd-shy one minds it more than most.
	 */
	float roominess(int x, int y, int plane, TransportMemory memory)
	{
		if (memory == null)
		{
			return roominess(x, y, plane);
		}
		if (memory.is(GolemTrait.SOCIABLE))
		{
			return 1f;
		}
		float room = roominess(x, y, plane);
		return memory.is(GolemTrait.CROWD_SHY) ? room * room : room;
	}

	/** How little a shortcut that keeps a crowded golem in place appeals. */
	private static final float STAYING_PUT = 0.1f;

	/**
	 * How much a shortcut from one tile to another appeals, from 0 to 1, given where golems are.
	 *
	 * <p>With room, this is the room at the far end; crowded, a way out is wanted and staying in
	 * the same region hardly appeals, which a walled place needs: Meiyerditch's ladders all land
	 * inside its own walls, leaving golems nowhere roomier to prefer.
	 */
	float appeal(int fromX, int fromY, int fromPlane, int toX, int toY, int toPlane)
	{
		return appeal(fromX, fromY, fromPlane, toX, toY, toPlane, null);
	}

	/**
	 * As {@link #appeal}, for one golem: a crowd weighs differently on a sociable golem than on a
	 * crowd-shy one. Only crowds — what a golem makes of the place itself is {@link #desire}, kept
	 * apart because a golem hemmed in by others should still take a way out it does not care for.
	 */
	float appeal(int fromX, int fromY, int fromPlane, int toX, int toY, int toPlane, TransportMemory memory)
	{
		if (census == null)
		{
			return 1f;
		}
		float here = roominess(fromX, fromY, fromPlane, memory);
		float there = roominess(toX, toY, toPlane, memory);
		if (here >= 1f)
		{
			return there;
		}
		if (fromPlane == toPlane && fromX >> 6 == toX >> 6 && fromY >> 6 == toY >> 6)
		{
			return STAYING_PUT;
		}
		return Math.min(1f, there / here);
	}

	/** What a golem makes of going from one tile to another, given its taste in places. */
	float desire(TransportMemory memory, int fromX, int fromY, int toX, int toY)
	{
		return climates == null ? 1f : climates.desire(memory, fromX, fromY, toX, toY);
	}

	/** How ready a golem is to leave where it is at all, which is less where it likes being. */
	float wanderlust(TransportMemory memory, int x, int y)
	{
		return climates == null ? 1f : climates.wanderlust(memory, x, y);
	}

	boolean crowdedRegion(int x, int y, int plane)
	{
		return census != null && census.roominess(x, y, plane) < 1f;
	}

	/**
	 * The route planner, so a golem in view can consider sailing. Set after construction: it
	 * needs the same singletons this context holds, so injecting both ways would be a cycle.
	 */
	@Setter
	private RoamPlanner planner;

	/** The shared model and animation cache, for clip lengths. Set after construction. */
	@Setter
	private GolemModelFactory models;

	/**
	 * The client's tick counter, used for cooldowns; set once per frame, and ticks rather than
	 * wall clock so cooldowns pause with the game.
	 */
	@Setter
	private int tick;

	/** How far through the tick this frame is, 0 to 1, so a golem in view glides along a route. */
	@Setter
	private float tickFraction;

	/** Whether this golem may spend one of the frame's path searches; set per golem as the plugin
	 * works down the roster, so the budget is shared rather than first-come. */
	@Setter
	private boolean mayPath;

	/**
	 * Whether an ocean search may still be run this frame. Crossings are cached per pair of ports,
	 * so almost every voyage is a lookup; the first golem to sail a route pays for an A* across a
	 * 2.4-million-tile sea.
	 */
	@Setter
	private boolean maySearchSea;

	/** "Restrict Golem ambition": golems stay on Wyrmscraig and never sail. Set each frame. */
	@Setter
	private boolean ambitionRestricted;

	void spendSeaSearch()
	{
		maySearchSea = false;
	}

	/** Golems standing on each tile this frame, keyed by {@link #tileKey}; null until filled. */
	@Setter
	private TileMap occupancy;

	/**
	 * Golems on a tile beyond which another should not go there: the game draws only so many
	 * things on one tile, and a pen of three hundred had them flashing in and out.
	 */
	static final int CROWD = 3;

	boolean crowded(int x, int y, int plane)
	{
		return occupancy != null && occupancy.getOrDefault(tileKey(x, y, plane), 0L) >= CROWD;
	}

	static long tileKey(int x, int y, int plane)
	{
		return ((long) plane << 40) | ((long) (x & 0xFFFFF) << 20) | (y & 0xFFFFF);
	}

	/** A space this many walkable tiles or larger is open ground. */
	static final int OPEN_AREA = 400;

	/** How long a measured area is trusted, in ticks, before doors may have changed it. */
	private static final int AREA_REFRESH_TICKS = 500;

	private final Map<Long, int[]> areas = new LinkedHashMap<Long, int[]>(1024, 0.75f, true)
	{
		/**
		 * Kept to the tiles asked about lately. Every tile a golem plans from is asked about, and over
		 * a long session with a big roster that was a map of most of the world, held for ever for
		 * answers that go stale in a minute anyway.
		 */
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, int[]> eldest)
		{
			return size() > MOST_AREAS;
		}
	};

	/** Tiles whose enclosed area is remembered at once; the least recently asked go first. */
	private static final int MOST_AREAS = 16384;

	/** Walkable tiles reachable from here, capped at {@link #OPEN_AREA}, cached for a while. */
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
