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
