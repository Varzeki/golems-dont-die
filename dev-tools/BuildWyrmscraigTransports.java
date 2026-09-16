import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Writes the transport rows for Wyrmscraig's shortcuts, which no public table has.
 *
 * <p>Shortest Path maps the whole game and has nothing on the island — not at the commit
 * this project pins, and not upstream. Without these rows a golem on Wyrmscraig has
 * nothing to climb, which makes the island the one place in the world where the transport
 * work is invisible.
 *
 * <p>The objects and their positions come from {@code WyrmscraigRecon}, which reads them
 * out of the cache. What that does <em>not</em> give is where a shortcut leads: an object
 * has a location, not a destination.
 *
 * <p>So the destination is derived from the collision map rather than guessed. A shortcut
 * exists precisely because two patches of walkable ground are otherwise unreachable from
 * each other, so the algorithm is: take the walkable tiles around the object, flood fill
 * them into connected components, and if exactly two substantial ones meet at the object,
 * the shortcut joins them. The nearest tile of each is the origin and the destination.
 *
 * <p>A shortcut whose surroundings turn out to be one connected component is reported and
 * skipped — that means the collision map already allows the walk, and adding a transport
 * would be inventing one.
 *
 * <pre>
 * javac -d out dev-tools/BuildWyrmscraigTransports.java
 * java  -cp out BuildWyrmscraigTransports &lt;collision-map.zip&gt; [out.tsv]
 * </pre>
 */
public class BuildWyrmscraigTransports
{
	private static final int REGION_SIZE = 64;
	private static final int BYTES_PER_PLANE = REGION_SIZE * REGION_SIZE * 2 / 8;

	/** How far around an object to look for the ground it connects. */
	private static final int RADIUS = 7;

	/** A component smaller than this is a ledge or an artefact, not somewhere to go. */
	private static final int MIN_COMPONENT = 3;

	/**
	 * Wyrmscraig's shortcuts, harvested with {@code WyrmscraigRecon}.
	 *
	 * <p>{objectId, x, y, plane, menuOption, name, agility level}. The level is the one
	 * number here that the cache does not carry — an object definition has no requirement,
	 * because the check lives in the script that runs when you click it. Zero means the
	 * shortcut is ungated, which is the safe default: a golem that can use a shortcut the
	 * player could not is a cosmetic oddity, where a golem stuck behind a level it does
	 * have is a bug nobody can diagnose.
	 */
	private static final Object[][] SHORTCUTS = {
		// The rock climb is two objects, not one. Recon found 62267 and both directions
		// were built from it. The optional trailing {objectId, x, y} names the other object
		// and the origin tile that uses it.
		//
		// That tile was first recorded as the east end, (2554, 2209), and it is the west.
		// Every sighting since — five, from both sides — has a player at (2550, 2209)
		// clicking 62265 and playing 740, and a player at (2554, 2209) clicking 62267.
		// The ids being swapped in the table left golems with two rows for each direction,
		// one per id, and the cooldown barred only one of them.
		{62267, 2553, 2209, 0, "Climb", "Rocks", 0, 62265, 2550, 2209},
		{62262, 2565, 2219, 0, "Cross", "Slippery basalt stepping stone", 0},
		{62261, 2614, 2248, 0, "Cross", "Extra slippery basalt stepping stone", 0},
		{62260, 2584, 2282, 0, "Cross", "Basalt stepping stone", 0},
		{62260, 2584, 2284, 0, "Cross", "Basalt stepping stone", 0},
		{62408, 2569, 2253, 0, "Climb-over", "Stile", 0},
	};

	/**
	 * Objects that lead to each other, where the cache names both ends.
	 *
	 * <p>A cave entrance has no destination in any definition — where it leads is decided
	 * by the script that runs when you click it. But Wyrmscraig's caves have an
	 * <em>Exit</em> object as well as an <em>Enter</em> one, and a pair like that is the
	 * two ends of the same passage. Given both, the landing tiles are just the nearest
	 * walkable ground to each.
	 *
	 * <p>This is the island's second way off, and the more interesting one: the caves hold
	 * their own dock, so a golem that goes underground can sail out through the mouth of
	 * the cave rather than from the main harbour.
	 *
	 * <p>{objectA, ax, ay, az, objectB, bx, by, bz, option, name}.
	 */
	private static final Object[][] PAIRS = {
		{62219, 2529, 2206, 0, 62220, 2562, 8629, 0, "Enter", "Cave"},
	};

