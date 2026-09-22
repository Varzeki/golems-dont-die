import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.fs.Store;

/**
 * Every Sailing boat part in the cache, by name: which hulls, sails and helms exist beyond the
 * 1x3 raft the golems already sail, and how big each one is.
 *
 * <pre>
 * java -cp "cache.jar;deps;out" BoatRecon [cache-dir]
 * </pre>
 */
public class BoatRecon
{
	public static void main(String[] args) throws Exception
	{
		File cacheDir = new File(args.length > 0 ? args[0]
			: System.getProperty("user.home") + "/jagexcache/oldschool/LIVE");
		try (Store store = new Store(cacheDir))
		{
			store.load();
			ObjectManager objects = new ObjectManager(store);
			objects.load();

			List<ObjectDefinition> found = new ArrayList<>();
			// Boat-shaped: long, narrow and at least as big as the raft the golems have.
			for (ObjectDefinition o : objects.getObjects())
			{
				// Anything built from a model in the boat family: the raft's hull is 58216, its
				// sail 58248 and its helm 58197, so the larger boats' parts are the neighbours.
				if (o.getObjectModels() == null)
				{
					continue;
				}
				boolean boaty = false;
				for (int model : o.getObjectModels())
				{
					boaty |= model >= 58190 && model <= 58270;
				}
				if (!boaty)
				{
					continue;
				}
				found.add(o);
			}
			found.sort((a, b) -> a.getId() - b.getId());
			System.out.println(found.size() + " objects the size of a larger boat");
			for (ObjectDefinition o : found)
			{
				System.out.printf("%7d  %-58s %dx%d models=%s%n", o.getId(), o.getName(),
					o.getSizeX(), o.getSizeY(), Arrays.toString(o.getObjectModels()));
			}
		}
	}
}
