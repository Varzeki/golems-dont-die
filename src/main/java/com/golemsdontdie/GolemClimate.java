package com.golemsdontdie;

import java.util.*;
import javax.inject.*;
import lombok.extern.slf4j.*;

/**
 * Where it is cold, where it is hot, and where home is, so that a golem with a taste for one of
 * them can be found there.
 *
 * <p>Curated by name against the place names the plugin already ships: every entry below is a name
 * the map or the region list gives that place, and the names are resolved to regions on load, so a
 * name that no longer exists costs nothing but a debug line. Places are marked a whole region at a
 * time, which is as fine as this needs to be — a taste in weather is not a taste in tiles.
 *
 * <p>From those regions a distance field is spread across the world in region steps, and that is
 * the whole of it: a golem likes somewhere in proportion to how near it is to the nearest place of
 * its kind. {@link #liking} is that, and {@link #desire} is what a golem makes of moving from one
 * place to another — never above 1, so a taste can only ever hold a golem back, not send it
 * somewhere the rest of its plan did not offer.
 */
@Slf4j
@Singleton
class GolemClimate
{
	/**
	 * Snow and ice.
	 *
	 * <p>Underground places are named separately where they are cold in their own right; a cave
	 * under a glacier is not, being a cave. Nothing here is a name two places share.
	 */
	private static final String[] COLD = {
		"Weiss", "Weissmere", "Weiss Melt", "Trollweiss Mountain", "Ice Path", "Trollheim",
		"Death Plateau", "Troll Stronghold", "Mountain Camp", "White Wolf Mountain", "Ice Mountain",
		"Ice Queen's Lair", "Asgarnian Ice Dungeon", "Polar Eagle Cave", "Ghorrock Dungeon",
		"Rellekka", "Neitiznot", "Jatizso", "Iceberg", "Ungael", "Waterbirth Island",
		"Wintertodt", "The Wintertodt", "Northern Tundras", "The Darkfrost", "Mount Quidamortem",
		"Winttumber Island",
	};

	/**
	 * Desert and lava, which a golem that likes the heat makes no distinction between.
	 *
	 * <p>"Bandit Camp" is deliberately absent: the desert has one and so does the Wilderness, and
	 * a name that means two places would mark them both.
	 */
	private static final String[] HOT = {
		"Al Kharid", "Shantay Pass", "Kharidian Desert", "Bedabin Camp", "Desert Mining Camp",
		"Quarry", "Pollnivneach", "Nardah", "Uzer", "Emir's Arena", "Desert Eagle Cave",
		"Kalphite Lair", "Kalphite Cave", "Agility Pyramid", "Pyramid", "Sophanem", "Menaphos",
		"Necropolis", "Ruins of Ullek", "Ruins of Unkah", "Smoke Dungeon",
		"Karamja", "Musa Point", "Mor Ul Rek", "Mount Karuulm", "Volcanic Mine", "Chasm of Fire",
		"Lava Maze", "Lava Dragon Isle", "Avium Savannah", "Locus Oasis",
	};

	/** Regions across the world, each side. A region ID is {@code x >> 6 << 8 | y >> 6}. */
	private static final int SIDE = 256;

	/** Distance in regions given to somewhere nothing of the kind was reached from. */
	private static final int UNREACHED = 127;

	/**
	 * How far a taste is felt, in regions: most of the world, so that a golem always has some idea
	 * which way the snow is. The slope this far out is very shallow and only its order is used —
	 * which of the ways on offer leads nearer — never as a chance in itself.
	 */
	private static final int REACH = 64;

	/** How much somewhere just outside appeals, against being in the place itself. */
	private static final float NEAR = 0.6f;

	/** How little somewhere out of reach appeals, against being in the place itself. */
	private static final float FLOOR = 0.35f;

	/** Region steps to the nearest cold place, the nearest hot one, and home, or {@link #UNREACHED}. */
	private byte[] toCold;
	private byte[] toHot;
	private byte[] toHome;

	/** Resolves the curated names and spreads the distance fields. Call once, after the names load. */
	void learn(PlaceNames names)
	{
		Set<Integer> cold = names.regionsNamed(COLD);
		Set<Integer> hot = names.regionsNamed(HOT);
		Set<Integer> home = new HashSet<>();
		for (int region : GolemContent.ISLAND_REGIONS)
		{
			home.add(region);
		}
		toCold = spread(cold);
		toHot = spread(hot);
		toHome = spread(home);
		log.debug("Climate: {} cold regions, {} hot", cold.size(), hot.size());
	}