	/**
	 * Endpoints measured in game, which override anything derived for the same pair.
	 *
	 * <p>The derivation puts a golem on the nearest walkable tile to the object, which is
	 * a reasonable guess and was wrong by one to three tiles here: it had the cave landing
	 * at (2562, 8630) and the surface return at (2529, 2205), where watching a player go
	 * through gives (2564, 8630) and (2530, 2205).
	 *
	 * <p>{@code ShortcutRecon} produced these. A measurement beats a derivation, so where
	 * one exists it is simply used.
	 *
	 * <p>{objectA, ax, ay, az, bx, by, bz, ticks}.
	 */
	private static final Object[][] MEASURED_PAIRS = {
		// Enter Cave from the surface, Exit Cave back out. Three ticks each way, one clip
		// (2796) rather than the human climbing set.
		//
		// The surface end was 2534, 2204 here and that was simply wrong — a transcription
		// slip against this block's own comment above, which says the measurement gives
		// (2530, 2205). Four later observations agree with the comment: two entries and
		// two exits, all at 2530, 2205. It showed up as the highlight sitting a few tiles
		// to the side of the cave mouth.
		{62219, 2530, 2205, 0, 2564, 8630, 0, 3},
	};

	private static final Map<Integer, byte[][]> regions = new HashMap<>();

	public static void main(String[] args) throws IOException
	{
		if (args.length < 1)
		{
			System.err.println("usage: BuildWyrmscraigTransports <collision-map.zip> [out.tsv]");
			System.exit(1);
		}
		readCollisionMap(new File(args[0]));

		List<String> rows = new ArrayList<>();
		for (Object[] shortcut : SHORTCUTS)
		{
			emit(shortcut, rows);
		}
		for (Object[] pair : PAIRS)
		{
			emitPair(pair, rows);
		}

		System.out.println();
		System.out.println(rows.size() + " rows");

		if (args.length > 1)
		{
			write(new File(args[1]), rows);
		}
	}

	/** Works out both ends of one shortcut and writes a row for each direction. */
	private static void emit(Object[] shortcut, List<String> rows)
	{
		int id = (Integer) shortcut[0];
		int ox = (Integer) shortcut[1];
		int oy = (Integer) shortcut[2];
		int oz = (Integer) shortcut[3];
		String option = (String) shortcut[4];
		String name = (String) shortcut[5];
		int level = (Integer) shortcut[6];

		// An obstacle you can approach from either side may be two objects with two
		// animations. Where that is known, the row leaving the named tile carries the
		// other id; everything else is unaffected and keeps the single id it always had.
		int farId = shortcut.length > 7 ? (Integer) shortcut[7] : id;
		int farX = shortcut.length > 9 ? (Integer) shortcut[8] : Integer.MIN_VALUE;
		int farY = shortcut.length > 9 ? (Integer) shortcut[9] : Integer.MIN_VALUE;

		List<List<int[]>> components = componentsAround(ox, oy, oz);
		components.removeIf(c -> c.size() < MIN_COMPONENT);

		System.out.println("=== " + name + " (" + id + ") at " + ox + ", " + oy + " ===");
		for (List<int[]> c : components)
		{
			int[] n = nearest(c, ox, oy);
			System.out.println("  component of " + c.size() + " tiles, nearest ("
				+ n[0] + ", " + n[1] + ") at distance " + distance(n, ox, oy));
		}

		if (components.size() < 2)
		{
			System.out.println("  !! fewer than two components — the map already connects this"
				+ " ground, so no transport row is written.");
			System.out.println();
			return;
		}

		int[][] crossing = bestCrossing(components, ox, oy);
		if (crossing == null)
		{
			System.out.println("  !! no pair of components lies across this object.");
			System.out.println();
			return;
		}
		int[] a = crossing[0];
		int[] b = crossing[1];

		System.out.println("  -> " + a[0] + " " + a[1] + " " + oz
			+ "  <->  " + b[0] + " " + b[1] + " " + oz);
		System.out.println();

		// The crossing is walked stone by stone, not leapt in one bound.
		//
		// A single row from bank to bank made the golem play a jump and arrive six tiles
		// away, which is not what a player does: they hop onto the first stone, then the
		// second, then the far bank. So the object tiles themselves become waypoints, and
		// each hop is its own transport with its own animation.
		//
		// The stones are blocked ground in the collision map — you cannot walk onto one —
		// which is exactly why each hop has to be a transport rather than a step, and why
		// the plugin has to let a golem stand somewhere only the network says it can.
		List<int[]> chain = new ArrayList<>();
		chain.add(a);

		// Only a crossing is walked stone by stone. A climb is one action: the player goes
		// up one side of the rocks and down the other without ever standing on top, so
		// chaining through the object would leave a golem perched on a cliff face.
		if ("Cross".equals(option))
		{
			chain.addAll(stonesBetween(a, b, oz));
		}
		chain.add(b);

		for (int i = 0; i + 1 < chain.size(); i++)
		{
			int[] from = chain.get(i);
			int[] to = chain.get(i + 1);
			addUnique(rows, row(from, to, oz, option, name,
				objectFor(from, farX, farY, id, farId), level));
			addUnique(rows, row(to, from, oz, option, name,
				objectFor(to, farX, farY, id, farId), level));
		}
	}

