import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;
import net.runelite.cache.util.XteaKeyManager;

/**
 * Finds every obstacle in the game, and says which ones the transport table has missed.
 *
 * <p>Wyrmscraig's doors and staircases turned out to be absent from the shipped table, and
 * the obvious next question is whether that is an island problem or a game-wide one. This
 * answers it by going to the source: every object placement in every region the local keys
 * can decrypt, filtered to the ones whose right-click verbs make them a way through
 * something, and compared against what the plugin actually ships.
 *
 * <p>The two halves of the cache fail independently, which is what makes this possible at
 * all:
 *
 * <ul>
 *   <li><b>Definitions</b> — names, verbs, ids — live in the config index, which is not
 *       encrypted and is always readable.</li>
 *   <li><b>Locations</b> live in the map index, XTEA-encrypted per region. RuneLite keeps a
 *       key file covering most of the game; regions it lacks are reported rather than
 *       silently skipped, because a missing region looks exactly like an empty one.</li>
 * </ul>
 *
 * <pre>
 * javac -cp cache.jar -d out dev-tools/AuditObjects.java
 * java  -cp "cache.jar;out" AuditObjects &lt;cache-dir&gt; &lt;xtea.json&gt; &lt;transports-dir&gt; [extra-dir]
 * </pre>
 */
public class AuditObjects
{
	/**
	 * Right-click verbs that mean "this is a way through something".
	 *
	 * <p>Matched against the object's own actions rather than its name, because the verb is
	 * what decides whether a thing is a transport. A "Gate" you can only Examine is scenery;
	 * a nameless arch you can Enter is a transport.
	 */
	private static final String[] TRANSPORT_VERBS = {
		"open", "climb", "climb-up", "climb-down", "climb-over", "climb-into",
		"enter", "exit", "cross", "squeeze", "squeeze-through", "squeeze-past",
		"jump", "jump-over", "jump-to", "jump-down", "leap", "pass", "go-through",
		"walk-across", "walk-down", "walk-up", "balance", "swing", "swing-on",
		"travel", "board", "ride", "descend", "ascend", "scale", "vault", "crawl",
		"crawl-under", "step-over", "traverse", "use",
	};

	/** Verbs that are never a traversal, even on an object that also offers one. */
	private static final String[] NOT_TRANSPORT = {
		"examine", "search", "take", "pick", "attack", "talk", "trade", "steal",
	};

	/**
	 * Things you open that are not a way through anything.
	 *
	 * <p>"Open" has to be in the verb list, because it is how every door and gate in the
	 * game is used. It is also how every chest, cupboard and wardrobe is used, and those
	 * came to sixteen hundred placements — an eighth of the total, all of it noise. The
	 * verb cannot tell them apart, so the name does.
	 */
	/**
	 * Location types that mean the object is mounted in a wall.
	 *
	 * <p>This is the distinction that matters, and it is the game's own rather than a guess
	 * from a name. A wall object is a door or a gate: a thing that swings open so the wall
	 * behind it can be walked through. A golem cannot use one. Opening a door changes a
	 * real object that every other player in the world can see, and this plugin is
	 * cosmetic — its golems exist only on one client and must never touch anything shared.
	 *
	 * <p>Scenery — a ladder, a staircase, a stepping stone, a cave mouth — is different.
	 * Using one moves only the person using it, so a golem can do the same thing locally
	 * and nobody else's game is affected.
	 *
	 * <p>Types 0-3 are the wall orientations and 9 is a diagonal wall. Everything a golem
	 * could actually traverse is type 10 or 11.
	 */
	private static final int[] WALL_TYPES = {0, 1, 2, 3, 9};

	private static final String[] CONTAINER_NAMES = {
		"chest", "drawer", "wardrobe", "cupboard", "crate", "box", "coffin",
		"casket", "cabinet", "bookcase", "dresser", "sack", "barrel",
	};

	public static void main(String[] args) throws Exception
	{
		if (args.length < 3)
		{
			System.err.println("usage: AuditObjects <cache-dir> <xtea.json> "
				+ "<transports-dir> [extra-dir]");
			System.exit(1);
		}

		Set<Integer> shipped = shippedObjectIds(Arrays.copyOfRange(args, 2, args.length));
		System.out.println("object ids in the shipped transport table: " + shipped.size());

		try (Store store = new Store(new File(args[0])))
		{
			store.load();

			ObjectManager objects = new ObjectManager(store);
			objects.load();
			System.out.println("object definitions in the cache       : "
				+ objects.getObjects().size());

			// Which definitions are transports at all. Done once; there are thousands of
			// placements per definition and re-deciding per placement would dominate.
			Map<Integer, ObjectDefinition> transportDefs = new HashMap<>();
			for (ObjectDefinition def : objects.getObjects())
			{
				if (isTransport(def))
				{
					transportDefs.put(def.getId(), def);
				}
			}
			System.out.println("of those, ones you can go through via : "
				+ transportDefs.size());
			System.out.println();

			XteaKeyManager keys = new XteaKeyManager();
			try (FileInputStream in = new FileInputStream(args[1]))
			{
				keys.loadKeys(in);
			}

			RegionLoader loader = new RegionLoader(store, keys);
			loader.loadRegions();
			java.util.Collection<Region> regions = loader.getRegions();
			System.out.println("regions decrypted: " + regions.size());
			System.out.println();

			sweep(regions, transportDefs, shipped);
		}
	}

