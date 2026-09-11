import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Builds the world passability mesh the golems roam.
 *
 * <p>Generalises {@code BuildIslandMap}, which clipped Shortest Path's collision map to
 * the nine regions around the crafting site. The same source, the same 2-bit north/east
 * packing, and the same reader on the other side — but the whole reachable world, and
 * with two extra bits per tile that turn stuck detection from a guess into a lookup:
 *
 * <ul>
 *   <li><b>ocean</b> — this tile is part of the single connected sea. Sailing needs to
 *       know that two ports share water before it tries to path between them, because
 *       enclosed basins exist: the inland body on Karamja is 26,000 tiles of water that
 *       goes nowhere.</li>
 *   <li><b>isolated</b> — this tile sits in a component too small to wander. A golem that
 *       woke up on one would be trapped in a courtyard forever, and the cheapest way to
 *       never let that happen is to have already written down which tiles they are.</li>
 * </ul>
 *
 * <p>The mesh is pruned to the union of the ocean and the land actually reachable on
 * foot, because half the collision map is instance templates, unreachable interiors and
 * ground no golem can get to.
 *
 * <pre>
 * javac -d out dev-tools/BuildWorldMesh.java
 * java -Xmx2g -cp out BuildWorldMesh &lt;collision-map.zip&gt; &lt;transports-dir&gt; [out.gz]
 * </pre>
 */
public class BuildWorldMesh
{
	private static final int REGION_SIZE = 64;
	private static final int TILES_PER_PLANE = REGION_SIZE * REGION_SIZE;
	private static final int MAX_PLANES = 4;
	private static final int BYTES_PER_PLANE = TILES_PER_PLANE * 2 / 8;

	private static final int FLAG_NORTH = 0;
	private static final int FLAG_EAST = 1;

	/** A component with fewer tiles than this is somewhere a golem must never be left. */
	private static final int ISOLATED_BELOW = 50;

	/** Open water off Wyrmscraig's west shore — the seed for the ocean fill. */
	private static final int SEA_X = 2530, SEA_Y = 2240;

	/** Lumbridge. The seed for "land a golem could walk to", and as central as it gets. */
	private static final int LAND_X = 3222, LAND_Y = 3218;

	/*
	 * There is deliberately no y-band exclusion here any more.
	 *
	 * The first version dropped every region above y 4160 on the grounds that instance
	 * templates live up there. They do — and so does every dungeon, cave and underground
	 * area in the game, because OSRS places underground maps roughly 6,400 tiles north of
	 * the surface they sit beneath. The rule threw away all of it: about 1,400 regions,
	 * which is more ground than it kept.
	 *
	 * That was invisible in the numbers and would have been very visible in play. Ladders
	 * and staircases are the largest group in the transport network by a wide margin —
	 * 2,600 rows — and 2,419 rows change plane. With the underground pruned away, every
	 * one of those leads somewhere unmapped, the arrival guard refuses them all, and the
	 * single biggest piece of the transport work does nothing.
	 *
	 * Reachability already answers the question the band rule was trying to answer, and
	 * answers it correctly: the land fill walks out of Lumbridge using item-free
	 * transports, so it reaches dungeons through their ladders and never reaches an
	 * instance template at all, because you arrive in one by being placed there rather
	 * than by walking. What the fills touch is what ships.
	 */

	/** region key -> per-plane flag bytes, indexed [plane][byte]. */
	private static Map<Integer, byte[][]> regions = new HashMap<>();

	/** Region keys in a stable order, so a tile can be given a dense index. */
	private static int[] regionKeys;
	private static Map<Integer, Integer> regionIndex = new HashMap<>();

