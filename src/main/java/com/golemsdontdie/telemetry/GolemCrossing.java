package com.golemsdontdie.telemetry;

import lombok.*;

/**
 * One crossing of an obstacle by a golem the player could see, as it is kept on disk.
 *
 * <p>The route is the pair of tiles the golem was meant to cross between; the positions are where it
 * actually was, frame by frame. {@link ObstacleDataFile} measures those against the route, the same
 * way a player's path is measured, so the two can be compared directly.
 *
 * <p>Positions are world coordinates in 128ths of a tile; times are client cycles (20 ms).
 */
@AllArgsConstructor
public final class GolemCrossing
{
	/** The scene object crossed. */
	public final int objectId;

	/** The tile the route starts from and the tile it ends on: x, y and floor. */
	public final int fromX;
	public final int fromY;
	public final int fromPlane;
	public final int toX;
	public final int toY;
	public final int toPlane;

	/** Where the golem was at each frame of the crossing: {cycle, x, y}. First and last included. */
	public final int[][] positions;

	/** True if the route passed over a tile another short hop starts on: a stepping stone skipped. */
	public final boolean overStone;
}
