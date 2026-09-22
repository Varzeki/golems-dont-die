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

	/** Coordinates this far north are underground. */
	private static final int UNDERGROUND = 4160;

	/** The bit in {@link #floors} that means the golem has been below ground. */
	private static final int UNDER = 1 << 2;

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

	/** The highest floor reached, in the low two bits, and {@link #UNDER} if it has been below. */
	@Getter
	private int floors;

	/** Where the golem was at the last sample; 0 before the first. */
	private int lastX;
	private int lastY;

	GolemHistory()
	{
		firstSeen = System.currentTimeMillis();
	}

	/** Puts back what was saved. The furthest distance is worked out again from home. */
	void restore(long firstSeen, int transports, int voyages, int walked, int furthestX, int furthestY,
		int floors, WorldPoint home)
	{
		this.firstSeen = firstSeen;
		this.transports = transports;
		this.voyages = voyages;
		this.walked = walked;
		this.furthestX = furthestX;
		this.furthestY = furthestY;
		this.floors = floors;
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
	void sample(int x, int y, int plane, WorldPoint home)
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

		if (plane > (floors & 3))
		{
			floors = (floors & ~3) | plane;
		}
		if (y >= UNDERGROUND)
		{
			floors |= UNDER;
		}

		int away = away(x, y, home);
		if (away > furthest)
		{
			furthest = away;
			furthestX = x;
			furthestY = y;
		}
	}

	/** The highest floor the golem has been up to, 0 for ground level. */
	int getHighestFloor()
	{
		return floors & 3;
	}

	boolean hasBeenUnderground()
	{
		return (floors & UNDER) != 0;
	}

	private static int away(int x, int y, WorldPoint home)
	{
		return home == null ? 0 : Math.max(Math.abs(x - home.getX()), Math.abs(y - home.getY()));
	}
}
