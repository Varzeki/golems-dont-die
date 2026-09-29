package com.golemsdontdie;

import java.util.List;
import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

/**
 * Where a golem will be, expressed so that knowing costs nothing until someone asks.
 *
 * <p>A golem out of view need only be somewhere plausible by the time anyone looks, so instead
 * of ticking it the plugin records its route and start tick; position is then a pure function of
 * elapsed time. More correct than a tick, not cheaper: no drift, nothing to catch up after an
 * hour tabbed out, no dependence on framerate.
 *
 * <p>Measured in <b>distance</b>, not waypoints: a straightened sea route can have waypoints
 * fifty tiles apart, and advancing one per tick made a three-hundred-tile crossing take twenty
 * ticks. Position is interpolated along the polyline, in 128ths of a tile.
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
	 * True if this route crosses water rather than walking. A roaming route is a straight line
	 * drawn without consulting the map, since at Tier 3 nothing observes it, so a golem promoted
	 * into view mid-route must be put back on walkable ground first. A voyage was searched
	 * across the ocean mesh, so it survives being watched.
	 */
	@Getter
	private final boolean voyage;

	/**
	 * Where a transport at the end of the walk puts the golem, or null for a plain route. Kept
	 * apart from the waypoints: as the last one, a ladder into a dungeon joins tiles some six
	 * thousand apart, and that distance was walked at a tick a tile — one ladder took an hour.
	 * Using the transport is a wait at the end of the walk.
	 */
	private final WorldPoint landing;

	/** The transport that ends the route, or null. Taken on arrival, not when planned. */
	private final GolemTransport transport;

	/**
	 * For a crossing, where the raft is at the end of every tick and which way it faces, in fine
	 * units and 2048ths of a turn; null otherwise. A boat turns a compass point a tick and moves
	 * the way it faces, so its path is decided when the crossing is planned and only read here.
	 */
	private final int[] fineXs;
	private final int[] fineYs;
	private final int[] facings;

	private Itinerary(int[] xs, int[] ys, int[] reached, int length, int plane,
		int startTick, int duration, boolean voyage, WorldPoint landing, GolemTransport transport,
		int[] fineXs, int[] fineYs, int[] facings)
	{
		this.fineXs = fineXs;
		this.fineYs = fineYs;
		this.facings = facings;
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
				// Chebyshev: a diagonal step costs one tick exactly as a cardinal one does.
				// Euclidean tiles would make diagonal legs take longer than the game does.
				travelled += Math.max(Math.abs(xs[i] - xs[i - 1]), Math.abs(ys[i] - ys[i - 1]));
			}
			reached[i] = travelled;
		}

		return new Itinerary(xs, ys, reached, travelled, plane, startTick,
			Math.max(1, travelled * ticksPerTile + extra), voyage, null, null, null, null, null);
	}

	/**
	 * Plans a walk to a transport's origin, then the transport. The walk takes a tick a tile, the
	 * golem then waits at the origin for as long as the transport takes.
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
			route.length + Math.max(1, transport.getDuration()), false, transport.destination(), transport, null, null, null);
	}

	/**
	 * A crossing with no drawn route: the golem waits where it is, then is at the landing. A
	 * voyage, so it survives being watched.
	 */
	static Itinerary passage(WorldPoint from, WorldPoint landing, int startTick, int ticks)
	{
		int[] xs = {from.getX()};
		int[] ys = {from.getY()};
		return new Itinerary(xs, ys, new int[]{0}, 0, from.getPlane(), startTick, Math.max(1, ticks), true,
			landing, null, null, null, null);
	}

	/**
	 * A crossing by sea, sailed tick by tick: where the raft is and which way it faces at the end
	 * of each tick, then the golem stepping ashore. It starts on the water, at the mooring, so
	 * the golem is on the raft from its first frame.
	 *
	 * @param fineXs  position at the end of each tick, in fine units; the mooring first
	 * @param facings heading at the end of each tick, 0 south, 512 west, 1024 north, 1536 east
	 */
	static Itinerary steered(int[] fineXs, int[] fineYs, int[] facings, int plane, int startTick, WorldPoint landing)
	{
		int n = fineXs.length;
		int[] xs = new int[n];
		int[] ys = new int[n];
		int[] reached = new int[n];
		int travelled = 0;
		for (int i = 0; i < n; i++)
		{
			xs[i] = Math.floorDiv(fineXs[i], Golem.TILE);
			ys[i] = Math.floorDiv(fineYs[i], Golem.TILE);
			if (i > 0)
			{
				travelled += Math.max(Math.abs(xs[i] - xs[i - 1]), Math.abs(ys[i] - ys[i - 1]));
			}
			reached[i] = travelled;
		}
		return new Itinerary(xs, ys, reached, travelled, plane, startTick, Math.max(1, n - 1), true, landing,
			null, fineXs, fineYs, facings);
	}

	/** Where a crossing is this far in, in fine units: gliding from one tick's position to the next. */
	private int[] alongCrossing(double time)
	{
		int last = fineXs.length - 1;
		if (time >= last)
		{
			return new int[]{fineXs[last], fineYs[last]};
		}
		int i = (int) Math.floor(time);
		if (jumps(i))
		{
			// Through a cave mouth: out of one and straight in at the other, not sliding
			// the thousands of tiles between.
			return new int[]{fineXs[i], fineYs[i]};
		}
		double into = time - i;
		return new int[]{
			fineXs[i] + (int) Math.round((fineXs[i + 1] - fineXs[i]) * into),
			fineYs[i] + (int) Math.round((fineYs[i + 1] - fineYs[i]) * into),
		};
	}

	/** A move between two ticks longer than any boat makes: a raft put through from one cave mouth to the other. */
	private static final int JUMP = 16 * Golem.TILE;

	private boolean jumps(int tick)
	{
		return Math.abs(fineXs[tick + 1] - fineXs[tick]) > JUMP || Math.abs(fineYs[tick + 1] - fineYs[tick]) > JUMP;
	}

	/**
	 * Which way a crossing's raft faces part way through a tick, turning the short way from one
	 * tick's heading to the next; -1 for any other route.
	 */
	int facingAt(int tick, float fraction)
	{
		if (facings == null)
		{
			return -1;
		}
		int last = facings.length - 1;
		double time = Math.max(0, tick + (double) fraction - startTick);
		if (time >= last)
		{
			return facings[last];
		}
		int i = (int) Math.floor(time);
		if (jumps(i))
		{
			return facings[i];
		}
		int turn = ((facings[i + 1] - facings[i] + 1024) & 2047) - 1024;
		return (facings[i] + (int) Math.round(turn * (time - i))) & 2047;
	}

	/** The waypoint the golem is at or has most recently passed at this tick. */
	private int segmentAt(int tick)
	{
		return fineXs != null ? Math.min(fineXs.length - 1, Math.max(0, tick - startTick))
			: segmentFor((int) Math.floor(travelledAt(tick)));
	}

	/**
	 * Where the golem is at this tick, in 128ths of a tile. Interpolated by distance, so a golem
	 * on a long straight leg moves along it, and clamped at both ends: an expired route means it
	 * has arrived and waits, not that it has vanished.
	 */
	int[] fineAt(int tick)
	{
		return fineAt(tick, 0f);
	}

	/**
	 * As {@link #fineAt(int)}, part way through a tick. For a golem in view: read in whole ticks
	 * and tiles, a boat stepped across the sea instead of gliding.
	 *
	 * @param fraction how far through the tick, 0 to 1
	 */
	int[] fineAt(int tick, float fraction)
	{
		if (landing != null && isFinished(tick))
		{
			return fine(landing.getX(), landing.getY());
		}
		if (fineXs != null)
		{
			return alongCrossing(Math.max(0, tick + (double) fraction - startTick));
		}
		return along(travelledAt(tick + (double) fraction));
	}

	/** The tick {@link #memoX} and {@link #memoY} were worked out for. */
	private int memoTick = Integer.MIN_VALUE;
	private int memoX;
	private int memoY;

	/**
	 * {@link #fineAt(int)}'s x, kept for the tick it was asked for: a route never changes once
	 * planned, so a tick's answer is worked out once however many frames ask for it.
	 */
	int fineXAt(int tick)
	{
		memoise(tick);
		return memoX;
	}

	/** {@link #fineAt(int)}'s y; see {@link #fineXAt}. */
	int fineYAt(int tick)
	{
		memoise(tick);
		return memoY;
	}

	private void memoise(int tick)
	{
		if (tick != memoTick)
		{
			int[] at = fineAt(tick);
			memoX = at[0];
			memoY = at[1];
			memoTick = tick;
		}
	}

	/** Tiles along the waypoints the golem has covered by this time, in ticks. */
	private double travelledAt(double time)
	{
		double elapsed = Math.min(Math.max(0, time - startTick), duration);
		if (landing != null)
		{
			// A tick a tile, then standing at the transport until it is used.
			return Math.min(elapsed, length);
		}
		return length <= 0 || duration <= 0 ? 0 : elapsed * length / duration;
	}

	/** The position a given distance along the waypoints, in fine units. */
	private int[] along(double travelled)
	{
		int segment = segmentFor((int) Math.floor(travelled));
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
		double into = travelled - from;
		int fromX = fine(xs[segment]);
		int fromY = fine(ys[segment]);
		int toX = fine(xs[segment + 1]);
		int toY = fine(ys[segment + 1]);

		return new int[]{
			fromX + (int) Math.round((toX - fromX) * into / span),
			fromY + (int) Math.round((toY - fromY) * into / span),
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
	 * The plane the golem is on at this tick: the walk's, until a transport has moved it.
	 * Reading the destination's plane had a golem walking to a ladder already upstairs.
	 */
	int planeAt(int tick)
	{
		return landing != null && isFinished(tick) ? landing.getPlane() : plane;
	}

	/**
	 * The direction the golem is travelling at this tick, as a fine-unit delta. Taken from the
	 * leg it is on, not from where it was last frame, so a stationary golem still faces the way
	 * it is going.
	 */
	int[] headingAt(int tick)
	{
		if (facings != null)
		{
			double angle = (facingAt(tick, 0f) - 1024) * Math.PI / 1024;
			return new int[]{(int) Math.round(Math.sin(angle) * Golem.TILE), (int) Math.round(Math.cos(angle) * Golem.TILE)};
		}
		if (length <= 0)
		{
			return new int[]{0, 0};
		}
		int segment = segmentAt(tick);
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
		// Made once: a golem out of view that has arrived asks for this every frame until
		// it gets a new route.
		if (walkEnd == null)
		{
			walkEnd = new WorldPoint(xs[xs.length - 1], ys[ys.length - 1], plane);
		}
		return walkEnd;
	}

	private WorldPoint walkEnd;

	/** True if this is a crossing by sea with legs to sail, rather than a passage or a walk. */
	boolean isSailed()
	{
		return voyage && xs.length > 1;
	}

	/** The transport this route ends in, or null. */
	GolemTransport transport()
	{
		return transport;
	}

	/**
	 * The same route walked backwards from where the golem has got to: the second rung of the
	 * raft's unstick ladder, since a boat in a dead-end inlet got there along an open route.
	 *
	 * @return a route back toward the start, or null if there is nothing to back out of
	 */
	Itinerary reversed(int tick, int newStartTick)
	{
		int at = segmentAt(tick);
		if (at < 1)
		{
			return null;
		}

		List<int[]> back = new java.util.ArrayList<>();
		for (int i = at; i >= 0; i--)
		{
			back.add(new int[]{xs[i], ys[i]});
		}

		// Same pace it came in at, which the route's length over its duration already says; a
		// walk to a transport went at a tick a tile.
		int ticksPerTile = landing != null ? 1 : Math.max(1, duration / Math.max(1, length));
		return of(back, plane, newStartTick, ticksPerTile, 0, voyage);
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
