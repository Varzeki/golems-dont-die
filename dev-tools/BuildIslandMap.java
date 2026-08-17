import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Builds the island passability map the plugin ships, from the Shortest Path
 * collision map, and checks it against a recon log before trusting it.
 *
 * <p>Shortest Path publishes a whole-world collision map generated from the game
 * cache with the XTEA keys it has. That is the only route to a Wyrmscraig map that
 * does not require the player to walk the island first — the local cache's object
 * locations are encrypted and the available key file predates the content.
 *
 * <p>It is not taken on trust. A downloaded map could easily predate the content, in
 * which case its Wyrmscraig regions exist but describe empty ground, and golems would
 * be given the run of an island the map thinks is featureless. So it is cross-checked
 * against the real collision recorded in game, tile by tile, over the regions recon
 * covered completely. Agreement means the map is current; disagreement means it is
 * not, and says so rather than shipping it.
 *
 * <p>Both formats are the same two-bits-per-tile north/east scheme, so the comparison
 * is direct.
 *
 * <pre>
 * java BuildIslandMap &lt;collision-map.zip&gt; &lt;recon.log&gt; &lt;out.gz&gt;
 * </pre>
 */
public class BuildIslandMap
{
	private static final int REGION_SIZE = 64;
	private static final int BYTES_PER_PLANE = REGION_SIZE * REGION_SIZE * 2 / 8;

	/**
	 * The block of regions clipped out as "the island".
	 *
	 * <p>Wyrmscraig's world map label is at (2600, 2240), region 10275 = (40, 35).
	 * The surrounding regions with authored terrain run (39..41, 34..36), which is
	 * what the recon lap loaded. Clipping to that keeps the resource small; the whole
	 * world map is 1.2MB and all but a corner of it is somewhere golems will never be.
	 */
	private static final int MIN_RX = 39, MAX_RX = 41;
	private static final int MIN_RY = 34, MAX_RY = 36;

	private static final int PLINTH_X = 2596, PLINTH_Y = 2257;

	public static void main(String[] args) throws Exception
	{
		if (args.length < 3)
		{
			System.err.println("usage: BuildIslandMap <collision-map.zip> <recon.log> <out.gz>");
			return;
		}

		Map<Integer, byte[]> world = readShortestPath(new File(args[0]));
		System.out.println("shortest path map: " + world.size() + " regions");

		Map<Integer, byte[]> recon = readRecon(new File(args[1]));
		System.out.println("recon log:         " + recon.size() + " complete regions");
		System.out.println();

		boolean trustworthy = validate(world, recon);

		Map<Integer, byte[]> island = clip(world);
		System.out.println();
		System.out.println("island clip: regions " + MIN_RX + ".." + MAX_RX + " x " + MIN_RY + ".." + MAX_RY
			+ " = " + island.size() + " regions");
		render(island);

		if (!trustworthy)
		{
			System.out.println();
			System.out.println("NOT WRITING: the downloaded map disagrees with what was seen in game.");
			System.out.println("It most likely predates Wyrmscraig. Use the recon-harvested map instead.");
			return;
		}

		File out = new File(args[2]);
		write(island, out);
		System.out.println();
		System.out.println("wrote " + out.getAbsolutePath() + " (" + out.length() + " bytes)");
	}

	/**
	 * Compares the downloaded map against the regions recon mapped completely.
	 *
	 * @return true if they agree well enough to ship the downloaded map
	 */
	private static boolean validate(Map<Integer, byte[]> world, Map<Integer, byte[]> recon)
	{
		System.out.println("=== validation against live collision ===");
		System.out.println("region  tiles  agree   disagree  agreement");

		int totalAgree = 0, totalTiles = 0;
		boolean any = false;

		for (Map.Entry<Integer, byte[]> entry : recon.entrySet())
		{
			byte[] theirs = world.get(entry.getKey());
			if (theirs == null)
			{
				System.out.printf("%-7d (absent from downloaded map)%n", entry.getKey());
				continue;
			}
			any = true;

			byte[] mine = entry.getValue();
			int agree = 0;
			for (int rx = 0; rx < REGION_SIZE; rx++)
			{
				for (int ry = 0; ry < REGION_SIZE; ry++)
				{
					if (walkable(mine, rx, ry) == walkable(theirs, rx, ry))
					{
						agree++;
					}
				}
			}

			int tiles = REGION_SIZE * REGION_SIZE;
			totalAgree += agree;
			totalTiles += tiles;
			System.out.printf("%-7d %-6d %-7d %-9d %.1f%%%n",
				entry.getKey(), tiles, agree, tiles - agree, 100.0 * agree / tiles);
		}

		if (!any || totalTiles == 0)
		{
			System.out.println("no overlap to check - cannot validate");
			return false;
		}

		double overall = 100.0 * totalAgree / totalTiles;
		System.out.printf("overall agreement: %.1f%%%n", overall);

		// A map generated from the same cache should agree almost exactly; the slack
		// is for objects that vary with a player's game state, which the live scene
		// reflects and a static export cannot.
		boolean ok = overall >= 95.0;
		System.out.println(ok
			? "-> downloaded map matches the live island. Current."
			: "-> downloaded map does NOT match. Stale, or a different place.");
		return ok;
	}

