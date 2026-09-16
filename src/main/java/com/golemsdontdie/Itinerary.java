package com.golemsdontdie;

import java.util.List;
import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

/**
 * Where a golem will be, expressed so that knowing costs nothing until someone asks.
 *
 * <p>A golem the player cannot see does not need to be stepped forward. It needs to be
 * <i>somewhere</i>, and to have got there plausibly, by the time anyone looks. So instead
 * of ticking it, the plugin writes down the route it is taking and when it set off; its
 * position at any instant is then a pure function of elapsed time.
 *
 * <p>This is not a cheaper approximation of a tick — it is more correct than one. There is
 * no accumulated drift, nothing to catch up after the client has been tabbed out for an
 * hour, and no dependence on framerate. A thousand golems crossing the world cost a few
 * hundred bytes each and no CPU at all between the moment the route is planned and the
 * moment someone walks into view of one.
 *
 * <p>Everything here is measured in <b>distance</b> rather than in waypoints, which is the
 * one thing it is easy to get wrong. A sea route is straightened before it gets here —
 * long runs collapsed to their corners — so its waypoints can be fifty tiles apart.
 * Advancing one waypoint per tick made a golem teleport a whole leg at a time and made a
 * three-hundred-tile crossing take twenty ticks. Position is interpolated along the
 * polyline instead, in the same 128ths of a tile everything else uses.
 */
final class Itinerary
{
	/** Tiles of the route, in order. The first is where the golem set off from. */
	private final int[] xs;
	private final int[] ys;
	private final int plane;

	/** Distance from the start to each waypoint, in tiles. */
	private final int[] reached;

	/** Total length of the route in tiles. */
	private final int length;

	/** The tick the golem left the first waypoint. */
	@Getter
	private final int startTick;

	/** Ticks the whole route takes, so expiry is a comparison rather than a walk. */
	@Getter
	private final int duration;

	/**
	 * True if this route crosses water rather than walking.
	 *
	 * <p>It changes what happens when the player catches up with the golem. A roaming
	 * route is a straight line drawn without consulting the map — deliberately
	 * approximate, because at Tier 3 nothing observes it — so a golem promoted into view
	 * mid-route has to be put back on real walkable ground before anyone sees it standing
	 * in a wall.
	 *
	 * <p>A voyage is different: it was searched properly across the ocean mesh, every
	 * waypoint is real water, and the golem is drawn on a boat. It survives being watched,
	 * so the player can sail out and meet a golem crossing.
	 */
	@Getter
	private final boolean voyage;

	/**
	 * Where a transport at the end of the walk puts the golem, or null for a plain route.
	 *
	 * <p>Kept apart from the waypoints on purpose. The landing used to be the last waypoint,
	 * and a ladder into a dungeon joins two tiles some six thousand apart — so that distance
	 * was walked, a tick a tile. One ladder took an hour, the golem slid across the map
	 * between the two ends the whole time, and a golem that reached a dungeon had barely got
	 * down its first ladder before the session was over. The walk is the waypoints; using
	 * the transport is a wait at the end of them; then the golem is at the landing.
	 */
	private final WorldPoint landing;

	/** The transport that ends the route, or null. Taken on arrival, not when planned. */
	private final GolemTransport transport;

	private Itinerary(int[] xs, int[] ys, int[] reached, int length, int plane,
		int startTick, int duration, boolean voyage, WorldPoint landing, GolemTransport transport)
	{
		this.transport = transport;
		this.xs = xs;
		this.ys = ys;
		this.reached = reached;
		this.length = length;
		this.plane = plane;
		this.startTick = startTick;
		this.duration = duration;
		this.voyage = voyage;
		this.landing = landing;
	}

	/** Plans a walking route, one tick per tile. */
	static Itinerary of(List<int[]> path, int plane, int startTick, int extra)
	{
		return of(path, plane, startTick, 1, extra, false);
	}

