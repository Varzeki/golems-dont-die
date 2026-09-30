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
		history.cameAshore(null);
		history.sample(2800, 3430, 0, HOME);
		assertEquals(GolemTravel.SAILED.ordinal(), history.travels()[0][2]);
	}

	/**
	 * Pacing between two places writes the first trip only; going somewhere else, or another way,
	 * writes again. A voyage is always a line.
	 */
	@Test
	public void backTheWayItCameIsNoJourney()
	{
		PlaceNames names = new PlaceNames();
		names.load();
		GolemHistory history = new GolemHistory();
		history.sample(2592, 2272, 0, HOME, names);
		history.sample(2528, 2208, 0, HOME, names);
		history.sample(2592, 2272, 0, HOME, names);
		history.sample(2528, 2208, 0, HOME, names);
		history.sample(2592, 2272, 0, HOME, names);
		assertEquals("only the walk to Ardeaglais", 1, history.travels().length);

		history.sample(2592, 2272 + 6400, 0, HOME, names);
		history.tookTransport(ladder(2592, 2272 + 6400, 0, 2592, 2272, 0));
		history.sample(2592, 2272, 0, HOME, names);
		history.tookTransport(ladder(2592, 2272, 1, 2592, 2272 + 6400, 0));
		history.sample(2592, 2272 + 6400, 0, HOME, names);
		history.tookTransport(ladder(2592, 2272 + 6400, 0, 2592, 2272, 1));
		history.sample(2592, 2272, 0, HOME, names);
		int[][] travels = history.travels();
		assertEquals("explored the cavern and climbed out, then no more", 3, travels.length);
		assertEquals(GolemTravel.CLIMBED.ordinal(), travels[0][2]);
		assertEquals(GolemTravel.EXPLORED.ordinal(), travels[1][2]);

		history.cameAshore(null);
		history.sample(2528, 2208, 0, HOME, names);
		history.cameAshore(null);
		history.sample(2592, 2272, 0, HOME, names);
		assertEquals("both voyages", 5, history.travels().length);
	}

	private static GolemTransport ladder(int fromX, int fromY, int fromPlane, int toX, int toY, int toPlane)
	{
		return new GolemTransport(fromX, fromY, fromPlane, toX, toY, toPlane, 1,
			GolemTransport.ARCHETYPE_LADDER, 1, new int[0], new int[0], new int[0], new int[0]);
	}

	/**
	 * A gangplank crossed without leaving the region is not how the golem reached the next one it
	 * walks into: the journal said it took a gangplank to Shilo Village.
	 */
	@Test
	public void aGangplankInPlaceIsNotTheNextJourney()
	{
		GolemHistory history = new GolemHistory();
		history.sample(2596, 2256, 0, HOME);
		history.tookTransport(new GolemTransport(2600, 2260, 0, 2602, 2260, 1, 1,
			GolemTransport.ARCHETYPE_GANGPLANK, 1, new int[0], new int[0], new int[0], new int[0]));
		history.sample(2602, 2260, 1, HOME);
		history.sample(2800, 3430, 0, HOME);
		assertEquals(GolemTravel.WALKED.ordinal(), history.travels()[0][2]);

		// And one that did land somewhere new is written as a gangplank.
		GolemHistory boarded = new GolemHistory();
		boarded.sample(2596, 2256, 0, HOME);
		boarded.tookTransport(new GolemTransport(2600, 2260, 0, 2800, 3430, 0, 1,
			GolemTransport.ARCHETYPE_GANGPLANK, 1, new int[0], new int[0], new int[0], new int[0]));
		boarded.sample(2800, 3430, 0, HOME);
		assertEquals(GolemTravel.BOARDED.ordinal(), boarded.travels()[0][2]);
	}
}
