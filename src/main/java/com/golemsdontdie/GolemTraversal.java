package com.golemsdontdie;

import java.util.List;

/**
 * One crossing a golem made in view: the route it used, and where it actually was while using it.
 *
 * <p>The golem's half of the obstacle record, so a slanted crossing shows up as a number; see
 * ObstacleDataBridge. Positions are in 128ths of a tile, as golems are simulated. Only short,
 * same-floor crossings are traced: a ladder has no path across it to be wrong about.
 */
final class GolemTraversal
{
	final GolemTransport route;

	/** Where the golem was when the crossing began and when it let go. */
	final int startX;
	final int startY;
	final int endX;
	final int endY;

	/** {cycles since the start, x, y}, a frame apart. */
	final List<int[]> samples;

	GolemTraversal(GolemTransport route, int startX, int startY, int endX, int endY, List<int[]> samples)
	{
		this.route = route;
		this.startX = startX;
		this.startY = startY;
		this.endX = endX;
		this.endY = endY;
		this.samples = samples;
	}
}
