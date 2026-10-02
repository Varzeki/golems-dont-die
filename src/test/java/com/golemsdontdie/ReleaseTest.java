package com.golemsdontdie;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * A golem let go from a quay alone sails if it can; if its crossing is still being worked out, it
 * walks off without its memory still saying it waits there for a boat.
 */
public class ReleaseTest
{
	private static final WorldPoint QUAY = new WorldPoint(2569, 2296, 0);

	/** A planner whose crossing is never ready: it holds the golem pending, as the real one does. */
	private static final class NotReady extends RoamPlanner
	{
		@Override
		Itinerary planVoyageAlone(WorldPoint from, int tick, Random random, TransportMemory memory, RoamContext context)
		{
			memory.holdPending(5, 7, tick);
			return RoamPlanner.stayPut(from, tick, 10);
		}
	}

	@Test
	public void aLoneGolemLetGoWithNoCrossingForgetsItWasWaiting() throws ReflectiveOperationException
	{
		GolemCrews crews = new GolemCrews();
		Field planner = GolemCrews.class.getDeclaredField("planner");
		planner.setAccessible(true);
		planner.set(crews, new NotReady());

		GolemSnapshot snapshot = new GolemSnapshot(1234, "Golem", new int[0], new short[0], new short[0],
			128, 128, 1, -1, -1, -1, QUAY, 0);
		Golem golem = Golem.onTile(snapshot, QUAY, 7, QUAY);
		golem.waitAshore(0, GolemCrews.WAIT_TICKS, QUAY);

		Method release = GolemCrews.class.getDeclaredMethod("release", List.class, int.class, RoamContext.class,
			boolean.class);
		release.setAccessible(true);
		release.invoke(crews, Collections.singletonList(golem), 40, new RoamContext(null, null, null, null), true);

		assertEquals("no longer waiting for a boat", -1, golem.getTransportMemory().getPendingPort());
		assertEquals("no longer waiting at the quay", null, golem.waitingSpot());
	}
}
