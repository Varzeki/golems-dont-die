package com.golemsdontdie;

import java.lang.reflect.Field;
import java.util.*;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A golem is not turned straight back into a space it just left, and a doorway is no shortcut. */
public class GoingBackTest
{
	/**
	 * A space per thousand tiles of x on the ground floor, with x = 2000 a wall between spaces 1
	 * and 2: a tile no space holds, as a ladder set in a wall is.
	 */
	private static final class Spaces extends WorldMesh
	{
		@Override
		int componentAt(int x, int y, int plane)
		{
			return plane == 0 && x > 0 && x != 2000 ? x / 1000 : 0;
		}
	}

	private TransportNetwork network;
	private RoamPlanner planner;

	@Before
	public void build() throws ReflectiveOperationException
	{
		network = new TransportNetwork();
		network.setLearnedRoutes(Arrays.asList(
			new int[]{1, 1500, 100, 0, 3500, 100, 0, 1},
			new int[]{2, 3500, 100, 0, 1500, 100, 0, 1},
			new int[]{3, 3500, 100, 0, 2500, 100, 0, 1},
			new int[]{4, 2000, 100, 0, 3600, 100, 0, 1}));
		planner = new RoamPlanner();
		Field mesh = RoamPlanner.class.getDeclaredField("mesh");
		mesh.setAccessible(true);
		mesh.set(planner, new Spaces());
	}

	private GolemTransport row(int fromX, int toX)
	{
		for (GolemTransport t : network.all())
		{
			if (t.getFromX() == fromX && t.getToX() == toX)
			{
				return t;
			}
		}
		throw new AssertionError("no row " + fromX + " to " + toX);
	}

	/** From space 1 into space 3, the way back into 1 is barred and the way on into 2 is not. */
	@Test
	public void notStraightBackIntoTheSpaceJustLeft()
	{
		TransportMemory memory = new TransportMemory();
		memory.used(row(1500, 3500), 0);
		Set<Integer> left = planner.spacesLeft(3500, 100, 0, 10, memory);
		assertEquals(Collections.singleton(1), left);
		assertTrue(planner.leadsInto(left, row(3500, 1500)));
		assertFalse(planner.leadsInto(left, row(3500, 2500)));
	}

	/** A start in a wall between two spaces bars neither: the golem stood in only one of them. */
	@Test
	public void aStartBetweenTwoSpacesBarsNeither()
	{
		TransportMemory memory = new TransportMemory();
		memory.used(row(2000, 3600), 0);
		assertTrue(planner.spacesLeft(3600, 100, 0, 10, memory).isEmpty());
	}

	/** A doorway walked through is remembered both ways, but is no shortcut to rest after. */
	@Test
	public void aDoorwayIsRememberedButNotRestedAfter()
	{
		TransportMemory memory = new TransportMemory();
		memory.walkedThrough(row(1500, 3500), 0);
		assertFalse(memory.restingFromTransports(1));
		assertTrue(memory.onCooldown(row(1500, 3500), 1));
		assertTrue(memory.onCooldown(row(3500, 1500), 1));

		memory.used(row(3500, 2500), 2);
		assertTrue(memory.restingFromTransports(3));
	}
}
