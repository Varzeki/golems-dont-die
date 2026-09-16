package com.golemsdontdie;

/**
 * Whether a learned route follows the obstacle it was learned from.
 *
 * <p>An obstacle moves the player along a fixed line: a stile from one of its tiles to the
 * other, a rockslide from its foot to its top, a stepping stone to the next. A route is two
 * tiles chosen around that movement — where a golem gets on and where it gets off — and a
 * golem performs it along the straight line between them. If those two tiles are not on the
 * obstacle's line, neither is the golem.
 *
 * <p>That is how the stile came to be crossed diagonally. Its route ends were taken from
 * where the player happened to stand before and after, and a player walking up from the side
 * and stepping off at an angle taught a route eighteen degrees off the stile, which every
 * golem then crossed on a slant. The validator found it after the fact. This refuses such a
 * route when it is learned, and ignores one already saved, so it never reaches a golem.
 */
final class RouteGeometry
{
	/**
	 * How far off an obstacle's line a route may run.
	 *
	 * <p>Ten degrees. A route and its line are both whole tiles, so real routes are either
	 * exactly on the line or off it by the angle of a whole tile's offset — the diagonal
	 * stile was eighteen — and nothing legitimate falls in between.
	 */
	static final double MAX_DEGREES_OFF = 10;

	/**
	 * Beyond this many tiles a traversal is a teleport — a cave mouth, a staircase — and has
	 * no line to follow. Its ends are wherever the game puts the player.
	 */
	static final int LOCAL_TILES = 8;

	private RouteGeometry()
	{
	}

	/** True if a movement is short enough to have a line at all. */
	static boolean local(int dx, int dy)
	{
		return (dx != 0 || dy != 0) && Math.abs(dx) <= LOCAL_TILES && Math.abs(dy) <= LOCAL_TILES;
	}

	/**
	 * True if a route runs along a line, in either direction.
	 *
	 * <p>A line of (0, 0) is unknown and anything follows it; so does any route too long to
	 * be local. Only a short route beside a known line is refused.
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

	/**
	 * A direction with its length and sense removed, so the same line crossed either way and
	 * at any length compares equal: (0, 3), (0, -1) and (0, 2) are all (0, 1).
	 */
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