	public static void main(String[] args) throws IOException
	{
		if (args.length < 2)
		{
			System.err.println("usage: BuildWorldMesh <collision-map.zip> <transports-dir> [out.gz]");
			System.exit(1);
		}

		readCollisionMap(new File(args[0]));
		System.out.println("collision map: " + regions.size() + " regions");

		order();

		// Local additions are read alongside the upstream tables. Without them the mesh
		// cannot see Wyrmscraig's caves at all: the only way in is a cave mouth that no
		// published table describes, so nothing reaches the underground and it prunes away.
		List<int[]> transports = readTransports(new File(args[1]));
		if (args.length > 3)
		{
			transports.addAll(readTransports(new File(args[3])));
		}
		System.out.println("transports:    " + transports.size() + " usable rows with both ends");
		System.out.println();

		BitSet ocean = fill(SEA_X, SEA_Y, 0, null, "ocean");

		// Seeded from Lumbridge *and* from every transport endpoint.
		//
		// Walking out from one seed misses anywhere the tables reach but the walk does
		// not: Trollheim's Agility climbs were absent for exactly this reason, and they
		// are plainly real content — 64 transport endpoints in one region. A row with
		// both ends is the game asserting that a player can stand at each of them, which
		// is better evidence of reachable ground than one flood fill's opinion.
		//
		// It cannot pull in instance templates, because reaching one means being placed
		// there rather than travelling, and the tables that describe that (minigames,
		// seasonal, spells) carry no origin and are skipped for want of both ends. Houses
		// are the exception that does have both ends, and are excluded by name.
		List<int[]> seeds = new ArrayList<>();
		seeds.add(new int[]{LAND_X, LAND_Y, 0});
		for (int[] t : transports)
		{
			seeds.add(new int[]{t[0], t[1], t[2]});
			seeds.add(new int[]{t[3], t[4], t[5]});
		}
		// The sea is off limits to this fill.
		//
		// Without that, one seed on water sinks the whole idea: the fill is seeded from
		// every transport endpoint, a handful of those are boat stops out on the water,
		// and from any one of them the entire 2.4-million-tile ocean floods as "land".
		// The mask then says the sea is walkable, which is the opposite of what it exists
		// to say.
		BitSet land = fillAll(seeds, transports, ocean, "land");

		Set<Integer> keep = regionsTouched(ocean, land);
		System.out.println();
		System.out.println("union: " + keep.size() + " of " + regions.size() + " regions");

		BitSet isolated = labelIsolated(keep);

		if (args.length > 2)
		{
			write(new File(args[2]), keep, ocean, isolated, land);
		}
	}

	// ------------------------------------------------------------------ reading

