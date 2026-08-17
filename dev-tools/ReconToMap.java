import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

/**
 * Turns a recon log into the island map the plugin ships.
 *
 * <p>The collision for Wyrmscraig cannot be read from the game cache: object
 * locations are XTEA-encrypted and the available key file predates the content. The
 * live client has them decrypted the moment a region loads, so recon mode dumps the
 * real thing as the player walks. This folds those dumps into one file, keeping the
 * most complete observation of each region.
 *
 * <p>Also renders the result as text, because a map with a hole in it is obvious to
 * look at and invisible in a tile count.
 *
 * <pre>
 * java ReconToMap &lt;recon.log&gt; [out.gz]
 * </pre>
 */
public class ReconToMap
{
	private static final int REGION_SIZE = 64;
	private static final int BYTES_PER_REGION = REGION_SIZE * REGION_SIZE * 2 / 8;

	/** The plinth, from the golem spawn positions in the log. */
	private static final int PLINTH_X = 2596;
	private static final int PLINTH_Y = 2257;

	/** Best observation of each region-plane, keyed as region<<4|plane. */
	private static final Map<Integer, byte[]> bits = new TreeMap<>();
	private static final Map<Integer, Integer> seen = new HashMap<>();

	public static void main(String[] args) throws Exception
	{
		if (args.length < 1)
		{
			System.err.println("usage: ReconToMap <recon.log> [out.gz]");
			return;
		}

		List<String> lines = Files.readAllLines(new File(args[0]).toPath(), StandardCharsets.UTF_8);
		int collisionLines = 0;

		for (String line : lines)
		{
			if (!line.startsWith("COLLISION\t"))
			{
				continue;
			}
			collisionLines++;

			Map<String, String> f = fields(line);
			int region = Integer.parseInt(f.get("region"));
			int plane = Integer.parseInt(f.get("plane"));
			int tiles = Integer.parseInt(f.get("tilesSeen"));
			int key = region << 4 | plane;

			// Keep the most complete observation. A region is dumped every time the
			// player is near it and only fully covered when they stand well inside it,
			// so the last dump is not necessarily the best one.
			Integer bestSoFar = seen.get(key);
			if (bestSoFar != null && bestSoFar >= tiles)
			{
				continue;
			}
			seen.put(key, tiles);
			bits.put(key, unhex(f.get("bits")));
		}

		System.out.println("collision lines read: " + collisionLines);
		System.out.println("region-planes kept:   " + bits.size());
		System.out.println();

		report();
		render();

		File out = new File(args.length > 1 ? args[1] : "island-map.gz");
		write(out);
		System.out.println();
		System.out.println("wrote " + out.getAbsolutePath() + " (" + out.length() + " bytes)");
	}

	private static void report()
	{
		System.out.println("region  plane  tilesSeen  walkable  complete");
		int totalWalkable = 0;
		for (Map.Entry<Integer, byte[]> entry : bits.entrySet())
		{
			int region = entry.getKey() >> 4;
			int plane = entry.getKey() & 0xF;
			int tiles = seen.get(entry.getKey());
			int walkable = countWalkable(entry.getValue());
			totalWalkable += walkable;
			System.out.printf("%-7d %-6d %-10d %-9d %s%n",
				region, plane, tiles, walkable, tiles == REGION_SIZE * REGION_SIZE ? "yes" : "PARTIAL");
		}
		System.out.println();
		System.out.println("total walkable tiles: " + totalWalkable);
		System.out.println();
	}

	/**
	 * Draws the 2x2 region block around the crafting site, two world tiles per
	 * character so it fits a terminal.
	 */
	private static void render()
	{
		int minX = 2496, maxX = 2623, minY = 2176, maxY = 2303;

		System.out.println("Island around the plinth  ('#' walkable, '.' mapped but blocked, ' ' unmapped, 'P' plinth)");
		System.out.println("X " + minX + ".." + maxX + "   Y " + minY + ".." + maxY + "   (2 tiles per character)");

		for (int y = maxY; y >= minY; y -= 2)
		{
			StringBuilder row = new StringBuilder();
			for (int x = minX; x <= maxX; x += 2)
			{
				if (Math.abs(x - PLINTH_X) <= 1 && Math.abs(y - PLINTH_Y) <= 1)
				{
					row.append('P');
					continue;
				}

				int region = ((x >> 6) << 8) | (y >> 6);
				byte[] data = bits.get(region << 4);
				if (data == null)
				{
					row.append(' ');
					continue;
				}
				row.append(walkable(data, x & 63, y & 63) ? '#' : '.');
			}
			System.out.println(row);
		}
	}

	// ---- bit plumbing, matching IslandMemory's layout ----

	private static boolean walkable(byte[] data, int rx, int ry)
	{
		int bit = (ry * REGION_SIZE + rx) * 2;
		return get(data, bit) || get(data, bit + 1);
	}

	private static boolean get(byte[] data, int bit)
	{
		return (data[bit >> 3] >> (bit & 7) & 1) != 0;
	}

	private static int countWalkable(byte[] data)
	{
		int count = 0;
		for (int rx = 0; rx < REGION_SIZE; rx++)
		{
			for (int ry = 0; ry < REGION_SIZE; ry++)
			{
				if (walkable(data, rx, ry))
				{
					count++;
				}
			}
		}
		return count;
	}

	private static byte[] unhex(String hex)
	{
		byte[] out = new byte[hex.length() / 2];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
		}
		if (out.length != BYTES_PER_REGION)
		{
			throw new IllegalArgumentException("expected " + BYTES_PER_REGION + " bytes, got " + out.length);
		}
		return out;
	}

	/**
	 * Writes the bundled form: gzipped region-plane records. Passability maps are
	 * long runs of the same answer, so this compresses to a fraction of its size —
	 * the difference between a resource and an embarrassment.
	 */
	private static void write(File file) throws IOException
	{
		try (DataOutputStream out = new DataOutputStream(
			new GZIPOutputStream(new FileOutputStream(file))))
		{
			out.writeInt(bits.size());
			for (Map.Entry<Integer, byte[]> entry : bits.entrySet())
			{
				out.writeInt(entry.getKey() >> 4);
				out.writeInt(entry.getKey() & 0xF);
				out.write(entry.getValue());
			}
		}
	}

	private static Map<String, String> fields(String line)
	{
		Map<String, String> out = new HashMap<>();
		for (String part : line.split("\t"))
		{
			int eq = part.indexOf('=');
			if (eq > 0)
			{
				out.put(part.substring(0, eq), part.substring(eq + 1));
			}
		}
		return out;
	}
}
