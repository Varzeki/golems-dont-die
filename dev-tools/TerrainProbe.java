import java.io.File;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;

/**
 * Renders the island's terrain as text, to find out how much of a walkability map
 * can be built without XTEA keys.
 *
 * <p>Object locations for the Wyrmscraig regions are encrypted and the local key file
 * predates the content, so the objects that block movement — walls, rocks, buildings —
 * cannot be read offline. Terrain can: map archives are not encrypted.
 *
 * <p>What this establishes is whether terrain alone is worth shipping as a baseline.
 * If the land/sea boundary is legible then golems can be given the whole island to
 * roam from the first login, with real collision refining it wherever the player
 * actually walks. If it is not, the map has to be harvested in game and there is no
 * shortcut.
 */
public class TerrainProbe
{
	private static final int[] REGIONS = {10274, 10275, 10018, 10019};

	public static void main(String[] args) throws Exception
	{
		File cacheDir = new File(args.length > 0
			? args[0]
			: System.getProperty("user.home") + "/.runelite/jagexcache/oldschool/LIVE");

		try (net.runelite.cache.fs.Store store = new net.runelite.cache.fs.Store(cacheDir))
		{
			store.load();

			RegionLoader loader = new RegionLoader(store, regionId -> new int[4]);
			loader.loadRegions();

			for (int wanted : REGIONS)
			{
				Region region = null;
				for (Region r : loader.getRegions())
				{
					if (r.getRegionID() == wanted)
					{
						region = r;
						break;
					}
				}

				if (region == null)
				{
					System.out.println("region " + wanted + ": not in cache");
					continue;
				}

				render(region);
			}
		}
	}

	/**
	 * One character per tile, north at the top.
	 *
	 * <p>'#' blocked terrain, '~' water (an overlay the engine will not let you walk
	 * on), '.' open ground, ' ' nothing authored at all — which is what open sea and
	 * unbuilt space look like.
	 */
	private static void render(Region region)
	{
		System.out.println();
		System.out.println("=== region " + region.getRegionID()
			+ "  base (" + region.getBaseX() + "," + region.getBaseY() + ") plane 0 ===");

		int blocked = 0, water = 0, open = 0, empty = 0;

		for (int y = Region.Y - 1; y >= 0; y--)
		{
			StringBuilder row = new StringBuilder();
			for (int x = 0; x < Region.X; x++)
			{
				int setting = region.getTileSetting(0, x, y);
				int overlay = region.getOverlayId(0, x, y);
				int underlay = region.getUnderlayId(0, x, y);

				char c;
				if ((setting & 1) != 0)
				{
					c = '#';
					blocked++;
				}
				else if (overlay == 0 && underlay == 0)
				{
					c = ' ';
					empty++;
				}
				else if (isWaterOverlay(overlay))
				{
					c = '~';
					water++;
				}
				else
				{
					c = '.';
					open++;
				}
				row.append(c);
			}
			System.out.println(row);
		}

		System.out.printf("blocked=%d water=%d open=%d empty=%d%n", blocked, water, open, empty);
	}

	/**
	 * Overlay IDs the game paints water with. Not exhaustive — this is a probe, and
	 * the point is only to see whether a coastline shows up at all.
	 */
	private static boolean isWaterOverlay(int overlay)
	{
		return overlay == 6 || overlay == 7 || overlay == 15 || overlay == 22
			|| overlay == 54 || overlay == 55 || overlay == 71 || overlay == 72;
	}
}
