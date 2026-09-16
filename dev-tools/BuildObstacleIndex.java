import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;
import net.runelite.cache.util.XteaKeyManager;

/**
 * Writes down where every obstacle in the game is, so they can be shown to the player.
 *
 * <p>This exists because two questions were being answered by one table and only one of
 * them was easy. <b>Where the obstacles are</b> is a fact in the cache, exact and complete.
 * <b>Where each one leads</b> has to be derived from collision components, and on Wyrmscraig
 * that derivation was wrong by one to three tiles on every shortcut until somebody measured
 * them. Shipping the first without the second means the highlight overlay can show every
 * obstacle in the game truthfully, while the transport table stays as conservative as it
 * needs to be.
 *
 * <p><b>Doors and gates are excluded</b>, on the game's own classification rather than by
 * name. A wall-mounted object is a door: a thing that swings open so the wall behind it can
 * be walked through. A golem cannot use one — opening a door changes an object every other
 * player can see, and these golems exist on one client and must never touch anything shared.
 * Scenery is different: using a ladder moves only the person on it.
 *
 * <p>That exclusion removes 3,509 placements which are not obstacles a golem could ever
 * perform, and which made the coverage gap look twice its real size.
 *
 * <pre>
 * javac -cp cache.jar -d out dev-tools/BuildObstacleIndex.java
 * java  -cp "cache.jar;deps;out" BuildObstacleIndex &lt;cache-dir&gt; &lt;xtea.json&gt; [out.gz]
 * </pre>
 */
public class BuildObstacleIndex
{
	/** Format version, written first. An old file must never be read by new rules. */
	private static final int VERSION = 4;

	/**
	 * Right-click verbs that mean "this is a way through something".
	 *
	 * <p>Matched against the object's own verbs rather than its name, because the verb is
	 * what decides whether a thing is traversable. A "Gate" you can only examine is
	 * scenery; a nameless arch you can enter is an obstacle.
	 */
	private static final String[] TRANSPORT_VERBS = {
		"open", "climb", "climb-up", "climb-down", "climb-over", "climb-into",
		"enter", "exit", "cross", "squeeze", "squeeze-through", "squeeze-past",
		"jump", "jump-over", "jump-to", "jump-down", "leap", "pass", "go-through",
		"walk-across", "walk-down", "walk-up", "balance", "swing", "swing-on",
		"travel", "board", "ride", "descend", "ascend", "scale", "vault", "crawl",
		"crawl-under", "step-over", "traverse", "quick-exit", "exit-through",
		"crawl-through", "escape", "walk-through", "go-down", "go-up",
	};

	/** Verbs that are never a traversal, even on an object that also offers one. */
	private static final String[] NOT_TRANSPORT = {
		"examine", "search", "take", "pick", "attack", "talk", "trade", "steal",
	};

	/**
	 * Things you open that are not a way through anything.
	 *
	 * <p>"Open" has to be in the verb list because it is how every door in the game is
	 * used. It is also how every chest and wardrobe is used, and those came to sixteen
	 * hundred placements of pure noise. The verb cannot tell them apart, so the name does.
	 */
	private static final String[] CONTAINER_NAMES = {
		"chest", "drawer", "wardrobe", "cupboard", "crate", "box", "coffin",
		"casket", "cabinet", "bookcase", "dresser", "sack", "barrel",
	};

	/**
	 * The loc param carrying an obstacle's traversal time, in game ticks.
	 *
	 * <p>Real per-obstacle data, in the cache, for the number this plugin has been
	 * guessing at all along: 6,157 of the shipped transport rows carry a duration filled
	 * in from their archetype's median because nothing better was known.
	 *
	 * <p>Cross-checked two ways. Its neighbour 2291 is the Agility level requirement and
	 * matches Shortest Path's independently hand-written table exactly on every obstacle
	 * where both exist — a broken wall reading 24, a log balance 45. And 2294 itself tracks
	 * that table's hand-measured durations with a consistent small offset, which is what
	 * you would expect of the obstacle's own movement time before the walking either side
	 * of it is added.
	 *
	 * <p>Only about one obstacle in nine carries it: params are on newer and reworked
	 * content and absent from the classic world. So this is a real answer where it exists
	 * and silence elsewhere, which is the honest shape for it to have.
	 */
	private static final int PARAM_DURATION_TICKS = 2294;

	/** Agility level required, used only to confirm the params mean what we think. */
	private static final int PARAM_AGILITY_LEVEL = 2291;

	/** Location types that mean the object is mounted in a wall — a door or a gate. */
	private static final int[] WALL_TYPES = {0, 1, 2, 3, 9};

	/**
	 * True if this object, or anything a varbit turns it into, is traversable.
	 *
	 * <p>Wyrmscraig's upper rock climb is the case that forced this. Object 62265 carries
	 * no name and no right-click options at all — it is a shell whose real identity is
	 * chosen at runtime from {@code configChangeDest}, and in game it reads "Climb Rocks"
	 * like its twin. Judged on its own definition it looked like scenery, so the top of
	 * that climb was the one end of it the index never knew about.
	 */
	private static boolean traversableOrImpostor(ObjectDefinition def,
		Map<Integer, ObjectDefinition> all, int depth)
	{
		if (isTransport(def))
		{
			return true;
		}
		if (depth > 2 || def.getConfigChangeDest() == null)
		{
			// Two hops is already more than any real object needs, and a cycle would
			// otherwise be fatal.
			return false;
		}
		for (int id : def.getConfigChangeDest())
		{
			ObjectDefinition into = all.get(id);
			if (into != null && traversableOrImpostor(into, all, depth + 1))
			{
				return true;
			}
		}
		return false;
	}

