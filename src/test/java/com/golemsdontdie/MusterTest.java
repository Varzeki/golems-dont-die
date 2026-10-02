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

	/**
	 * A muster lets its golems go when its time is up, unless its crossing is still being worked
	 * out; then when that wait is up too; and never later than the longest a muster lasts.
	 */
	@Test
	public void aMusterLetsGoOnItsOwnClock()
	{
		assertFalse("still mustering", GolemCrews.isDone(29, 0, false, 0));
		assertTrue("time up, no crossing waited on", GolemCrews.isDone(30, 0, false, 0));
		assertFalse("time up, crossing still being worked out", GolemCrews.isDone(40, 0, true, 62));
		assertTrue("and that wait up too", GolemCrews.isDone(63, 0, true, 62));
		assertTrue("never past the longest", GolemCrews.isDone(GolemCrews.LONGEST_MUSTER, 0, true, 500));
		assertTrue("the clock went back", GolemCrews.isDone(5, 100, false, 0));
	}

	/**
	 * A golem's own wait outlasts any muster, so it is the muster that ends it: the golems in a
	 * crew still waiting on its crossing walked off while counted as its crew, each on a clock of
	 * its own. Ended by the muster, it plans for itself again.
	 */
	@Test
	public void theMusterEndsTheWait() throws ReflectiveOperationException
	{
		assertTrue(GolemCrews.WAIT_TICKS > GolemCrews.LONGEST_MUSTER);

		GolemSnapshot snapshot = new GolemSnapshot(1234, "Golem", new int[0], new short[0], new short[0],
			128, 128, 1, -1, -1, -1, QUAY, 0);
		Golem golem = Golem.onTile(snapshot, QUAY, 7, QUAY);
		golem.waitAshore(0, GolemCrews.WAIT_TICKS, QUAY);
		assertEquals(GolemCrews.WAIT_TICKS, end(golem));
		assertEquals(QUAY, golem.waitingSpot());

		golem.endWait();
		assertEquals(null, golem.waitingSpot());
		assertEquals(null, itinerary(golem));
	}

	private static Itinerary itinerary(Golem golem) throws ReflectiveOperationException
	{
		Field f = Golem.class.getDeclaredField("itinerary");
		f.setAccessible(true);
		return (Itinerary) f.get(golem);
	}

	private static int end(Golem golem) throws ReflectiveOperationException
	{
		Field f = Golem.class.getDeclaredField("itinerary");
		f.setAccessible(true);
		Itinerary itinerary = (Itinerary) f.get(golem);
		return itinerary.getStartTick() + itinerary.getDuration();
	}
}
