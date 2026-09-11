import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import net.runelite.cache.EntityOpsDefinition;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;
import net.runelite.cache.util.XteaKeyManager;

/**
 * Harvests the game's sailing docks: where each one is, and where a golem boards it.
 *
 * <p>The dock table ({@code DBTableID.SailingDock}) carries a dock id, two names, a
 * Sailing level and a sprite — and <b>no location at all</b>. A dock's position in the
 * world lives in its scene objects, so it is harvested here and shipped, the way every
 * other identifier in this project was.
 *
 * <p>Two different objects matter, and conflating them was a wrong turn worth recording:
 *
 * <ul>
 *   <li>The <b>docking buoy</b> ({@code Dock} action) marks where a boat moors. There are
 *       exactly 61 types, one per table row, and the table's {@code COL_DOCK_ID} is the
 *       index into them in object-id order — verified against Wyrmscraig, dock id 59
 *       against the 60th buoy, and its cavern, 60 against the 61st. That makes the buoy
 *       the dock's <i>identity</i> and its position on the water.</li>
 *   <li>The <b>gangplank</b> is what anyone actually walks across to get on or off. It is
 *       not interchangeable with the buoy — 93 gangplank types exist against 61 docks,
 *       because most belong to old pre-Sailing boats — so it cannot be indexed the same
 *       way. It is found instead as the nearest gangplank to the dock's buoy.</li>
 * </ul>
 *
 * <pre>
 * javac -cp cache.jar -d out dev-tools/BuildDocks.java
 * java  -cp "cache.jar;deps.jar;out" BuildDocks &lt;cache-dir&gt; [out.gz]
 * </pre>
 */
public class BuildDocks
{
	/** The action that marks a docking buoy — where a boat moors, and the dock's identity. */
	private static final String DOCK_ACTION = "dock";

	/** Gangplanks are named plainly, and are what a golem walks across. */
	private static final String GANGPLANK_NAME = "gangplank";

	/** How far from a buoy its gangplank may be, in tiles. */
	private static final int GANGPLANK_RADIUS = 48;

	public static void main(String[] args) throws IOException
	{
		File cacheDir = new File(args.length > 0 ? args[0]
			: System.getProperty("user.home") + "/.runelite/jagexcache/oldschool/LIVE");

		List<int[]> buoys = new ArrayList<>();
		List<int[]> planks = new ArrayList<>();

		try (Store store = new Store(cacheDir))
		{
			store.load();

			ObjectManager objects = new ObjectManager(store);
			objects.load();

			Set<Integer> buoyTypes = new HashSet<>();
			Set<Integer> plankTypes = new HashSet<>();

			for (ObjectDefinition def : objects.getObjects())
			{
				String name = def.getName() == null ? ""
					: def.getName().toLowerCase(Locale.ROOT);
				if (name.contains(GANGPLANK_NAME))
				{
					plankTypes.add(def.getId());
				}
				for (String op : ops(def))
				{
					if (op.toLowerCase(Locale.ROOT).contains(DOCK_ACTION))
					{
						buoyTypes.add(def.getId());
						break;
					}
				}
			}
			System.out.println("buoy types: " + buoyTypes.size()
				+ ", gangplank types: " + plankTypes.size());

			RegionLoader loader = new RegionLoader(store, new XteaKeyManager());
			loader.loadRegions();

			for (Region region : loader.getRegions())
			{
				for (Location loc : region.getLocations())
				{
					int[] at = {loc.getId(), loc.getPosition().getX(),
						loc.getPosition().getY(), loc.getPosition().getZ()};
					if (buoyTypes.contains(loc.getId()))
					{
						buoys.add(at);
					}
					if (plankTypes.contains(loc.getId()))
					{
						planks.add(at);
					}
				}
			}
		}

		// Object-id order is content order, which is what makes the index meaningful, and
		// one entry per type — a buoy placed twice is one dock with a spare, and keeping
		// both would shift every later dock id by one.
		buoys.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0])
			: a[1] != b[1] ? Integer.compare(a[1], b[1]) : Integer.compare(a[2], b[2]));

		List<int[]> docks = new ArrayList<>();
		int lastId = -1;
		for (int[] buoy : buoys)
		{
			if (buoy[0] != lastId)
			{
				docks.add(buoy);
				lastId = buoy[0];
			}
		}

		System.out.println("dock ids 0.." + (docks.size() - 1)
			+ " — must match the row count of DBTableID.SailingDock (61)");
		System.out.println("gangplanks placed in the world: " + planks.size());
		System.out.println();
		System.out.println("  dock  buoy x,y,z          gangplank x,y,z      gap");

		List<int[]> table = new ArrayList<>();
		int without = 0;

		for (int id = 0; id < docks.size(); id++)
		{
			int[] buoy = docks.get(id);
			int[] plank = nearest(planks, buoy);
			int gap = plank == null ? -1
				: Math.abs(plank[1] - buoy[1]) + Math.abs(plank[2] - buoy[2]);

			if (plank == null)
			{
				// A dock with no gangplank near it is kept: golems can still arrive there,
				// they just cannot walk aboard, and saying so beats dropping the port.
				without++;
				plank = buoy;
			}

			System.out.println("  " + pad(id, 6)
				+ pad(buoy[1] + "," + buoy[2] + "," + buoy[3], 19)
				+ pad(plank[1] + "," + plank[2] + "," + plank[3], 21)
				+ (gap < 0 ? "none" : String.valueOf(gap)));

			table.add(new int[]{buoy[1], buoy[2], buoy[3], plank[1], plank[2], plank[3]});
		}

		System.out.println();
		System.out.println(without + " docks have no gangplank within "
			+ GANGPLANK_RADIUS + " tiles");

		if (args.length > 1)
		{
			write(new File(args[1]), table);
		}
	}

	/** The gangplank closest to a buoy, preferring the same plane, or null. */
	private static int[] nearest(List<int[]> planks, int[] buoy)
	{
		int[] best = null;
		int bestGap = Integer.MAX_VALUE;

		for (int[] plank : planks)
		{
			int gap = Math.abs(plank[1] - buoy[1]) + Math.abs(plank[2] - buoy[2]);
			if (gap > GANGPLANK_RADIUS)
			{
				continue;
			}
			// A plank on another floor of the same dock is still that dock's plank, but a
			// plank on this one is the better answer.
			int score = gap + (plank[3] == buoy[3] ? 0 : GANGPLANK_RADIUS);
			if (score < bestGap)
			{
				bestGap = score;
				best = plank;
			}
		}
		return best;
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

	private static void write(File out, List<int[]> table) throws IOException
	{
		try (DataOutputStream d = new DataOutputStream(
			new GZIPOutputStream(new FileOutputStream(out))))
		{
			d.writeInt(table.size());
			for (int[] dock : table)
			{
				for (int value : dock)
				{
					d.writeShort(value);
				}
			}
		}
		System.out.println("Wrote " + out + " (" + out.length() + " bytes, "
			+ table.size() + " docks)");
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
