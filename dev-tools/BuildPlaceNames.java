import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.DataOutputStream;
import java.io.FileReader;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import net.runelite.cache.AreaManager;
import net.runelite.cache.WorldMapManager;
import net.runelite.cache.definitions.AreaDefinition;
import net.runelite.cache.definitions.WorldMapElementDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Position;

/**
 * Harvests the world map's own labels: the names drawn across the map, with the tile each sits on.
 *
 * <p>The plugin needs to say where a golem is in words — "Taverley Dungeon", "Catherby" — and the
 * game already has that text. A label is an area definition with a name; where it appears is a world
 * map element pointing at that area. Joining the two gives a few thousand named points, underground
 * ones included, which is a better answer than any list written by hand and it follows the game.
 *
 * <p>A label's text is stored with its line breaks in, and how large the game draws it, so the
 * plugin can prefer the name of a region over the name of a monster standing in it.
 *
 * <p>Map labels alone name a place by whatever is drawn nearest, which in a dungeon is usually its
 * monsters: Taverley Dungeon comes out as "Black demons". So a second, coarser source is read where
 * it is available — the region-to-area list from the Location Display plugin by trinhc2
 * (https://github.com/trinhc2/Location-Display, BSD 2-Clause), which names a whole 64-tile region at
 * a time and has no monsters in it. A region's curated name wins; labels fill in everywhere it is
 * silent, which includes anything added to the game since that list was written.
 *
 * <pre>
 * javac -cp cache.jar -d out dev-tools/BuildPlaceNames.java
 * java  -cp "cache.jar;deps.jar;out" BuildPlaceNames [cache-dir] [out.gz] [Locations.json]
 * </pre>
 */
public class BuildPlaceNames
{
	/** Bumped when the format changes, so an older file is never misread. */
	private static final int VERSION = 2;

	public static void main(String[] args) throws Exception
	{
		File cacheDir = new File(args.length > 0 ? args[0]
			: System.getProperty("user.home") + "/jagexcache/oldschool/LIVE");
		File out = new File(args.length > 1 ? args[1] : "src/main/resources/places.gz");
		File regionNames = args.length > 2 ? new File(args[2]) : null;

		List<String[]> places = new ArrayList<>();
		try (Store store = new Store(cacheDir))
		{
			store.load();

			AreaManager areas = new AreaManager(store);
			areas.load();
			WorldMapManager maps = new WorldMapManager(store);
			maps.load();

			for (WorldMapElementDefinition element : maps.getElements())
			{
				AreaDefinition area = areas.getArea(element.getAreaDefinitionId());
				Position at = element.getWorldPosition();
				if (area == null || area.name == null || area.name.isEmpty() || at == null)
				{
					continue;
				}
				// Labels are drawn on several lines, and the line breaks are in the name itself.
				String name = area.name.replace("<br>", " ").replaceAll("\s+", " ").trim();
				if (name.length() < 3)
				{
					continue;
				}
				places.add(new String[]{name, String.valueOf(at.getX()), String.valueOf(at.getY()),
					String.valueOf(at.getZ()), String.valueOf(Math.max(0, Math.min(255, area.textScale)))});
			}
		}

		places.sort((a, b) -> a[0].equals(b[0])
			? Integer.compare(Integer.parseInt(a[1]), Integer.parseInt(b[1]))
			: a[0].compareTo(b[0]));

		// {area name: [[regionX, regionY], ...]}, as the Location Display plugin stores it.
		Map<String, int[][]> regions = new LinkedHashMap<>();
		if (regionNames != null && regionNames.isFile())
		{
			try (FileReader in = new FileReader(regionNames))
			{
				Map<String, List<List<Integer>>> read = new Gson().fromJson(in,
					new TypeToken<LinkedHashMap<String, List<List<Integer>>>>()
					{
					}.getType());
				read.forEach((name, list) ->
				{
					int[][] coords = new int[list.size()][];
					for (int i = 0; i < list.size(); i++)
					{
						coords[i] = new int[]{list.get(i).get(0), list.get(i).get(1)};
					}
					regions.put(name, coords);
				});
			}
		}

		try (DataOutputStream data = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(out))))
		{
			data.writeInt(VERSION);
			data.writeInt(places.size());
			for (String[] place : places)
			{
				data.writeUTF(place[0]);
				data.writeShort(Integer.parseInt(place[1]));
				data.writeShort(Integer.parseInt(place[2]));
				data.writeByte(Integer.parseInt(place[3]));
				// How large the game draws the label: a region's name is drawn bigger than a
				// monster's, which is what tells the two apart when both are near a golem.
				data.writeByte(Integer.parseInt(place[4]));
			}

			int regionCount = regions.values().stream().mapToInt(c -> c.length).sum();
			data.writeInt(regionCount);
			for (Map.Entry<String, int[][]> area : regions.entrySet())
			{
				for (int[] region : area.getValue())
				{
					data.writeUTF(area.getKey());
					data.writeByte(region[0]);
					data.writeByte(region[1]);
				}
			}
		}
		System.out.println("Wrote " + places.size() + " map labels and "
			+ regions.values().stream().mapToInt(c -> c.length).sum() + " named regions to " + out + " ("
			+ out.length() / 1024 + "KB)");
	}
}
