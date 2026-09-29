package com.golemsdontdie;

import java.util.*;
import javax.inject.*;
import lombok.extern.slf4j.*;

/**
 * How many golems are in each part of the world, so that they spread out rather than gather.
 *
 * <p>Each golem's choice among nearby shortcuts is purely local, so golems settle where the map's
 * shape sends them: a simulated year put a third of every golem in Meiyerditch, a walled maze with
 * a dozen ladders and no way out on foot. This count is all golems know of each other.
 *
 * <p>Counted by map region and floor — 64 tiles square, about one leg of a walk — and recounted
 * every few ticks: it need only be roughly right, and a golem's move must stay one write.
 */
@Slf4j
@Singleton
class GolemCensus
{
	/** Golems in one region and floor that nobody minds. */
	static final int ROOMY = 5;

	/** Region ids are 16 bits and floors two, so everywhere has a slot. */
	private final int[] counts = new int[1 << 18];

	void recount(Iterable<Golem> golems)
	{
		begin();
		for (Golem golem : golems)
		{
			add(golem.getFineX() / Golem.TILE, golem.getFineY() / Golem.TILE, golem.getPlane());
		}
	}

	/** Starts a count, filled with {@link #add}. */
	void begin()
	{
		Arrays.fill(counts, 0);
	}

	void add(int x, int y, int plane)
	{
		counts[key(x, y, plane)]++;
	}

	void clear()
	{
		Arrays.fill(counts, 0);
	}

	/** Golems in the region and floor this tile is in. */
	int at(int x, int y, int plane)
	{
		return counts[key(x, y, plane)];
	}

	/**
	 * How willing a golem should be to go here: 1 where there is room, towards 0 where packed. A
	 * multiplier, never a bar — a golem with one way on takes it.
	 */
	float roominess(int x, int y, int plane)
	{
		int crowd = counts[key(x, y, plane)] - ROOMY;
		return crowd <= 0 ? 1f : 1f / (1f + crowd);
	}

	private static int key(int x, int y, int plane)
	{
		return ((x >> 6) << 10) | ((y >> 6) << 2) | (plane & 3);
	}
}
