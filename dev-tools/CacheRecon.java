import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.runelite.cache.AreaManager;
import net.runelite.cache.EntityOpsDefinition;
import net.runelite.cache.NpcManager;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.WorldMapManager;
import net.runelite.cache.definitions.AreaDefinition;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.WorldMapElementDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Position;

/**
 * Offline reconnaissance against the local OSRS cache.
 *
 * <p>Run once, by hand, to harvest the identifiers for Golem Crafting so they can be
 * hardcoded into the plugin. Nothing here ships — it exists so that the plugin does
 * not have to discover at runtime what the cache already knows.
 *
 * <p>The cache is the right source for most of it. {@code NpcDefinition} carries the
 * standing, walking and run animation IDs, which the RuneLite client API does not
 * expose on {@code NPCComposition} at all — so what would otherwise have to be read
 * off a live actor is simply readable here.
 *
 * <pre>
 * javac -cp cache-jar-with-dependencies.jar CacheRecon.java
 * java  -cp cache-jar-with-dependencies.jar;. CacheRecon &lt;cache-dir&gt;
 * </pre>
 */
public class CacheRecon
{
	/** Names worth reporting. Deliberately wide: better a long list than a missed golem. */
	private static final String[] NPC_TERMS = {"golem", "wyrmscraig", "clay", "stone chunk"};
	private static final String[] OBJECT_TERMS = {"golem", "plinth", "wyrmscraig", "chunk"};
	private static final String[] MAP_TERMS = {"wyrmscraig", "golem"};

	public static void main(String[] args) throws Exception
	{
		File cacheDir = new File(args.length > 0
			? args[0]
			: System.getProperty("user.home") + "/.runelite/jagexcache/oldschool/LIVE");

		System.out.println("Cache: " + cacheDir.getAbsolutePath());
		System.out.println();

		try (Store store = new Store(cacheDir))
		{
			store.load();

			dumpNpcs(store);
			dumpObjects(store);
			dumpMapElements(store);
		}
	}

	private static void dumpNpcs(Store store) throws Exception
	{
		NpcManager npcs = new NpcManager(store);
		npcs.load();

		System.out.println("=== NPCs ===");
		List<NpcDefinition> hits = new ArrayList<>();
		for (NpcDefinition npc : npcs.getNpcs())
		{
			if (matches(npc.name, NPC_TERMS))
			{
				hits.add(npc);
			}
		}
		hits.sort((a, b) -> Integer.compare(a.id, b.id));

		for (NpcDefinition npc : hits)
		{
			System.out.printf(
				"id=%-6d name=%-28s size=%d stand=%-6d walk=%-6d run=%-6d "
					+ "rotL=%-6d rotR=%-6d rot180=%-6d wScale=%d hScale=%d%n",
				npc.id, npc.name, npc.size,
				npc.standingAnimation, npc.walkingAnimation, npc.runAnimation,
				npc.rotateLeftAnimation, npc.rotateRightAnimation, npc.rotate180Animation,
				npc.widthScale, npc.heightScale);
			System.out.printf("        models=%s recolourFrom=%s recolourTo=%s actions=%s%n",
				Arrays.toString(npc.models),
				Arrays.toString(npc.recolorToFind),
				Arrays.toString(npc.recolorToReplace),
				describeOps(npc.ops));
		}
		System.out.println("(" + hits.size() + " matching NPCs)");
		System.out.println();
	}

	private static void dumpObjects(Store store) throws Exception
	{
		ObjectManager objects = new ObjectManager(store);
		objects.load();

		System.out.println("=== Objects ===");
		int count = 0;
		for (ObjectDefinition obj : objects.getObjects())
		{
			if (!matches(obj.getName(), OBJECT_TERMS))
			{
				continue;
			}
			count++;
			System.out.printf("id=%-6d name=%-30s size=%dx%d actions=%s%n",
				obj.getId(), obj.getName(),
				obj.getSizeX(), obj.getSizeY(),
				describeOps(obj.getOps()));
		}
		System.out.println("(" + count + " matching objects)");
		System.out.println();
	}

	/**
	 * World map labels, which is how the island's location is found without anyone
	 * having to stand on it and read a coordinate off the screen.
	 */
	private static void dumpMapElements(Store store) throws Exception
	{
		// World map elements carry only an area ID; the label lives on the area
		// definition, so both have to be loaded and joined.
		AreaManager areas = new AreaManager(store);
		areas.load();

		System.out.println("=== Named areas ===");
		int named = 0;
		for (AreaDefinition area : areas.getAreas())
		{
			if (matches(area.name, MAP_TERMS))
			{
				named++;
				System.out.printf("areaId=%-6d name=%s%n", area.id, area.name);
			}
		}
		System.out.println("(" + named + " matching areas)");
		System.out.println();

		WorldMapManager maps = new WorldMapManager(store);
		maps.load();

		System.out.println("=== World map elements for those areas ===");
		int count = 0;
		for (WorldMapElementDefinition element : maps.getElements())
		{
			AreaDefinition area = areas.getArea(element.getAreaDefinitionId());
			if (area == null || !matches(area.name, MAP_TERMS))
			{
				continue;
			}
			count++;
			Position at = element.getWorldPosition();
			if (at == null)
			{
				System.out.printf("name=%-30s (no position)%n", area.name);
				continue;
			}
			int regionId = (at.getX() >> 6) << 8 | (at.getY() >> 6);
			System.out.printf("name=%-30s world=(%d,%d,%d) region=%d%n",
				area.name, at.getX(), at.getY(), at.getZ(), regionId);
		}
		System.out.println("(" + count + " matching map elements)");
	}

	/**
	 * Right-click options as a flat list. Newer caches moved these off a plain
	 * String[] and onto a structured ops object, so they have to be walked out.
	 */
	private static String describeOps(EntityOpsDefinition ops)
	{
		if (ops == null || ops.getOps() == null || ops.getOps().isEmpty())
		{
			return "[]";
		}
		List<String> out = new ArrayList<>();
		for (EntityOpsDefinition.Op op : ops.getOps())
		{
			if (op != null && op.text != null)
			{
				out.add(op.text);
			}
		}
		return out.toString();
	}

	private static boolean matches(String name, String[] terms)
	{
		if (name == null || name.equals("null"))
		{
			return false;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		for (String term : terms)
		{
			if (lower.contains(term))
			{
				return true;
			}
		}
		return false;
	}
}