	/** The object a row leaving this tile interacts with. */
	private static int objectFor(int[] origin, int farX, int farY, int id, int farId)
	{
		return origin[0] == farX && origin[1] == farY ? farId : id;
	}

	/**
	 * Shortcut objects standing between the two banks, in order of travel.
	 *
	 * <p>A crossing of several stones is several objects and one shortcut, so every stone
	 * on the line between the banks belongs to this crossing — and the golem should touch
	 * each of them on the way over.
	 */
	private static List<int[]> stonesBetween(int[] a, int[] b, int plane)
	{
		List<int[]> found = new ArrayList<>();
		for (Object[] shortcut : SHORTCUTS)
		{
			if ((Integer) shortcut[3] != plane)
			{
				continue;
			}
			int sx = (Integer) shortcut[1];
			int sy = (Integer) shortcut[2];

			// Strictly between the banks, on the line joining them.
			boolean betweenX = sx > Math.min(a[0], b[0]) && sx < Math.max(a[0], b[0])
				|| (a[0] == b[0] && sx == a[0]);
			boolean betweenY = sy > Math.min(a[1], b[1]) && sy < Math.max(a[1], b[1])
				|| (a[1] == b[1] && sy == a[1]);

			if (betweenX && betweenY && !(sx == a[0] && sy == a[1])
				&& !(sx == b[0] && sy == b[1]))
			{
				found.add(new int[]{sx, sy});
			}
		}

		// In travel order, so the chain runs from one bank to the other rather than
		// zig-zagging between stones.
		found.sort((p, q) -> a[0] == b[0]
			? Integer.compare(Math.abs(p[1] - a[1]), Math.abs(q[1] - a[1]))
			: Integer.compare(Math.abs(p[0] - a[0]), Math.abs(q[0] - a[0])));
		return found;
	}

	private static void addUnique(List<String> rows, String row)
	{
		if (!rows.contains(row))
		{
			rows.add(row);
		}
	}

	/**
	 * Links two objects that are the two ends of one passage.
	 *
	 * <p>Unlike a shortcut, there is no component analysis to do: the two ends are in
	 * different places entirely — different map squares, six thousand tiles apart — so the
	 * only question is which tile a golem stands on at each end. That is the nearest
	 * walkable ground to the object, which is where the game puts you.
	 */
	private static void emitPair(Object[] pair, List<String> rows)
	{
		int idA = (Integer) pair[0];
		int ax = (Integer) pair[1];
		int ay = (Integer) pair[2];
		int az = (Integer) pair[3];
		int idB = (Integer) pair[4];
		int bx = (Integer) pair[5];
		int by = (Integer) pair[6];
		int bz = (Integer) pair[7];
		String option = (String) pair[8];
		String name = (String) pair[9];

		int[] a = nearestWalkable(ax, ay, az);
		int[] b = nearestWalkable(bx, by, bz);
		int ticks = 0;

		for (Object[] measured : MEASURED_PAIRS)
		{
			if ((Integer) measured[0] == idA)
			{
				a = new int[]{(Integer) measured[1], (Integer) measured[2]};
				az = (Integer) measured[3];
				b = new int[]{(Integer) measured[4], (Integer) measured[5]};
				bz = (Integer) measured[6];
				ticks = (Integer) measured[7];
				break;
			}
		}

		System.out.println("=== " + name + " " + idA + " <-> " + idB
			+ (ticks > 0 ? "  (measured, " + ticks + " ticks)" : "  (derived)") + " ===");
		if (a == null || b == null)
		{
			System.out.println("  !! no walkable ground beside "
				+ (a == null ? "(" + ax + "," + ay + ")" : "(" + bx + "," + by + ")")
				+ " — that map square is not in the collision map.");
			System.out.println();
			return;
		}

		System.out.println("  -> " + a[0] + " " + a[1] + " " + az
			+ "  <->  " + b[0] + " " + b[1] + " " + bz);
		System.out.println();

		rows.add(rowAcross(a, az, b, bz, option, name, idA, ticks));
		rows.add(rowAcross(b, bz, a, az, "Exit", name, idB, ticks));
	}