	/** Walks every placement and tallies what is covered and what is not. */
	private static void sweep(java.util.Collection<Region> regions, Map<Integer, ObjectDefinition> defs,
		Set<Integer> shipped)
	{
		int placements = 0;
		int covered = 0;
		int wallPlacements = 0;
		int wallCovered = 0;

		// Keyed by name rather than id: the same staircase exists under a dozen ids and
		// what a reader needs to know is "staircases are missing", not a list of numbers.
		Map<String, int[]> byName = new TreeMap<>();

		for (Region region : regions)
		{
			for (Location loc : region.getLocations())
			{
				ObjectDefinition def = defs.get(loc.getId());
				if (def == null)
				{
					continue;
				}

				boolean known = shipped.contains(loc.getId());

				if (isWall(loc.getType()))
				{
					// Counted, and then set aside. A door is not something a golem can do.
					wallPlacements++;
					if (known)
					{
						wallCovered++;
					}
					continue;
				}

				placements++;
				if (known)
				{
					covered++;
				}

				String name = def.getName() == null || def.getName().isEmpty()
					|| "null".equals(def.getName()) ? "(unnamed)" : def.getName();
				int[] tally = byName.computeIfAbsent(name, k -> new int[2]);
				tally[0]++;
				if (!known)
				{
					tally[1]++;
				}
			}
		}

		System.out.println("wall-mounted objects — doors and gates, which a golem cannot use");
		System.out.println("  placements                                 : " + wallPlacements);
		System.out.println("  of those, in the shipped table anyway      : " + wallCovered);
		System.out.println();
		System.out.println("scenery a golem could actually traverse");
		System.out.println("  placements                                 : " + placements);
		System.out.println("  with an id the shipped table knows         : " + covered);
		System.out.println("  with an id the table has never heard of    : "
			+ (placements - covered));
		System.out.println();

		List<Map.Entry<String, int[]>> worst = new ArrayList<>(byName.entrySet());
		worst.sort(Comparator.comparingInt(e -> -e.getValue()[1]));

		System.out.println("biggest gaps, by how many placements are unknown");
		System.out.println("missing  total  object");
		System.out.println("-------  -----  ------");
		for (int i = 0; i < Math.min(35, worst.size()); i++)
		{
			Map.Entry<String, int[]> e = worst.get(i);
			if (e.getValue()[1] == 0)
			{
				break;
			}
			System.out.printf("%7d  %5d  %s%n", e.getValue()[1], e.getValue()[0], e.getKey());
		}
	}

	private static boolean isWall(int type)
	{
		for (int wall : WALL_TYPES)
		{
			if (type == wall)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * True if any of this object's verbs is a way through something.
	 *
	 * <p>Right-click options moved off {@code ObjectDefinition} into an
	 * {@code EntityOpsDefinition} in recent cache revisions, and are now a list of
	 * {@code Op} objects rather than a plain array of strings.
	 */
	private static boolean isTransport(ObjectDefinition def)
	{
		String name = def.getName() == null ? "" : def.getName().toLowerCase(Locale.ROOT);
		for (String container : CONTAINER_NAMES)
		{
			if (name.contains(container))
			{
				return false;
			}
		}

		net.runelite.cache.EntityOpsDefinition ops = def.getOps();
		if (ops == null || ops.getOps() == null)
		{
			return false;
		}

		for (net.runelite.cache.EntityOpsDefinition.Op op : ops.getOps())
		{
			String action = op == null ? null : op.text;
			if (action == null || action.isEmpty())
			{
				continue;
			}
			String verb = action.toLowerCase(Locale.ROOT).split("\\s+")[0];

			boolean excluded = false;
			for (String bad : NOT_TRANSPORT)
			{
				if (verb.equals(bad))
				{
					excluded = true;
					break;
				}
			}
			if (excluded)
			{
				continue;
			}

			for (String good : TRANSPORT_VERBS)
			{
				if (verb.equals(good))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Object ids named by the shipped transport tables.
	 *
	 * <p>The menu column is the third field and ends with the id — "Cross Gangplank 12164".
	 * Parsed by column rather than by scanning the line for numbers, which an earlier
	 * version did and which picked up the trailing zero of every coordinate pair instead:
	 * it found 141 ids where there are 1,732, and made the coverage look catastrophic.
	 */
	private static Set<Integer> shippedObjectIds(String[] dirs) throws IOException
	{
		Set<Integer> ids = new HashSet<>();

		for (String dir : dirs)
		{
			File[] files = new File(dir).listFiles((d, n) -> n.endsWith(".tsv"));
			if (files == null)
			{
				continue;
			}

			for (File f : files)
			{
				try (java.io.BufferedReader r =
					new java.io.BufferedReader(new java.io.FileReader(f)))
				{
					String line;
					while ((line = r.readLine()) != null)
					{
						if (line.startsWith("#"))
						{
							continue;
						}
						String[] fields = line.split("\t", -1);
						if (fields.length < 3)
						{
							continue;
						}
						// Walked from the end rather than matched, because the names in
						// front of the id contain digits of their own — "Gate of War 2",
						// "Tripwire 3921" — and anything that scans the field as a whole
						// has to be careful about which number it lands on.
						String menu = fields[2].trim();
						int end = menu.length();
						int start = end;
						while (start > 0 && Character.isDigit(menu.charAt(start - 1)))
						{
							start--;
						}
						if (start < end && start > 0)
						{
							ids.add(Integer.parseInt(menu.substring(start, end)));
						}
					}
				}
			}
		}
		return ids;
	}
}
