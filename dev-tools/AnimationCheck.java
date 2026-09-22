import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.definitions.SpotAnimDefinition;
import net.runelite.cache.definitions.loaders.SequenceLoader;
import net.runelite.cache.definitions.loaders.SpotAnimLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;

/**
 * Whether given animations can be played on a golem, and how long each one runs.
 *
 * <p>The golem is rigged to framemap 0, the human one. A frame built for another framemap lands
 * every transform on the wrong vertices, which is a golem folded through itself rather than an
 * animation that looks a little off. {@code FramemapProbe} established that and this borrows its
 * lookup; this one answers the same question for a handful of ids at a time, which is what adding
 * an animation to the plugin asks.
 *
 * <pre>
 * javac -cp cache-jar-with-dependencies.jar -d out dev-tools/AnimationCheck.java
 * java  -cp "cache-jar-with-dependencies.jar;out" AnimationCheck 862 866 2106
 * </pre>
 */
public class AnimationCheck
{
	/** The framemap the golem's own four sequences are on. See GolemContent.GOLEM_FRAMEMAP. */
	private static final int GOLEM_FRAMEMAP = 0;

	/** A game tick is 600ms, and frame lengths are in client cycles of 20ms. */
	private static final double CYCLE_SECONDS = 0.02;

	public static void main(String[] args) throws Exception
	{
		File cacheDir = new File(System.getProperty("cache",
			System.getProperty("user.home") + "/.runelite/jagexcache/oldschool/LIVE"));
		System.out.println("Cache: " + cacheDir.getAbsolutePath());
		try (Store store = new Store(cacheDir))
		{
			store.load();
			Map<Integer, SequenceDefinition> sequences = loadSequences(store);
			Map<Integer, Integer> framemaps = mapSequencesToFramemaps(store, sequences);

			System.out.println();
			if (args.length > 0 && args[0].equals("spot"))
			{
				// What a spot animation is made of, for drawing one on a golem: the client plays
				// these on an actor and golems are not actors, so the model and the sequence have
				// to be fetched and drawn as scenery. See FakeProp.
				System.out.printf("%-8s %-10s %-10s %-10s %s%n",
					"spot", "model", "animation", "framemap", "name");
				Map<Integer, SpotAnimDefinition> spots = loadSpotAnims(store);
				System.out.println("(" + spots.size() + " spot animations in the cache)");
				for (int i = 1; i < args.length; i++)
				{
					int id = Integer.parseInt(args[i].trim());
					SpotAnimDefinition spot = spots.get(id);
					if (spot == null)
					{
						System.out.printf("%-8d %s%n", id, "no such spot animation");
						continue;
					}
					Integer framemap = framemaps.get(spot.animationId);
					System.out.printf("%-8d %-10d %-10d %-10s %s%n", id, spot.modelId, spot.animationId,
						framemap == null ? "-" : framemap.toString(),
						spot.debugName == null ? "" : spot.debugName);
				}
				return;
			}

			System.out.printf("%-8s %-10s %-8s %-12s %s%n", "anim", "framemap", "frames", "length", "verdict");
			for (String arg : args)
			{
				report(Integer.parseInt(arg.trim()), sequences, framemaps);
			}
		}
	}

	private static void report(int id, Map<Integer, SequenceDefinition> sequences,
		Map<Integer, Integer> framemaps)
	{
		SequenceDefinition sequence = sequences.get(id);
		if (sequence == null)
		{
			System.out.printf("%-8d %-10s %-8s %-12s %s%n", id, "-", "-", "-", "no such animation");
			return;
		}
		Integer framemap = framemaps.get(id);
		int frames = sequence.frameIDs == null ? 0 : sequence.frameIDs.length;
		int cycles = 0;
		for (int i = 0; sequence.frameLengths != null && i < sequence.frameLengths.length; i++)
		{
			cycles += sequence.frameLengths[i];
		}
		String verdict = framemap == null ? "framemap unknown"
			: framemap == GOLEM_FRAMEMAP ? "plays on a golem"
			: "WRONG RIG — it would fold the golem up";
		System.out.printf("%-8d %-10s %-8d %-12s %s%n", id,
			framemap == null ? "?" : framemap.toString(), frames,
			String.format("%.1fs", cycles * CYCLE_SECONDS), verdict);
	}

	private static Map<Integer, SpotAnimDefinition> loadSpotAnims(Store store) throws Exception
	{
		Storage storage = store.getStorage();
		Index index = store.getIndex(IndexType.CONFIGS);
		Archive archive = index.getArchive(ConfigType.SPOTANIM.getId());
		ArchiveFiles files = archive.getFiles(storage.loadArchive(archive));

		SpotAnimLoader loader = new SpotAnimLoader();
		Map<Integer, SpotAnimDefinition> out = new LinkedHashMap<>();
		for (FSFile file : files.getFiles())
		{
			out.put(file.getFileId(), loader.load(file.getFileId(), file.getContents()));
		}
		return out;
	}

	private static Map<Integer, SequenceDefinition> loadSequences(Store store) throws Exception
	{
		Storage storage = store.getStorage();
		Index index = store.getIndex(IndexType.CONFIGS);
		Archive archive = index.getArchive(ConfigType.SEQUENCE.getId());
		ArchiveFiles files = archive.getFiles(storage.loadArchive(archive));

		SequenceLoader loader = new SequenceLoader();
		Map<Integer, SequenceDefinition> out = new LinkedHashMap<>();
		for (FSFile file : files.getFiles())
		{
			out.put(file.getFileId(), loader.load(file.getFileId(), file.getContents()));
		}
		return out;
	}

	/** A sequence's framemap is the one its first frame addresses: the frame's first two bytes. */
	private static Map<Integer, Integer> mapSequencesToFramemaps(Store store,
		Map<Integer, SequenceDefinition> sequences)
	{
		Storage storage = store.getStorage();
		Index frames = store.getIndex(IndexType.ANIMATIONS);
		Map<Integer, Map<Integer, byte[]>> archiveCache = new HashMap<>();
		Map<Integer, Integer> out = new LinkedHashMap<>();

		for (Map.Entry<Integer, SequenceDefinition> entry : sequences.entrySet())
		{
			SequenceDefinition sequence = entry.getValue();
			if (sequence.frameIDs == null || sequence.frameIDs.length == 0)
			{
				continue;
			}
			int packed = sequence.frameIDs[0];
			int archiveId = packed >>> 16;
			int fileId = packed & 0xffff;

			Map<Integer, byte[]> archiveFiles = archiveCache.get(archiveId);
			if (archiveFiles == null)
			{
				archiveFiles = new HashMap<>();
				Archive archive = frames.getArchive(archiveId);
				if (archive != null)
				{
					try
					{
						ArchiveFiles loaded = archive.getFiles(storage.loadArchive(archive));
						for (FSFile file : loaded.getFiles())
						{
							archiveFiles.put(file.getFileId(), file.getContents());
						}
					}
					catch (Exception ignored)
					{
						// An archive that will not decompress leaves its sequences unmapped.
					}
				}
				archiveCache.put(archiveId, archiveFiles);
			}

			byte[] frame = archiveFiles.get(fileId);
			if (frame != null && frame.length >= 2)
			{
				out.put(entry.getKey(), ((frame[0] & 0xff) << 8) | (frame[1] & 0xff));
			}
		}
		return out;
	}
}
