package com.golemsdontdie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/**
 * Routes a raft across the ocean.
 *
 * <p>The finding that made this easy: the collision map already marks open water as
 * passable and shoreline edges as blocked. That is why Wyrmscraig reads as a sealed
 * 2,456-tile island <em>and</em> why 2,458,003 tiles of connected sea sit in the same
 * resource. There is no separate sea navigation to build — golems path the ocean with the
 * same flags and the same corner rule they use in a corridor.
 *
 * <p>Three differences from the land pathfinder, all of them because an ocean is enormous:
 *
 * <ul>
 *   <li><b>A*, not breadth-first.</b> Three thousand nodes does not cross an ocean.</li>
 *   <li><b>An inflated heuristic.</b> Weighting it above the true distance expands far
 *       fewer nodes and produces straighter routes. Both are wanted: a boat holding a
 *       heading looks better than one weaving optimally around a headland.</li>
 *   <li><b>Straightening afterwards.</b> Sailing moves in long axis-aligned or perfectly
 *       diagonal runs, so the tile path is collapsed into legs before anything draws it.</li>
 * </ul>
 *
 * <p>This runs at Tier 3, off the frame budget, a handful of times per golem per crossing.
 */
@Slf4j
@Singleton
class SeaMesh
{
	/**
	 * Ceiling on tiles expanded for one crossing.
	 *
	 * <p>Large, because the alternative is a golem that cannot leave. An ocean crossing is
	 * planned once and then replayed from a timestamp for several minutes, so the cost is
	 * amortised over the whole voyage rather than paid per frame.
	 */
	private static final int NODE_BUDGET = 200_000;

	/**
	 * How much the heuristic is inflated. Above 1 this stops being optimal and starts
	 * being fast and straight, which is the trade wanted here — nobody measures a golem's
	 * route for optimality, but a visibly wandering boat reads as broken.
	 */
	private static final float HEURISTIC_WEIGHT = 1.4f;

	private static final int[] DX = {0, 0, 1, -1, 1, -1, 1, -1};
	private static final int[] DY = {1, -1, 0, 0, 1, 1, -1, -1};

	@Inject
	private WorldMesh mesh;

	/**
	 * True if two points sit on the same body of water.
	 *
	 * <p>Always ask before pathing. Not every water tile is the ocean — the inland body on
	 * Karamja is 26,000 tiles that connect to nothing — and a search between two
	 * disconnected basins would burn the whole node budget before failing.
	 */
	boolean sameWater(WorldPoint a, WorldPoint b)
	{
		return mesh.isOcean(a.getX(), a.getY(), 0) && mesh.isOcean(b.getX(), b.getY(), 0);
	}

	/**
	 * Routes already computed, keyed by the pair of endpoints.
	 *
	 * <p>This is what makes the search affordable at all. There are only sixty-odd ports,
	 * so however many golems cross however many times, there are a few thousand distinct
	 * crossings in the entire game — and in a real session, a handful. The first golem to
	 * sail Wyrmscraig to Catherby pays for the search; every golem after it reads the
	 * answer.
	 *
	 * <p>Failures are cached too, as an empty list. A pair with no route will not acquire
	 * one by being asked again, and re-running a quarter-million-node search to rediscover
	 * that is the worst thing this class could do.
	 */
	private final Map<Long, List<int[]>> routes = new HashMap<>();

	/**
	 * A cached route, or null if this pair has not been searched yet.
	 *
	 * <p>Separate from {@link #route} so the caller can take the cheap answer on a render
	 * frame and defer the expensive one.
	 */
	/**
	 * Crossings between every pair of moorings, computed offline by
	 * {@code dev-tools/probe/com/golemsdontdie/BuildSeaRoutes.java}.
	 *
	 * <p>The live search could not be relied on for them. Sampling forty pairs of ports, it
	 * found no route for twenty-one — the ocean is one body of water, so every one of those was
	 * the search running out of budget, not a real answer — and each failure took a quarter to
	 * two fifths of a second on the client thread, then was remembered as unroutable for the
	 * rest of the session. The search remains for a pair the table does not have.
	 */
	private static final String SHIPPED = "/sea-routes.gz";

	private boolean shippedLoaded;

	private void loadShipped()
	{
		if (shippedLoaded)
		{
			return;
		}
		shippedLoaded = true;
		try (java.io.InputStream raw = SeaMesh.class.getResourceAsStream(SHIPPED))
		{
			if (raw == null)
			{
				log.warn("Shipped sea routes missing from the jar; crossings will be searched live");
				return;
			}
			try (java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(raw);
				 java.io.DataInputStream data = new java.io.DataInputStream(gz))
			{
				int count = data.readInt();
				for (int i = 0; i < count; i++)
				{
					int legs = data.readShort() & 0xFFFF;
					List<int[]> route = new ArrayList<>(legs);
					for (int j = 0; j < legs; j++)
					{
						route.add(new int[]{data.readShort() & 0xFFFF, data.readShort() & 0xFFFF});
					}
					int[] first = route.get(0);
					int[] last = route.get(route.size() - 1);
					routes.put(key(first[0], first[1], last[0], last[1]), route);
					List<int[]> back = new ArrayList<>(route);
					Collections.reverse(back);
					routes.put(key(last[0], last[1], first[0], first[1]), back);
				}
				log.debug("Loaded {} shipped sea routes", count);
			}
		}
		catch (java.io.IOException | RuntimeException e)
		{
			log.warn("Shipped sea routes unreadable; crossings will be searched live", e);
		}
	}

