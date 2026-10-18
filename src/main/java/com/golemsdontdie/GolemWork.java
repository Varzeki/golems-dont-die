package com.golemsdontdie;

import lombok.*;

/**
 * Something a golem in view can do with its hands for a while: chop a tree, mine a rock, fish,
 * cook, spin, rake a patch. Only ever performed, never done: the tree stays standing, the rock
 * keeps its ore and nothing lands in anyone's inventory, as a golem cannot open a door either.
 *
 * <p>The animations are the player's own, which golems can play because they share the human rig.
 * The tools are the wield models the game draws in a player's hand, read from the cache. Those sit
 * where a human hand is, which is {@link GolemModelFactory#TOOL_DROP_Y} above a golem's fist.
 */
@Getter
@RequiredArgsConstructor
enum GolemWork
{
	// Animation, then the tool's wield model or -1 for bare hands.
	WOODCUTTING(879, 510),
	MINING(625, 509),
	NET_FISHING(621, 551),
	BIG_NET_FISHING(620, 489),
	ROD_FISHING(623, 562),
	CAGE_FISHING(619, 531),
	HARPOON_FISHING(618, 503),
	COOKING_ON_RANGE(896, -1),
	COOKING_ON_FIRE(897, -1),
	POTTERY(883, -1),
	SPINNING(894, -1),
	WEAVING(2270, -1),
	RAKING(2273, 7125),
	// Done anywhere, with nothing to stand at: a golem whittling, or stitching leather.
	FLETCHING(1248, 4045),
	LEATHERWORK(1249, 4047);

	/** The player animation played, looped for as long as the golem keeps at it. */
	private final int animation;

	/** The wield model held while working, or -1 if the work is done bare-handed. */
	private final int toolModel;

	/** What a golem can do anywhere, wanting no object to stand at. */
	static final GolemWork[] HANDIWORK = {FLETCHING, LEATHERWORK};

	/** True for the ways of fishing. */
	boolean isFishing()
	{
		return this == NET_FISHING || this == BIG_NET_FISHING || this == ROD_FISHING
			|| this == CAGE_FISHING || this == HARPOON_FISHING;
	}

	/** True for what a crafty golem makes: at a wheel or a loom, or in its hands anywhere. */
	boolean isCraft()
	{
		return this == POTTERY || this == SPINNING || this == WEAVING || this == FLETCHING || this == LEATHERWORK;
	}

	// ------------------------------------------------------------------ the working traits

	/** How much more often than most a golem with these traits goes to work: see GolemTrait. */
	static float eagerness(int traits)
	{
		if (GolemTrait.HARD_WORKING.in(traits))
		{
			return 2.5f;
		}
		return GolemTrait.WORK_SHY.in(traits) ? 0.25f : 1f;
	}

	/** How much more often than most it takes up handiwork where it stands, with nothing nearby. */
	static float handiness(int traits)
	{
		return GolemTrait.CRAFTY.in(traits) ? 4f : 1f;
	}

	/** How much longer than most it keeps at this work. An angler stays at the river. */
	static float stamina(int traits, GolemWork work)
	{
		float scale = GolemTrait.HARD_WORKING.in(traits) ? 1.5f : GolemTrait.WORK_SHY.in(traits) ? 0.5f : 1f;
		if (work.isFishing() && GolemTrait.ANGLER.in(traits))
		{
			scale *= 1.5f;
		}
		return scale;
	}

	/** True if this is the golem's trade, which it picks over anything else in reach. */
	static boolean isTradeOf(int traits, GolemWork work)
	{
		return work == WOODCUTTING && GolemTrait.LUMBERJACK.in(traits)
			|| work == MINING && GolemTrait.PROSPECTOR.in(traits)
			|| work.isFishing() && GolemTrait.ANGLER.in(traits)
			|| work.isCraft() && GolemTrait.CRAFTY.in(traits);
	}

	/**
	 * What a golem would do at a scene object, by what the game offers a player there, or null. Read
	 * from the menu rather than a list of object ids: every tree that can be chopped says so, and a
	 * felled one, a stump, does not.
	 */
	static GolemWork forObject(String name, String[] actions)
	{
		if (actions != null)
		{
			for (String action : actions)
			{
				if (action == null)
				{
					continue;
				}
				switch (action)
				{
					case "Chop down":
					case "Chop":
						return WOODCUTTING;
					case "Mine":
						return MINING;
					case "Cook":
						return isFire(name) ? COOKING_ON_FIRE : COOKING_ON_RANGE;
					case "Spin":
						return SPINNING;
					case "Weave":
						return WEAVING;
					case "Rake":
						return RAKING;
					default:
						break;
				}
			}
		}
		if (name == null)
		{
			return null;
		}
		// What a player uses an item on rather than clicks: no menu entry says what it is for.
		switch (name)
		{
			case "Fire":
				return COOKING_ON_FIRE;
			case "Range":
			case "Cooking range":
			case "Stove":
				return COOKING_ON_RANGE;
			case "Potter's Wheel":
			case "Potter's wheel":
				return POTTERY;
			case "Spinning wheel":
				return SPINNING;
			case "Loom":
				return WEAVING;
			default:
				// Every farming patch, weeded or growing: a golem tends it with a rake either way.
				return name.endsWith(" patch") ? RAKING : null;
		}
	}

	private static boolean isFire(String name)
	{
		return name != null && name.toLowerCase().contains("fire");
	}

	/**
	 * How a golem would fish at a fishing spot, by the spot's menu, or null if it is no fishing spot.
	 * A spot offering two ways is fished either way, picked by {@code pick}.
	 */
	static GolemWork forFishingSpot(String[] actions, int pick)
	{
		if (actions == null)
		{
			return null;
		}
		GolemWork[] found = new GolemWork[actions.length];
		int count = 0;
		for (String action : actions)
		{
			GolemWork work = action == null ? null : fishingBy(action);
			if (work != null)
			{
				found[count++] = work;
			}
		}
		return count == 0 ? null : found[Math.floorMod(pick, count)];
	}

	private static GolemWork fishingBy(String action)
	{
		switch (action)
		{
			case "Net":
			case "Small Net":
				return NET_FISHING;
			case "Big Net":
				return BIG_NET_FISHING;
			case "Bait":
			case "Lure":
				return ROD_FISHING;
			case "Cage":
				return CAGE_FISHING;
			case "Harpoon":
				return HARPOON_FISHING;
			default:
				return null;
		}
	}
}