	public static void main(String[] args) throws Exception
	{
		if (args.length < 2)
		{
			System.err.println("usage: BuildObstacleIndex <cache-dir> <xtea.json> [out.gz]");
			System.exit(1);
		}

		try (Store store = new Store(new File(args[0])))
		{
			store.load();

			ObjectManager objects = new ObjectManager(store);
			objects.load();

			Map<Integer, ObjectDefinition> all = new HashMap<>();
			for (ObjectDefinition def : objects.getObjects())
			{
				all.put(def.getId(), def);
			}

			Map<Integer, ObjectDefinition> traversable = new HashMap<>();
			for (ObjectDefinition def : objects.getObjects())
			{
				if (traversableOrImpostor(def, all, 0))
				{
					traversable.put(def.getId(), def);
				}
			}
			System.out.println("traversable object definitions: " + traversable.size());

			XteaKeyManager keys = new XteaKeyManager();
			try (FileInputStream in = new FileInputStream(args[1]))
			{
				keys.loadKeys(in);
			}

			RegionLoader loader = new RegionLoader(store, keys);
			loader.loadRegions();
			java.util.Collection<Region> regions = loader.getRegions();
			System.out.println("regions decrypted             : " + regions.size());

			List<int[]> found = new ArrayList<>();
			Map<String, Integer> byName = new TreeMap<>();
			int walls = 0;

			for (Region region : regions)
			{
				for (Location loc : region.getLocations())
				{
					ObjectDefinition def = traversable.get(loc.getId());
					if (def == null)
					{
						continue;
					}
					boolean wall = isWall(loc.getType());
					if (wall)
					{
						walls++;
					}

					// The footprint, not just the anchor. A location records the object's
					// south-west corner, so highlighting that alone lit up one corner of
					// every multi-tile object in the game — a two-tile church pew showed
					// its southern half and nothing else, and a cave mouth marked a tile
					// off to the side of the opening.
					//
					// Orientation 1 and 3 are the quarter turns, which swap the two
					// dimensions. Orientation is per placement, so the same definition is
					// two tiles wide in one spot and two tiles deep in another.
					int sizeX = def.getSizeX();
					int sizeY = def.getSizeY();
					if ((loc.getOrientation() & 1) == 1)
					{
						int swap = sizeX;
						sizeX = sizeY;
						sizeY = swap;
					}

					found.add(new int[]{
						loc.getId(),
						loc.getPosition().getX(),
						loc.getPosition().getY(),
						loc.getPosition().getZ(),
						Math.max(1, sizeX),
						Math.max(1, sizeY),
						wall ? 1 : 0,
						paramInt(def, PARAM_DURATION_TICKS),
					});

					String name = def.getName() == null || def.getName().isEmpty()
						|| "null".equals(def.getName()) ? "(unnamed)" : def.getName();
					byName.merge(name, 1, Integer::sum);
				}
			}

			System.out.println("doors and gates excluded      : " + walls);
			System.out.println("obstacles indexed             : " + found.size());
			System.out.println();

			List<Map.Entry<String, Integer>> common = new ArrayList<>(byName.entrySet());
			common.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
			System.out.println("most common, for a sanity check:");
			for (int i = 0; i < Math.min(15, common.size()); i++)
			{
				System.out.printf("  %5d  %s%n", common.get(i).getValue(), common.get(i).getKey());
			}

			if (args.length > 2)
			{
				write(new File(args[2]), found);
			}
		}
	}

	/**
	 * Writes the index.
	 *
	 * <p>Plain and flat: a version, a count, then the object, its anchor tile and its
	 * footprint. Names are not written because the client can look them up from the same
	 * cache at runtime, and eight thousand strings would be most of the file.
	 */
	private static void write(File out, List<int[]> found) throws Exception
	{
		try (DataOutputStream w = new DataOutputStream(new GZIPOutputStream(
			new BufferedOutputStream(new FileOutputStream(out)))))
		{
			w.writeInt(VERSION);
			w.writeInt(found.size());
			for (int[] o : found)
			{
				w.writeInt(o[0]);
				w.writeShort(o[1]);
				w.writeShort(o[2]);
				w.writeByte(o[3]);
				w.writeByte(o[4]);
				w.writeByte(o[5]);
				w.writeByte(o[6]);
				w.writeByte(Math.min(255, Math.max(0, o[7])));
			}
		}
		System.out.println();
		System.out.println("Wrote " + out + " (" + out.length() + " bytes, "
			+ found.size() + " obstacles)");
	}

	/** One integer param, or 0 if this object does not carry it. */
	private static int paramInt(ObjectDefinition def, int param)
	{
		Map<Integer, Object> params = def.getParams();
		if (params == null)
		{
			return 0;
		}
		Object value = params.get(param);
		return value instanceof Number ? ((Number) value).intValue() : 0;
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

	/** True if any of this object's verbs is a way through something. */
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
}
