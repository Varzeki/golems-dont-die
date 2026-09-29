package com.golemsdontdie;

import java.io.*;
import java.util.*;
import lombok.*;
import net.runelite.api.coords.*;
import static com.golemsdontdie.RouteGeometry.span;

/**
 * What one golem has done, in six numbers and a short journal: enough for its own page, and little
 * enough to save beside a roster of ten thousand.
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
	 * The last twenty places the golem arrived in, and how it got to each.
	 *
	 * <p>One int apiece — region, plane and manner packed together — and the array is not made
	 * until a golem goes somewhere, because ten thousand golems pay for anything kept per golem.
	 * The names are looked up when the page is opened rather than stored: they are the same
	 * strings for every golem that has been to the same place.
	 */
	private static final int KEPT = 20;

	private int[] journal;

	/** When each entry was written, in minutes since the epoch: what "an hour ago" is worked out from. */
	private int[] when;

	/** The name of the place the last entry was written for, so walking about inside it writes nothing. */
	private String lastPlace;

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
	private void note(int x, int y, int plane, PlaceNames names)
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

		// Walked off the surface — into a cave, or somewhere laid out apart from the map, such as
		// God Wars or Zanaris: that is exploring, and reads better than walking.
		GolemTravel how = manner == GolemTravel.WALKED && !WorldLayout.isSurface(y)
			? GolemTravel.EXPLORED : manner;
		manner = GolemTravel.WALKED;

		// By name, as the page shows it: a place is several regions, and walking from one into the
		// next wrote "Walked to Wyrmscraig" over and over. A walk to where the last entry already
		// is, or to somewhere with no name to write, is not a journey; a climb or a crossing is.
		if (names != null)
		{
			if (lastPlace == null && written > 0)
			{
				// A journal read back from the save knows its last place by region only.
				int newest = journal[Math.floorMod(written - 1, KEPT)] >>> 6;
				lastPlace = names.nameFor((newest >> 8) * 64 + 32, (newest & 0xff) * 64 + 32, plane);
			}
			String place = names.nameFor((region >> 8) * 64 + 32, (region & 0xff) * 64 + 32, plane);
			boolean walked = how == GolemTravel.WALKED || how == GolemTravel.EXPLORED;
			if (place == null || walked && place.equals(lastPlace))
			{
				return;
			}
			lastPlace = place;
		}

		if (journal == null)
		{
			journal = new int[KEPT];
			when = new int[KEPT];
			Arrays.fill(journal, -1);
		}
		journal[written % KEPT] = region << 6 | (plane & 3) << 4 | how.ordinal();
		when[written % KEPT] = (int) (System.currentTimeMillis() / 60_000L);
		written++;
	}

	/**
	 * The journal, newest first: region, plane, manner and the minute it was written, four to an
	 * entry. Empty for a golem that has not been anywhere yet.
	 */
	int[][] travels()
	{
		if (journal == null)
		{
			return new int[0][];
		}
		java.util.List<int[]> out = new ArrayList<>(KEPT);
		for (int i = 1; i <= KEPT; i++)
		{
			int packed = journal[Math.floorMod(written - i, KEPT)];
			if (packed >= 0)
			{
				out.add(new int[]{packed >>> 6, packed >>> 4 & 3, packed & 15,
					when[Math.floorMod(written - i, KEPT)]});
			}
		}
		return out.toArray(new int[0][]);
	}

	/**
	 * The journal for the save file, oldest first: each entry's packed region, plane and manner in
	 * three bytes, then its minute — the first in full, the rest as minutes since the one before,
	 * which are small — in base 64. About six characters an entry rather than the twenty the
	 * numbers written out would take, over a roster of thousands. Empty for no journal.
	 */
	String journalCode()
	{
		if (journal == null)
		{
			return "";
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int before = 0;
		for (int i = Math.min(written, KEPT); i >= 1; i--)
		{
			int at = Math.floorMod(written - i, KEPT);
			int packed = journal[at];
			if (packed < 0)
			{
				continue;
			}
			out.write(packed >>> 16);
			out.write(packed >>> 8);
			out.write(packed);
			writeVarint(out, out.size() == 3 ? when[at] : Math.max(0, when[at] - before));
			before = when[at];
		}
		return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
	}

	/** Puts back a journal written by {@link #journalCode()}; anything unreadable is dropped. */
	void restoreJournal(String code)
	{
		if (code == null || code.isEmpty())
		{
			return;
		}
		try
		{
			ByteArrayInputStream in = new ByteArrayInputStream(Base64.getUrlDecoder().decode(code));
			int minute = 0;
			boolean first = true;
			while (in.available() >= 4)
			{
				int packed = in.read() << 16 | in.read() << 8 | in.read();
				int time = readVarint(in);
				minute = first ? time : minute + time;
				first = false;
				if (journal == null)
				{
					journal = new int[KEPT];
					when = new int[KEPT];
					Arrays.fill(journal, -1);
				}
				journal[written % KEPT] = packed;
				when[written % KEPT] = minute;
				written++;
			}
		}
		catch (IllegalArgumentException e)
		{
			journal = null;
			when = null;
			written = 0;
		}
	}

	private static void writeVarint(ByteArrayOutputStream out, int value)
	{
		while ((value & ~0x7F) != 0)
		{
			out.write(value & 0x7F | 0x80);
			value >>>= 7;
		}
		out.write(value);
	}

	private static int readVarint(ByteArrayInputStream in)
	{
		int value = 0;
		for (int shift = 0; shift < 35; shift += 7)
		{
			int b = in.read();
			if (b < 0)
			{
				throw new IllegalArgumentException("journal ends mid-entry");
			}
			value |= (b & 0x7F) << shift;
			if ((b & 0x80) == 0)
			{
				return value;
			}
		}
		throw new IllegalArgumentException("journal time too long");
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
		sample(x, y, plane, home, null);
	}

	/** @param names what places are called, to keep walks about one place out of the journal */
	void sample(int x, int y, int plane, WorldPoint home, PlaceNames names)
	{
		note(x, y, plane, names);
		if (lastX != 0)
		{
			int step = span(x - lastX, y - lastY);
			if (step <= WALKED_AT_MOST)
			{
				walked += step;
			}
		}
		lastX = x;
		lastY = y;

		// -1 for somewhere with no ground above it to measure from: not far, just unmeasurable.
		int away = away(x, y, home);
		if (away > furthest)
		{
			furthest = away;
			furthestX = x;
			furthestY = y;
		}
	}

	/**
	 * How far a tile is from home, or -1 where that cannot be said.
	 *
	 * <p>A cave counts as the ground above it: it is laid out a hundred regions north of what it runs
	 * under, and a golem in the cave under the island is not six thousand tiles from home, it is
	 * under it. Somewhere laid out apart from the map — God Wars, TzHaar — is under nowhere that can
	 * be named, and a number measured to it would be a number about the layout, not the golem.
	 */
	private static int away(int x, int y, WorldPoint home)
	{
		if (home == null)
		{
			return 0;
		}
		int above = WorldLayout.groundAbove(y);
		if (above < 0)
		{
			return -1;
		}
		return span(x - home.getX(), above - WorldLayout.groundAbove(home.getY()));
	}
}