	/** The nearest tile something could stand on, spiralling out from an object. */
	private static int[] nearestWalkable(int x, int y, int z)
	{
		for (int radius = 0; radius <= 8; radius++)
		{
			for (int dx = -radius; dx <= radius; dx++)
			{
				for (int dy = -radius; dy <= radius; dy++)
				{
					if (radius > 0 && Math.abs(dx) != radius && Math.abs(dy) != radius)
					{
						continue;
					}
					if (walkable(x + dx, y + dy, z))
					{
						return new int[]{x + dx, y + dy};
					}
				}
			}
		}
		return null;
	}

	/** A row whose two ends are on different planes or different map squares. */
	private static String rowAcross(int[] from, int fromZ, int[] to, int toZ,
		String option, String name, int id, int ticks)
	{
		return from[0] + " " + from[1] + " " + fromZ
			+ "\t" + to[0] + " " + to[1] + " " + toZ
			+ "\t" + option + " " + name + " " + id
			+ "\t\t\t\t\t\t" + (ticks > 0 ? String.valueOf(ticks) : "") + "\t";
	}

	/**
	 * A row in Shortest Path's transport format.
	 *
	 * <p>Columns: Origin, Destination, menu, Skills, Items, Quests, Varbits, VarPlayers,
	 * Duration, Display info. Written in exactly that order so the plugin's own loader —
	 * which maps by header text — reads it with no special case.
	 */
	private static String row(int[] from, int[] to, int plane, String option, String name,
		int id, int level)
	{
		return from[0] + " " + from[1] + " " + plane
			+ "\t" + to[0] + " " + to[1] + " " + plane
			+ "\t" + option + " " + name + " " + id
			+ "\t" + (level > 0 ? level + " Agility" : "")
			+ "\t\t\t\t\t" + duration(option) + "\t";
	}

	/**
	 * How long a shortcut takes, in game ticks.
	 *
	 * <p>Deliberately 1 — meaning "no claim" — rather than a number chosen to look right.
	 *
	 * <p>An earlier version put 4 here for a climb and 2 for a hop. Those were invented.
	 * Nothing measured them, and because the plugin takes the larger of the table duration
	 * and the animation's own length, an invented value silently overrides a real one.
	 *
	 * <p>The real lengths are known and come from the cache: a climb loop runs 44 client
	 * cycles, a stepping-stone jump 68, a ditch vault 89. Leaving this at 1 lets the clip
	 * govern, which is the measured answer rather than a guess dressed as one. Raise it
	 * only for a shortcut genuinely observed to take longer than its own animation.
	 */
	private static String duration(String option)
	{
		if ("Cross".equals(option))
		{
			// One tick, arrived at by watching.
			//
			// The basalt hops were judged right in game when a transition ran 33 client
			// cycles, and wrong — visibly slow — at 68. One tick is 30 cycles, which is
			// the value that reproduces what looked right. It is also the commonest
			// duration in the whole table.
			//
			// Stated explicitly rather than left blank, because a blank takes the jump
			// archetype's median of four ticks, and an average over every gap, pillar and
			// chasm in the game is not this crossing.
			return "1";
		}

		// Blank, meaning "this table does not know".
		//
		// Wyrmscraig's rock climb has never been timed, so a number here would be
		// invented — an earlier version put 4 purely because it looked plausible.
		// BuildTransports fills a blank with the median of the same archetype across the
		// 150 climbs that *have* been timed, which is an answer drawn from comparable
		// obstacles rather than a guess dressed as one.
		return "";
	}

	/**
	 * The pair of components that lie across the object, rather than merely near it.
	 *
	 * <p>Nearness alone is not enough, and the basalt stepping stones are why: a stone in a
	 * river has walkable bank to the north and south — the crossing — and also walkable
	 * ground to the east and west along the near bank, which is just as close and not a
	 * crossing at all. Picking the two nearest components joins a bank to itself.
	 *
	 * <p>What distinguishes a crossing is that its two ends are on <em>opposite sides</em>
	 * of the object. So every pair is scored by how nearly opposite its directions are —
	 * the dot product of the two unit vectors from the object, most negative winning — and
	 * distance is only the tie-break. A pair that is not roughly opposite is rejected
	 * outright rather than accepted as the best of a bad set.
	 */
	private static int[][] bestCrossing(List<List<int[]>> components, int ox, int oy)
	{
		int[][] best = null;
		double bestScore = Double.MAX_VALUE;

		for (int i = 0; i < components.size(); i++)
		{
			for (int j = i + 1; j < components.size(); j++)
			{
				int[] a = nearest(components.get(i), ox, oy);
				int[] b = nearest(components.get(j), ox, oy);

				double ax = a[0] - ox;
				double ay = a[1] - oy;
				double bx = b[0] - ox;
				double by = b[1] - oy;
				double la = Math.hypot(ax, ay);
				double lb = Math.hypot(bx, by);
				if (la == 0 || lb == 0)
				{
					continue;
				}

				// -1 is perfectly opposite, +1 is the same direction.
				double opposition = (ax * bx + ay * by) / (la * lb);
				if (opposition > -0.5)
				{
					continue;
				}

				double score = opposition + (la + lb) / 100.0;
				if (score < bestScore)
				{
					bestScore = score;
					best = new int[][]{a, b};
				}
			}
		}
		return best;
	}