	List<int[]> cachedRoute(WorldPoint from, WorldPoint to)
	{
		loadShipped();
		List<int[]> cached = routes.get(key(from, to));
		return cached == null || cached.isEmpty() ? null : cached;
	}

	/** True if this pair has already been searched, successfully or not. */
	boolean isSearched(WorldPoint from, WorldPoint to)
	{
		loadShipped();
		return routes.containsKey(key(from, to));
	}

	/**
	 * A route between two moorings, as straightened legs, or null if there is none.
	 *
	 * <p>Cached, so this is expensive exactly once per pair of ports. The first call may
	 * expand a large number of nodes; every call after it is a map lookup.
	 *
	 * @return waypoints including both ends, or null
	 */
	List<int[]> route(WorldPoint from, WorldPoint to)
	{
		loadShipped();
		long key = key(from, to);
		List<int[]> cached = routes.get(key);
		if (cached != null)
		{
			return cached.isEmpty() ? null : cached;
		}

		if (!sameWater(from, to))
		{
			routes.put(key, Collections.emptyList());
			return null;
		}

		Map<Long, Long> cameFrom = new HashMap<>();
		Map<Long, Integer> best = new HashMap<>();
		PriorityQueue<long[]> open = new PriorityQueue<>((p, q) -> Integer.compare(
			(int) p[1], (int) q[1]));

		long start = pack(from.getX(), from.getY());
		long goal = pack(to.getX(), to.getY());
		best.put(start, 0);
		open.add(new long[]{start, heuristic(from.getX(), from.getY(), to.getX(), to.getY())});

		int expanded = 0;
		boolean found = false;

		while (!open.isEmpty() && expanded < NODE_BUDGET)
		{
			long[] current = open.poll();
			long at = current[0];
			if (at == goal)
			{
				found = true;
				break;
			}
			expanded++;

			int cx = unpackX(at);
			int cy = unpackY(at);
			int cost = best.getOrDefault(at, Integer.MAX_VALUE);

			for (int d = 0; d < 8; d++)
			{
				int nx = cx + DX[d];
				int ny = cy + DY[d];
				if (!mesh.isOcean(nx, ny, 0) || !canStep(cx, cy, DX[d], DY[d]))
				{
					continue;
				}

				long next = pack(nx, ny);
				int tentative = cost + 1;
				if (tentative >= best.getOrDefault(next, Integer.MAX_VALUE))
				{
					continue;
				}
				best.put(next, tentative);
				cameFrom.put(next, at);
				open.add(new long[]{next,
					tentative + heuristic(nx, ny, to.getX(), to.getY())});
			}
		}

		if (!found)
		{
			log.debug("No sea route from {} to {} after {} nodes", from, to, expanded);
			routes.put(key, Collections.emptyList());
			return null;
		}

		List<int[]> path = new ArrayList<>();
		for (long at = goal; at != start; at = cameFrom.get(at))
		{
			path.add(0, new int[]{unpackX(at), unpackY(at)});
		}
		path.add(0, new int[]{from.getX(), from.getY()});

		List<int[]> straightened = straighten(path);
		routes.put(key, straightened);
		return straightened;
	}

	/**
	 * Collapses runs of tiles into legs.
	 *
	 * <p>The tile path is only interesting where it turns. Keeping every tile would mean a
	 * raft interpolating between adjacent points for the whole crossing — the same motion,
	 * many times the storage, and an itinerary hundreds of waypoints long for something
	 * nobody is watching.
	 */
	private static List<int[]> straighten(List<int[]> path)
	{
		if (path.size() < 3)
		{
			return path;
		}
		List<int[]> out = new ArrayList<>();
		out.add(path.get(0));

		int dx = Integer.signum(path.get(1)[0] - path.get(0)[0]);
		int dy = Integer.signum(path.get(1)[1] - path.get(0)[1]);

		for (int i = 1; i < path.size(); i++)
		{
			int ndx = Integer.signum(path.get(i)[0] - path.get(i - 1)[0]);
			int ndy = Integer.signum(path.get(i)[1] - path.get(i - 1)[1]);
			if (ndx != dx || ndy != dy)
			{
				out.add(path.get(i - 1));
				dx = ndx;
				dy = ndy;
			}
		}
		out.add(path.get(path.size() - 1));
		return out;
	}

	/** Octile distance, weighted. Admissible before the weight, deliberately not after. */
	private static int heuristic(int x, int y, int goalX, int goalY)
	{
		int dx = Math.abs(x - goalX);
		int dy = Math.abs(y - goalY);
		return (int) (Math.max(dx, dy) * HEURISTIC_WEIGHT);
	}

	/** The game's corner rule, restricted to water. */
	private boolean canStep(int x, int y, int dx, int dy)
	{
		if (dx == 0 || dy == 0)
		{
			return true;
		}
		// A diagonal needs both ways round to be water, or the raft would cut a headland.
		return mesh.isOcean(x + dx, y, 0) && mesh.isOcean(x, y + dy, 0);
	}

	/** Endpoint pair key. Direction matters: a route is reversed, not reused. */
	private static long key(WorldPoint from, WorldPoint to)
	{
		return key(from.getX(), from.getY(), to.getX(), to.getY());
	}

	private static long key(int fromX, int fromY, int toX, int toY)
	{
		return ((long) fromX << 45) ^ ((long) fromY << 30) ^ ((long) toX << 15) ^ toY;
	}

	private static long pack(int x, int y)
	{
		return ((long) x << 32) | (y & 0xFFFFFFFFL);
	}

	private static int unpackX(long packed)
	{
		return (int) (packed >> 32);
	}

	private static int unpackY(long packed)
	{
		return (int) packed;
	}
}
