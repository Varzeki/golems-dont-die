package com.golemsdontdie;

import java.lang.reflect.Proxy;
import java.util.Map;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/**
 * Checks InstanceMap's reading of instance chunks against RuneLite's own conversion.
 *
 * <p>The packing of a template chunk into an int is not documented anywhere but RuneLite's
 * source. Rather than trust a transcription of it, a fake instance is built and every tile
 * is converted both ways — by InstanceMap and by WorldPoint.fromLocalInstance — and the two
 * must agree.
 */
public class InstanceMapHarness
{
	private static int failures;

	public static void main(String[] args)
	{
		int baseX = 11520;
		int baseY = 6400;
		int[][][] chunks = new int[4][13][13];

		// A room of four unrotated chunks, copied from template chunks (318, 280) and
		// (319, 280), (318, 281), (319, 281) on plane 0 — near Wyrmscraig's cathedral —
		// placed at scene chunks (1..2, 3..4). And one rotated chunk, which InstanceMap
		// must decline rather than misplace.
		chunks[0][1][3] = pack(318, 280, 0, 0);
		chunks[0][2][3] = pack(319, 280, 0, 0);
		chunks[0][1][4] = pack(318, 281, 0, 0);
		chunks[0][2][4] = pack(319, 281, 0, 0);
		chunks[0][5][5] = pack(320, 282, 0, 1);

		WorldView wv = worldView(baseX, baseY, chunks);
		Scene scene = scene(chunks);

		int checked = 0;
		for (int sx = 0; sx < 104; sx++)
		{
			for (int sy = 0; sy < 104; sy++)
			{
				int data = chunks[0][sx / 8][sy / 8];
				if (data == 0)
				{
					continue;
				}
				WorldPoint ours = InstanceMap.templateOf(wv, new WorldPoint(baseX + sx, baseY + sy, 0));
				WorldPoint theirs = WorldPoint.fromLocalInstance(scene,
					new LocalPoint(sx * 128 + 64, sy * 128 + 64, -1), 0);
				if (InstanceMap.rotation(data) != 0)
				{
					check("rotated chunk declined at " + sx + "," + sy, ours == null);
				}
				else
				{
					check("tile " + sx + "," + sy + " -> " + theirs, theirs.equals(ours));
				}
				checked++;
			}
		}

		// And back: a template tile finds its place in the instance.
		Map<Long, int[]> placed = InstanceMap.sceneChunks(wv);
		WorldPoint template = new WorldPoint(319 * 8 + 3, 281 * 8 + 5, 0);
		int[] at = placed.get(InstanceMap.chunkKey(template.getX() >> 3, template.getY() >> 3, 0));
		check("template chunk found in instance", at != null && at[0] == 2 && at[1] == 4 && at[2] == 0);
		check("rotated chunk left out of the drawing map",
			!placed.containsKey(InstanceMap.chunkKey(320, 282, 0)));
		WorldPoint drawn = new WorldPoint(baseX + (at[0] << 3) + (template.getX() & 7),
			baseY + (at[1] << 3) + (template.getY() & 7), at[2]);
		check("round trip " + template + " -> " + drawn, template.equals(InstanceMap.templateOf(wv, drawn)));
		check("outside an instance a tile is itself",
			new WorldPoint(1, 2, 0).equals(InstanceMap.templateOf(worldView(0, 0, null), new WorldPoint(1, 2, 0))));

		System.out.println(checked + " tiles compared; " + (failures == 0 ? "ALL PASSED" : failures + " FAILED"));
	}

	/** RuneLite's layout: plane << 24 | chunkX << 14 | chunkY << 3 | rotation << 1. */
	private static int pack(int chunkX, int chunkY, int plane, int rotation)
	{
		return plane << 24 | chunkX << 14 | chunkY << 3 | rotation << 1;
	}

	private static WorldView worldView(int baseX, int baseY, int[][][] chunks)
	{
		return (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(),
			new Class<?>[]{WorldView.class}, (proxy, method, a) ->
			{
				switch (method.getName())
				{
					case "isInstance":
						return chunks != null;
					case "getBaseX":
						return baseX;
					case "getBaseY":
						return baseY;
					case "getInstanceTemplateChunks":
						return chunks;
					case "getSizeX":
					case "getSizeY":
						return 104;
					default:
						throw new UnsupportedOperationException(method.getName());
				}
			});
	}

	private static Scene scene(int[][][] chunks)
	{
		return (Scene) Proxy.newProxyInstance(Scene.class.getClassLoader(),
			new Class<?>[]{Scene.class}, (proxy, method, a) ->
			{
				switch (method.getName())
				{
					case "isInstance":
						return true;
					case "getInstanceTemplateChunks":
						return chunks;
					default:
						throw new UnsupportedOperationException(method.getName());
				}
			});
	}

	private static void check(String label, boolean ok)
	{
		if (!ok)
		{
			failures++;
			System.out.println("FAIL " + label);
		}
	}
}
