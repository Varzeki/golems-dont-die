import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Audits what the shipped data misses, and where.
 *
 * <p>Wyrmscraig was found by being told about it. That is not a method. This asks the two
 * questions that would have surfaced it without being told, and asks them of the whole
 * world:
 *
 * <ol>
 *   <li><b>Which walkable regions did the prune drop?</b> The mesh keeps the ocean plus the
 *       land reachable on foot from Lumbridge. An island reachable only by boat is not
 *       reachable on foot, so it survives only incidentally — because its coastal regions
 *       also contain ocean and whole regions are written. An island whose land sits in a
 *       region with no ocean tile at all would vanish silently.</li>
 *   <li><b>Which kept regions have walkable land but no transports?</b> That is the
 *       Wyrmscraig signature exactly: real ground, in the map, that no published table
 *       describes. Golems can walk there but never use anything.</li>
 * </ol>
 *
 * <pre>
 * javac -d out dev-tools/AuditCoverage.java
 * java -Xmx2g -cp out AuditCoverage &lt;collision-map.zip&gt; &lt;world-mesh.gz&gt; &lt;transports-dir&gt; [extra-dir]
 * </pre>
 */
public class AuditCoverage
{
	private static final int REGION_SIZE = 64;
	private static final int BYTES_PER_PLANE = REGION_SIZE * REGION_SIZE * 2 / 8;

	/** Below this many walkable tiles a region is scenery backdrop, not somewhere to be. */
	private static final int INTERESTING_TILES = 200;

	private static final Map<Integer, byte[][]> collision = new HashMap<>();

	public static void main(String[] args) throws IOException
	{
		if (args.length < 3)
		{
			System.err.println("usage: AuditCoverage <collision-map.zip> <world-mesh.gz>"
				+ " <transports-dir> [extra-dir]");
			System.exit(1);
		}

		readCollisionMap(new File(args[0]));
		Set<Integer> meshed = readMeshRegions(new File(args[1]));

		Set<Integer> transportRegions = new HashSet<>();
		Map<Integer, Integer> transportCounts = new HashMap<>();
		readTransports(new File(args[2]), transportRegions, transportCounts);
		if (args.length > 3)
		{
			readTransports(new File(args[3]), transportRegions, transportCounts);
		}

		System.out.println("collision map : " + collision.size() + " regions");
		System.out.println("shipped mesh  : " + meshed.size() + " regions");
		System.out.println("transports in : " + transportRegions.size() + " regions");
		System.out.println();

		droppedReachable(meshed, transportRegions, transportCounts);
		droppedRegions(meshed);
		transportGaps(meshed, transportRegions);
	}

	/**
	 * The sharp test: regions the prune dropped that a transport table says you can get to.
	 *
	 * <p>A dropped region is usually fine — most of the collision map is genuinely
	 * unreachable interior and instance templates. But if a published transport row has an
	 * endpoint inside one, then the game says a player can stand there, and the flood fill
	 * disagreeing means the fill is wrong rather than the region being unreachable.
	 *
	 * <p>Anything listed here is a hole a golem could be put into and not walk out of.
	 */
	private static void droppedReachable(Set<Integer> meshed, Set<Integer> transportRegions,
		Map<Integer, Integer> transportCounts)
	{
		System.out.println("=== DROPPED regions that transports reach ===");
		System.out.println("  (the tables say you can get here; the mesh says it does not exist)");
		System.out.println();

		Map<Integer, Integer> holes = new TreeMap<>();
		for (int regionId : transportRegions)
		{
			if (meshed.contains(regionId))
			{
				continue;
			}
			Integer key = keyOf(regionId);
			if (key == null)
			{
				continue;
			}
			holes.put(regionId, transportCounts.getOrDefault(regionId, 0));
		}

		List<Map.Entry<Integer, Integer>> byCount = new ArrayList<>(holes.entrySet());
		byCount.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

		System.out.println("  " + holes.size() + " of " + transportRegions.size()
			+ " transport-bearing regions are missing from the mesh");
		System.out.println();
		System.out.println("  region  rx  ry   world x,y        endpoints  walkable");
		int shown = 0;
		for (Map.Entry<Integer, Integer> e : byCount)
		{
			if (shown++ >= 30)
			{
				System.out.println("  ... and " + (byCount.size() - 30) + " more");
				break;
			}
			int regionId = e.getKey();
			int rx = regionId >> 8;
			int ry = regionId & 0xFF;
			System.out.println("  " + pad(regionId, 8) + pad(rx, 4) + pad(ry, 5)
				+ pad(rx * REGION_SIZE + "," + ry * REGION_SIZE, 17)
				+ pad(e.getValue(), 11) + walkableTiles(keyOf(regionId)));
		}
		System.out.println();
	}

