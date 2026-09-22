import java.io.File;
import java.util.Arrays;
import net.runelite.cache.IndexType;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.loaders.ModelLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.Store;

/** One object each, in full: size, models, recolours, and how big the model really is. */
public class PartRecon
{
	public static void main(String[] args) throws Exception
	{
		File cacheDir = new File(System.getProperty("cache",
			System.getProperty("user.home") + "/.runelite/jagexcache/oldschool/LIVE"));
		try (Store store = new Store(cacheDir))
		{
			store.load();
			ObjectManager objects = new ObjectManager(store);
			objects.load();
			for (String arg : args)
			{
				ObjectDefinition o = objects.getObject(Integer.parseInt(arg));
				if (o == null)
				{
					System.out.println(arg + " missing");
					continue;
				}
				System.out.printf("%d %s  %dx%d  anim=%d offset=%d,%d,%d scale=%d,%d,%d%n",
					o.getId(), o.getName(), o.getSizeX(), o.getSizeY(), o.getAnimationID(),
					o.getOffsetX(), o.getOffsetHeight(), o.getOffsetY(),
					o.getModelSizeX(), o.getModelSizeHeight(), o.getModelSizeY());
				System.out.printf("   models=%s types=%s%n", Arrays.toString(o.getObjectModels()),
					Arrays.toString(o.getObjectTypes()));
				System.out.printf("   recolour %s -> %s%n", Arrays.toString(o.getRecolorToFind()),
					Arrays.toString(o.getRecolorToReplace()));
				if (o.getObjectModels() != null)
				{
					for (int id : o.getObjectModels())
					{
						bounds(store, id);
					}
				}
			}
		}
	}

	private static void bounds(Store store, int id) throws Exception
	{
		Archive archive = store.getIndex(IndexType.MODELS).getArchive(id);
		if (archive == null)
		{
			System.out.println("      model " + id + " missing");
			return;
		}
		byte[] data = archive.decompress(store.getStorage().loadArchive(archive));
		ModelDefinition model = new ModelLoader().load(id, data);
		model.computeNormals();
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (int i = 0; i < model.vertexCount; i++)
		{
			minX = Math.min(minX, model.vertexX[i]);
			maxX = Math.max(maxX, model.vertexX[i]);
			minY = Math.min(minY, model.vertexY[i]);
			maxY = Math.max(maxY, model.vertexY[i]);
			minZ = Math.min(minZ, model.vertexZ[i]);
			maxZ = Math.max(maxZ, model.vertexZ[i]);
		}
		System.out.printf("      model %d: %d faces, x %d..%d, height %d..%d, z %d..%d%n",
			id, model.faceCount, minX, maxX, minY, maxY, minZ, maxZ);
	}
}
