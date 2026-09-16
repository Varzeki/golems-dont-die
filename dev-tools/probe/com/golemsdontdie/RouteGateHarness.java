package com.golemsdontdie;

import java.util.List;

/** Feeds the real stile routes through ObstacleKnowledge and prints what it keeps. */
public class RouteGateHarness
{
	private static int failures;

	public static void main(String[] args)
	{
		check("stile straight (0,3) on (0,1)", RouteGeometry.follows(0, 3, 0, 1), true);
		check("stile diagonal (-1,3) on (0,1)", RouteGeometry.follows(-1, 3, 0, 1), false);
		check("stile approach (-1,1) on (0,1)", RouteGeometry.follows(-1, 1, 0, 1), false);
		check("rockslide (4,0) on (4,0)", RouteGeometry.follows(4, 0, 4, 0), true);
		check("rockslide back (-4,0) on (4,0)", RouteGeometry.follows(-4, 0, 4, 0), true);
		check("door (1,0) on (-1,0)", RouteGeometry.follows(1, 0, -1, 0), true);
		check("cave teleport ignored", RouteGeometry.follows(34, 6425, 1, 0), true);
		check("unknown line", RouteGeometry.follows(-1, 3, 0, 0), true);

		ObstacleKnowledge knowledge = new ObstacleKnowledge();

		// What is already saved: the two diagonal routes and a straight one, each seen twice,
		// before any line was known.
		knowledge.deserialiseRoutes("62408,2569,2252,0>2568,2255,0,3;"
			+ "62408,2570,2253,0>2569,2254,0,2;62262,2565,2217,0>2565,2219,0,4");
		print("saved routes, no line known yet", knowledge.learnedRoutes());

		// A new straight crossing, with the stile's own movement (0,1).
		knowledge.record(sighting(2569, 2252, 2569, 2255, 0, 1));
		knowledge.record(sighting(2569, 2252, 2569, 2255, 0, 1));
		// A diagonal one of the kind that used to be learned.
		knowledge.record(sighting(2569, 2252, 2568, 2255, 0, 1));
		List<int[]> after = knowledge.learnedRoutes();
		print("after crossing the stile", after);

		boolean diagonalKept = false;
		boolean straightKept = false;
		for (int[] r : after)
		{
			diagonalKept |= r[0] == 62408 && r[4] != r[1];
			straightKept |= r[0] == 62408 && r[1] == 2569 && r[4] == 2569;
		}
		check("diagonal stile routes no longer offered", diagonalKept, false);
		check("straight stile route offered", straightKept, true);
		check("stepping stone untouched", after.stream().anyMatch(r -> r[0] == 62262), true);
		check("line survives a save", knowledge.serialiseLines().startsWith("62408=0,1,"), true);

		System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
	}

	private static ObstacleSighting sighting(int fx, int fy, int tx, int ty, int lx, int ly)
	{
		return new ObstacleSighting(62408, "Stile", "Climb-over Stile", new int[]{839}, 3,
			fx, fy, 0, tx, ty, 0, false, 4, 60, null, lx, ly);
	}

	private static void print(String label, List<int[]> routes)
	{
		System.out.println(label + ":");
		for (int[] r : routes)
		{
			System.out.println("  " + r[0] + " " + r[1] + "," + r[2] + " -> " + r[4] + "," + r[5]);
		}
	}

	private static void check(String label, boolean actual, boolean expected)
	{
		boolean ok = actual == expected;
		failures += ok ? 0 : 1;
		System.out.println((ok ? "ok   " : "FAIL ") + label);
	}
}
