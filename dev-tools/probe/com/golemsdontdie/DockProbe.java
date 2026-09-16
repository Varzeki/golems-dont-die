package com.golemsdontdie;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/**
 * The shipped docking buoys, judged against the world mesh, without a client.
 *
 * <p>Everything about sailing that does not need the game: where the sixty-one buoys are,
 * whether each snaps to the open sea, whether there is land beside it for a golem to board
 * from, and whether the ocean mesh can actually route between them. What this cannot see is
 * the dock table — the levels and quests that decide which ports are open — because that is
 * game data read at login.
 *
 *   java com.golemsdontdie.DockProbe [pairs]
 */
public class DockProbe
{
	public static void main(String[] args) throws Exception
	{
		int pairs = args.length > 0 ? Integer.parseInt(args[0]) : 12;

		WorldMesh mesh = new WorldMesh();
		mesh.load();

		SailingDocks docks = new SailingDocks();
		set(docks, "mesh", mesh);
		Method loadBuoys = SailingDocks.class.getDeclaredMethod("loadBuoys");
		loadBuoys.setAccessible(true);
		loadBuoys.invoke(docks);

		@SuppressWarnings("unchecked")
		List<WorldPoint> buoys = (List<WorldPoint>) get(docks, "buoys");
		System.out.println("docking buoys shipped: " + buoys.size());

		Method snapWater = SailingDocks.class.getDeclaredMethod("snapToWater", WorldPoint.class);
		Method snapShore = SailingDocks.class.getDeclaredMethod("snapToShore", WorldPoint.class);
		snapWater.setAccessible(true);
		snapShore.setAccessible(true);

		List<WorldPoint> moorings = new ArrayList<>();
		int onSea = 0;
		int ashore = 0;
		StringBuilder offSea = new StringBuilder();
		for (int i = 0; i < buoys.size(); i++)
		{
			WorldPoint buoy = buoys.get(i);
			WorldPoint water = (WorldPoint) snapWater.invoke(docks, buoy);
			WorldPoint shore = (WorldPoint) snapShore.invoke(docks, buoy);
			// As SailingDocks.load does: the floor under a quayside is land from then on.
			mesh.admitDockFloor(shore.getX(), shore.getY(), shore.getPlane());
			moorings.add(water);
			if (water != null)
			{
				onSea++;
			}
			else
			{
				offSea.append(' ').append(i).append('@').append(buoy.getX()).append(',').append(buoy.getY())
					.append(',').append(buoy.getPlane());
			}
			boolean land = shore != null && mesh.isLandWalkable(shore.getX(), shore.getY(), shore.getPlane());
			if (land)
			{
				ashore++;
			}
			if (i < 4 || !land || water == null)
			{
				System.out.printf("  dock %2d buoy %s -> mooring %s, quayside %s%s%n", i, show(buoy), show(water),
					show(shore), land ? "" : "  (NO LAND TO BOARD FROM)");
			}
		}
		System.out.println("on the open sea: " + onSea + " of " + buoys.size()
			+ (offSea.length() == 0 ? "" : "; off it:" + offSea));
		System.out.println("with land to board from: " + ashore + " of " + buoys.size());

		// Can the ocean mesh actually get between ports? The first crossing of a pair is the
		// expensive one, and a pair with no route is cached as a failure for the session.
		SeaMesh sea = new SeaMesh();
		set(sea, "mesh", mesh);
		int routed = 0;
		int tried = 0;
		long slowest = 0;
		for (int i = 0; i < moorings.size() && tried < pairs; i++)
		{
			for (int j = i + 1; j < moorings.size() && tried < pairs; j += 7)
			{
				WorldPoint from = moorings.get(i);
				WorldPoint to = moorings.get(j);
				if (from == null || to == null)
				{
					continue;
				}
				tried++;
				long started = System.nanoTime();
				List<int[]> route = sea.route(from, to);
				long took = (System.nanoTime() - started) / 1_000_000;
				slowest = Math.max(slowest, took);
				routed += route == null ? 0 : 1;
				System.out.printf("  %s -> %s: %s in %d ms%n", show(from), show(to),
					route == null ? "NO ROUTE" : route.size() + " legs", took);
			}
		}
		System.out.println("crossings routed: " + routed + " of " + tried + ", slowest " + slowest + " ms");
	}

	private static String show(WorldPoint at)
	{
		return at == null ? "none" : at.getX() + "," + at.getY() + "," + at.getPlane();
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
