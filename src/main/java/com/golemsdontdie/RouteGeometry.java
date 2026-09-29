package com.golemsdontdie;

/**
 * Whether a learned route follows the obstacle it was learned from.
 *
 * <p>An obstacle moves the player along a fixed line: a stile from one of its tiles to the other.
 * A route is the two tiles a golem gets on and off at, travelled in a straight line, so off that
 * line is off the obstacle. That is how the stile came to be crossed diagonally — its ends came
 * from where the player happened to stand, eighteen degrees off. Such a route is refused when
 * learned and ignored if already saved.
 */
final class RouteGeometry
{
	/**
	 * How far off an obstacle's line a route may run. Both are whole tiles, so a real route is on
	 * the line or off by a whole tile's angle — the diagonal stile was eighteen — never between.
	 */
	static final double MAX_DEGREES_OFF = 10;

	/** Beyond this many tiles a traversal is a teleport (cave mouth, staircase) with no line. */
	static final int LOCAL_TILES = 8;

	private RouteGeometry()
	{
	}

	/** Tiles between two points as a golem walks them, a diagonal step counting as one. */
	static int span(int dx, int dy)
	{
		return Math.max(Math.abs(dx), Math.abs(dy));
	}

	/** True if a movement is short enough to have a line at all. */
	static boolean local(int dx, int dy)
	{
		return (dx != 0 || dy != 0) && Math.abs(dx) <= LOCAL_TILES && Math.abs(dy) <= LOCAL_TILES;
	}

	/**
	 * True if a route runs along a line, either direction. A line of (0, 0) is unknown and a
	 * non-local route has none, so both pass; only a short route beside a known line is refused.
	 */
	static boolean follows(int routeX, int routeY, int lineX, int lineY)
	{
		if (!local(lineX, lineY) || !local(routeX, routeY))
		{
			return true;
		}
		return degreesOff(routeX, routeY, lineX, lineY) <= MAX_DEGREES_OFF;
	}

	static double degreesOff(int routeX, int routeY, int lineX, int lineY)
	{
		double cos = Math.abs(routeX * lineX + routeY * lineY)
			/ (Math.hypot(routeX, routeY) * Math.hypot(lineX, lineY));
		return Math.toDegrees(Math.acos(Math.min(1, cos)));
	}

	/** A direction with length and sense removed: (0, 3), (0, -1) and (0, 2) are all (0, 1). */
	static int[] canonical(int dx, int dy)
	{
		int divisor = gcd(Math.abs(dx), Math.abs(dy));
		if (divisor == 0)
		{
			return new int[]{0, 0};
		}
		int x = dx / divisor;
		int y = dy / divisor;
		if (x < 0 || (x == 0 && y < 0))
		{
			x = -x;
			y = -y;
		}
		return new int[]{x, y};
	}

	private static int gcd(int a, int b)
	{
		return b == 0 ? a : gcd(b, a % b);
	}
}
