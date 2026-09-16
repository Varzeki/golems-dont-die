package com.golemsdontdie;

import java.util.Random;

/**
 * The area a golem is allowed to wander: everywhere the island map says is walkable.
 *
 * <p>There used to be a second, penned shape that kept golems within a few tiles of
 * the plinth they were made on. It is gone. Free roam turned out to be the only
 * version worth having, and keeping the alternative around only offered a way to make
 * the plugin worse.
 *
 * <p>No outer edge is needed. {@link IslandMemory} only knows about the island, so
 * asking it whether a tile is walkable is already asking whether the tile is on the
 * island — a golem cannot wander into the sea because the map has nothing to say
 * about the sea.
 */
class RoamBounds
{
	/**
	 * How far a golem looks for its next destination, in tiles.
	 *
	 * <p>Sampling from the whole island instead would have golems repeatedly setting
	 * off on island-long treks, which both reads wrong — a golem is a wandering lump,
	 * not a commuter — and makes every path search run to its node budget. A window
	 * around the golem gives the same eventual coverage through a random walk at a
	 * fraction of the cost.
	 */
	private static final int WANDER_WINDOW = 12;

	private final IslandMemory memory;
	private final int plane;
	private final int centreX;
	private final int centreY;

	/** Destinations to pass over — a crowded tile — or null. Never blocks walking through. */
	private final java.util.function.BiPredicate<Integer, Integer> avoid;

	RoamBounds(IslandMemory memory, int plane, int centreX, int centreY)
	{
		this(memory, plane, centreX, centreY, null);
	}

	RoamBounds(IslandMemory memory, int plane, int centreX, int centreY,
		java.util.function.BiPredicate<Integer, Integer> avoid)
	{
		this.memory = memory;
		this.plane = plane;
		this.centreX = centreX;
		this.centreY = centreY;
		this.avoid = avoid;
	}

	/** True if this tile should not be chosen as somewhere to go. */
	boolean avoids(int worldX, int worldY)
	{
		return avoid != null && avoid.test(worldX, worldY);
	}

	/** True if a golem may enter this world tile. */
	boolean contains(int worldX, int worldY)
	{
		return memory.isKnownWalkable(worldX, worldY, plane);
	}

	/** A candidate destination. May be unwalkable — the caller rejects and retries. */
	int[] sample(Random random)
	{
		return new int[]{
			centreX - WANDER_WINDOW + random.nextInt(WANDER_WINDOW * 2 + 1),
			centreY - WANDER_WINDOW + random.nextInt(WANDER_WINDOW * 2 + 1),
		};
	}
}
