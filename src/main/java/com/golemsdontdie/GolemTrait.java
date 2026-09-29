package com.golemsdontdie;

import java.util.*;
import lombok.*;

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
@AllArgsConstructor
enum GolemTrait
{
	// ------------------------------------------------------------------ what a golem does

	LIKES_THE_COLD("Likes the cold", "Happiest in snow and ice.", 3),
	LIKES_THE_HEAT("Likes the heat", "Drawn to the desert.", 3),
	TEMPERATE("Temperate", "It keeps to the green places.", 3),
	HOMESICK("Homesick", "Never away from Wyrmscraig for long.", 3),
	SEAFARER("Seafarer", "Takes to the water every chance it gets.", 3),
	SPELUNKER("Spelunker", "Goes down whenever there is a down to go.", 3),
	CLIMBER("Climber", "Ladders, stairs and cliffs: anything that leads up.", 3),
	RESTLESS("Restless", "Can't stand still.", 3),
	CROWD_SHY("Shy", "Keeps away from other golems.", 3),
	SOCIABLE("Sociable", "Loves a crowd.", 3),
	CAUTIOUS("Cautious", "Will not risk danger.", 3),
	SURE_FOOTED("Sure-footed", "Actually likes Agility.", 3),

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

	private static final GolemTrait[] ALL = values();

	/**
	 * For each trait, the traits it pulls against, as a mask, and a golem is never dealt both. A
	 * golem both homesick and fond of the cold wants to be in the snow on Wyrmscraig, which is
	 * nowhere, and it would simply stand about; one both shy and the life of the party is two
	 * golems. Kept as a table rather than a group apiece because a trait can pull against several
	 * that sit happily together: restless is against patient and against an old soul, and those two
	 * are the same golem.
	 */
	private static final int[] CLASHES = new int[ALL.length];

	static
	{
		// Somewhere it wants to be: the weather it likes, or home. Any two are two places at once.
		clash(LIKES_THE_COLD, LIKES_THE_HEAT, TEMPERATE, HOMESICK);
		clash(LIKES_THE_HEAT, TEMPERATE, HOMESICK);
		clash(TEMPERATE, HOMESICK);
		// A view on obstacles, and a direction in a stairwell.
		clash(CAUTIOUS, SURE_FOOTED);
		clash(SPELUNKER, CLIMBER);
		// Company: a shy golem keeps away from the ones that come looking for it. Friendly and the
		// life of the party are both sociable in their way, and sit with it and each other.
		clash(CROWD_SHY, SOCIABLE, FRIENDLY, LIFE_OF_THE_PARTY);
		// Pace: a golem that can't stand still is none of the ones that wait, nap or think it over.
		clash(RESTLESS, PATIENT, OLD_SOUL, PONDEROUS);
		// A golem that just needs a nap is not the one still dancing.
		clash(OLD_SOUL, LIFE_OF_THE_PARTY);
	}

	/** The first trait against each of the rest, both ways; the rest may still sit together. */
	private static void clash(GolemTrait trait, GolemTrait... against)
	{
		for (GolemTrait other : against)
		{
			CLASHES[trait.ordinal()] |= other.mask();
			CLASHES[other.ordinal()] |= trait.mask();
		}
	}

	/** Whether a golem may never be dealt both of these. */
	boolean clashesWith(GolemTrait other)
	{
		return (CLASHES[ordinal()] & other.mask()) != 0;
	}

	/** How many traits a golem may have. The draw leans hard on the low end. */
	static final int MOST_TRAITS = 5;

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
		Collections.addAll(left, ALL);
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
			// Nothing that pulls against what it just drew: see CLASHES.
			for (Iterator<GolemTrait> rest = left.iterator(); rest.hasNext(); )
			{
				GolemTrait trait = rest.next();
				if (drawn.clashesWith(trait))
				{
					total -= trait.weight;
					rest.remove();
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
