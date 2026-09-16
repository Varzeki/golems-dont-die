package com.golemsdontdie;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Checks the island map's handling of partly harvested regions.
 *
 * <ul>
 *   <li>An old-format save keeps its island regions and drops the partly read ones.</li>
 *   <li>A region with a seen-tile mask answers from the mesh for tiles it never read, and
 *       from itself for tiles it did.</li>
 *   <li>Masks survive a save and load.</li>
 * </ul>
 *
 *   java com.golemsdontdie.IslandMapHarness [profile.properties]
 */
public class IslandMapHarness
{
	private static int failures;

	public static void main(String[] args) throws Exception
	{
		WorldMesh mesh = new WorldMesh();
		mesh.load();

		if (args.length > 0)
		{
			String saved = null;
			for (String line : Files.readAllLines(Paths.get(args[0])))
			{
				if (line.startsWith("golemsdontdie.islandMap="))
				{
					saved = line.substring(line.indexOf('=') + 1).replace("\\", "");
				}
			}
			IslandMemory old = memory(mesh);
			old.deserialise(saved);
			// An old save gets a guessed mask for every region; a new one carries masks for the
			// regions it only partly read. Either way no region may be left without a mask
			// that is not whole, which after the shipped map loads is checked below.
			int beforeBundled = seen(old).size();
			check("saved map has masks for partly read regions (" + regions(old).size() + " regions, "
				+ beforeBundled + " masks)", beforeBundled > 0 && beforeBundled <= regions(old).size());
			old.loadBundled();
			boolean islandWhole = true;
			for (Long key : seen(old).keySet())
			{
				islandWhole &= !isIsland((int) (key >> 8));
			}
			check("shipped map makes island regions whole (" + seen(old).size() + " partly read left)", islandWhole);
			// The basement landing, which only a harvest knows.
			check("basement landing still walkable", old.isKnownWalkable(2575, 8657, 0));
			// Sea the harvest and the shipped map both recorded as open.
			check("sea off the cathedral refused (saved region)", !old.isKnownWalkable(2537, 2171, 0));
			check("sea off the cathedral refused (island region)", !old.isKnownWalkable(2539, 2190, 0));
			check("plinth still walkable", old.isKnownWalkable(2596, 2256, 0));
			check("cathedral door still walkable", old.isKnownWalkable(2541, 2214, 0));
		}

		IslandMemory shipped = memory(mesh);
		shipped.loadBundled();
		check("sea refused on the shipped map alone", !shipped.isKnownWalkable(2539, 2190, 0)
			&& !shipped.isKnownWalkable(2539, 2180, 0));

		// A fresh memory with one partly read region in the cave, off the island.
		IslandMemory memory = memory(mesh);
		int caveX = 2590;
		int caveY = 8590;
		int regionId = ((caveX >> 6) << 8) | (caveY >> 6);
		long key = ((long) regionId << 8);
		long[] bits = new long[4096 * 2 / 64];
		long[] mask = new long[64];
		// Read one tile, (0,0) of the region, as open to the north and east.
		bits[0] |= 0b11;
		mask[0] |= 1;
		regions(memory).put(key, bits);
		seen(memory).put(key, mask);

		boolean meshSays = mesh.isLandWalkable(caveX, caveY, 0);
		boolean memorySays = memory.isKnownWalkable(caveX, caveY, 0);
		check("unread tile answered by the mesh (mesh " + meshSays + ", memory " + memorySays + ")",
			memorySays == (meshSays && memoryFallbackWalkable(mesh, caveX, caveY)));
		int baseX = (regionId >> 8) << 6;
		int baseY = (regionId & 0xFF) << 6;
		check("read tile answered by the region", memory.north(baseX, baseY, 0) && memory.east(baseX, baseY, 0));

		String saved = memory.serialise();
		IslandMemory reloaded = memory(mesh);
		reloaded.deserialise(saved);
		check("mask survives a save", seen(reloaded).containsKey(key) && regions(reloaded).containsKey(key));
		check("reloaded answers the same", reloaded.isKnownWalkable(caveX, caveY, 0) == memorySays);

		System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
	}

	/** What IslandMemory's own fallback to the mesh answers for a tile, edge by edge. */
	private static boolean memoryFallbackWalkable(WorldMesh mesh, int x, int y)
	{
		return mesh.isLandWalkable(x, y + 1, 0) && mesh.north(x, y, 0)
			|| mesh.isLandWalkable(x + 1, y, 0) && mesh.east(x, y, 0)
			|| mesh.isLandWalkable(x, y - 1, 0) && mesh.north(x, y - 1, 0)
			|| mesh.isLandWalkable(x - 1, y, 0) && mesh.east(x - 1, y, 0);
	}

	private static IslandMemory memory(WorldMesh mesh) throws Exception
	{
		IslandMemory memory = new IslandMemory();
		Field field = IslandMemory.class.getDeclaredField("worldMesh");
		field.setAccessible(true);
		field.set(memory, mesh);
		return memory;
	}

	@SuppressWarnings("unchecked")
	private static Map<Long, long[]> regions(IslandMemory memory) throws Exception
	{
		Field field = IslandMemory.class.getDeclaredField("regions");
		field.setAccessible(true);
		return (Map<Long, long[]>) field.get(memory);
	}

	@SuppressWarnings("unchecked")
	private static Map<Long, long[]> seen(IslandMemory memory) throws Exception
	{
		Field field = IslandMemory.class.getDeclaredField("seen");
		field.setAccessible(true);
		return (Map<Long, long[]>) field.get(memory);
	}

	private static boolean isIsland(int regionId)
	{
		for (int r : GolemContent.ISLAND_REGIONS)
		{
			if (r == regionId)
			{
				return true;
			}
		}
		return false;
	}

	private static void check(String label, boolean ok)
	{
		failures += ok ? 0 : 1;
		System.out.println((ok ? "ok   " : "FAIL ") + label);
	}
}
