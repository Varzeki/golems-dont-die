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

	/** Counted without an obstacle to name: a restored golem, or a hop with no row behind it. */
	void tookTransport()
	{
		tookTransport(null);
	}

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

	void sailed()
	{
		voyages++;
	}

	/**
	 * The last fifteen regions the golem arrived in, and how it got to each.
	 *
	 * <p>One int apiece — region, plane and manner packed together — and the array is not made
	 * until a golem goes somewhere, because ten thousand golems pay for anything kept per golem.
	 * The names are looked up when the page is opened rather than stored: they are the same
	 * fifteen strings for every golem that has been to the same place.
	 */
	private static final int KEPT = 15;

	private int[] journal;

	/** Where the last entry went, so the ring can be read back in order. */
	private int written;

	/** The region the last entry was for, so standing still writes nothing. */
	private int lastRegion = -1;

	/** How the golem got where it is going, set as it arrives and spent on the next entry. */
	private transient GolemTravel manner = GolemTravel.WALKED;

	/** Notes an obstacle as the golem steps off it. */
	void tookTransport(GolemTransport transport)
	{
		transports++;
		if (transport != null)
		{
			manner = GolemTravel.of(transport.getArchetype(), transport.getFromPlane(),
				transport.getToPlane());
		}
	}

	/** Notes a crossing as the golem steps ashore. */
	void cameAshore()
	{
		manner = GolemTravel.SAILED;
	}

	/**
	 * Writes the golem's arrival somewhere new, if it is somewhere new.
	 *
	 * <p>Called from the census pass with everything else that is counted per golem, so an entry
	 * costs a comparison for the golems that have not moved region, which is nearly all of them
	 * nearly always.
	 */
	private void note(int x, int y, int plane)
	{
		int region = (x >> 6) << 8 | (y >> 6);
		if (region == lastRegion)
		{
			return;
		}
		boolean first = lastRegion == -1;
		lastRegion = region;
		if (first)
		{
			// Where it was standing when the plugin first looked is not a journey.
			return;
		}

		// Walked into the dark: that is exploring, and reads better than walking.
		GolemTravel how = manner == GolemTravel.WALKED && y >= UNDERGROUND
			? GolemTravel.EXPLORED : manner;
		manner = GolemTravel.WALKED;

		if (journal == null)
		{
			journal = new int[KEPT];
			java.util.Arrays.fill(journal, -1);
		}
		journal[written % KEPT] = region << 6 | (plane & 3) << 4 | how.ordinal();
		written++;
	}

	/**
	 * The journal, newest first: region, plane and manner, three to an entry. Empty for a golem
	 * that has not been anywhere yet.
	 */
	int[][] travels()
	{
		if (journal == null)
		{
			return new int[0][];
		}
		java.util.List<int[]> out = new java.util.ArrayList<>(KEPT);
		for (int i = 1; i <= KEPT; i++)
		{
			int packed = journal[Math.floorMod(written - i, KEPT)];
			if (packed >= 0)
			{
				out.add(new int[]{packed >>> 6, packed >>> 4 & 3, packed & 15});
			}
		}
		return out.toArray(new int[0][]);
	}

	/**
	 * Takes a crossing back off the tally: a golem held at the quayside to wait for a crew had
	 * already been counted out, and will be counted again when it really goes.
	 */
	void unsailed()
	{
		voyages = Math.max(0, voyages - 1);
	}

	/**
	 * Notes where the golem is now. Called for every golem on the census pass, so it is a handful
	 * of comparisons and nothing else.
	 */
	void sample(int x, int y, int plane, WorldPoint home)
	{
		note(x, y, plane);
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
