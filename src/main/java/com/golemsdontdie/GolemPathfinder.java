package com.golemsdontdie;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Random;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Picks somewhere for a golem to go, and works out how to walk there.
 *
 * <p>Everything is in world tiles, queried against {@link IslandMemory}, so a search works the
 * same whether the golem is on screen or three regions away: pathing cannot depend on the scene.
 * Paths are breadth-first over cardinal steps — distances are short, and BFS is exhaustive within
 * its budget, so a golem penned in by scenery finds out it is stuck rather than walking into it.
 */
@Singleton
class GolemPathfinder
{
	/** Ceiling on tiles per search. Reached when a golem is walled in, which recurs every plan. */
	private static final int NODE_BUDGET = 3000;

	/** Tries for a reachable destination before the golem gives up and idles. */
	private static final int DESTINATION_ATTEMPTS = 10;

	/**
	 * The eight steps a golem may take, cardinals first.
	 *
	 * <p>Uniform-cost BFS over eight neighbours is exact for this game: a diagonal step costs one
	 * game tick exactly as a cardinal does. Cardinals come first so that where two routes are the
	 * same length the straight one wins; whether a diagonal is legal is canStep's corner rule.
	 */
	private static final int[] DX = {1, -1, 0, 0, 1, -1, 1, -1};
	private static final int[] DY = {0, 0, 1, -1, 1, 1, -1, -1};

	@Inject
	private IslandMemory memory;

	/**
	 * A path to a random reachable destination inside {@code bounds}. Destinations are sampled and
	 * rejected rather than enumerated, which would bias the choice toward the densest part.
	 *
	 * @return world tiles to walk through, excluding the start; empty if nowhere goes
	 */
	Deque<int[]> wanderPath(int startX, int startY, int plane, RoamBounds bounds, Random random)
	{
		// Attempts are spent on searches, not on samples: a sample landing on a wall costs nothing to
		// reject, and counting it meant a golem in the cathedral, where most of the window is walls,
		// ran out of tries before it had searched once.
		int searched = 0;
		for (int sampled = 0; sampled < DESTINATION_ATTEMPTS * 8 && searched < DESTINATION_ATTEMPTS; sampled++)
		{
			int[] target = bounds.sample(random);
			if (target == null || (target[0] == startX && target[1] == startY))
			{
				continue;
			}
			if (!bounds.contains(target[0], target[1]) || !memory.isKnownWalkable(target[0], target[1], plane)
				|| bounds.avoids(target[0], target[1]))
			{
				continue;
			}
			searched++;

			Deque<int[]> path = findPath(startX, startY, plane, target[0], target[1], bounds);
			if (!path.isEmpty())
			{
				return path;
			}
		}
		return smallFloorPath(startX, startY, plane, bounds, random);
	}

	/** The most tiles a floor may have for its every tile to be worth listing when sampling misses. */
	private static final int SMALL_FLOOR = 200;

	/**
	 * A walk to somewhere on the floor the golem is standing on, chosen from the tiles it can
	 * actually reach. Sampling the window around a golem on a lookout of a dozen tiles mostly lands
	 * on walls, on other floors it cannot reach, and on tiles golems are standing on: every search
	 * failed, and after a dozen the watchdog carried the golem off to the plinth. Only when
	 * sampling has found nothing, so open ground keeps its unbiased choice.
	 */
	private Deque<int[]> smallFloorPath(int startX, int startY, int plane, RoamBounds bounds, Random random)
	{
		TileMap reach = flood(startX, startY, plane, SMALL_FLOOR);
		for (int tries = 0; tries < 8 && reach.size() > 1; tries++)
		{
			long pick = reach.keyAt(1 + random.nextInt(reach.size() - 1));
			int x = unpackX(pick);
			int y = unpackY(pick);
			if (bounds.contains(x, y) && !bounds.avoids(x, y))
			{
				return pathFrom(reach, startX, startY, x, y);
			}
		}
		return new ArrayDeque<>();
	}