	/**
	 * Plans a route.
	 *
	 * @param path         tiles in order, as {@code {x, y}} pairs; the golem's tile first
	 * @param ticksPerTile pace — one for walking, more for something slower
	 * @param extra        ticks on top of the travel itself, for boarding and the like
	 * @param voyage       true if this crosses water
	 */
	static Itinerary of(List<int[]> path, int plane, int startTick, int ticksPerTile,
		int extra, boolean voyage)
	{
		if (path == null || path.isEmpty())
		{
			return null;
		}

		int[] xs = new int[path.size()];
		int[] ys = new int[path.size()];
		int[] reached = new int[path.size()];

		int travelled = 0;
		for (int i = 0; i < path.size(); i++)
		{
			xs[i] = path.get(i)[0];
			ys[i] = path.get(i)[1];
			if (i > 0)
			{
				// Chebyshev, because a diagonal step costs one tick exactly as a cardinal
				// one does. Measuring in Euclidean tiles would make diagonal legs take
				// longer than the game would take to walk them.
				travelled += Math.max(Math.abs(xs[i] - xs[i - 1]), Math.abs(ys[i] - ys[i - 1]));
			}
			reached[i] = travelled;
		}

		return new Itinerary(xs, ys, reached, travelled, plane, startTick,
			Math.max(1, travelled * ticksPerTile + extra), voyage, null, null);
	}

	/**
	 * Plans a walk to a transport's origin, then the transport.
	 *
	 * <p>The walk takes a tick a tile; the golem then stands at the origin for as long as the
	 * transport takes, and is at its landing once the route is finished.
	 *
	 * @param walk tiles to the transport's origin, the golem's own tile first
	 */
	static Itinerary thenTransport(List<int[]> walk, int plane, int startTick, GolemTransport transport)
	{
		Itinerary route = of(walk, plane, startTick, 1, 0, false);
		if (route == null)
		{
			return null;
		}
		return new Itinerary(route.xs, route.ys, route.reached, route.length, plane, startTick,
			route.length + Math.max(1, transport.getDuration()), false, transport.destination(), transport);
	}

	/**
	 * A crossing with no drawn route: the golem waits where it is, then is at the landing.
	 *
	 * <p>A voyage, so it survives being watched like any other; nothing is interpolated.
	 */
	static Itinerary passage(WorldPoint from, WorldPoint landing, int startTick, int ticks)
	{
		int[] xs = {from.getX()};
		int[] ys = {from.getY()};
		return new Itinerary(xs, ys, new int[]{0}, 0, from.getPlane(), startTick, Math.max(1, ticks), true,
			landing, null);
	}

	/**
	 * Where the golem is at this tick, in 128ths of a tile.
	 *
	 * <p>Interpolated along the route by distance, so a golem crossing a long straight leg
	 * moves along it rather than appearing at the far end.
	 *
	 * <p>Clamped at both ends: before the start it is still at the first waypoint, and
	 * after the route runs out it waits at the last. Waiting at the destination is the
	 * correct behaviour — a golem whose route has expired has arrived, not vanished.
	 */
	int[] fineAt(int tick)
	{
		if (landing != null && isFinished(tick))
		{
			return fine(landing.getX(), landing.getY());
		}
		return along(travelledAt(tick));
	}

	/** Tiles along the waypoints the golem has covered by this tick. */
	private int travelledAt(int tick)
	{
		int elapsed = Math.min(Math.max(0, tick - startTick), duration);
		if (landing != null)
		{
			// A tick a tile, then standing at the transport until it is used.
			return Math.min(elapsed, length);
		}
		return length <= 0 || duration <= 0 ? 0 : (int) ((long) elapsed * length / duration);
	}

	/** The position a given distance along the waypoints, in fine units. */
	private int[] along(int travelled)
	{
		int segment = segmentFor(travelled);
		if (segment >= xs.length - 1)
		{
			return fine(xs[xs.length - 1], ys[ys.length - 1]);
		}

		int from = reached[segment];
		int span = reached[segment + 1] - from;
		if (span <= 0)
		{
			return fine(xs[segment], ys[segment]);
		}

		// Sub-tile position along this leg, so movement is smooth rather than stepped.
		int into = travelled - from;
		int fromX = fine(xs[segment]);
		int fromY = fine(ys[segment]);
		int toX = fine(xs[segment + 1]);
		int toY = fine(ys[segment + 1]);

		return new int[]{
			fromX + (int) ((long) (toX - fromX) * into / span),
			fromY + (int) ((long) (toY - fromY) * into / span),
		};
	}

