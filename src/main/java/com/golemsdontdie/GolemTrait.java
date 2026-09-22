package com.golemsdontdie;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import lombok.Getter;

/**
 * What a golem is like: a handful of traits it keeps for life.
 *
 * <p>Drawn from the golem's own seed, so they are decided the moment it is made, never change, and
 * cost nothing to store — a golem saved before this existed comes back with the traits it always
 * had. Most golems have one or two; five is rare.
 *
 * <p>Some lean on the chances the planner already rolls, which is what makes a golem that likes the
 * cold spend its life in the snow. The rest are only true of it, and wait for the golem's own page
 * to be read.
 *
 * <p>Nothing about a golem's traits is stored, so editing this list — adding one, reordering, even
 * changing a weight — deals every golem a fresh hand. Harmless while nothing shows them; once the
 * golem's page does, a change here is a change to every golem a player thought they knew.
 */
@Getter
enum GolemTrait
{
	// ------------------------------------------------------------------ what a golem does

	LIKES_THE_COLD("Likes the cold", "Happiest in snow and ice.", 3, Clash.WEATHER),
	LIKES_THE_HEAT("Likes the heat", "Drawn to the desert.", 3, Clash.WEATHER),
	TEMPERATE("Temperate", "It keeps to the green places.", 3, Clash.WEATHER),
	HOMESICK("Homesick", "Never away from Wyrmscraig for long.", 3, Clash.WEATHER),
	SEAFARER("Seafarer", "Takes to the water every chance it gets.", 3),
	SPELUNKER("Spelunker", "Goes down whenever there is a down to go.", 3, Clash.FLOORS),
	CLIMBER("Climber", "Ladders, stairs and cliffs: anything that leads up.", 3, Clash.FLOORS),
	RESTLESS("Restless", "Can't stand still.", 3),
	CROWD_SHY("Shy", "Keeps away from other golems.", 3, Clash.CROWDS),
	SOCIABLE("Sociable", "Loves a crowd.", 3, Clash.CROWDS),
	CAUTIOUS("Cautious", "Will not risk danger.", 3, Clash.OBSTACLES),
	SURE_FOOTED("Sure-footed", "Actually likes Agility.", 3, Clash.OBSTACLES),

	// ------------------------------------------------------------------ what a golem is
	//
	// Flavour for the golem's own page, with nothing behind it. Nothing here claims anything about
	// how a golem looks: they are all carved from the same rock, and a page saying otherwise would
	// be contradicted by the golem standing in front of you.

	FRIENDLY("Friendly", "Will probably say hello.", 6),
	LIFE_OF_THE_PARTY("Life of the party", "Loves to boogie.", 4),
	PONDEROUS("Ponderous", "Thinks things over. At length.", 6),
	HUMS("Hums to itself", "Hmmmmm.", 5),
	OLD_SOUL("Old soul", "Just needs a nap.", 4),
	SUPERSTITIOUS("Superstitious", "Avoids black cats.", 4),
	STOIC("Stoic", "Never complains.", 5),
	CURIOUS("Curious", "Always looking for something new.", 5),
	LUCKY("Lucky", "Things just work out.", 4),
	PATIENT("Patient", "Whatever it is waiting for, it will wait.", 5),
	COUNTS_THINGS("Counts things", "Stones, steps, golems. Quietly.", 5),
	FOND_OF_GOATS("Fond of goats", "Has views on the Wyrmscraig goat, and holds them firmly.", 4),
	LOYAL("Loyal", "Would never betray you.", 5);

	/** What the golem's page calls this. */
	private final String label;

	/** A line for the golem's page. */
	private final String description;

	/**
	 * How often this comes up, against the others. Cosmetic traits are commoner than ones that
	 * change what a golem does: a golem with four habits pulling against each other has none.
	 */
	private final int weight;

	/**
	 * Traits that pull against each other share a clash, and a golem is dealt at most one of them:
	 * somewhere it wants to be, a view on crowds, a view on obstacles, and a direction in a
	 * stairwell. A golem both homesick and fond of the cold wants to be in the snow on Wyrmscraig,
	 * which is nowhere, and it would simply stand about.
	 */
	private final int clash;

	/**
	 * The clashes, in a class of their own: an enum constant may not name a field of its own enum,
	 * and these are wanted in the list above.
	 */
	private static final class Clash
	{
		/** Where a golem wants to be: the weather it likes, or home. */
		static final int WEATHER = 1;
		static final int CROWDS = 2;
		static final int OBSTACLES = 3;
		static final int FLOORS = 4;
	}

	GolemTrait(String label, String description, int weight)
	{
		this(label, description, weight, 0);
	}

	GolemTrait(String label, String description, int weight, int clash)
	{
		this.label = label;
		this.description = description;
		this.weight = weight;
		this.clash = clash;
	}

	private static final GolemTrait[] ALL = values();

	/** How many traits a golem may have. The draw leans hard on the low end. */
	private static final int MOST_TRAITS = 5;

	/**
	 * The traits belonging to a seed, as a bitmask.
	 *
	 * <p>Drawn from a generator of its own rather than the golem's, so that asking a golem what it is
	 * like never disturbs where it walks: the two would otherwise share a sequence, and a golem's
	 * traits would change what it did next.
	 */
	static int of(long seed)
	{
		Random random = new Random(seed * 0x9E3779B97F4A7C15L ^ 0x5DEECE66DL);
		int count = 1;
		// Each further trait is half as likely as the one before: most golems have one or two.
		while (count < MOST_TRAITS && random.nextFloat() < 0.4f)
		{
			count++;
		}

		int total = 0;
		for (GolemTrait trait : ALL)
		{
			total += trait.weight;
		}

		int traits = 0;
		List<GolemTrait> left = new ArrayList<>(ALL.length);
		java.util.Collections.addAll(left, ALL);
		for (int i = 0; i < count && !left.isEmpty(); i++)
		{
			int roll = random.nextInt(total);
			GolemTrait drawn = left.get(left.size() - 1);
			for (GolemTrait trait : left)
			{
				roll -= trait.weight;
				if (roll < 0)
				{
					drawn = trait;
					break;
				}
			}
			traits |= drawn.mask();
			total -= drawn.weight;
			left.remove(drawn);
			// Nothing that pulls against what it just drew: see clash.
			if (drawn.clash != 0)
			{
				for (java.util.Iterator<GolemTrait> rest = left.iterator(); rest.hasNext(); )
				{
					GolemTrait trait = rest.next();
					if (trait.clash == drawn.clash)
					{
						total -= trait.weight;
						rest.remove();
					}
				}
			}
		}
		return traits;
	}

	boolean in(int traits)
	{
		return (traits & mask()) != 0;
	}

	/** This trait alone, as a mask. */
	int mask()
	{
		return 1 << ordinal();
	}

	/** The traits in a mask, in the order they are declared. */
	static List<GolemTrait> list(int traits)
	{
		List<GolemTrait> found = new ArrayList<>();
		for (GolemTrait trait : ALL)
		{
			if (trait.in(traits))
			{
				found.add(trait);
			}
		}
		return found;
	}
}
