package com.golemsdontdie;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.coords.*;
import static com.golemsdontdie.RouteGeometry.span;

/**
 * Routes a raft across the ocean.
 *
 * <p>The collision map already marks open water as passable and shoreline edges as blocked,
 * which is why Wyrmscraig reads as a sealed 2,456-tile island <em>and</em> why 2,458,003 tiles
 * of connected sea sit in the same resource: golems path the ocean with the same flags and corner
 * rule they use in a corridor. Three differences, all because an ocean is enormous: A*, since
 * three thousand nodes does not cross one; an inflated heuristic, which expands far fewer nodes
 * and gives straighter routes; and straightening afterwards, sailing moving in long runs.
 */
@Slf4j
@Singleton
class SeaMesh
{
	/** Ceiling on tiles expanded for one crossing. Large: a crossing is planned once per pair. */
	private static final int NODE_BUDGET = 200_000;

	/** Heuristic inflation. Above 1 it is fast and straight, not optimal; a weaving boat reads badly. */
	private static final float HEURISTIC_WEIGHT = 1.4f;

	private static final int[] DX = {0, 0, 1, -1, 1, -1, 1, -1};
	private static final int[] DY = {1, -1, 0, 0, 1, 1, -1, -1};

	@Inject
	private WorldMesh mesh;

	/**
	 * True if two points sit on the same body of water. Always ask before pathing: the inland body
	 * on Karamja is 26,000 tiles connecting to nothing, and a search between basins burns the budget.
	 */
	boolean sameWater(WorldPoint a, WorldPoint b)
	{
		return mesh.isOcean(a.getX(), a.getY(), 0) && mesh.isOcean(b.getX(), b.getY(), 0);
	}

	/**
	 * Routes already computed, keyed by the pair of endpoints.
	 *
	 * <p>What makes the search affordable at all: there are only sixty-odd ports, so a real session
	 * sees a handful of crossings. Failures are cached too, as an empty list.
	 */
	private final Map<Long, List<int[]>> routes = new HashMap<>();

	/**
	 * Crossings between every pair of moorings, computed offline and shipped. The live search could
	 * not be
	 * relied on: sampling forty pairs of ports it found no route for twenty-one — the ocean being
	 * one body of water, every one was the budget running out, at a quarter to two fifths of a
	 * second each on the client thread. It remains for a pair the table does not have.
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
		try (InputStream raw = SeaMesh.class.getResourceAsStream(SHIPPED))
		{
			if (raw == null)
			{
				log.warn("Shipped sea routes missing from the jar; crossings will be searched live");
				return;
			}
			try (GZIPInputStream gz = new GZIPInputStream(raw);
				 DataInputStream data = new DataInputStream(gz))
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
		catch (IOException | RuntimeException e)
		{
			log.warn("Shipped sea routes unreadable; crossings will be searched live", e);
		}
	}

	/** True if this pair has already been searched, successfully or not. */
	boolean isSearched(WorldPoint from, WorldPoint to)
	{
		loadShipped();
		return routes.containsKey(key(from, to));
	}

	/**
	 * A route between two moorings, as straightened legs. Cached, so this is expensive exactly once
	 * per pair of ports; every call after the first is a map lookup.
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
	 * Collapses runs of tiles into legs. The tile path is only interesting where it turns; keeping
	 * every tile would mean the same motion at many times the storage.
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

	/**
	 * Distance to a destination by sea, for every tile of open water near a route to it. Travel
	 * distance, not straight-line: filled outward from the destination round every headland, so from
	 * any tile some neighbour is one step nearer. Only within {@code band} tiles of the route.
	 */
	/** Which tiles a raft may sail on: the ocean, or one enclosed body of water. */
	interface Water
	{
		boolean at(int x, int y);
	}

	/** The one connected sea. */
	final Water ocean = (x, y) -> mesh.isOcean(x, y, 0);

	/** Water that is not the sea, by component: a cave lake, passable and marked as nothing else. */
	Water lake(int component)
	{
		return (x, y) -> component != 0 && mesh.componentAt(x, y, 0) == component;
	}

	/** Which connected component a surface tile belongs to, 0 for none. */
	int componentAt(int x, int y)
	{
		return mesh.componentAt(x, y, 0);
	}

	@AllArgsConstructor
	static final class Field
	{
		private final TileMap distance;
		private final SeaMesh sea;
		private final Water water;

		/** True if a raft sailing this field may be on this tile. */
		boolean isWater(int x, int y)
		{
			return water.at(x, y);
		}

		/** Steps to the destination from this tile, or -1 if it is not in the field. */
		int distance(int x, int y)
		{
			return (int) distance.getOrDefault(pack(x, y), -1);
		}

