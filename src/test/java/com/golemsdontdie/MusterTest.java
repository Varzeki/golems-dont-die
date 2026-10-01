package com.golemsdontdie;

import java.lang.reflect.Field;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** When golems waiting at a quay cast off together, and how long each of them waits. */
public class MusterTest
{
	private static final WorldPoint QUAY = new WorldPoint(2569, 2296, 0);

	/** Two make a crew; it waits a few seconds for more after the last came, then goes. */
	@Test
	public void aCrewWaitsAMomentForMore()
	{
		assertFalse("one is no crew", GolemCrews.isReady(1, 100, 0, 0));
		assertFalse("two, the second just come", GolemCrews.isReady(2, 5, 0, 3));
		assertTrue("two, nobody new for a while", GolemCrews.isReady(2, 20, 0, 3));
		assertTrue("a full boat goes at once", GolemCrews.isReady(GolemBoat.SLOOP.getBerths(), 5, 0, 4));
		assertTrue("the muster is up", GolemCrews.isReady(2, 30, 0, 29));
	}

	/** A muster is over when its time is up, or when the tick count has started again since. */
	@Test
	public void aMusterRunsOut()
	{
		assertFalse(GolemCrews.isOver(29, 0));
		assertTrue(GolemCrews.isOver(30, 0));
		assertTrue("the clock went back", GolemCrews.isOver(5, 100));
	}

	/** A golem waiting at the quay waits as long as its crew does: longer if asked, never shorter. */
	@Test
	public void aWaitIsHeldAsLongAsTheCrewWaits() throws ReflectiveOperationException
	{
		GolemSnapshot snapshot = new GolemSnapshot(1234, "Golem", new int[0], new short[0], new short[0],
			128, 128, 1, -1, -1, -1, QUAY, 0);
		Golem golem = Golem.onTile(snapshot, QUAY, 7, QUAY);
		golem.waitAshore(0, 30, QUAY);
		assertEquals(30, end(golem));

		golem.holdWaitUntil(25, 60);
		assertEquals(60, end(golem));
		assertEquals(QUAY, golem.waitingSpot());

		golem.holdWaitUntil(26, 40);
		assertEquals("never shortened", 60, end(golem));
	}

	private static int end(Golem golem) throws ReflectiveOperationException
	{
		Field f = Golem.class.getDeclaredField("itinerary");
		f.setAccessible(true);
		Itinerary itinerary = (Itinerary) f.get(golem);
		return itinerary.getStartTick() + itinerary.getDuration();
	}
}
