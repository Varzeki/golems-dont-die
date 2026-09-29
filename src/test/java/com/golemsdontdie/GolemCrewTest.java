package com.golemsdontdie;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A crew on its boat: one golem steers, and the rest are passengers. */
public class GolemCrewTest
{
	private static final WorldPoint QUAY = new WorldPoint(2803, 3421, 0);

	private static Golem golem(long seed)
	{
		GolemSnapshot snapshot = new GolemSnapshot(1234, "Golem", new int[0], new short[0], new short[0],
			128, 128, 1, -1, -1, -1, QUAY, 0);
		return Golem.onTile(snapshot, QUAY, seed, QUAY);
	}

	@Test
	public void onlyTheFirstSignedTakesTheHelm()
	{
		GolemCrew crew = new GolemCrew(GolemBoat.SLOOP, null);
		Golem first = golem(1L);
		Golem second = golem(2L);
		Golem third = golem(3L);
		crew.sign(first);
		crew.sign(second);
		crew.sign(third);
		assertTrue(first.isAtHelm());
		assertFalse(second.isAtHelm());
		assertFalse(third.isAtHelm());
	}

	/** A golem on a raft of its own is its own helmsman, and so is one whose crew has been paid off. */
	@Test
	public void aGolemAloneSteersItself()
	{
		Golem alone = golem(4L);
		assertTrue(alone.isAtHelm());
		GolemCrew crew = new GolemCrew(GolemBoat.SKIFF, null);
		crew.sign(golem(5L));
		crew.sign(alone);
		assertFalse(alone.isAtHelm());
		crew.payOff();
		assertTrue(alone.isAtHelm());
	}
}
