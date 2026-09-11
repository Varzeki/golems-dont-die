import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.runelite.cache.EntityOpsDefinition;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;

/**
 * Finds Wyrmscraig's agility shortcuts so golems can use them.
 *
 * <p>Shortest Path's transport tables have no rows anywhere on the island — not in the
 * version this project pins, and not upstream — so the shortcuts the player can see have
 * to be harvested here and written into a supplementary table.
 *
 * <p>Two halves, and they fail independently:
 *
 * <ul>
 *   <li><b>Definitions</b> come from the config index, which is not encrypted. Names,
 *       right-click verbs and ids are always readable, so the objects can be identified
 *       even on a machine that cannot decrypt a single map square.</li>
 *   <li><b>Locations</b> come from the map index, which is XTEA-encrypted per region. If
 *       the local key file predates the content — which it does for Wyrmscraig — the
 *       coordinates simply cannot be read, and the tool says so rather than guessing.</li>
 * </ul>
 *
 * <pre>
 * javac -cp cache.jar -d out dev-tools/WyrmscraigRecon.java
 * java  -cp "cache.jar;deps.jar;out" WyrmscraigRecon &lt;cache-dir&gt; [xtea.json]
 * </pre>
 */
public class WyrmscraigRecon
{
	/**
	 * The regions the island occupies, above ground and below.
	 *
	 * <p>OSRS lays an underground map roughly 6,400 tiles north of the surface it sits
	 * under — a hundred region rows — so Wyrmscraig's caves are the same nine columns at
	 * ry 134-136 rather than 34-36. The island has a second way off it through there: an
	 * agility shortcut leads into a dungeon with its own dock, and you sail out through
	 * the mouth of the cave.
	 */
	private static final int[] ISLAND_REGIONS = {
		// Surface.
		10018, 10019, 10020,
		10274, 10275, 10276,
		10530, 10531, 10532,
		// The caves beneath it.
		10118, 10119, 10120,
		10374, 10375, 10376,
		10630, 10631, 10632,
	};

	/** Right-click verbs that mean "this is a shortcut a golem could take". */
	private static final String[] SHORTCUT_VERBS = {
		"climb", "hop", "jump", "cross", "squeeze", "scramble", "vault", "leap", "traverse",
	};

	/** Names worth reporting even without a matching verb. */
	private static final String[] SHORTCUT_NAMES = {
		"rock", "stepping stone", "stones", "cliff", "ledge", "crevice", "gap", "handhold",
	};

	public static void main(String[] args) throws Exception
	{
		File cacheDir = new File(args.length > 0 ? args[0]
			: System.getProperty("user.home") + "/.runelite/jagexcache/oldschool/LIVE");

		System.out.println("Cache: " + cacheDir.getAbsolutePath());
		System.out.println();

		try (Store store = new Store(cacheDir))
		{
			store.load();

			ObjectManager objects = new ObjectManager(store);
			objects.load();

			List<ObjectDefinition> candidates = candidates(objects);
			report(candidates);

			locations(store, objects, candidates, args.length > 1 ? new File(args[1]) : null);
		}
	}

	// ------------------------------------------------------------- definitions

	private static List<ObjectDefinition> candidates(ObjectManager objects)
	{
		List<ObjectDefinition> out = new ArrayList<>();
		for (ObjectDefinition def : objects.getObjects())
		{
			String name = def.getName() == null ? "" : def.getName().toLowerCase(Locale.ROOT);
			if (name.isEmpty() || "null".equals(name))
			{
				continue;
			}

			List<String> ops = ops(def);
			boolean verbMatches = false;
			for (String op : ops)
			{
				for (String verb : SHORTCUT_VERBS)
				{
					if (op.toLowerCase(Locale.ROOT).contains(verb))
					{
						verbMatches = true;
						break;
					}
				}
			}

			boolean nameMatches = false;
			for (String word : SHORTCUT_NAMES)
			{
				if (name.contains(word))
				{
					nameMatches = true;
					break;
				}
			}

			// Both, not either. "Rock" alone matches hundreds of mining rocks; a verb alone
			// matches every ladder in the game. The pair is what picks out a shortcut.
			if (verbMatches && nameMatches)
			{
				out.add(def);
			}
		}
		return out;
	}

