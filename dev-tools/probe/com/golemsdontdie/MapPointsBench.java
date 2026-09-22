package com.golemsdontdie;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.runelite.api.coords.WorldPoint;

/**
 * What it costs to sort a roster into world map faces, at the sizes a golem crafter reaches.
 *
 * <p>The drawing needs the client — the zoom, the window, the map's own points — but the sorting
 * does not, and the sorting is the part that runs once a tick for every golem there is. This calls
 * it directly with a full-map view, which is the worst case: every golem on screen and every one
 * of them counted into a cell.
 *
 * <pre>
 * java -cp "probe;classes;resources;slf4j;api" com.golemsdontdie.MapPointsBench [golems] [runs]
 * </pre>
 */
public class MapPointsBench
{
	/** Tiles to a cell at the zoom where the whole world fits on screen. See GolemMapPoints. */
	private static final int CELL_TILES = 15;

	public static void main(String[] args) throws Exception
	{
		int count = args.length > 0 ? Integer.parseInt(args[0]) : 10_000;
		int runs = args.length > 1 ? Integer.parseInt(args[1]) : 200;

		GolemSnapshot snapshot = new GolemSnapshot(1234, "Golem", new int[0], new short[0], new short[0],
			128, 128, 1, -1, -1, -1, new WorldPoint(2596, 2256, 0), 0);
		Random random = new Random(7);
		List<Golem> golems = new ArrayList<>(count);
		for (int i = 0; i < count; i++)
		{
			// Spread across the walkable world, which is what a roster looks like after a while.
			WorldPoint at = new WorldPoint(1200 + random.nextInt(2400), 2600 + random.nextInt(1600), 0);
			Golem golem = Golem.onTile(snapshot, at, random.nextLong(), at);
			if (i % 10 == 0)
			{
				golem.setNickname("Named " + i);
			}
			golems.add(golem);
		}

		GolemMapPoints points = new GolemMapPoints();
		Method gather = GolemMapPoints.class.getDeclaredMethod("gather",
			List.class, boolean.class, int.class, int.class, int.class, int.class, int.class);
		gather.setAccessible(true);

		for (boolean named : new boolean[]{false, true})
		{
			// Warm up, then time the pass the plugin makes every tick the map is open.
			for (int i = 0; i < 50; i++)
			{
				gather.invoke(points, golems, named, CELL_TILES, 1000, 4000, 2000, 4400);
			}
			long start = System.nanoTime();
			for (int i = 0; i < runs; i++)
			{
				gather.invoke(points, golems, named, CELL_TILES, 1000, 4000, 2000, 4400);
			}
			long each = (System.nanoTime() - start) / runs;
			System.out.printf("%,d golems, %s: %,d us a tick%n", count,
				named ? "named only" : "all of them", each / 1000);
		}
	}
}
