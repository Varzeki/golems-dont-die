package com.golemsdontdie;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Picks somewhere for a golem to go, and works out how to walk there.
 *
 * <p>Everything is in world tiles, queried against {@link IslandMemory}, so a search
 * works the same whether the golem is on screen or three regions away. That is the
 * whole point of harvesting the map: pathing cannot depend on the scene, because
 * most golems will not be in it.
 *
 * <p>Paths are breadth-first over cardinal steps. A* would be the reflex, but the
 * distances are short — a golem picks a tile within its wander window, it does not
 * cross the island in one go — and BFS has the property that matters here: it is
 * exhaustive within its budget, so a golem penned in by scenery finds out it is
 * stuck rather than walking hopefully into a wall.
 */
@Singleton
class GolemPathfinder
{
	/**
	 * Ceiling on tiles visited per search. Reached only when a golem is walled in and
	 * the flood fill has to exhaust its pen before giving up — which is precisely the
	 * case worth bounding, because it recurs every time that golem picks a target.
	 */
	private static final int NODE_BUDGET = 3000;

	/** Tries for a reachable destination before the golem gives up and idles. */
	private static final int DESTINATION_ATTEMPTS = 10;

	private static final int[] DX = {1, -1, 0, 0};
	private static final int[] DY = {0, 0, 1, -1};

	@Inject
	private IslandMemory memory;

	/**
	 * A path to a random reachable destination inside {@code bounds}.
	 *
	 * <p>Destinations are sampled and rejected rather than enumerated. Enumerating
	 * every walkable tile would bias the choice toward whichever part of the area has
	 * the most of them; sampling keeps it even over the ground the player watches the
	 * golem cross.
	 *
	 * @return world tiles to walk through, excluding the start; empty if nowhere goes
	 */
	Deque<int[]> wanderPath(int startX, int startY, int plane, RoamBounds bounds, Random random)
	{
		for (int attempt = 0; attempt < DESTINATION_ATTEMPTS; attempt++)
		{
			int[] target = bounds.sample(random);
			if (target == null || (target[0] == startX && target[1] == startY))
			{
				continue;
			}
			if (!bounds.contains(target[0], target[1]) || !memory.isKnownWalkable(target[0], target[1], plane))
			{
				continue;
			}

			Deque<int[]> path = findPath(startX, startY, plane, target[0], target[1], bounds);
			if (!path.isEmpty())
			{
				return path;
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

		// Keyed by packed world tile rather than indexed by scene position: in free
		// roam the search is not confined to a scene, so there is no fixed-size grid
		// to index into.
		Map<Long, Long> cameFrom = new HashMap<>();
		Deque<Long> queue = new ArrayDeque<>();

		long start = pack(startX, startY);
		long goal = pack(goalX, goalY);
		cameFrom.put(start, start);
		queue.add(start);

		int visited = 0;
		boolean found = false;

		while (!queue.isEmpty() && visited < NODE_BUDGET)
		{
			long current = queue.poll();
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

				cameFrom.put(next, current);
				queue.add(next);
			}
		}

		if (!found)
		{
			return result;
		}

		// Walk the parent chain back, pushing each tile onto the front, which reverses
		// it into start-to-goal order.
		for (long at = goal; at != start; at = cameFrom.get(at))
		{
			result.addFirst(new int[]{unpackX(at), unpackY(at)});
		}
		return result;
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
