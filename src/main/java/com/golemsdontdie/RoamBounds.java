package com.golemsdontdie;

import java.util.*;
import java.util.function.*;

/**
 * The area a golem is allowed to wander: everywhere the island map says is walkable.
 *
 * <p>A penned shape holding golems near their plinth used to exist too. No outer edge is needed:
 * {@link IslandMemory} only knows the island, so asking whether a tile is walkable asks that.
 */
class RoamBounds
{
	/**
	 * How far a golem looks for its next destination, in tiles. Sampling the whole island
	 * sends golems on island-long treks and runs every path search to its node budget; a
	 * window reaches the same coverage by random walk.
	 */
	private static final int WANDER_WINDOW = 12;

	private final IslandMemory memory;
	private final int plane;
	private final int centreX;
	private final int centreY;

	/** Destinations to pass over - a crowded tile - or null. Never blocks walking through. */
	private final BiPredicate<Integer, Integer> avoid;

	RoamBounds(IslandMemory memory, int plane, int centreX, int centreY)
	{
		this(memory, plane, centreX, centreY, null);
	}

	RoamBounds(IslandMemory memory, int plane, int centreX, int centreY,
		BiPredicate<Integer, Integer> avoid)
	{
		this.memory = memory;
		this.plane = plane;
		this.centreX = centreX;
		this.centreY = centreY;
		this.avoid = avoid;
	}

	boolean avoids(int worldX, int worldY)
	{
		return avoid != null && avoid.test(worldX, worldY);
	}

	/** True if a golem may enter this world tile. */
	boolean contains(int worldX, int worldY)
	{
		return memory.isKnownWalkable(worldX, worldY, plane);
	}

	/** A candidate destination. May be unwalkable - the caller rejects and retries. */
	int[] sample(Random random)
	{
		return new int[]{
			centreX - WANDER_WINDOW + random.nextInt(WANDER_WINDOW * 2 + 1),
			centreY - WANDER_WINDOW + random.nextInt(WANDER_WINDOW * 2 + 1),
		};
	}
}