	/**
	 * Breadth-first path between two world tiles, staying inside {@code bounds}.
	 *
	 * @return the tiles to walk through, excluding the start; empty if unreachable
	 */
	Deque<int[]> findPath(int startX, int startY, int plane, int goalX, int goalY, RoamBounds bounds)
	{
		Deque<int[]> result = new ArrayDeque<>();
		if (startX == goalX && startY == goalY)
		{
			return result;
		}

		// Keyed by packed world tile rather than indexed by scene position: in free roam there is no
		// fixed-size grid to index into. Every tile reached is queued exactly when it is recorded, so
		// the record's own order is the queue.
		TileMap cameFrom = new TileMap(1024);
		int head = 0;

		long start = pack(startX, startY);
		long goal = pack(goalX, goalY);
		cameFrom.add(start, start);

		int visited = 0;
		boolean found = false;

		while (head < cameFrom.size() && visited < NODE_BUDGET)
		{
			long current = cameFrom.keyAt(head++);
			visited++;

			if (current == goal)
			{
				found = true;
				break;
			}

			int cx = unpackX(current);
			int cy = unpackY(current);

			for (int d = 0; d < DX.length; d++)
			{
				int nx = cx + DX[d];
				int ny = cy + DY[d];
				long next = pack(nx, ny);

				if (cameFrom.containsKey(next)
					|| !bounds.contains(nx, ny)
					|| !memory.canStep(cx, cy, plane, DX[d], DY[d]))
				{
					continue;
				}

				cameFrom.add(next, current);
			}
		}

		if (!found)
		{
			return result;
		}

		// Walk the parent chain back, pushing onto the front to reverse it into start-to-goal order.
		for (long at = goal; at != start; at = cameFrom.get(at))
		{
			result.addFirst(new int[]{unpackX(at), unpackY(at)});
		}
		return result;
	}

	/**
	 * Every tile that can be walked to from a start, breadth first, up to a budget: each tile mapped
	 * to the one it was reached from, so {@link #pathFrom} reads back the walk without searching.
	 */
	TileMap flood(int startX, int startY, int plane, int budget)
	{
		// The record's order is the queue, as in findPath.
		TileMap cameFrom = new TileMap(budget + 8);
		int head = 0;
		long start = pack(startX, startY);
		cameFrom.add(start, start);

		while (head < cameFrom.size() && cameFrom.size() < budget)
		{
			long current = cameFrom.keyAt(head++);
			int cx = unpackX(current);
			int cy = unpackY(current);
			for (int d = 0; d < DX.length; d++)
			{
				int nx = cx + DX[d];
				int ny = cy + DY[d];
				long next = pack(nx, ny);
				if (cameFrom.containsKey(next)
					|| !memory.isKnownWalkable(nx, ny, plane)
					|| !memory.canStep(cx, cy, plane, DX[d], DY[d]))
				{
					continue;
				}
				cameFrom.add(next, current);
			}
		}
		return cameFrom;
	}

	/**
	 * The walk from a flood's start to a tile it reached, excluding the start.
	 *
	 * @return the tiles in order, empty if the goal is the start, null if it was not reached
	 */
	static Deque<int[]> pathFrom(TileMap cameFrom, int startX, int startY, int goalX, int goalY)
	{
		long start = pack(startX, startY);
		long goal = pack(goalX, goalY);
		if (!cameFrom.containsKey(goal))
		{
			return null;
		}
		Deque<int[]> result = new ArrayDeque<>();
		for (long at = goal; at != start; at = cameFrom.get(at))
		{
			result.addFirst(new int[]{unpackX(at), unpackY(at)});
		}
		return result;
	}

	/**
	 * How many tiles can be walked to from here, counting up to a limit. Stops there because only
	 * whether a space is small matters: an open field and a continent are the same to a golem.
	 */
	int areaSize(int x, int y, int plane, int limit)
	{
		TileMap seen = new TileMap(limit + 8);
		int head = 0;
		long start = pack(x, y);
		seen.add(start, 0);
		while (head < seen.size() && seen.size() < limit)
		{
			long current = seen.keyAt(head++);
			int cx = unpackX(current);
			int cy = unpackY(current);
			for (int d = 0; d < DX.length; d++)
			{
				long next = pack(cx + DX[d], cy + DY[d]);
				if (!seen.containsKey(next) && memory.canStep(cx, cy, plane, DX[d], DY[d]))
				{
					seen.add(next, 0);
				}
			}
		}
		return seen.size();
	}

	static long pack(int x, int y)
	{
		return ((long) x << 32) | (y & 0xFFFFFFFFL);
	}

	static int unpackX(long packed)
	{
		return (int) (packed >> 32);
	}

	static int unpackY(long packed)
	{
		return (int) packed;
	}
}