	/** Chebyshev distance in regions from every region to the nearest of the given ones. */
	private static byte[] spread(Set<Integer> seeds)
	{
		byte[] grid = new byte[SIDE * SIDE];
		Arrays.fill(grid, (byte) UNREACHED);
		int[] queue = new int[SIDE * SIDE];
		int tail = 0;
		for (int region : seeds)
		{
			grid[region] = 0;
			queue[tail++] = region;
		}
		for (int head = 0; head < tail; head++)
		{
			int region = queue[head];
			int step = grid[region] + 1;
			if (step >= UNREACHED)
			{
				continue;
			}
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					int x = (region >> 8) + dx;
					int y = (region & 255) + dy;
					if (x < 0 || y < 0 || x >= SIDE || y >= SIDE || grid[x << 8 | y] <= step)
					{
						continue;
					}
					grid[x << 8 | y] = (byte) step;
					queue[tail++] = x << 8 | y;
				}
			}
		}
		return grid;
	}

	/**
	 * Whether a golem cares where it is at all. Most do not, and pay nothing for any of this.
	 */
	boolean cares(TransportMemory memory)
	{
		return toCold != null && memory != null
			&& (memory.is(GolemTrait.LIKES_THE_COLD) || memory.is(GolemTrait.LIKES_THE_HEAT)
			|| memory.is(GolemTrait.TEMPERATE) || memory.is(GolemTrait.HOMESICK));
	}

	/**
	 * How much a golem likes being at a tile, from {@link #FLOOR} to 1.
	 *
	 * <p>A golem picking where to walk takes the leg it likes best of the few it found, which over
	 * a few hours is what carries it to the snow and, once there, keeps it in it.
	 */
	float liking(TransportMemory memory, int x, int y)
	{
		if (!cares(memory))
		{
			return 1f;
		}
		float liking = 1f;
		if (memory.is(GolemTrait.LIKES_THE_COLD))
		{
			liking *= nearness(toCold, x, y, false);
		}
		if (memory.is(GolemTrait.LIKES_THE_HEAT))
		{
			liking *= nearness(toHot, x, y, false);
		}
		if (memory.is(GolemTrait.TEMPERATE))
		{
			liking *= farness(toCold, x, y) * farness(toHot, x, y);
		}
		if (memory.is(GolemTrait.HOMESICK))
		{
			liking *= nearness(toHome, x, y, true);
		}
		return liking;
	}

	/**
	 * How much a golem would rather be at the far tile than the near one, from 0 to 1: 1 unless it
	 * is somewhere it likes and the offer is somewhere it likes less.
	 *
	 * <p>The shortfall counts twice over, which is deliberate: {@link #liking} decides which way a
	 * golem goes out of what its plan found, and this is what it makes of one particular offer —
	 * turning down the shortcut out of the snow it came for.
	 */
	float desire(TransportMemory memory, int fromX, int fromY, int toX, int toY)
	{
		if (!cares(memory))
		{
			return 1f;
		}
		float here = liking(memory, fromX, fromY);
		float there = liking(memory, toX, toY);
		return there >= here ? 1f : there * there / (here * here);
	}

	/** How ready a golem that likes where it is remains to go anywhere else. */
	private static final float SETTLED = 0.25f;

	/**
	 * How ready a golem is to leave where it is at all, as a multiplier on the chances of looking
	 * for a shortcut or a boat: a golem standing in the snow it came for is in no hurry to be
	 * anywhere.
	 *
	 * <p>Wanted as well as {@link #desire}, and not instead of it, because turning shortcuts down
	 * one at a time does not keep a golem anywhere: a town offers a dozen, each rolled for
	 * separately, and one of them always wins. This is the roll that keeps it still.
	 */
	float wanderlust(TransportMemory memory, int x, int y)
	{
		if (!cares(memory))
		{
			return 1f;
		}
		// Only a golem that went somewhere settles there. A temperate golem is not looking for
		// anywhere in particular — it is avoiding two — so it never stops travelling.
		boolean arrived = memory.is(GolemTrait.LIKES_THE_COLD) && isCold(x, y)
			|| memory.is(GolemTrait.LIKES_THE_HEAT) && isHot(x, y)
			|| memory.is(GolemTrait.HOMESICK) && nearness(toHome, x, y, true) >= 1f;
		return arrived ? SETTLED : 1f;
	}

	/**
	 * @param underAsAbove whether a tile underground counts as being where it is underneath.
	 *                     True of home, which a golem is near or is not — the island's own cave is
	 *                     part of it. False of weather, which a cave has none of.
	 */
	private static float nearness(byte[] grid, int x, int y, boolean underAsAbove)
	{
		// Only a cave has ground above it to count as; somewhere laid out apart from the map is
		// taken as itself.
		int above = WorldLayout.groundAbove(y);
		int region = x >> 6 << 8 | (underAsAbove && above >= 0 ? above : y) >> 6;
		if (region < 0 || region >= grid.length)
		{
			return FLOOR;
		}
		int away = grid[region];
		if (away == 0)
		{
			return 1f;
		}
		// Being in the place is worth a step down from anywhere outside it, which is what keeps a
		// golem in the snow once it is there; past that the slope is gentle, and only wanted for
		// its direction — it is what tells a golem which way the snow is.
		return NEAR - (NEAR - FLOOR) * Math.min(1f, (away - 1) / (float) (REACH - 1));
	}

	/**
	 * The mirror of {@link #nearness}, for the golem that wants none of it: being in the place is
	 * as bad as it gets, just outside is little better, and a long way off is all it asks.
	 */
	private static float farness(byte[] grid, int x, int y)
	{
		int region = x >> 6 << 8 | y >> 6;
		if (region < 0 || region >= grid.length)
		{
			return 1f;
		}
		int away = grid[region];
		if (away == 0)
		{
			return FLOOR;
		}
		return NEAR + (1f - NEAR) * Math.min(1f, (away - 1) / (float) (REACH - 1));
	}

	/** Whether a tile is one of the cold places. */
	boolean isCold(int x, int y)
	{
		return toCold != null && toCold[x >> 6 << 8 | y >> 6] == 0;
	}

	/** Whether a tile is one of the hot places. */
	boolean isHot(int x, int y)
	{
		return toHot != null && toHot[x >> 6 << 8 | y >> 6] == 0;
	}
}