	// ---------------------------------------------------------------- geometry

	/** Walkable components in the box around a point, each a list of tiles. */
	private static List<List<int[]>> componentsAround(int ox, int oy, int oz)
	{
		Set<Long> seen = new HashSet<>();
		List<List<int[]>> out = new ArrayList<>();

		for (int x = ox - RADIUS; x <= ox + RADIUS; x++)
		{
			for (int y = oy - RADIUS; y <= oy + RADIUS; y++)
			{
				if (!walkable(x, y, oz) || seen.contains(key(x, y)))
				{
					continue;
				}
				out.add(fill(x, y, oz, ox, oy, seen));
			}
		}
		return out;
	}

	/** Flood fill bounded to the box, so a component is local rather than the whole island. */
	private static List<int[]> fill(int sx, int sy, int z, int ox, int oy, Set<Long> seen)
	{
		List<int[]> out = new ArrayList<>();
		Deque<int[]> queue = new ArrayDeque<>();
		seen.add(key(sx, sy));
		queue.add(new int[]{sx, sy});

		while (!queue.isEmpty())
		{
			int[] at = queue.poll();
			out.add(at);

			for (int d = 0; d < 8; d++)
			{
				int nx = at[0] + DX[d];
				int ny = at[1] + DY[d];
				if (Math.abs(nx - ox) > RADIUS || Math.abs(ny - oy) > RADIUS
					|| seen.contains(key(nx, ny)) || !canStep(at[0], at[1], z, DX[d], DY[d]))
				{
					continue;
				}
				seen.add(key(nx, ny));
				queue.add(new int[]{nx, ny});
			}
		}
		return out;
	}

	private static int[] nearest(List<int[]> component, int ox, int oy)
	{
		int[] best = component.get(0);
		for (int[] tile : component)
		{
			if (distance(tile, ox, oy) < distance(best, ox, oy))
			{
				best = tile;
			}
		}
		return best;
	}

	private static int distance(int[] tile, int ox, int oy)
	{
		return Math.abs(tile[0] - ox) + Math.abs(tile[1] - oy);
	}

	private static long key(int x, int y)
	{
		return ((long) x << 32) | (y & 0xFFFFFFFFL);
	}

	private static final int[] DX = {0, 0, 1, -1, 1, -1, 1, -1};
	private static final int[] DY = {1, -1, 0, 0, 1, 1, -1, -1};

	private static boolean north(int x, int y, int z)
	{
		return flag(x, y, z, 0);
	}

	private static boolean east(int x, int y, int z)
	{
		return flag(x, y, z, 1);
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
		byte[][] region = regions.get(((x / REGION_SIZE) << 16) | (y / REGION_SIZE));
		if (region == null || z < 0 || z > 3 || region[z] == null)
		{
			return false;
		}
		int bit = ((y & (REGION_SIZE - 1)) * REGION_SIZE
			+ (x & (REGION_SIZE - 1))) * 2 + which;
		return (region[z][bit >> 3] >>> (bit & 7) & 1) != 0;
	}

	// ----------------------------------------------------------------- loading

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
				regions.put((Integer.parseInt(parts[0]) << 16) | Integer.parseInt(parts[1]),
					byPlane);
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

	private static void write(File out, List<String> rows) throws IOException
	{
		try (PrintWriter w = new PrintWriter(out, "UTF-8"))
		{
			w.println("# Origin\tDestination\tmenuOption menuTarget objectID\tSkills\tItems"
				+ "\tQuests\tVarbits\tVarPlayers\tDuration\tDisplay info");
			w.println("# Wyrmscraig shortcuts, derived by dev-tools/BuildWyrmscraigTransports.");
			w.println("# Objects and positions read from the game cache; destinations derived");
			w.println("# from the collision map by finding the two components each joins.");
			for (String row : rows)
			{
				w.println(row);
			}
		}
		System.out.println("Wrote " + out + " (" + rows.size() + " rows)");
	}
}
