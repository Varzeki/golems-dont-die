package com.golemsdontdie;

/**
 * One obstacle, watched being used, reduced to the facts worth keeping.
 *
 * <p>What {@link ObstacleObserver} produces once a traversal is judged real, and what telemetry
 * would send, so it deliberately holds nothing identifying who saw it.
 */
final class ObstacleSighting
{
	final int objectId;

	/** The object's name from the cache, for a human to read. */
	final String name;

	/** The menu text, e.g. "Climb-down Ladder". */
	final String menu;

	/** Every animation the player played, in order. */
	final int[] clips;

	/** Game ticks from the first clip to standing still somewhere new. */
	final int ticks;

	/**
	 * Client cycles from the first clip until the player actually moves: the wind-up, during which
	 * they hold still. Measured on the basalt stones at <b>33 cycles of stillness then 12 of
	 * movement</b>, identical across four hops. Without it a golem drifts from the first frame.
	 */
	final int moveDelay;

	/** Client cycles the movement lasts, once it starts. */
	final int moveSpan;

	/**
	 * The traversal as it happened, or null if it could not be recorded. Delay and span above
	 * remain for obstacles no curve was captured for.
	 */
	final MotionCurve curve;

	final int fromX;
	final int fromY;
	final int fromPlane;
	final int toX;
	final int toY;
	final int toPlane;

	/**
	 * True if this happened inside an instanced region: the animation is worth keeping, the
	 * destination is not. One church pew recorded three exits, (12105,4551) among them.
	 */
	final boolean instance;

	/**
	 * The way the obstacle itself moved the player, in tiles: from the tile they began on to where
	 * they finished. (0, 0) for no local movement — a ladder or a teleport. The route, where a golem
	 * gets on and off, may be a tile either side but must lie along this. See {@link RouteGeometry}.
	 */
	final int lineX;
	final int lineY;

	/**
	 * True if the traversal began inside an instance, and if it ended inside one: the route's ends
	 * are in template coordinates either way, so only these say which side is an instance.
	 */
	final boolean fromInstance;
	final boolean toInstance;

	ObstacleSighting(int objectId, String name, String menu, int[] clips, int ticks,
		int fromX, int fromY, int fromPlane, int toX, int toY, int toPlane, boolean instance,
		int moveDelay, int moveSpan, MotionCurve curve, int lineX, int lineY)
	{
		this(objectId, name, menu, clips, ticks, fromX, fromY, fromPlane, toX, toY, toPlane, instance,
			moveDelay, moveSpan, curve, lineX, lineY, false, false);
	}

	ObstacleSighting(int objectId, String name, String menu, int[] clips, int ticks,
		int fromX, int fromY, int fromPlane, int toX, int toY, int toPlane, boolean instance,
		int moveDelay, int moveSpan, MotionCurve curve, int lineX, int lineY,
		boolean fromInstance, boolean toInstance)
	{
		this.fromInstance = fromInstance;
		this.toInstance = toInstance;
		this.lineX = lineX;
		this.lineY = lineY;
		this.curve = curve;
		this.instance = instance;
		this.moveDelay = moveDelay;
		this.moveSpan = moveSpan;
		this.objectId = objectId;
		this.name = name;
		this.menu = menu;
		this.clips = clips;
		this.ticks = ticks;
		this.fromX = fromX;
		this.fromY = fromY;
		this.fromPlane = fromPlane;
		this.toX = toX;
		this.toY = toY;
		this.toPlane = toPlane;
	}

	/** True if the player went somewhere, which is what makes this a traversal. */
	boolean moved()
	{
		return fromX != toX || fromY != toY || fromPlane != toPlane;
	}

	@Override
	public String toString()
	{
		StringBuilder sb = new StringBuilder();
		sb.append(objectId).append(" \"").append(menu).append("\" clips=");
		for (int i = 0; i < clips.length; i++)
		{
			sb.append(i == 0 ? "" : ",").append(clips[i]);
		}
		return sb.append(" ticks=").append(ticks)
			.append(" delay=").append(moveDelay).append(" span=").append(moveSpan)
			.append(curve == null ? "" : " [" + curve + "]")
			.append(" ").append(fromX).append(",").append(fromY).append(",").append(fromPlane)
			.append(" -> ").append(toX).append(",").append(toY).append(",").append(toPlane)
			.append(" line=").append(lineX).append(",").append(lineY)
			.append(toInstance && !fromInstance ? " into-instance" : fromInstance && !toInstance ? " out-of-instance" : "")
			.toString();
	}
}
