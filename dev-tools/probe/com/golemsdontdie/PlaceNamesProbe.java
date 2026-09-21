package com.golemsdontdie;

/** Prints what the plugin would call a handful of known places. */
public class PlaceNamesProbe
{
	private static final int[][] SPOTS = {
		{2596, 2256, 0}, {2565, 2219, 0}, {2564, 8630, 0},
		{3222, 3218, 0}, {2884, 9797, 0}, {2792, 3414, 0}, {2611, 3394, 0},
		{3616, 3296, 0}, {3616, 3296, 1}, {2440, 3420, 0}, {2400, 4450, 0},
		{2623, 3391, 0}, {2620, 9797, 0}, {1640, 3670, 0}, {3100, 3500, 0},
	};

	public static void main(String[] args)
	{
		PlaceNames places = new PlaceNames();
		places.load();
		for (int[] spot : SPOTS)
		{
			System.out.printf("  %4d,%4d,%d  %s%n", spot[0], spot[1], spot[2],
				places.nameFor(spot[0], spot[1], spot[2]));
		}
	}
}
