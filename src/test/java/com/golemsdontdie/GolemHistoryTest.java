package com.golemsdontdie;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What a golem's page says about where it has been, and the world layout that decides it: the
 * surface, the caves under it, and the places laid out apart from both.
 */
public class GolemHistoryTest
{
	private static final WorldPoint HOME = new WorldPoint(2596, 2256, 0);

	@Test
	public void theWorldHasThreeLayers()
	{
		assertTrue("the Frozen Temple is on the surface", WorldLayout.isSurface(4121));
		assertTrue("the cave under Wyrmscraig", WorldLayout.isCave(2256 + 6400));
		assertFalse("God Wars is neither", WorldLayout.isSurface(5300) || WorldLayout.isCave(5300));

		assertEquals(2256, WorldLayout.groundAbove(2256));
		assertEquals(2256, WorldLayout.groundAbove(2256 + 6400));
		assertEquals(-1, WorldLayout.groundAbove(5300));

		assertTrue(WorldLayout.sameLayer(2256, 3200));
		assertFalse("a cave and its hill are not a walk apart", WorldLayout.sameLayer(2256, 2256 + 6400));
		assertFalse("God Wars and TzHaar are not one place", WorldLayout.sameLayer(5300, 5100));
	}

	/**
	 * A golem in God Wars is not fourteen hundred tiles from home: God Wars has no ground above it
	 * to measure from, so it sets no record at all.
	 */
	@Test
	public void somewhereWithNoGroundAboveSetsNoRecord()
	{
		GolemHistory history = new GolemHistory();
		history.sample(2600, 2256, 0, HOME);
		history.sample(2880, 5300, 2, HOME);
		assertEquals(4, history.getFurthest());

		// But a cave under somewhere far away counts as the far away place.
		history.sample(3200, 3400 + 6400, 0, HOME);
		assertEquals(3400 - 2256, history.getFurthest());
	}

	/** Walking off the surface is exploring, whichever layer it walks onto. */
	@Test
	public void walkingOffTheSurfaceIsExploring()
	{
		GolemHistory history = new GolemHistory();
		history.sample(2596, 2256, 0, HOME);
		history.sample(2880, 5300, 2, HOME);
		int[][] travels = history.travels();
		assertEquals(1, travels.length);
		assertEquals(GolemTravel.EXPLORED.ordinal(), travels[0][2]);
	}

	/** A crossing ends in a line saying the golem sailed there, not walked. */
	@Test
	public void comingAshoreIsSailing()
	{
		GolemHistory history = new GolemHistory();
		history.sample(2596, 2256, 0, HOME);
		history.cameAshore();
		history.sample(2800, 3430, 0, HOME);
		assertEquals(GolemTravel.SAILED.ordinal(), history.travels()[0][2]);
	}
}