	/** Regions with real walkable ground that the prune left out. */
	private static void droppedRegions(Set<Integer> meshed)
	{
		System.out.println("=== Walkable regions NOT in the shipped mesh ===");
		System.out.println("  (ground a golem could stand on that was pruned away)");
		System.out.println();

		Map<Integer, Integer> dropped = new TreeMap<>();
		for (int key : collision.keySet())
		{
			int regionId = regionId(key);
			if (meshed.contains(regionId))
			{
				continue;
			}
			int tiles = walkableTiles(key);
			if (tiles >= INTERESTING_TILES)
			{
				dropped.put(regionId, tiles);
			}
		}

		List<Map.Entry<Integer, Integer>> byTiles = new ArrayList<>(dropped.entrySet());
		byTiles.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

		System.out.println("  " + dropped.size() + " regions with " + INTERESTING_TILES
			+ "+ walkable tiles were dropped");
		System.out.println();
		System.out.println("  region  rx  ry   world x,y        walkable");
		int shown = 0;
		for (Map.Entry<Integer, Integer> e : byTiles)
		{
			if (shown++ >= 40)
			{
				System.out.println("  ... and " + (byTiles.size() - 40) + " more");
				break;
			}
			int regionId = e.getKey();
			int rx = regionId >> 8;
			int ry = regionId & 0xFF;
			System.out.println("  " + pad(regionId, 8) + pad(rx, 4) + pad(ry, 5)
				+ pad(rx * REGION_SIZE + "," + ry * REGION_SIZE, 17) + e.getValue());
		}
		System.out.println();
	}

	/** Regions in the mesh with walkable ground but nothing to interact with. */
	private static void transportGaps(Set<Integer> meshed, Set<Integer> transportRegions)
	{
		System.out.println("=== Kept regions with walkable ground but NO transports ===");
		System.out.println("  (the Wyrmscraig signature: real ground no table describes)");
		System.out.println();

		Map<Integer, Integer> gaps = new TreeMap<>();
		for (int regionId : meshed)
		{
			if (transportRegions.contains(regionId))
			{
				continue;
			}
			Integer key = keyOf(regionId);
			if (key == null)
			{
				continue;
			}
			int tiles = walkableTiles(key);
			if (tiles >= INTERESTING_TILES)
			{
				gaps.put(regionId, tiles);
			}
		}

		List<Map.Entry<Integer, Integer>> byTiles = new ArrayList<>(gaps.entrySet());
		byTiles.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

		System.out.println("  " + gaps.size() + " of " + meshed.size()
			+ " kept regions have " + INTERESTING_TILES + "+ walkable tiles and no transport");
		System.out.println();
		System.out.println("  region  rx  ry   world x,y        walkable");
		int shown = 0;
		for (Map.Entry<Integer, Integer> e : byTiles)
		{
			if (shown++ >= 40)
			{
				System.out.println("  ... and " + (byTiles.size() - 40) + " more");
				break;
			}
			int regionId = e.getKey();
			int rx = regionId >> 8;
			int ry = regionId & 0xFF;
			System.out.println("  " + pad(regionId, 8) + pad(rx, 4) + pad(ry, 5)
				+ pad(rx * REGION_SIZE + "," + ry * REGION_SIZE, 17) + e.getValue());
		}
		System.out.println();
	}

	// ----------------------------------------------------------------- reading

