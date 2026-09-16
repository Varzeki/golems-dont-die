package com.golemsdontdie;

/**
 * One obstacle, watched being used, reduced to the facts worth keeping.
 *
 * <p>This is what falls out of {@link ObstacleObserver} once a traversal has finished and
 * been judged real. It is the unit {@link ObstacleKnowledge} learns from and the unit
 * {@link ObstacleTelemetry} would send, so it is deliberately small and deliberately free
 * of anything about the player: an object id, the clips it played, how long it took, and
 * where it went. Nothing here identifies who saw it.
 */
final class ObstacleSighting
{
	/** The scene object interacted with. */
	final int objectId;

	/** The object's name from the cache, for reading by a human later. */
	final String name;

	/** The menu text, e.g. "Climb-down Ladder". */
	final String menu;

	/** Every animation the player played, in order. */
	final int[] clips;

	/** Game ticks from the first clip starting to the player standing still somewhere new. */
	final int ticks;

	/**
	 * Client cycles from the first clip starting until the player actually moves.
	 *
	 * <p>The wind-up, and the thing that was missing. A traversal is not "play a clip while
	 * sliding across" — the player holds still for a good part of the animation and then
	 * goes. Measured on the basalt stones at <b>33 cycles of stillness followed by 12 of
	 * movement</b>, identical across four consecutive hops, and matching the game's own
	 * script for the same obstacle elsewhere.
	 *
	 * <p>Without it a golem starts drifting on the first frame, which is why hops never
	 * looked right however carefully the total duration was tuned.
	 */
	final int moveDelay;

	/** Client cycles the movement itself lasts, once it starts. */
	final int moveSpan;

	/**
	 * The traversal as it happened, or null if it could not be recorded.
	 *
	 * <p>The whole motion rather than a summary of it. The delay and span above remain for
	 * obstacles no curve was ever captured for, and as something readable in the journal,
	 * but the curve is what a golem actually performs.
	 */
	final MotionCurve curve;

	final int fromX;
	final int fromY;
	final int fromPlane;
	final int toX;
	final int toY;
	final int toPlane;

	/**
	 * True if this happened inside an instanced region.
	 *
	 * <p>The animation is still worth keeping; the destination is not. An instance is
	 * built fresh each time with different coordinates, so the same church pew recorded
	 * three exits to (12105,4551), (12873,4551) and (12297,4744) — none of which will
	 * exist next time anybody walks through it.
	 */
	final boolean instance;

	/**
	 * The way the obstacle itself moved the player, in tiles: from the tile they were on as
	 * it began to where they were when it finished. (0, 0) if there was no local movement —
	 * a teleport, a ladder, a silent staircase.
	 *
	 * <p>Separate from the route, which is where a golem gets on and off and may be a tile
	 * either side of this. The route has to lie along it; see {@link RouteGeometry}.
	 */
	final int lineX;
	final int lineY;

	/**
	 * True if the traversal began inside an instance, and if it ended inside one.
	 *
	 * <p>The route's ends are in template coordinates either way, so they cannot say this for
	 * themselves: the pew into the Mad Angel's room goes from one side of the cathedral pew to
	 * the other, and it is only these that say the far side is an instance.
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

	/** True if the player actually went somewhere, which is what makes this a traversal. */
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
