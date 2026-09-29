package com.golemsdontdie;

import java.util.*;
import java.util.List;
import java.util.function.*;

/**
 * Hands out craft numbers: which golem each was to be crafted, the number an ordinal name is made
 * of.
 *
 * <p>A number is given once and kept, saved with the golem. A new golem takes the one after the
 * highest in the roster. Golems saved before numbers were kept are numbered on their first restore,
 * oldest first, by when each was first known; one saved before even that is older than any that
 * has a date, and among themselves they keep the order of the save, which is the order they joined.
 */
final class CraftNumbers
{
	private CraftNumbers()
	{
	}

	/**
	 * Numbers every golem in the roster that has no number yet.
	 *
	 * @param age when each golem was first known, in epoch milliseconds, or 0 if that was never kept;
	 *            only the order matters
	 * @return how many golems were numbered
	 */
	static int number(List<Golem> roster, ToLongFunction<Golem> age)
	{
		int highest = 0;
		List<Golem> unnumbered = new ArrayList<>();
		for (Golem golem : roster)
		{
			if (golem.getCraftNumber() > 0)
			{
				highest = Math.max(highest, golem.getCraftNumber());
			}
			else
			{
				unnumbered.add(golem);
			}
		}
		// A stable sort: golems of the same age keep the roster's order.
		unnumbered.sort(Comparator.comparingLong(age));
		for (Golem golem : unnumbered)
		{
			golem.setCraftNumber(++highest);
		}
		return unnumbered.size();
	}
}