		/** A neighbour one step nearer the destination, or null at the destination itself. */
		int[] downhill(int x, int y)
		{
			int here = distance(x, y);
			for (int d = 0; d < 8; d++)
			{
				int nx = x + DX[d];
				int ny = y + DY[d];
				int there = distance(nx, ny);
				if (there >= 0 && there < here && canStep(water, x, y, DX[d], DY[d]))
				{
					return new int[]{nx, ny};
				}
			}
			return null;
		}

		int size()
		{
			return distance.size();
		}
	}

	/** True if this tile of the surface is open sea. */
	boolean isWater(int x, int y)
	{
		return mesh.isOcean(x, y, 0);
	}

	/** The distance field to the last point of a route, over open water within {@code band} tiles of the route. */
	Field fieldAlong(List<int[]> route, int band)
	{
		return fieldAlong(route, band, 0, 0);
	}

	/**
	 * As {@link #fieldAlong(List, int)}, over water with room around it. A raft is three tiles long
	 * and a tile wide, and a course plotted for the golem at its helm ran it through gaps its bow
	 * could not fit; {@code openNearEnds} tiles of either end are exempt, for coming in to a dock.
	 */
	Field fieldAlong(List<int[]> route, int band, int clearance, int openNearEnds)
	{
		return fieldAlong(route, band, clearance, openNearEnds, ocean);
	}

	/** As {@link #fieldAlong(List, int, int, int)}, over a given body of water. */
	Field fieldAlong(List<int[]> route, int band, int clearance, int openNearEnds, Water water)
	{
		int[] start = route.get(0);
		int[] goal = route.get(route.size() - 1);
		// Breadth first, and every tile is queued exactly when its distance is recorded, so the
		// record's own order is the queue.
		TileMap distance = new TileMap(1 << 14);
		distance.add(pack(goal[0], goal[1]), 0);
		// Water refused for itself: too far from the route, or too near a shore. Both are questions
		// about the tile alone, and each of its neighbours offers it again, so the answer is kept.
		TileMap tooFar = new TileMap(1 << 12);
		int head = 0;
		while (head < distance.size())
		{
			long at = distance.keyAt(head++);
			int x = (int) (at >> 32);
			int y = (int) at;
			long steps = distance.get(at);
			for (int d = 0; d < 8; d++)
			{
				int nx = x + DX[d];
				int ny = y + DY[d];
				long key = pack(nx, ny);
				if (distance.containsKey(key) || !water.at(nx, ny) || !canStep(water, x, y, DX[d], DY[d])
					|| tooFar.containsKey(key))
				{
					continue;
				}
				if (!near(route, nx, ny, band) || !roomy(water, nx, ny, clearance, start, goal, openNearEnds))
				{
					tooFar.add(key, 0);
					continue;
				}
				distance.add(key, steps + 1);
			}
		}
		return new Field(distance, this, water);
	}

	/** True if a tile has open sea for {@code clearance} tiles all round, or is near enough an end not to need it. */
	private static boolean roomy(Water water, int x, int y, int clearance, int[] start, int[] goal, int openNearEnds)
	{
		if (clearance <= 0
			|| span(x - start[0], y - start[1]) <= openNearEnds
			|| span(x - goal[0], y - goal[1]) <= openNearEnds)
		{
			return true;
		}
		for (int dx = -clearance; dx <= clearance; dx++)
		{
			for (int dy = -clearance; dy <= clearance; dy++)
			{
				if (!water.at(x + dx, y + dy))
				{
					return false;
				}
			}
		}
		return true;
	}

	/** True if a tile is within {@code band} tiles of any leg of a route. */
	private static boolean near(List<int[]> route, int x, int y, int band)
	{
		for (int i = 0; i + 1 < route.size(); i++)
		{
			int[] a = route.get(i);
			int[] b = route.get(i + 1);
			double dx = b[0] - a[0];
			double dy = b[1] - a[1];
			double lengthSquared = dx * dx + dy * dy;
			double t = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, ((x - a[0]) * dx + (y - a[1]) * dy) / lengthSquared));
			if (Math.hypot(x - (a[0] + dx * t), y - (a[1] + dy * t)) <= band)
			{
				return true;
			}
		}
		return route.size() == 1 && span(x - route.get(0)[0], y - route.get(0)[1]) <= band;
	}

	/** The game's corner rule, restricted to water. */
	boolean canStep(int x, int y, int dx, int dy)
	{
		return canStep(ocean, x, y, dx, dy);
	}

	static boolean canStep(Water water, int x, int y, int dx, int dy)
	{
		if (dx == 0 || dy == 0)
		{
			return true;
		}
		// A diagonal needs both ways round to be water, or the raft would cut a headland.
		return water.at(x + dx, y) && water.at(x, y + dy);
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
