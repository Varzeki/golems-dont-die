package com.golemsdontdie;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import net.runelite.api.coords.WorldPoint;

/**
 * Computes every sea crossing between docks, offline, and writes them for {@link SeaMesh}.
 *
 * <p>The moorings are the plugin's own: the shipped buoys snapped onto the open sea by
 * {@code SailingDocks}, over the shipped mesh, exactly as the client will do it — so the
 * endpoints here are the endpoints the client looks up.
 *
 * <p>One breadth-first flood per mooring over a bitmap of the ocean reaches every other
 * mooring by a shortest route, where a search per pair ran out of budget on half of them.
 * The same corner rule as the live search: a diagonal needs both of its sides to be sea.
 * Each route is then straightened: consecutive tiles are replaced by the longest straight
 * leg whose every sampled tile is still sea, since a boat follows its legs by interpolation.
 *
 * <pre>
 * java com.golemsdontdie.BuildSeaRoutes out.gz
 * </pre>
 */
public class BuildSeaRoutes
{
	private static final int W = 4096;
	private static final int H = 4480;
	private static final int[] DX = {0, 0, 1, -1, 1, -1, 1, -1};
	private static final int[] DY = {1, -1, 0, 0, 1, 1, -1, -1};

	private static boolean[] ocean;

	public static void main(String[] args) throws Exception
	{
		long started = System.currentTimeMillis();
		WorldMesh mesh = new WorldMesh();
		mesh.load();

		SailingDocks docks = new SailingDocks();
		set(docks, "mesh", mesh);
		Method loadBuoys = SailingDocks.class.getDeclaredMethod("loadBuoys");
		loadBuoys.setAccessible(true);
		loadBuoys.invoke(docks);
		@SuppressWarnings("unchecked")
		List<WorldPoint> buoys = (List<WorldPoint>) get(docks, "buoys");
		Method snapToWater = SailingDocks.class.getDeclaredMethod("snapToWater", WorldPoint.class);
		snapToWater.setAccessible(true);

		Set<Long> seen = new LinkedHashSet<>();
		List<int[]> moorings = new ArrayList<>();
		for (WorldPoint buoy : buoys)
		{
			WorldPoint water = (WorldPoint) snapToWater.invoke(docks, buoy);
			if (water != null && seen.add(((long) water.getX() << 32) | water.getY()))
			{
				moorings.add(new int[]{water.getX(), water.getY()});
			}
		}
		System.out.println("moorings on the open sea: " + moorings.size());

		ocean = new boolean[W * H];
		int oceanTiles = 0;
		for (int y = 0; y < H; y++)
		{
			for (int x = 0; x < W; x++)
			{
				if (mesh.isOcean(x, y, 0))
				{
					ocean[y * W + x] = true;
					oceanTiles++;
				}
			}
		}
		System.out.println("ocean tiles: " + oceanTiles);

		byte[] parent = new byte[W * H];
		int[] queue = new int[W * H];
		List<List<int[]>> routes = new ArrayList<>();
		int unreachable = 0;
		int maxLegs = 0;
		long tiles = 0;

		for (int i = 0; i < moorings.size(); i++)
		{
			int[] from = moorings.get(i);
			Arrays.fill(parent, (byte) -1);
			int start = from[1] * W + from[0];
			parent[start] = 8;
			int head = 0;
			int tail = 0;
			queue[tail++] = start;
			int wanted = moorings.size() - 1 - i;
			Set<Integer> targets = new java.util.HashSet<>();
			for (int j = i + 1; j < moorings.size(); j++)
			{
				targets.add(moorings.get(j)[1] * W + moorings.get(j)[0]);
			}
			while (head < tail && wanted > 0)
			{
				int at = queue[head++];
				if (targets.contains(at))
				{
					wanted--;
				}
				int cx = at % W;
				int cy = at / W;
				for (int d = 0; d < 8; d++)
				{
					int nx = cx + DX[d];
					int ny = cy + DY[d];
					if (nx < 0 || ny < 0 || nx >= W || ny >= H)
					{
						continue;
					}
					int next = ny * W + nx;
					if (parent[next] != -1 || !ocean[next])
					{
						continue;
					}
					if (DX[d] != 0 && DY[d] != 0 && (!ocean[cy * W + nx] || !ocean[ny * W + cx]))
					{
						continue;
					}
					parent[next] = (byte) d;
					queue[tail++] = next;
				}
			}

			for (int j = i + 1; j < moorings.size(); j++)
			{
				int[] to = moorings.get(j);
				int goal = to[1] * W + to[0];
				if (parent[goal] == -1)
				{
					unreachable++;
					continue;
				}
				List<int[]> path = new ArrayList<>();
				for (int at = goal; ; )
				{
					path.add(new int[]{at % W, at / W});
					int d = parent[at];
					if (d == 8)
					{
						break;
					}
					at = (at / W - DY[d]) * W + (at % W - DX[d]);
				}
				java.util.Collections.reverse(path);
				tiles += path.size();
				List<int[]> legs = straighten(path);
				maxLegs = Math.max(maxLegs, legs.size());
				routes.add(legs);
			}
		}

		File out = new File(args.length > 0 ? args[0] : "sea-routes.gz");
		try (DataOutputStream data = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(out))))
		{
			data.writeInt(routes.size());
			for (List<int[]> route : routes)
			{
				data.writeShort(route.size());
				for (int[] p : route)
				{
					data.writeShort(p[0]);
					data.writeShort(p[1]);
				}
			}
		}
		System.out.printf("routes: %d (both directions %d), unreachable pairs: %d, tiles crossed: %d, most legs: %d%n",
			routes.size(), routes.size() * 2, unreachable, tiles, maxLegs);
		System.out.printf("wrote %s: %d bytes in %d s%n", out, out.length(), (System.currentTimeMillis() - started) / 1000);
	}

	/** The fewest legs that follow the path and never leave the sea. */
	private static List<int[]> straighten(List<int[]> path)
	{
		List<int[]> out = new ArrayList<>();
		int a = 0;
		out.add(path.get(0));
		while (a < path.size() - 1)
		{
			// Gallop out while the leg stays at sea, then close in on the furthest that does.
			int good = a + 1;
			int step = 1;
			while (good + step < path.size() && clear(path.get(a), path.get(good + step)))
			{
				good += step;
				step *= 2;
			}
			int low = good;
			int high = Math.min(path.size() - 1, good + step);
			while (low < high)
			{
				int mid = (low + high + 1) >>> 1;
				if (clear(path.get(a), path.get(mid)))
				{
					low = mid;
				}
				else
				{
					high = mid - 1;
				}
			}
			a = low;
			out.add(path.get(a));
		}
		return out;
	}

	/** Every tile a straight leg passes over, sampled four times a tile, is sea. */
	private static boolean clear(int[] from, int[] to)
	{
		int dx = to[0] - from[0];
		int dy = to[1] - from[1];
		int samples = Math.max(1, Math.max(Math.abs(dx), Math.abs(dy)) * 4);
		for (int s = 0; s <= samples; s++)
		{
			int x = (int) Math.floor(from[0] + 0.5 + dx * (double) s / samples);
			int y = (int) Math.floor(from[1] + 0.5 + dy * (double) s / samples);
			if (x < 0 || y < 0 || x >= W || y >= H || !ocean[y * W + x])
			{
				return false;
			}
		}
		return true;
	}

	private static void set(Object target, String name, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static Object get(Object target, String name) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}
}
