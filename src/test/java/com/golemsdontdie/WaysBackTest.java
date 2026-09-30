package com.golemsdontdie;

import java.util.*;
import java.util.function.*;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A golem goes nowhere it has no way back from. */
public class WaysBackTest
{
	/** Home's space and four others, one per thousand tiles of x; everything on the ground floor. */
	private static final int HOME = GolemContent.PLINTH_X / 1000;

	private static final class Spaces extends WorldMesh
	{
		@Override
		int componentAt(int x, int y, int plane)
		{
			return plane == 0 && x > 0 ? x / 1000 : 0;
		}
	}

	/** A tile in a space. */
	private static int[] in(int space)
	{
		return space == HOME
			? new int[]{GolemContent.PLINTH_X, GolemContent.PLINTH_Y}
			: new int[]{space * 1000 + 500, 100};
	}

	/** Learned routes, the one kind of row a table not loaded from the jar can be given. */
	private static TransportNetwork network(int[]... hops)
	{
		List<int[]> routes = new ArrayList<>();
		int id = 1;
		for (int[] hop : hops)
		{
			int[] from = in(hop[0]);
			int[] to = in(hop[1]);
			routes.add(new int[]{id++, from[0], from[1], 0, to[0], to[1], 0, 1});
		}
		TransportNetwork network = new TransportNetwork();
		network.setLearnedRoutes(routes);
		return network;
	}

	private static GolemTransport row(TransportNetwork network, int from, int to)
	{
		for (GolemTransport t : network.all())
		{
			if (t.getFromX() == in(from)[0] && t.getToX() == in(to)[0])
			{
				return t;
			}
		}
		throw new AssertionError("no row " + from + " to " + to);
	}

	private static boolean comesBack(WaysBack back, WorldMesh mesh, GolemTransport t)
	{
		return back.comesBack(mesh.spacesAt(t.getFromX(), t.getFromY(), t.getFromPlane()), back.landing(t));
	}

	@Test
	public void throughADoorAndBack()
	{
		WorldMesh mesh = new Spaces();
		TransportNetwork network = network(new int[]{HOME, 5}, new int[]{5, HOME});
		WaysBack back = new WaysBack(mesh, network, t -> true);
		back.refresh(0, Collections::emptySet);
		assertTrue(comesBack(back, mesh, row(network, HOME, 5)));
	}

	/**
	 * A drop with no way back up is refused until one is usable, and taken from then on. A drop
	 * whose far side leads home some other way is no trap at all.
	 */
	@Test
	public void notDownADropWithNoWayBack()
	{
		WorldMesh mesh = new Spaces();
		TransportNetwork network = network(new int[]{HOME, 6}, new int[]{6, HOME}, new int[]{HOME, 7},
			new int[]{7, 8}, new int[]{8, HOME});
		GolemTransport climbOut = row(network, 6, HOME);
		boolean[] learned = {false};
		WaysBack back = new WaysBack(mesh, network, t -> t != climbOut || learned[0]);
		back.refresh(0, Collections::emptySet);
		assertFalse("down a drop with no way back", comesBack(back, mesh, row(network, HOME, 6)));
		assertTrue("down a drop into a cave that comes out elsewhere", comesBack(back, mesh, row(network, HOME, 7)));

		learned[0] = true;
		back.refresh(WaysBack.RECHECK_TICKS, Collections::emptySet);
		assertTrue("the way out was learned", comesBack(back, mesh, row(network, HOME, 6)));
	}

	/** An island with a dock is no trap while the golem may sail; kept ashore, it is. */
	@Test
	public void anIslandWithADockIsAWayBack()
	{
		WorldMesh mesh = new Spaces();
		TransportNetwork network = network(new int[]{HOME, 9});
		WaysBack back = new WaysBack(mesh, network, t -> true);
		Set<Integer> ports = new HashSet<>(Arrays.asList(HOME, 9));
		back.refresh(0, () -> ports);
		assertTrue("the boat home", comesBack(back, mesh, row(network, HOME, 9)));

		back.refresh(WaysBack.RECHECK_TICKS, Collections::emptySet);
		assertFalse("kept ashore", comesBack(back, mesh, row(network, HOME, 9)));
	}
}