	private static void readCollisionMap(File zip) throws IOException
	{
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

				byte[] raw = readAll(in);
				int planes = Math.min(MAX_PLANES, Math.max(1, raw.length / BYTES_PER_PLANE));
				byte[][] byPlane = new byte[MAX_PLANES][];
				for (int p = 0; p < planes; p++)
				{
					byPlane[p] = Arrays.copyOfRange(raw, p * BYTES_PER_PLANE,
						Math.min(raw.length, (p + 1) * BYTES_PER_PLANE));
					if (byPlane[p].length < BYTES_PER_PLANE)
					{
						byPlane[p] = Arrays.copyOf(byPlane[p], BYTES_PER_PLANE);
					}
				}
				regions.put(key(rx, ry), byPlane);
			}
		}
	}

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

	private static void order()
	{
		regionKeys = new int[regions.size()];
		int i = 0;
		for (int k : new TreeMap<>(regions).keySet())
		{
			regionKeys[i] = k;
			regionIndex.put(k, i);
			i++;
		}
	}

	/**
	 * Tables that lead somewhere a golem must never be.
	 *
	 * <p>A player-owned house is built from template chunks on demand. There is no
	 * persistent ground there to stand on, and a golem placed in one would be inside a
	 * house that does not exist until someone enters it. Excluded from the mesh and from
	 * the shipped transport table alike.
	 */
	private static final String[] SKIP_FILES = {"teleportation_portals_poh.tsv"};

	/** Usable transports with both ends, as {fromX, fromY, fromZ, toX, toY, toZ}. */
	private static List<int[]> readTransports(File dir) throws IOException
	{
		List<int[]> out = new ArrayList<>();
		File[] files = dir.listFiles((d, n) -> n.endsWith(".tsv"));
		if (files == null)
		{
			return out;
		}
		Arrays.sort(files);

		for (File f : files)
		{
			if (Arrays.asList(SKIP_FILES).contains(f.getName()))
			{
				continue;
			}
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
					if (!itemsAllowed(col(header, fields, "Items")))
					{
						continue;
					}
					int[] from = coord(col(header, fields, "Origin"));
					int[] to = coord(col(header, fields, "Destination"));
					if (from != null && to != null)
					{
						out.add(new int[]{from[0], from[1], from[2], to[0], to[1], to[2]});
					}
				}
			}
		}
		return out;
	}

	/** Item ids and named tokens a golem is granted. Must match {@code BuildTransports}. */
	private static final int COINS = 995;
	private static final int MAX_COINS = 500_000;
	private static final String[] GRANTED_NAMES = {"COINS", "DRAMEN_STAFF", "DRAMEN_BRANCH"};

	/**
	 * True if a golem may use a row given its item requirement.
	 *
	 * <p>This has to agree with {@code BuildTransports}, and originally did not: the mesh
	 * was built allowing only item-free rows while the shipped transport table — and the
	 * runtime — granted coins and a dramen staff. Anywhere reachable only through a fare
	 * was therefore absent from the map but present in the network, so golems were offered
	 * transports into ground the mesh said did not exist and the arrival guard refused
	 * them. Two filters that have to match are a standing hazard; they are at least now
	 * written the same way and say so.
	 */
	private static boolean itemsAllowed(String items)
	{
		String expr = items.replace(" ", "").toUpperCase().replace("&&", "&").replace("||", "|");
		if (expr.isEmpty())
		{
			return true;
		}
		for (String alternative : expr.split("\\|"))
		{
			boolean all = true;
			for (String term : alternative.split("&"))
			{
				if (!termGranted(term))
				{
					all = false;
					break;
				}
			}
			if (all)
			{
				return true;
			}
		}
		return false;
	}

	private static boolean termGranted(String term)
	{
		if (term.isEmpty())
		{
			return true;
		}
		int eq = term.indexOf('=');
		String name = eq < 0 ? term : term.substring(0, eq);
		int qty = 1;
		if (eq >= 0)
		{
			try
			{
				qty = Integer.parseInt(term.substring(eq + 1));
			}
			catch (NumberFormatException ignored)
			{
				qty = 1;
			}
		}

		for (String granted : GRANTED_NAMES)
		{
			if (granted.equals(name))
			{
				return true;
			}
		}
		try
		{
			return Integer.parseInt(name) == COINS;
		}
		catch (NumberFormatException e)
		{
			return false;
		}
	}

	// ------------------------------------------------------------------ filling

	/**
	 * Flood fills from a seed, optionally following transports.
	 *
	 * <p>Eight-way, using the game's own corner rule, so the component a tile lands in is
	 * the component a golem can actually walk around. Filling four-way would label
	 * diagonal-only passages as separate components and strand golems in them.
	 */
	/** Flood fills from many seeds at once, sharing one visited set. */
	private static BitSet fillAll(List<int[]> seeds, List<int[]> transports, BitSet barred,
		String what)
	{
		Map<Long, List<int[]>> byOrigin = new HashMap<>();
		for (int[] t : transports)
		{
			byOrigin.computeIfAbsent(tileKey(t[0], t[1], t[2]), k -> new ArrayList<>()).add(t);
		}

		BitSet seen = new BitSet();
		Deque<int[]> queue = new ArrayDeque<>();

		int planted = 0;
		for (int[] seed : seeds)
		{
			// A transport endpoint is often the object's own tile, and an object's tile is
			// usually blocked — you stand beside a ladder, not on it. Taking the endpoint
			// literally therefore skips the seed and loses everything behind it, which is
			// how a couple of dozen otherwise-reachable regions went missing. Snapping to
			// the nearest walkable tile is what the game does anyway.
			int[] at = snapSeed(seed);
			if (at == null)
			{
				continue;
			}
			int idx = index(at[0], at[1], at[2]);
			if (idx < 0 || seen.get(idx) || (barred != null && barred.get(idx)))
			{
				continue;
			}
			seen.set(idx);
			queue.add(at);
			planted++;
		}

		long count = expand(queue, seen, byOrigin, barred);
		System.out.println(what + ": " + count + " tiles from " + planted + " seeds");
		return seen;
	}

	/** The nearest walkable tile to a seed, within two tiles, or null. */
	private static int[] snapSeed(int[] seed)
	{
		if (walkable(seed[0], seed[1], seed[2]))
		{
			return seed;
		}
		for (int radius = 1; radius <= 2; radius++)
		{
			for (int dx = -radius; dx <= radius; dx++)
			{
				for (int dy = -radius; dy <= radius; dy++)
				{
					if (Math.abs(dx) != radius && Math.abs(dy) != radius)
					{
						continue;
					}
					if (walkable(seed[0] + dx, seed[1] + dy, seed[2]))
					{
						return new int[]{seed[0] + dx, seed[1] + dy, seed[2]};
					}
				}
			}
		}
		return null;
	}

	private static BitSet fill(int seedX, int seedY, int seedZ, List<int[]> transports, String what)
	{
		// Transports indexed by origin, so the fill can follow them without rescanning.
		Map<Long, List<int[]>> byOrigin = new HashMap<>();
		if (transports != null)
		{
			for (int[] t : transports)
			{
				byOrigin.computeIfAbsent(tileKey(t[0], t[1], t[2]), k -> new ArrayList<>()).add(t);
			}
		}

		BitSet seen = new BitSet();
		Deque<int[]> queue = new ArrayDeque<>();

		int seed = index(seedX, seedY, seedZ);
		if (seed < 0)
		{
			System.out.println(what + ": seed (" + seedX + "," + seedY + "," + seedZ
				+ ") is not in the map");
			return seen;
		}
		seen.set(seed);
		queue.add(new int[]{seedX, seedY, seedZ});

		long count = expand(queue, seen, byOrigin, null);
		System.out.println(what + ": " + count + " tiles");
		return seen;
	}

	/**
	 * Drains a queue of tiles, marking everything reachable from it.
	 *
	 * <p>Eight-way with the game's corner rule, plus a hop along any transport whose
	 * origin is the tile being left — which is what carries the fill underground, since
	 * the only way into a dungeon is a ladder.
	 */
	private static long expand(Deque<int[]> queue, BitSet seen, Map<Long, List<int[]>> byOrigin,
		BitSet barred)
	{
		long count = 0;
		while (!queue.isEmpty())
		{
			int[] at = queue.poll();
			count++;

			for (int d = 0; d < 8; d++)
			{
				int nx = at[0] + DX[d];
				int ny = at[1] + DY[d];
				if (!canStep(at[0], at[1], at[2], DX[d], DY[d]))
				{
					continue;
				}
				int idx = index(nx, ny, at[2]);
				if (idx < 0 || seen.get(idx) || (barred != null && barred.get(idx)))
				{
					continue;
				}
				seen.set(idx);
				queue.add(new int[]{nx, ny, at[2]});
			}

			List<int[]> here = byOrigin.get(tileKey(at[0], at[1], at[2]));
			if (here != null)
			{
				for (int[] t : here)
				{
					int idx = index(t[3], t[4], t[5]);
					if (idx < 0 || seen.get(idx) || (barred != null && barred.get(idx)))
					{
						continue;
					}
					seen.set(idx);
					queue.add(new int[]{t[3], t[4], t[5]});
				}
			}
		}
		return count;
	}

	private static Set<Integer> regionsTouched(BitSet ocean, BitSet land)
	{
		Set<Integer> keep = new HashSet<>();
		for (BitSet set : new BitSet[]{ocean, land})
		{
			for (int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1))
			{
				keep.add(regionKeys[i / (MAX_PLANES * TILES_PER_PLANE)]);
			}
		}
		return keep;
	}

	/**
	 * Finds every tile in a component too small to wander.
	 *
	 * <p>Walks every passable tile in the kept regions, flood filling any that has not yet
	 * been assigned. Components at or above the threshold are discarded as soon as they
	 * are measured — only the small ones are worth remembering, and they are rare, so what
	 * ships is a short list rather than a label per tile.
	 */
	private static BitSet labelIsolated(Set<Integer> keep)
	{
		BitSet seen = new BitSet();
		BitSet isolated = new BitSet();
		int components = 0;
		int small = 0;

		for (int region : keep)
		{
			Integer ri = regionIndex.get(region);
			if (ri == null)
			{
				continue;
			}
			int baseX = unpackX(region) * REGION_SIZE;
			int baseY = unpackY(region) * REGION_SIZE;

			for (int p = 0; p < MAX_PLANES; p++)
			{
				if (regions.get(region)[p] == null)
				{
					continue;
				}
				for (int dy = 0; dy < REGION_SIZE; dy++)
				{
					for (int dx = 0; dx < REGION_SIZE; dx++)
					{
						int x = baseX + dx;
						int y = baseY + dy;
						int idx = index(x, y, p);
						if (idx < 0 || seen.get(idx) || !walkable(x, y, p))
						{
							continue;
						}

						List<int[]> component = component(x, y, p, seen);
						components++;
						if (component.size() < ISOLATED_BELOW)
						{
							small++;
							for (int[] tile : component)
							{
								isolated.set(index(tile[0], tile[1], tile[2]));
							}
						}
					}
				}
			}
		}

		System.out.println();
		System.out.println("components: " + components + " total, " + small + " under "
			+ ISOLATED_BELOW + " tiles");
		System.out.println("isolated tiles: " + isolated.cardinality());
		return isolated;
	}

	/** One connected component, collected and marked seen. */
	private static List<int[]> component(int seedX, int seedY, int plane, BitSet seen)
	{
		List<int[]> out = new ArrayList<>();
		Deque<int[]> queue = new ArrayDeque<>();
		seen.set(index(seedX, seedY, plane));
		queue.add(new int[]{seedX, seedY, plane});

		while (!queue.isEmpty())
		{
			int[] at = queue.poll();
			out.add(at);

			// Bounded: past this size the component is large enough that the answer is
			// already known, and the remaining tiles cost time to visit for nothing.
			if (out.size() >= ISOLATED_BELOW)
			{
				// Keep draining so the tiles are marked seen, but stop collecting.
				while (!queue.isEmpty())
				{
					int[] more = queue.poll();
					for (int d = 0; d < 8; d++)
					{
						int nx = more[0] + DX[d];
						int ny = more[1] + DY[d];
						int idx = index(nx, ny, more[2]);
						if (idx < 0 || seen.get(idx) || !canStep(more[0], more[1], more[2], DX[d], DY[d]))
						{
							continue;
						}
						seen.set(idx);
						queue.add(new int[]{nx, ny, more[2]});
					}
				}
				return out;
			}

			for (int d = 0; d < 8; d++)
			{
				int nx = at[0] + DX[d];
				int ny = at[1] + DY[d];
				int idx = index(nx, ny, at[2]);
				if (idx < 0 || seen.get(idx) || !canStep(at[0], at[1], at[2], DX[d], DY[d]))
				{
					continue;
				}
				seen.set(idx);
				queue.add(new int[]{nx, ny, at[2]});
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ writing

	/**
	 * Writes the mesh: per region-plane, the collision bytes and the two derived bitmaps.
	 *
	 * <p>Same shape as the island map so the plugin's reader barely changes — count, then
	 * region, plane and payload per entry.
	 */
	private static void write(File out, Set<Integer> keep, BitSet ocean, BitSet isolated,
		BitSet land) throws IOException
	{
		int entries = 0;
		for (int region : keep)
		{
			for (int p = 0; p < MAX_PLANES; p++)
			{
				if (regions.get(region)[p] != null)
				{
					entries++;
				}
			}
		}

		try (DataOutputStream d = new DataOutputStream(
			new GZIPOutputStream(new FileOutputStream(out))))
		{
			d.writeInt(entries);
			for (int region : new TreeMap<>(toMap(keep)).keySet())
			{
				int baseX = unpackX(region) * REGION_SIZE;
				int baseY = unpackY(region) * REGION_SIZE;

				for (int p = 0; p < MAX_PLANES; p++)
				{
					byte[] flags = regions.get(region)[p];
					if (flags == null)
					{
						continue;
					}

					d.writeInt(regionId(region));
					d.writeByte(p);
					d.write(flags);

					// One bit per tile for each derived map, in the same tile order.
					byte[] oceanBits = new byte[TILES_PER_PLANE / 8];
					byte[] isolatedBits = new byte[TILES_PER_PLANE / 8];
					byte[] landBits = new byte[TILES_PER_PLANE / 8];
					for (int dy = 0; dy < REGION_SIZE; dy++)
					{
						for (int dx = 0; dx < REGION_SIZE; dx++)
						{
							int idx = index(baseX + dx, baseY + dy, p);
							if (idx < 0)
							{
								continue;
							}
							int bit = dy * REGION_SIZE + dx;
							if (ocean.get(idx))
							{
								oceanBits[bit >> 3] |= (byte) (1 << (bit & 7));
							}
							if (isolated.get(idx))
							{
								isolatedBits[bit >> 3] |= (byte) (1 << (bit & 7));
							}
							if (land.get(idx))
							{
								landBits[bit >> 3] |= (byte) (1 << (bit & 7));
							}
						}
					}
					d.write(oceanBits);
					d.write(isolatedBits);

					// Reached by the land fill, which walks from Lumbridge and every
					// transport endpoint. Shoreline edges are blocked in the source map,
					// so that fill cannot leak onto water — which makes this an exact
					// answer to "may a golem stand here on foot".
					//
					// The ocean bit alone could not answer it: it marks only the one
					// connected sea, so cave water, lakes and enclosed basins came back
					// passable-and-not-ocean and golems walked out onto them.
					d.write(landBits);
				}
			}
		}

		System.out.println();
		System.out.println("Wrote " + out + " (" + out.length() + " bytes, "
			+ entries + " region-planes)");
	}

	private static Map<Integer, Boolean> toMap(Set<Integer> keys)
	{
		Map<Integer, Boolean> out = new HashMap<>();
		for (int k : keys)
		{
			out.put(k, Boolean.TRUE);
		}
		return out;
	}

	// ------------------------------------------------------------------ geometry

	private static final int[] DX = {0, 0, 1, -1, 1, -1, 1, -1};
	private static final int[] DY = {1, -1, 0, 0, 1, 1, -1, -1};

	private static boolean north(int x, int y, int z)
	{
		return flag(x, y, z, FLAG_NORTH);
	}

	private static boolean east(int x, int y, int z)
	{
		return flag(x, y, z, FLAG_EAST);
	}

	private static boolean south(int x, int y, int z)
	{
		return north(x, y - 1, z);
	}

	private static boolean west(int x, int y, int z)
	{
		return east(x - 1, y, z);
	}

	private static boolean walkable(int x, int y, int z)
	{
		return north(x, y, z) || east(x, y, z) || south(x, y, z) || west(x, y, z);
	}

	/** The game's movement rule, cardinals and the four-term diagonal corner test. */
	private static boolean canStep(int x, int y, int z, int dx, int dy)
	{
		if (dx == 0 && dy == 1)
		{
			return north(x, y, z);
		}
		if (dx == 0 && dy == -1)
		{
			return south(x, y, z);
		}
		if (dx == 1 && dy == 0)
		{
			return east(x, y, z);
		}
		if (dx == -1 && dy == 0)
		{
			return west(x, y, z);
		}
		if (dx == 1 && dy == 1)
		{
			return north(x, y, z) && east(x, y + 1, z) && east(x, y, z) && north(x + 1, y, z);
		}
		if (dx == -1 && dy == 1)
		{
			return north(x, y, z) && west(x, y + 1, z) && west(x, y, z) && north(x - 1, y, z);
		}
		if (dx == 1 && dy == -1)
		{
			return south(x, y, z) && east(x, y - 1, z) && east(x, y, z) && south(x + 1, y, z);
		}
		if (dx == -1 && dy == -1)
		{
			return south(x, y, z) && west(x, y - 1, z) && west(x, y, z) && south(x - 1, y, z);
		}
		return false;
	}

	private static boolean flag(int x, int y, int z, int which)
	{
		byte[][] region = regions.get(key(x / REGION_SIZE, y / REGION_SIZE));
		if (region == null || z < 0 || z >= MAX_PLANES || region[z] == null)
		{
			return false;
		}
		int bit = ((y & (REGION_SIZE - 1)) * REGION_SIZE + (x & (REGION_SIZE - 1))) * 2 + which;
		return (region[z][bit >> 3] >>> (bit & 7) & 1) != 0;
	}

	/** A dense index for a tile, or -1 if its region is not in the map. */
	private static int index(int x, int y, int z)
	{
		Integer ri = regionIndex.get(key(x / REGION_SIZE, y / REGION_SIZE));
		if (ri == null || z < 0 || z >= MAX_PLANES)
		{
			return -1;
		}
		return ri * MAX_PLANES * TILES_PER_PLANE + z * TILES_PER_PLANE
			+ (y & (REGION_SIZE - 1)) * REGION_SIZE + (x & (REGION_SIZE - 1));
	}

	private static int key(int rx, int ry)
	{
		return (rx << 16) | (ry & 0xFFFF);
	}

	private static int unpackX(int key)
	{
		return key >> 16;
	}

	private static int unpackY(int key)
	{
		return key & 0xFFFF;
	}

	/** The region id the game uses, which is what the plugin indexes by. */
	private static int regionId(int key)
	{
		return (unpackX(key) << 8) | unpackY(key);
	}

	private static long tileKey(int x, int y, int z)
	{
		return ((long) x << 34) | ((long) y << 4) | (z & 0xF);
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
}