	private static void readCollisionMap(File zip) throws IOException
	{
		try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip)))
		{
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null)
			{
				String[] parts = entry.getName().split("_");
				if (parts.length != 2)
				{
					continue;
				}
				byte[] raw = readAll(in);
				int planes = Math.max(1, raw.length / BYTES_PER_PLANE);
				byte[][] byPlane = new byte[4][];
				for (int p = 0; p < Math.min(4, planes); p++)
				{
					byPlane[p] = Arrays.copyOfRange(raw, p * BYTES_PER_PLANE,
						Math.min(raw.length, (p + 1) * BYTES_PER_PLANE));
					if (byPlane[p].length < BYTES_PER_PLANE)
					{
						byPlane[p] = Arrays.copyOf(byPlane[p], BYTES_PER_PLANE);
					}
				}
				collision.put((Integer.parseInt(parts[0]) << 16) | Integer.parseInt(parts[1]),
					byPlane);
			}
		}
	}

	private static Set<Integer> readMeshRegions(File mesh) throws IOException
	{
		Set<Integer> out = new HashSet<>();
		try (DataInputStream d = new DataInputStream(
			new GZIPInputStream(new FileInputStream(mesh))))
		{
			int entries = d.readInt();
			for (int i = 0; i < entries; i++)
			{
				out.add(d.readInt());
				d.readByte();
				d.skipBytes(BYTES_PER_PLANE + 512 + 512);
			}
		}
		return out;
	}

	private static void readTransports(File dir, Set<Integer> regions,
		Map<Integer, Integer> counts) throws IOException
	{
		File[] files = dir.listFiles((d, n) -> n.endsWith(".tsv"));
		if (files == null)
		{
			return;
		}
		for (File f : files)
		{
			try (BufferedReader r = new BufferedReader(new FileReader(f)))
			{
				String[] header = null;
				String line;
				while ((line = r.readLine()) != null)
				{
					if (line.trim().isEmpty())
					{
						continue;
					}
					if (line.startsWith("#"))
					{
						if (header == null)
						{
							header = line.substring(1).trim().split("\t");
						}
						continue;
					}
					if (header == null)
					{
						header = line.split("\t");
						continue;
					}

					String[] fields = line.split("\t", -1);
					for (String column : new String[]{"Origin", "Destination"})
					{
						int[] at = coord(col(header, fields, column));
						if (at == null)
						{
							continue;
						}
						int regionId = ((at[0] >> 6) << 8) | (at[1] >> 6);
						regions.add(regionId);
						counts.merge(regionId, 1, Integer::sum);
					}
				}
			}
		}
	}

	// ---------------------------------------------------------------- geometry

	/** Walkable tiles across every plane of a region. */
	private static int walkableTiles(int key)
	{
		byte[][] region = collision.get(key);
		if (region == null)
		{
			return 0;
		}
		int baseX = (key >> 16) * REGION_SIZE;
		int baseY = (key & 0xFFFF) * REGION_SIZE;

		int count = 0;
		for (int p = 0; p < 4; p++)
		{
			if (region[p] == null)
			{
				continue;
			}
			for (int dy = 0; dy < REGION_SIZE; dy++)
			{
				for (int dx = 0; dx < REGION_SIZE; dx++)
				{
					if (walkable(baseX + dx, baseY + dy, p))
					{
						count++;
					}
				}
			}
		}
		return count;
	}

	private static boolean walkable(int x, int y, int z)
	{
		return flag(x, y, z, 0) || flag(x, y, z, 1)
			|| flag(x, y - 1, z, 0) || flag(x - 1, y, z, 1);
	}

	private static boolean flag(int x, int y, int z, int which)
	{
		byte[][] region = collision.get(((x / REGION_SIZE) << 16) | (y / REGION_SIZE));
		if (region == null || z < 0 || z > 3 || region[z] == null)
		{
			return false;
		}
		int bit = ((y & (REGION_SIZE - 1)) * REGION_SIZE
			+ (x & (REGION_SIZE - 1))) * 2 + which;
		return (region[z][bit >> 3] >>> (bit & 7) & 1) != 0;
	}

	private static int regionId(int key)
	{
		return ((key >> 16) << 8) | (key & 0xFFFF);
	}

	private static Integer keyOf(int regionId)
	{
		int key = ((regionId >> 8) << 16) | (regionId & 0xFF);
		return collision.containsKey(key) ? key : null;
	}

	// ----------------------------------------------------------------- helpers

	private static byte[] readAll(ZipInputStream in) throws IOException
	{
		byte[] buffer = new byte[8192];
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		int read;
		while ((read = in.read(buffer)) > 0)
		{
			out.write(buffer, 0, read);
		}
		return out.toByteArray();
	}

	private static String col(String[] header, String[] fields, String name)
	{
		for (int i = 0; i < header.length; i++)
		{
			if (header[i].trim().equalsIgnoreCase(name) && i < fields.length)
			{
				return fields[i].trim();
			}
		}
		return "";
	}

	private static int[] coord(String s)
	{
		String[] parts = s.trim().split("\\s+");
		if (parts.length != 3)
		{
			return null;
		}
		try
		{
			return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
				Integer.parseInt(parts[2])};
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	private static String pad(Object value, int width)
	{
		StringBuilder sb = new StringBuilder(String.valueOf(value));
		while (sb.length() < width)
		{
			sb.append(' ');
		}
		return sb.toString();
	}
}
