package com.golemsdontdie;

import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/** The traits a seed draws: always some, never many, and always the same ones. */
public class GolemTraitTest
{
	@Test
	public void everyGolemHasBetweenOneAndFiveTraits()
	{
		int[] counts = new int[8];
		for (long seed = 0; seed < 20_000; seed++)
		{
			int traits = GolemTrait.of(seed);
			int count = GolemTrait.list(traits).size();
			Assert.assertTrue("at least one trait for seed " + seed, count >= 1);
			Assert.assertTrue("at most five traits for seed " + seed, count <= 5);
			counts[count]++;
		}
		// Leaning on the low end: most golems are one or two things, not five.
		Assert.assertTrue("one or two traits is the common case, was " + counts[1] + "/" + counts[2],
			counts[1] + counts[2] > 15_000);
		Assert.assertTrue("five traits happens at all", counts[5] > 0);
		Assert.assertTrue("five traits is rare", counts[5] < 1_000);
	}

	@Test
	public void aSeedAlwaysDrawsTheSameTraits()
	{
		for (long seed = 1; seed < 200; seed++)
		{
			Assert.assertEquals(GolemTrait.of(seed), GolemTrait.of(seed));
		}
		Assert.assertNotEquals("different golems are not all alike",
			GolemTrait.of(12345), GolemTrait.of(12346));
	}

	/**
	 * Every pair that contradicts itself, written out here rather than read back from the enum, so
	 * a clash dropped from the table is caught.
	 */
	private static final GolemTrait[][] CONTRADICTIONS = {
		{GolemTrait.LIKES_THE_COLD, GolemTrait.LIKES_THE_HEAT},
		{GolemTrait.LIKES_THE_COLD, GolemTrait.TEMPERATE},
		{GolemTrait.LIKES_THE_COLD, GolemTrait.HOMESICK},
		{GolemTrait.LIKES_THE_HEAT, GolemTrait.TEMPERATE},
		{GolemTrait.LIKES_THE_HEAT, GolemTrait.HOMESICK},
		{GolemTrait.TEMPERATE, GolemTrait.HOMESICK},
		{GolemTrait.CAUTIOUS, GolemTrait.SURE_FOOTED},
		{GolemTrait.SPELUNKER, GolemTrait.CLIMBER},
		{GolemTrait.CROWD_SHY, GolemTrait.SOCIABLE},
		{GolemTrait.CROWD_SHY, GolemTrait.FRIENDLY},
		{GolemTrait.CROWD_SHY, GolemTrait.LIFE_OF_THE_PARTY},
		{GolemTrait.RESTLESS, GolemTrait.PATIENT},
		{GolemTrait.RESTLESS, GolemTrait.OLD_SOUL},
		{GolemTrait.RESTLESS, GolemTrait.PONDEROUS},
		{GolemTrait.OLD_SOUL, GolemTrait.LIFE_OF_THE_PARTY},
	};

	/** A golem is never dealt two traits that pull against each other. See GolemTrait.CLASHES. */
	@Test
	public void nothingIsDrawnAgainstItself()
	{
		for (long seed = 0; seed < 200_000; seed++)
		{
			int traits = GolemTrait.of(seed);
			for (GolemTrait[] pair : CONTRADICTIONS)
			{
				Assert.assertFalse("seed " + seed + " drew " + GolemTrait.list(traits),
					pair[0].in(traits) && pair[1].in(traits));
			}
		}
	}

	/** The table and the list above agree, both ways round, and nothing else is kept apart. */
	@Test
	public void theClashesAreExactlyTheContradictions()
	{
		int pairs = 0;
		for (GolemTrait a : GolemTrait.values())
		{
			for (GolemTrait b : GolemTrait.values())
			{
				Assert.assertEquals(a + " and " + b + " clash one way only", a.clashesWith(b), b.clashesWith(a));
				pairs += a.ordinal() < b.ordinal() && a.clashesWith(b) ? 1 : 0;
			}
		}
		Assert.assertEquals(CONTRADICTIONS.length, pairs);
		for (GolemTrait[] pair : CONTRADICTIONS)
		{
			Assert.assertTrue(pair[0] + " and " + pair[1], pair[0].clashesWith(pair[1]));
		}
	}

	/** Not over-constrained: the ones that only look alike still turn up together. */
	@Test
	public void kindredTraitsStillMeet()
	{
		GolemTrait[][] kindred = {
			{GolemTrait.FRIENDLY, GolemTrait.SOCIABLE},
			{GolemTrait.FRIENDLY, GolemTrait.LIFE_OF_THE_PARTY},
			{GolemTrait.PATIENT, GolemTrait.OLD_SOUL},
			{GolemTrait.PONDEROUS, GolemTrait.OLD_SOUL},
		};
		for (GolemTrait[] pair : kindred)
		{
			boolean met = false;
			for (long seed = 0; seed < 20_000 && !met; seed++)
			{
				int traits = GolemTrait.of(seed);
				met = pair[0].in(traits) && pair[1].in(traits);
			}
			Assert.assertTrue(pair[0] + " and " + pair[1] + " never met", met);
		}
	}

	@Test
	public void everyTraitIsDrawnSometimesAndNoneTwice()
	{
		boolean[] seen = new boolean[GolemTrait.values().length];
		for (long seed = 0; seed < 20_000; seed++)
		{
			List<GolemTrait> drawn = GolemTrait.list(GolemTrait.of(seed));
			Assert.assertEquals("no trait twice", drawn.size(), new java.util.HashSet<>(drawn).size());
			for (GolemTrait trait : drawn)
			{
				seen[trait.ordinal()] = true;
			}
		}
		for (GolemTrait trait : GolemTrait.values())
		{
			Assert.assertTrue(trait + " is drawn sometimes", seen[trait.ordinal()]);
		}
	}
}
