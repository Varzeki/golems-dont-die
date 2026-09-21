import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.definitions.loaders.ModelLoader;
import net.runelite.cache.definitions.loaders.SequenceLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Store;

/**
 * The Sailing raft, taken apart: which models make the 1x3 boat, how big each is, and whether
 * the golem can play the raft's steering animation.
 *
 * <p>The boat drawn under sailing golems was the old swamp rowing boat, because the only boat
 * search was of NPCs. Sailing's boats are built from scene objects, and object definitions
 * expose their models only in the cache — so they are read here, as the props were.
 *
 * <pre>
 * javac -cp cache.jar -d out dev-tools/RaftRecon.java
 * java  -cp "cache.jar;deps;out" RaftRecon [cache-dir]
 * </pre>
 */
public class RaftRecon
{
	/** SAILING_BOAT_HULL / SAIL / STEERING (plain, in use, idle) _KANDARIN_1X3_WOOD, and the raft cargo hold. */
	private static final int[] PARTS = {59494, 59530, 59555, 29506};

	/** The raft helm's own clips, the human versions, the golem's walk and a human stand for reference. */
	private static final int[] SEQUENCES = {13334, 13335, 13336, 13340, 13341, 13342, 13343, 14452, 14453, 808};

	public static void main(String[] args) throws Exception
	{
		File cacheDir = new File(args.length > 0 ? args[0]
			: System.getProperty("user.home") + "/.runelite/jagexcache/oldschool/LIVE");
		try (Store store = new Store(cacheDir))
		{
			store.load();
			ObjectManager objects = new ObjectManager(store);
			objects.load();

			System.out.println("=== raft parts ===");
			for (int id : PARTS)
			{
				ObjectDefinition o = objects.getObject(id);
				if (o == null)
				{
					System.out.println("  " + id + " missing");
					continue;
				}
				System.out.printf("  %d %-12s size %dx%d anim=%d offset=%d,%d,%d scale=%d,%d,%d models=%s types=%s%n",
					id, o.getName(), o.getSizeX(), o.getSizeY(), o.getAnimationID(),
					o.getOffsetX(), o.getOffsetHeight(), o.getOffsetY(),
					o.getModelSizeX(), o.getModelSizeHeight(), o.getModelSizeY(),
					Arrays.toString(o.getObjectModels()), Arrays.toString(o.getObjectTypes()));
				System.out.printf("      recolour %s -> %s, retexture %s -> %s%n",
					Arrays.toString(o.getRecolorToFind()), Arrays.toString(o.getRecolorToReplace()),
					Arrays.toString(o.getRetextureToFind()), Arrays.toString(o.getTextureToReplace()));
				if (o.getObjectModels() != null)
				{
					for (int model : o.getObjectModels())
					{
						bounds(store, model);
					}
				}
			}

			System.out.println("=== sequences: framemap of first frame ===");
			Map<Integer, SequenceDefinition> sequences = loadSequences(store);
			for (int id : SEQUENCES)
			{
				SequenceDefinition s = sequences.get(id);
				System.out.printf("  %d frames=%d framemap=%d%n", id, s == null || s.frameIDs == null ? 0 : s.frameIDs.length,
					framemap(store, s));
			}
		}
	}

	private static void bounds(Store store, int id) throws Exception
	{
		Index models = store.getIndex(IndexType.MODELS);
		Archive archive = models.getArchive(id);
		if (archive == null)
		{
			System.out.println("      model " + id + " missing");
			return;
		}
		ModelDefinition m = new ModelLoader().load(id, archive.decompress(store.getStorage().loadArchive(archive)));
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = minX, maxY = maxX, minZ = minX, maxZ = maxX;
		for (int i = 0; i < m.vertexCount; i++)
		{
			minX = Math.min(minX, (int) m.vertexX[i]);
			maxX = Math.max(maxX, (int) m.vertexX[i]);
			minY = Math.min(minY, (int) m.vertexY[i]);
			maxY = Math.max(maxY, (int) m.vertexY[i]);
			minZ = Math.min(minZ, (int) m.vertexZ[i]);
			maxZ = Math.max(maxZ, (int) m.vertexZ[i]);
		}
		System.out.printf("      model %d verts %d  x %d..%d  height %d..%d  z %d..%d  rigged=%b%n", id, m.vertexCount,
			minX, maxX, minY, maxY, minZ, maxZ, m.packedVertexGroups != null);
		java.util.TreeMap<Integer, Integer> colours = new java.util.TreeMap<>();
		int textured = 0, transparent = 0;
		for (int f = 0; f < m.faceCount; f++)
		{
			colours.merge((int) m.faceColors[f], 1, Integer::sum);
			textured += m.faceTextures != null && m.faceTextures[f] != -1 ? 1 : 0;
			transparent += m.faceTransparencies != null && m.faceTransparencies[f] != 0 ? 1 : 0;
		}
		System.out.printf("        faces %d, textured %d, transparent %d, colours %s%n", m.faceCount, textured, transparent, colours);
	}

	private static Map<Integer, SequenceDefinition> loadSequences(Store store) throws Exception
	{
		Index index = store.getIndex(IndexType.CONFIGS);
		Archive archive = index.getArchive(ConfigType.SEQUENCE.getId());
		ArchiveFiles files = archive.getFiles(store.getStorage().loadArchive(archive));
		SequenceLoader loader = new SequenceLoader();
		Map<Integer, SequenceDefinition> out = new HashMap<>();
		for (FSFile f : files.getFiles())
		{
			out.put(f.getFileId(), loader.load(f.getFileId(), f.getContents()));
		}
		return out;
	}

	private static int framemap(Store store, SequenceDefinition s) throws Exception
	{
		if (s == null || s.frameIDs == null || s.frameIDs.length == 0)
		{
			return -1;
		}
		int packed = s.frameIDs[0];
		Archive archive = store.getIndex(IndexType.ANIMATIONS).getArchive(packed >>> 16);
		if (archive == null)
		{
			return -1;
		}
		for (FSFile f : archive.getFiles(store.getStorage().loadArchive(archive)).getFiles())
		{
			if (f.getFileId() == (packed & 0xffff))
			{
				byte[] frame = f.getContents();
				return ((frame[0] & 0xff) << 8) | (frame[1] & 0xff);
			}
		}
		return -1;
	}
}
