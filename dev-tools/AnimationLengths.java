import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
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

/**
 * Reads how long each transport animation actually runs for.
 *
 * <p>The golem plays these clips, so how long it should be busy is not a judgement call —
 * it is the length of the clip, and the cache knows it exactly. A sequence stores a frame
 * length per frame in client cycles, so the total is their sum.
 *
 * <p>Written because the first pass invented the numbers, and invented numbers are how
 * golems ended up scaling a cliff in two thirds of a second.
 *
 * <pre>
 * javac -cp cache.jar -d out dev-tools/AnimationLengths.java
 * java  -cp "cache.jar;deps.jar;out" AnimationLengths [cache-dir]
 * </pre>
 */
public class AnimationLengths
{
	/** The catalogue, by the name this project knows each clip as. */
	private static final Object[][] WANTED = {
		{"ANIM_LADDER_GRAB", 828},
		{"ANIM_LADDER_GRAB_TOP", 833},
		{"ANIM_CLIMB_READY", 738},
		{"ANIM_CLIMB_LOOP", 4435},
		{"ANIM_CLIMB_MERGE", 12338},
		{"ANIM_DITCH_VAULT", 6132},
		{"ANIM_JUMP_STEPPINGSTONE", 769},
		{"ANIM_JUMP_STONES", 1604},
		{"ANIM_JUMP_LONG", 807},
		{"ANIM_WALL_JUMP", 2583},
		{"ANIM_STILE", 14235},
		{"ANIM_SQUEEZE_READY", 747},
		{"ANIM_SQUEEZE_LOOP", 746},
		{"ANIM_SQUEEZE_END", 748},
		{"ANIM_BALANCE_WALK", 762},
		{"ANIM_BALANCE_WALK_LOOP", 7134},
		{"ANIM_TIGHTROPE", 4772},
		{"GOLEM_WALK", 14452},
		{"GOLEM_IDLE", 14453},
		{"GOLEM_DEATH", 14455},
		{"MEASURED stepping stone", 741},
		{"MEASURED rock climb", 4435},
		{"MEASURED cave", 2796},
		{"MEASURED door", 4282},
	};

	public static void main(String[] args) throws Exception
	{
		File cacheDir = new File(args.length > 0 ? args[0]
			: System.getProperty("user.home") + "/.runelite/jagexcache/oldschool/LIVE");

		try (Store store = new Store(cacheDir))
		{
			store.load();

			Map<Integer, SequenceDefinition> sequences = load(store);

			System.out.println("  name                      id      frames  cycles  ticks  loops");
			for (Object[] wanted : WANTED)
			{
				int id = (Integer) wanted[1];
				SequenceDefinition seq = sequences.get(id);
				if (seq == null)
				{
					System.out.println("  " + pad(wanted[0], 26) + pad(id, 8) + "not in cache");
					continue;
				}

				int frames = seq.frameIDs == null ? 0 : seq.frameIDs.length;
				int cycles = 0;
				if (seq.frameLengths != null)
				{
					for (int length : seq.frameLengths)
					{
						cycles += length;
					}
				}

				// A sequence's frame lengths are in client cycles of 20ms; a game tick is
				// 30 of them. Ticks are what the transport tables are written in.
				System.out.println("  " + pad(wanted[0], 26) + pad(id, 8) + pad(frames, 8)
					+ pad(cycles, 8) + pad(String.format("%.1f", cycles / 30.0), 7)
					+ (seq.maxLoops == -1 ? "-" : String.valueOf(seq.maxLoops)));
			}
		}
	}

	private static Map<Integer, SequenceDefinition> load(Store store) throws Exception
	{
		Storage storage = store.getStorage();
		Index index = store.getIndex(IndexType.CONFIGS);
		Archive archive = index.getArchive(ConfigType.SEQUENCE.getId());
		ArchiveFiles files = archive.getFiles(storage.loadArchive(archive));

		SequenceLoader loader = new SequenceLoader();
		Map<Integer, SequenceDefinition> out = new LinkedHashMap<>();
		for (FSFile f : files.getFiles())
		{
			out.put(f.getFileId(), loader.load(f.getFileId(), f.getContents()));
		}
		return out;
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
