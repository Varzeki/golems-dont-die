package com.golemsdontdie;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A wave or a dance lasts seconds, whatever happens to the clock it was timed against: a golem must
 * never be held still for hours because the tick count went backwards under it.
 */
public class GolemMomentsTest
{
	private static final WorldPoint PLINTH = new WorldPoint(2596, 2256, 0);

	private static Golem golem()
	{
		GolemSnapshot snapshot = new GolemSnapshot(1234, "Golem", new int[0], new short[0], new short[0],
			128, 128, 1, -1, -1, -1, PLINTH, 0);
		return Golem.onTile(snapshot, PLINTH, 42L, PLINTH);
	}

	@Test
	public void aWaveLastsAWave()
	{
		Golem golem = golem();
		golem.greet(104, 1, 0);
		assertTrue(golem.isGreeting(100));
		assertFalse(golem.isGreeting(104));
	}

	/** Timed three hours into a session, then the clock starts again from nothing. */
	@Test
	public void aClockThatWentBackwardsDoesNotHoldItStill()
	{
		Golem golem = golem();
		golem.greet(18004, 1, 0);
		golem.startParty(18017);
		assertFalse("waving for three hours", golem.isGreeting(5));
		assertFalse("dancing for three hours", golem.isPartying(5));
	}

	@Test
	public void aHopForgetsTheMoment()
	{
		Golem golem = golem();
		golem.greet(104, 1, 0);
		golem.startParty(117);
		golem.forgetMoments();
		assertFalse(golem.isGreeting(100));
		assertFalse(golem.isPartying(100));
	}
}
