package com.golemsdontdie;

import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

/**
 * What one golem has done, in six numbers: enough for its own page, and little enough to save
 * beside a roster of ten thousand.
 *
 * <p>Distance is sampled rather than counted. Nothing watches a far golem walk — it is moved along
 * a route by the tick — so this takes the ground between one census and the next, a few seconds
 * apart, and throws away any step too long to have been walked: that was a shortcut or a crossing,
 * and they are counted on their own.
 */
class GolemHistory
{
	/** Tiles between two samples beyond which the golem cannot have walked there. */
	private static final int WALKED_AT_MOST = 24;

	/** When the golem was first known, in epoch milliseconds; 0 if it was made before this was kept. */
	@Getter
	private long firstSeen;

	/** Shortcuts, doors, ladders and travel systems used. */
	@Getter
	private int transports;

	@Getter
	private int voyages;

	/** Tiles walked, to the nearest sample. */
	@Getter
	private int walked;

	/** The furthest tile from home the golem has stood on, and how far that is. */
	@Getter
	private int furthestX;
	@Getter
	private int furthestY;
	@Getter
	private int furthest;

	/** Where the golem was at the last sample; 0 before the first. */
	private int lastX;
	private int lastY;

	GolemHistory()
	{
		firstSeen = System.currentTimeMillis();
	}

	/** Puts back what was saved. The furthest distance is worked out again from home. */
	void restore(long firstSeen, int transports, int voyages, int walked, int furthestX, int furthestY,
		WorldPoint home)
	{
		this.firstSeen = firstSeen;
		this.transports = transports;
		this.voyages = voyages;
		this.walked = walked;
		this.furthestX = furthestX;
		this.furthestY = furthestY;
		this.furthest = furthestX == 0 && furthestY == 0 ? 0 : away(furthestX, furthestY, home);
	}

	void tookTransport()
	{
		transports++;
	}

	void sailed()
	{
		voyages++;
	}

	/**
	 * Notes where the golem is now. Called for every golem on the census pass, so it is a handful
	 * of comparisons and nothing else.
	 */
	void sample(int x, int y, WorldPoint home)
	{
		if (lastX != 0)
		{
			int step = Math.max(Math.abs(x - lastX), Math.abs(y - lastY));
			if (step <= WALKED_AT_MOST)
			{
				walked += step;
			}
		}
		lastX = x;
		lastY = y;

		int away = away(x, y, home);
		if (away > furthest)
		{
			furthest = away;
			furthestX = x;
			furthestY = y;
		}
	}

	/**
	 * How far a tile is from home.
	 *
	 * <p>Underground counts as the ground above it: a dungeon is drawn a hundred regions north of
	 * what it runs under, and a golem in the cave under the island is not six thousand tiles from
	 * home, it is under it.
	 */
	private static int away(int x, int y, WorldPoint home)
	{
		if (home == null)
		{
			return 0;
		}
		int above = y >= UNDERGROUND ? y - UNDERGROUND : y;
		return Math.max(Math.abs(x - home.getX()), Math.abs(above - home.getY()));
	}

	/** How far below the surface the underground is laid out, in tiles. */
	private static final int UNDERGROUND = 6400;
}