	/** The waypoint the golem has most recently passed. */
	private int segmentFor(int travelled)
	{
		int low = 0;
		int high = reached.length - 1;
		while (low < high)
		{
			int mid = (low + high + 1) >>> 1;
			if (reached[mid] <= travelled)
			{
				low = mid;
			}
			else
			{
				high = mid - 1;
			}
		}
		return low;
	}

	/** Where the golem is at this tick, to the nearest tile. */
	WorldPoint positionAt(int tick)
	{
		int[] at = fineAt(tick);
		return new WorldPoint(at[0] / Golem.TILE, at[1] / Golem.TILE, planeAt(tick));
	}

	/**
	 * The plane the golem is on at this tick.
	 *
	 * <p>The walk's plane until a transport has put it somewhere else. Reading the
	 * destination's plane instead had a golem walking to a ladder already upstairs, and one
	 * promoted into view on the way was snapped onto the floor above.
	 */
	int planeAt(int tick)
	{
		return landing != null && isFinished(tick) ? landing.getPlane() : plane;
	}

	/**
	 * The direction the golem is travelling at this tick, as a fine-unit delta.
	 *
	 * <p>Taken from the leg it is on rather than from where it was last frame, so a golem
	 * that is momentarily stationary still faces the way it is going.
	 */
	int[] headingAt(int tick)
	{
		if (length <= 0)
		{
			return new int[]{0, 0};
		}
		int segment = segmentFor(travelledAt(tick));
		if (segment >= xs.length - 1)
		{
			segment = Math.max(0, xs.length - 2);
		}
		return new int[]{
			(xs[segment + 1] - xs[segment]) * Golem.TILE,
			(ys[segment + 1] - ys[segment]) * Golem.TILE,
		};
	}

	/** True once the route has been fully travelled and a new one is needed. */
	boolean isFinished(int tick)
	{
		return tick - startTick >= duration;
	}

	/** Where the golem ends up: the landing of a transport, or the last waypoint. */
	WorldPoint destination()
	{
		return landing != null ? landing : walkEnd();
	}

	/** The last waypoint: the origin of a transport, or the destination of a plain route. */
	WorldPoint walkEnd()
	{
		return new WorldPoint(xs[xs.length - 1], ys[ys.length - 1], plane);
	}

	/** True if this route ends by taking a transport rather than on its last tile. */
	boolean endsInTransport()
	{
		return landing != null;
	}

	/** The transport this route ends in, or null. */
	GolemTransport transport()
	{
		return transport;
	}

	int waypoints()
	{
		return xs.length;
	}

	/**
	 * The same route walked backwards from where the golem has got to.
	 *
	 * <p>The second rung of the raft's unstick ladder. A boat that has run itself into a
	 * dead-end inlet got there along a route that was open, so the cheapest way out is the
	 * way in — and it costs nothing to work out, because the waypoints are already here.
	 *
	 * @return a route back toward the start, or null if there is nothing to back out of
	 */
	Itinerary reversed(int tick, int newStartTick)
	{
		int at = segmentFor(travelledAt(tick));
		if (at < 1)
		{
			return null;
		}

		List<int[]> back = new java.util.ArrayList<>();
		for (int i = at; i >= 0; i--)
		{
			back.add(new int[]{xs[i], ys[i]});
		}

		// Same pace it came in at, which is what the route's own length over its duration
		// says — no need to be told again. A walk to a transport went at a tick a tile.
		int ticksPerTile = landing != null ? 1 : Math.max(1, duration / Math.max(1, length));
		return of(back, plane, newStartTick, ticksPerTile, 0, voyage);
	}

	/**
	 * How far along the route the golem is, as a fraction.
	 *
	 * <p>Used for deciding whether interrupting a journey is worth it — a golem nine
	 * tenths of the way across an ocean should finish the crossing even if the reason it
	 * set out has since evaporated.
	 */
	float progress(int tick)
	{
		return duration <= 0 ? 1f
			: Math.min(1f, Math.max(0f, (tick - startTick) / (float) duration));
	}

	private static int fine(int tile)
	{
		return tile * Golem.TILE + Golem.TILE / 2;
	}

	private static int[] fine(int tileX, int tileY)
	{
		return new int[]{fine(tileX), fine(tileY)};
	}
}