	private static Map<Integer, byte[]> clip(Map<Integer, byte[]> world)
	{
		Map<Integer, byte[]> out = new TreeMap<>();
		for (int rx = MIN_RX; rx <= MAX_RX; rx++)
		{
			for (int ry = MIN_RY; ry <= MAX_RY; ry++)
			{
				int region = (rx << 8) | ry;
				byte[] data = world.get(region);
				if (data != null)
				{
					out.put(region, data);
				}
			}
		}
		return out;
	}

	private static void render(Map<Integer, byte[]> island)
	{
		int minX = MIN_RX << 6, maxX = ((MAX_RX + 1) << 6) - 1;
		int minY = MIN_RY << 6, maxY = ((MAX_RY + 1) << 6) - 1;

		System.out.println();
		System.out.println("('#' walkable, '.' blocked, ' ' no data, 'P' plinth)  3 tiles per character");
		for (int y = maxY; y >= minY; y -= 3)
		{
			StringBuilder row = new StringBuilder();
			for (int x = minX; x <= maxX; x += 3)
			{
				if (Math.abs(x - PLINTH_X) <= 2 && Math.abs(y - PLINTH_Y) <= 2)
				{
					row.append('P');
					continue;
				}
				byte[] data = island.get(((x >> 6) << 8) | (y >> 6));
				row.append(data == null ? ' ' : walkable(data, x & 63, y & 63) ? '#' : '.');
			}
			System.out.println(row);
		}
	}

	// ---- readers ----

	/** Shortest Path's zip: one entry per region, named {@code regionX_regionY}. */
	private static Map<Integer, byte[]> readShortestPath(File zip) throws IOException
	{
		Map<Integer, byte[]> out = new HashMap<>();
		try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip.toPath())))
		{
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null)
			{
				String[] parts = entry.getName().split("_");
				if (parts.length != 2)
				{
					continue;
				}
				int rx = Integer.parseInt(parts[0]);
				int ry = Integer.parseInt(parts[1]);

				// Only plane 0 is kept. Golem Crafting happens at ground level and
				// carrying three unused floors would triple the resource.
				byte[] all = readAll(in);
				byte[] plane0 = new byte[BYTES_PER_PLANE];
				System.arraycopy(BitSet.valueOf(all).toByteArray(), 0, plane0, 0,
					Math.min(BYTES_PER_PLANE, BitSet.valueOf(all).toByteArray().length));
				out.put((rx << 8) | ry, plane0);
			}
		}
		return out;
	}

	/** Recon log: only regions observed complete are usable as ground truth. */
	private static Map<Integer, byte[]> readRecon(File log) throws IOException
	{
		Map<Integer, byte[]> out = new TreeMap<>();
		for (String line : Files.readAllLines(log.toPath(), StandardCharsets.UTF_8))
		{
			if (!line.startsWith("COLLISION\t"))
			{
				continue;
			}
			Map<String, String> f = new HashMap<>();
			for (String part : line.split("\t"))
			{
				int eq = part.indexOf('=');
				if (eq > 0)
				{
					f.put(part.substring(0, eq), part.substring(eq + 1));
				}
			}
			if (!"true".equals(f.get("complete")) || !"0".equals(f.get("plane")))
			{
				continue;
			}
			out.put(Integer.parseInt(f.get("region")), unhex(f.get("bits")));
		}
		return out;
	}

	// ---- bits ----

	private static boolean walkable(byte[] data, int rx, int ry)
	{
		int bit = (ry * REGION_SIZE + rx) * 2;
		return get(data, bit) || get(data, bit + 1);
	}

	private static boolean get(byte[] data, int bit)
	{
		int index = bit >> 3;
		return index < data.length && (data[index] >> (bit & 7) & 1) != 0;
	}

	private static byte[] unhex(String hex)
	{
		byte[] out = new byte[hex.length() / 2];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
		}
		return out;
	}

	private static byte[] readAll(InputStream in) throws IOException
	{
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		byte[] block = new byte[4096];
		int n;
		while ((n = in.read(block)) != -1)
		{
			buf.write(block, 0, n);
		}
		return buf.toByteArray();
	}

	private static void write(Map<Integer, byte[]> island, File file) throws IOException
	{
		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(file))))
		{
			out.writeInt(island.size());
			for (Map.Entry<Integer, byte[]> entry : island.entrySet())
			{
				out.writeInt(entry.getKey());
				out.writeInt(0);
				out.write(entry.getValue());
			}
		}
	}
}
