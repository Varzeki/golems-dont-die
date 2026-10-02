package com.golemsdontdie;

import java.lang.reflect.Field;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Where a golem is about to be: the tile it stands on, or the one the step under way ends on. A walk
 * there is a walk of nothing rather than no way there, and a dock it is arriving at is not walked to.
 */
public class ArrivingTest
{
	private static final WorldPoint HERE = new WorldPoint(2596, 2256, 0);

	private static Golem golem()
	{
		GolemSnapshot snapshot = new GolemSnapshot(1234, "Golem", new int[0], new short[0], new short[0],
			128, 128, 1, -1, -1, -1, HERE, 0);
		return Golem.onTile(snapshot, HERE, 3, HERE);
	}

	@Test
	public void standingStillItArrivesWhereItIs()
	{
		Golem golem = golem();
		assertTrue(golem.arrivingAt(2596, 2256));
		assertFalse(golem.arrivingAt(2597, 2256));
	}

	/** Partway through a step east, it is arriving at the tile the step ends on, not the one it left. */
	@Test
	public void midStepItArrivesWhereTheStepEnds() throws ReflectiveOperationException
	{
		Golem golem = golem();
		set(golem, "stepping", true);
		set(golem, "stepToX", 2597 * Golem.TILE + Golem.TILE / 2);
		set(golem, "stepToY", 2256 * Golem.TILE + Golem.TILE / 2);
		assertTrue(golem.arrivingAt(2597, 2256));
		assertFalse(golem.arrivingAt(2596, 2256));
	}

	/**
	 * The golem being looked for, underground, is drawn on the surface map over the ground above
	 * it; not if that ground is off the map or out at sea, nor if it was never underground.
	 */
	@Test
	public void theGolemLookedForShowsOverTheGroundAboveIt()
	{
		int cave = WorldLayout.CAVE_OFFSET;
		assertEquals(3200, GolemMapPoints.groundAbove(3000, 3200 + cave, (x, y) -> true, (x, y) -> false));
		assertEquals("off the map", -1, GolemMapPoints.groundAbove(3000, 3200 + cave, (x, y) -> false, (x, y) -> false));
		assertEquals("in the sea", -1, GolemMapPoints.groundAbove(3000, 3200 + cave, (x, y) -> true, (x, y) -> true));
		assertEquals("on the surface already", -1, GolemMapPoints.groundAbove(3000, 3200, (x, y) -> true, (x, y) -> false));
	}

	private static void set(Golem golem, String name, Object value) throws ReflectiveOperationException
	{
		Field f = Golem.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(golem, value);
	}
}
