package com.golemsdontdie;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** Which golem was crafted when: oldest first, given once and kept. */
public class CraftNumbersTest
{
	private static final WorldPoint PLINTH = new WorldPoint(2596, 2256, 0);

	private static Golem golem(long seed)
	{
		GolemSnapshot snapshot = new GolemSnapshot(1234, "Golem", new int[0], new short[0], new short[0],
			128, 128, 1, -1, -1, -1, PLINTH, 0);
		return Golem.onTile(snapshot, PLINTH, seed, PLINTH);
	}

	/**
	 * A roster restored for the first time: two with dates, out of order in the save, and two from
	 * before dates were kept, which are older than both and keep the save's order between them.
	 */
	@Test
	public void theOldestComeFirst()
	{
		Golem newer = golem(1);
		Golem undatedA = golem(2);
		Golem older = golem(3);
		Golem undatedB = golem(4);
		Map<Golem, Long> age = new HashMap<>();
		age.put(newer, 2_000L);
		age.put(undatedA, 0L);
		age.put(older, 1_000L);
		age.put(undatedB, 0L);

		assertEquals(4, CraftNumbers.number(Arrays.asList(newer, undatedA, older, undatedB), age::get));

		assertEquals(1, undatedA.getCraftNumber());
		assertEquals(2, undatedB.getCraftNumber());
		assertEquals(3, older.getCraftNumber());
		assertEquals(4, newer.getCraftNumber());
	}

	/** A number once given is kept; a new golem takes the one after the highest, gaps and all. */
	@Test
	public void aNewGolemComesNext()
	{
		Golem first = golem(1);
		first.setCraftNumber(1);
		Golem kept = golem(2);
		kept.setCraftNumber(453);
		Golem crafted = golem(3);
		List<Golem> roster = Arrays.asList(first, kept, crafted);

		assertEquals(1, CraftNumbers.number(roster, golem -> 0L));
		assertEquals(1, first.getCraftNumber());
		assertEquals(453, kept.getCraftNumber());
		assertEquals(454, crafted.getCraftNumber());
		assertEquals("nothing left to number", 0, CraftNumbers.number(roster, golem -> 0L));
	}
}
