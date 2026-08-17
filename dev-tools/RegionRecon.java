import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.definitions.loaders.SequenceLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;

/**
 * Second reconnaissance pass: animations and world placement.
 *
 * <p>The first pass ({@code CacheRecon}) established that the crafted golem is NPC
 * 16304, walking on 14452 and standing on 14453. Two things it could not answer are
 * settled here.
 *
 * <p>The <b>death animation</b> is not on the NPC definition — nothing is, beyond the
 * gait, because a crumble is played by a script rather than declared as a pose. But
 * it is certainly in the same authoring block as the golem's own animations, so the
 * sequences either side are dumped with their frame counts and total durations. A
 * crumble is short, plays once and does not loop, which separates it from the walk
 * and idle cycles at a glance.
 *
 * <p>The <b>crafting site</b> is found by sweeping every region for the plinth object
 * IDs. That pins the exact tiles the activity happens on, and therefore which regions
 * the island actually occupies — which is what the free-roam map has to cover.
 */
public class RegionRecon
{
	/** The animation block the golem's own sequences sit in. */
	private static final int SEQ_FROM = 14440;
	private static final int SEQ_TO = 14470;

	/** The Golem Crafting object chain, from CacheRecon. */
	private static final int[] CRAFTING_OBJECTS = {62353, 62354, 62355, 62356, 62357, 62358};

	/** Wyrmscraig, per the world map element. */
	private static final int WYRMSCRAIG_REGION = 10275;

	public static void main(String[] args) throws Exception
	{
		File cacheDir = new File(args.length > 0
			? args[0]
			: System.getProperty("user.home") + "/.runelite/jagexcache/oldschool/LIVE");

		try (Store store = new Store(cacheDir))
		{
			store.load();

			dumpSequences(store);
			sweepRegions(store);
		}
	}

	private static void dumpSequences(Store store) throws Exception
	{
		Storage storage = store.getStorage();
		Index index = store.getIndex(IndexType.CONFIGS);
		Archive archive = index.getArchive(ConfigType.SEQUENCE.getId());
		byte[] data = storage.loadArchive(archive);
		ArchiveFiles files = archive.getFiles(data);

		SequenceLoader loader = new SequenceLoader();

		System.out.println("=== Sequences " + SEQ_FROM + ".." + SEQ_TO + " ===");
		System.out.println("id      frames  duration(cycles)  frameStep  maxLoops  note");

		for (FSFile f : files.getFiles())
		{
			int id = f.getFileId();
			if (id < SEQ_FROM || id > SEQ_TO)
			{
				continue;
			}

			SequenceDefinition seq;
			try
			{
				seq = loader.load(id, f.getContents());
			}
			catch (RuntimeException e)
			{
				System.out.printf("%-7d (unreadable: %s)%n", id, e);
				continue;
			}

			int frames = seq.frameIDs == null ? 0 : seq.frameIDs.length;
			int duration = 0;
			if (seq.frameLengths != null)
			{
				for (int len : seq.frameLengths)
				{
					duration += len;
				}
			}

			System.out.printf("%-7d %-7d %-17d %-10d %-9d %s%n",
				id, frames, duration, seq.frameStep, seq.maxLoops, note(id));
		}
		System.out.println();
	}

	private static String note(int id)
	{
		switch (id)
		{
			case 14449:
				return "<- Wyrmscraig Goat walk";
			case 14452:
				return "<- GOLEM WALK (npc 16304)";
			case 14453:
				return "<- GOLEM IDLE (npc 16304)";
			case 14456:
				return "<- Broken/Powered golem stand";
			default:
				return "";
		}
	}

	/**
	 * Sweeps every region in the cache for the crafting objects, and reports what is
	 * around Wyrmscraig.
	 *
	 * <p>Locations are XTEA-encrypted per region. Regions whose key is unknown simply
	 * fail to decode and are counted rather than reported — a region that will not
	 * open is not evidence of anything, and pretending otherwise would put phantom
	 * holes in the island map.
	 */
	private static void sweepRegions(Store store) throws Exception
	{
		// Zero keys. Plenty of regions are unencrypted or keyed with zeros; the rest
		// will fail to decode and are counted separately.
		RegionLoader loader = new RegionLoader(store, regionId -> new int[4]);
		loader.loadRegions();

		Map<Integer, List<String>> craftingHits = new TreeMap<>();
		Map<Integer, Integer> islandRegions = new TreeMap<>();
		int total = 0;

		int anchorRx = WYRMSCRAIG_REGION >> 8;
		int anchorRy = WYRMSCRAIG_REGION & 0xFF;

		for (Region region : loader.getRegions())
		{
			total++;

			int rx = region.getRegionX();
			int ry = region.getRegionY();
			if (Math.abs(rx - anchorRx) <= 4 && Math.abs(ry - anchorRy) <= 4)
			{
				islandRegions.put(region.getRegionID(), walkableTiles(region));
			}

			for (Location loc : region.getLocations())
			{
				for (int wanted : CRAFTING_OBJECTS)
				{
					if (loc.getId() == wanted)
					{
						craftingHits
							.computeIfAbsent(region.getRegionID(), k -> new ArrayList<>())
							.add(String.format("obj %d at (%d,%d,%d) type=%d",
								loc.getId(),
								loc.getPosition().getX(), loc.getPosition().getY(),
								loc.getPosition().getZ(), loc.getType()));
					}
				}
			}
		}

		System.out.println("=== Crafting objects in the world ===");
		if (craftingHits.isEmpty())
		{
			System.out.println("(none found - the site's region is probably XTEA-locked,");
			System.out.println(" or the objects are spawned by script rather than placed in the map)");
		}
		for (Map.Entry<Integer, List<String>> entry : craftingHits.entrySet())
		{
			System.out.println("region " + entry.getKey() + ":");
			for (String hit : entry.getValue())
			{
				System.out.println("    " + hit);
			}
		}
		System.out.println();

		System.out.println("=== Regions around Wyrmscraig (" + WYRMSCRAIG_REGION + ") ===");
		System.out.println("region  regionX,regionY  baseX,baseY   walkable tiles (plane 0)");
		for (Map.Entry<Integer, Integer> entry : islandRegions.entrySet())
		{
			int id = entry.getKey();
			int rx = id >> 8;
			int ry = id & 0xFF;
			System.out.printf("%-7d %-16s %-13s %d%n",
				id, rx + "," + ry, (rx << 6) + "," + (ry << 6), entry.getValue());
		}
		System.out.println();
		System.out.println("(" + total + " regions in cache, " + islandRegions.size() + " near Wyrmscraig)");
	}

	/**
	 * Tiles on plane 0 that are not flagged as blocked terrain.
	 *
	 * <p>A rough measure, deliberately. It counts ground the engine considers
	 * standable before objects are taken into account, which is enough to tell an
	 * inhabited region from open sea.
	 */
	private static int walkableTiles(Region region)
	{
		int count = 0;
		for (int x = 0; x < Region.X; x++)
		{
			for (int y = 0; y < Region.Y; y++)
			{
				if ((region.getTileSetting(0, x, y) & 1) == 0)
				{
					count++;
				}
			}
		}
		return count;
	}
}