	private static List<String> ops(ObjectDefinition def)
	{
		List<String> out = new ArrayList<>();
		EntityOpsDefinition entity = def.getOps();
		if (entity == null || entity.getOps() == null)
		{
			return out;
		}
		for (EntityOpsDefinition.Op op : entity.getOps())
		{
			if (op != null && op.text != null && !op.text.isEmpty())
			{
				out.add(op.text);
			}
		}
		return out;
	}

	private static void report(List<ObjectDefinition> candidates)
	{
		System.out.println("=== Shortcut-shaped objects in the cache ===");
		System.out.println("  " + candidates.size() + " objects have both a shortcut verb"
			+ " and a shortcut-ish name");
		System.out.println();
		System.out.println("  id      size   name                            ops");
		for (ObjectDefinition def : candidates)
		{
			System.out.println("  " + pad(def.getId(), 8)
				+ pad(def.getSizeX() + "x" + def.getSizeY(), 7)
				+ pad(def.getName(), 32)
				+ ops(def));
		}
		System.out.println();
	}

	// --------------------------------------------------------------- locations

	/**
	 * Where those objects actually stand on the island.
	 *
	 * <p>Needs XTEA keys for each region. Without them the map squares are noise, and the
	 * honest output is to say which regions could not be read rather than to report an
	 * empty island as though it had no shortcuts.
	 */
	private static void locations(Store store, ObjectManager objects,
		List<ObjectDefinition> candidates, File xtea) throws Exception
	{
		System.out.println("=== Locations on Wyrmscraig ===");

		net.runelite.cache.util.XteaKeyManager keys =
			new net.runelite.cache.util.XteaKeyManager();
		if (xtea != null)
		{
			try (java.io.FileInputStream in = new java.io.FileInputStream(xtea))
			{
				keys.loadKeys(in);
			}
			catch (Exception e)
			{
				System.out.println("  (could not read " + xtea + ": " + e + ")");
			}
		}

		RegionLoader loader = new RegionLoader(store, keys);
		loader.loadRegions();

		List<Integer> ids = new ArrayList<>();
		for (ObjectDefinition def : candidates)
		{
			ids.add(def.getId());
		}

		int readable = 0;
		List<Integer> unreadable = new ArrayList<>();

		for (int regionId : ISLAND_REGIONS)
		{
			Region region = loader.findRegionForRegionCoordinates(regionId >> 8, regionId & 0xFF);
			if (region == null)
			{
				unreadable.add(regionId);
				continue;
			}
			readable++;

			for (Location loc : region.getLocations())
			{
				// Everything interactive, not just the name-matched candidates. The name
				// filter is a good first sieve across forty thousand objects, but on nine
				// regions it is cheap to look at all of them — and a shortcut whose name
				// nobody anticipated is exactly what a filter built from guesses misses.
				ObjectDefinition here = objects.getObject(loc.getId());
				boolean interactive = here != null && !ops(here).isEmpty();
				if (!ids.contains(loc.getId()) && !interactive)
				{
					continue;
				}
				ObjectDefinition def = objects.getObject(loc.getId());
				System.out.println("  " + pad(loc.getPosition().getX(), 7)
					+ pad(loc.getPosition().getY(), 7)
					+ pad(loc.getPosition().getZ(), 3)
					+ pad("type=" + loc.getType(), 10)
					+ pad("rot=" + loc.getOrientation(), 8)
					+ pad(loc.getId(), 8)
					+ (def == null ? "?" : def.getName() + "  " + ops(def)));
			}
		}

		System.out.println();
		System.out.println("  " + readable + " of " + ISLAND_REGIONS.length
			+ " island regions were readable");
		if (!unreadable.isEmpty())
		{
			System.out.println("  unreadable (no XTEA key): " + unreadable);
			System.out.println();
			System.out.println("  Wyrmscraig postdates the local key file, so its map squares");
			System.out.println("  cannot be decrypted here. The object ids above are still");
			System.out.println("  correct — only their coordinates are missing, and those can");
			System.out.println("  be read off a live scene instead.");
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
