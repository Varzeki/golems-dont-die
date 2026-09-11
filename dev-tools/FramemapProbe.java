import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.NpcManager;
import net.runelite.cache.definitions.FramemapDefinition;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.definitions.loaders.FramemapLoader;
import net.runelite.cache.definitions.loaders.ModelLoader;
import net.runelite.cache.definitions.loaders.SequenceLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;

/**
 * Phase 01's gate: can the golem play the game's own animations?
 *
 * <p>An OSRS model carries a rig — {@code packedVertexGroups}, one group label per
 * vertex. An animation's frames belong to a framemap, which is a list of transforms
 * addressed <em>by group number</em>. Play a frame built for framemap A against a model
 * rigged for framemap B and every transform lands on the wrong vertices: the golem folds
 * through itself. There is no partial credit and no retargeting at runtime. The framemap
 * ids match or they do not.
 *
 * <p>That single boolean decides the whole animation budget for this project. If the
 * golem shares a framemap with the human animations, the catalogue is a lookup table
 * wired through {@code GolemModelFactory.animationFor(int)} and costs essentially
 * nothing. If it does not, every clip has to be authored against the golem's own rig and
 * applied by writing vertex floats — which also breaks the shared model cache and forces
 * a re-light per frame.
 *
 * <p>So rather than test the three animations the plan names and stop, this walks every
 * sequence in the cache and groups them by framemap. That answers the yes/no question
 * and, in the same pass, produces the list of <em>every</em> animation the golem can
 * play — which is the catalogue, measured rather than guessed.
 *
 * <pre>
 * javac -cp cache-jar-with-dependencies.jar -d out dev-tools/FramemapProbe.java
 * java  -cp "cache-jar-with-dependencies.jar;out" FramemapProbe &lt;cache-dir&gt;
 * </pre>
 */
public class FramemapProbe
{
	/** The golem. Harvested in the first recon pass; see {@code GolemContent}. */
	private static final int GOLEM_NPC_ID = 16304;

	/** The golem's own sequences, in the order they matter. */
	private static final int[] GOLEM_SEQUENCES = {14451, 14452, 14453, 14455};
	private static final String[] GOLEM_SEQUENCE_NAMES = {"spawn", "walk", "idle", "death"};

	/**
	 * The catalogue from the handover, by gameval name.
	 *
	 * <p>Names rather than ids: the ids are what this tool is for. A name that does not
	 * resolve is reported rather than skipped — a typo in the plan and a genuinely absent
	 * animation look identical otherwise, and only one of them is worth chasing.
	 */
	private static final String[][] CATALOGUE = {
		{"1 ladder grab", "HUMAN_REACHFORLADDER", "HUMAN_REACHFORLADDERTOP"},
		{"2 sustained climb", "HUMAN_CLIMBING_READY", "HUMAN_CLIMBING_LOOP", "HUMAN_CLIMBING_MERGE"},
		{"3 ditch vault", "WILD_DITCH_JUMP"},
		{"4 gap jump", "HUMAN_STEPPINGSTONEJUMP", "HUMAN_JUMP_STONES", "HUMAN_LONGJUMP"},
		{"5 gangplank", "DOCK_GANGPLANK01"},
		{"6 climb-over", "HUMAN_MYQ6_AGILITY_STILE", "AGILITY_SHORTCUT_WALL_JUMP"},
		{"7 squeeze", "HUMAN_PIPESQUEEZE_READY", "HUMAN_PIPESQUEEZE", "HUMAN_PIPEUNSQUEEZE"},
		{"8 balance walk", "HUMAN_WALK_LOGBALANCE", "HUMAN_WALK_LOGBALANCE_LOOP", "MYQ3_HUMAN_TIGHTROPE"},
		{"9 helm grip", "SAILING_ALPHA_HELM_RAFT01_ACTIVE01", "SAILING_ALPHA_HELM_RAFT01_ACTIVE01_LOOP"},
	};

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

			Map<Integer, String> animNames = loadAnimationNames();
			Map<Integer, SequenceDefinition> sequences = loadSequences(store);
			Map<Integer, Integer> sequenceFramemaps = mapSequencesToFramemaps(store, sequences);

			int golemFramemap = reportGolemRig(store, sequenceFramemaps, animNames);
			reportCompatibleSequences(sequenceFramemaps, animNames, golemFramemap);
			reportCatalogue(sequences, sequenceFramemaps, animNames, golemFramemap);
		}
	}

	// ---------------------------------------------------------------- the golem

	/**
	 * The golem's model rig and the framemap its own animations use.
	 *
	 * @return the framemap id shared by the golem's sequences, or -1 if they disagree
	 */
	private static int reportGolemRig(Store store, Map<Integer, Integer> sequenceFramemaps,
		Map<Integer, String> animNames) throws Exception
	{
		System.out.println("=== Golem rig (NPC " + GOLEM_NPC_ID + ") ===");

		NpcManager npcs = new NpcManager(store);
		npcs.load();
		NpcDefinition golem = npcs.get(GOLEM_NPC_ID);
		if (golem == null)
		{
			System.out.println("  NPC not found — wrong cache?");
			return -1;
		}

		System.out.println("  name: " + golem.name);
		System.out.println("  models: " + Arrays.toString(golem.models));

		// NPC 16304 is a transform parent: it carries the name and the animations but no
		// geometry, and the client swaps in a child NPC chosen by a varbit. That is why
		// the plugin reads getTransformedComposition() at runtime. Offline the same hop
		// has to be made by hand, or the rig check has nothing to look at.
		List<Integer> modelIds = new ArrayList<>();
		for (int id : golem.models == null ? new int[0] : golem.models)
		{
			modelIds.add(id);
		}

		if (modelIds.isEmpty() && golem.configs != null)
		{
			System.out.println("  transform: varbit " + golem.varbitId
				+ ", varp " + golem.varpIndex
				+ ", children " + Arrays.toString(golem.configs));

			for (int childId : golem.configs)
			{
				if (childId == -1 || childId == 65535)
				{
					continue;
				}
				NpcDefinition child = npcs.get(childId);
				if (child == null || child.models == null || child.models.length == 0)
				{
					continue;
				}
				System.out.println("    child " + childId + " (" + child.name + "): "
					+ Arrays.toString(child.models)
					+ "  stand=" + child.standingAnimation
					+ " walk=" + child.walkingAnimation
					+ " ambient=" + child.ambient
					+ " contrast=" + child.contrast);
				for (int id : child.models)
				{
					if (!modelIds.contains(id))
					{
						modelIds.add(id);
					}
				}
			}
		}

		System.out.println();
		System.out.println("  model     vertices  groups  group range  maya");

		ModelLoader modelLoader = new ModelLoader();
		Index modelIndex = store.getIndex(IndexType.MODELS);
		Storage storage = store.getStorage();

		for (int modelId : modelIds)
		{
			Archive archive = modelIndex.getArchive(modelId);
			if (archive == null)
			{
				System.out.println("  " + pad(modelId, 10) + "MISSING");
				continue;
			}
			byte[] data = storage.loadArchive(archive);
			ModelDefinition model = modelLoader.load(modelId, archive.decompress(data));

			int[] groups = model.packedVertexGroups;
			int distinct = 0;
			int min = Integer.MAX_VALUE;
			int max = Integer.MIN_VALUE;
			if (groups != null)
			{
				boolean[] seen = new boolean[256];
				for (int g : groups)
				{
					int id = g & 0xff;
					if (!seen[id])
					{
						seen[id] = true;
						distinct++;
					}
					min = Math.min(min, id);
					max = Math.max(max, id);
				}
			}

			boolean maya = model.animayaGroups != null && model.animayaGroups.length > 0;
			System.out.println("  " + pad(modelId, 10) + pad(model.vertexCount, 10)
				+ pad(groups == null ? 0 : distinct, 8)
				+ pad(groups == null ? "none" : min + ".." + max, 13)
				+ (maya ? "yes" : "no"));
		}

		System.out.println();
		System.out.println("  sequence          framemap  gameval name");
		int framemap = -1;
		boolean consistent = true;
		for (int i = 0; i < GOLEM_SEQUENCES.length; i++)
		{
			int seq = GOLEM_SEQUENCES[i];
			Integer fm = sequenceFramemaps.get(seq);
			System.out.println("  " + pad(seq + " (" + GOLEM_SEQUENCE_NAMES[i] + ")", 18)
				+ pad(fm == null ? "?" : fm.toString(), 10)
				+ animNames.getOrDefault(seq, ""));
			if (fm == null)
			{
				continue;
			}
			if (framemap == -1)
			{
				framemap = fm;
			}
			else if (framemap != fm)
			{
				consistent = false;
			}
		}

		if (!consistent)
		{
			System.out.println();
			System.out.println("  !! the golem's own sequences do not share a framemap.");
			System.out.println("     Compatibility must then be judged per sequence, not per rig.");
		}

		FramemapDefinition def = loadFramemap(store, framemap);
		if (def != null)
		{
			System.out.println();
			System.out.println("  framemap " + framemap + ": " + def.length + " transforms");
		}

		System.out.println();
		return consistent ? framemap : -1;
	}

	// ------------------------------------------------------- what else fits it

	/** Every other sequence built on the golem's framemap — the true catalogue. */
	private static void reportCompatibleSequences(Map<Integer, Integer> sequenceFramemaps,
		Map<Integer, String> animNames, int golemFramemap)
	{
		System.out.println("=== Framemap population ===");

		Map<Integer, List<Integer>> byFramemap = new TreeMap<>();
		for (Map.Entry<Integer, Integer> e : sequenceFramemaps.entrySet())
		{
			byFramemap.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
		}

		System.out.println("  " + sequenceFramemaps.size() + " sequences across "
			+ byFramemap.size() + " framemaps");

		// Biggest framemaps first. The human rig is almost certainly the largest, and
		// seeing its size next to the golem's is the quickest read on whether the golem is
		// a human-rigged NPC or something bespoke.
		System.out.println();
		System.out.println("  largest framemaps:");
		byFramemap.entrySet().stream()
			.sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
			.limit(10)
			.forEach(e -> System.out.println("    framemap " + pad(e.getKey(), 8)
				+ pad(e.getValue().size(), 8) + "sequences"
				+ (e.getKey() == golemFramemap ? "   <-- GOLEM" : "")));

		if (golemFramemap < 0)
		{
			System.out.println();
			System.out.println("  (no single golem framemap; skipping the compatible list)");
			System.out.println();
			return;
		}

		List<Integer> compatible = byFramemap.getOrDefault(golemFramemap, new ArrayList<>());
		System.out.println();
		System.out.println("  " + compatible.size() + " sequences share the golem's framemap ("
			+ golemFramemap + ").");
		System.out.println();

		// Named ones only. An unnamed sequence is just as playable but unidentifiable, and
		// several hundred bare ids is not a list anyone can act on.
		int named = 0;
		for (int seq : compatible)
		{
			String name = animNames.get(seq);
			if (name != null)
			{
				System.out.println("    " + pad(seq, 8) + name);
				named++;
			}
		}
		System.out.println();
		System.out.println("  (" + named + " named, " + (compatible.size() - named) + " unnamed)");
		System.out.println();
	}

	// ------------------------------------------------------------- the verdict

	/** The handover's catalogue, each entry resolved and judged. */
	private static void reportCatalogue(Map<Integer, SequenceDefinition> sequences,
		Map<Integer, Integer> sequenceFramemaps, Map<Integer, String> animNames, int golemFramemap)
	{
		System.out.println("=== Catalogue verdict ===");
		System.out.println("  golem framemap = " + golemFramemap);
		System.out.println();

		// Built from reflection rather than inverting animNames: an id can carry several
		// names, and inverting a id-to-name map silently drops every alias but one — which
		// is exactly how a catalogue entry comes back NOT FOUND when it is really present.
		Map<String, Integer> byName = loadAnimationIds();

		int ok = 0;
		int bad = 0;
		int missing = 0;

		for (String[] set : CATALOGUE)
		{
			System.out.println("  " + set[0]);
			for (int i = 1; i < set.length; i++)
			{
				String name = set[i];
				Integer id = byName.get(name);
				if (id == null)
				{
					System.out.println("    " + pad(name, 44) + "NOT FOUND");
					suggest(byName, name);
					missing++;
					continue;
				}

				Integer fm = sequenceFramemaps.get(id);
				SequenceDefinition seq = sequences.get(id);
				String maya = seq != null && seq.animMayaID != -1
					? "  maya=" + seq.animMayaID : "";
				boolean compatible = fm != null && fm == golemFramemap;
				System.out.println("    " + pad(name, 44) + pad(id, 8)
					+ "framemap " + pad(fm == null ? "?" : fm.toString(), 8)
					+ (compatible ? "COMPATIBLE" : "incompatible") + maya);

				if (compatible)
				{
					ok++;
				}
				else
				{
					bad++;
				}
			}
		}

		System.out.println();
		System.out.println("  " + ok + " compatible, " + bad + " incompatible, "
			+ missing + " unresolved");
		System.out.println();
		System.out.println(ok > 0 && bad == 0
			? "  => BRANCH A. Wire the ids through GolemModelFactory.animationFor()."
			: ok == 0
				? "  => BRANCH B. The golem does not share the human rig; clips must be authored."
				: "  => MIXED. Use cache animations where compatible, author the rest.");
	}

	/** Near-miss names, so a rename in the cache is obvious rather than a dead end. */
	private static void suggest(Map<String, Integer> byName, String name)
	{
		String stem = name.split("_")[0];
		if (stem.length() < 4)
		{
			return;
		}
		int shown = 0;
		for (Map.Entry<String, Integer> e : new TreeMap<>(byName).entrySet())
		{
			if (e.getKey().startsWith(stem) && shown < 6)
			{
				System.out.println("        near: " + e.getKey() + " = " + e.getValue());
				shown++;
			}
		}
	}

	// ----------------------------------------------------------------- loading

	/** Every sequence in the cache, by id. */
	private static Map<Integer, SequenceDefinition> loadSequences(Store store) throws Exception
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

	/**
	 * Sequence id to framemap id.
	 *
	 * <p>A sequence names its frames as packed ints: the top 16 bits are the archive in
	 * the animation index, the low 16 the file within it. The framemap the frame was built
	 * against is the first two bytes of that file — so the mapping is readable without
	 * decoding a single frame.
	 *
	 * <p>The first frame is enough. A sequence whose frames spanned framemaps could not be
	 * played by the client at all.
	 */
	private static Map<Integer, Integer> mapSequencesToFramemaps(Store store,
		Map<Integer, SequenceDefinition> sequences) throws Exception
	{
		Storage storage = store.getStorage();
		Index frames = store.getIndex(IndexType.ANIMATIONS);

		// One archive holds many frames, and many sequences draw on the same archive.
		Map<Integer, Map<Integer, byte[]>> archiveCache = new HashMap<>();
		Map<Integer, Integer> out = new LinkedHashMap<>();

		for (Map.Entry<Integer, SequenceDefinition> e : sequences.entrySet())
		{
			SequenceDefinition seq = e.getValue();
			if (seq.frameIDs == null || seq.frameIDs.length == 0)
			{
				continue;
			}

			int packed = seq.frameIDs[0];
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
						for (FSFile f : loaded.getFiles())
						{
							archiveFiles.put(f.getFileId(), f.getContents());
						}
					}
					catch (Exception ignored)
					{
						// An archive that will not decompress tells us nothing about the
						// golem; the sequences it holds simply go unmapped.
					}
				}
				archiveCache.put(archiveId, archiveFiles);
			}

			byte[] frame = archiveFiles.get(fileId);
			if (frame == null || frame.length < 2)
			{
				continue;
			}
			out.put(e.getKey(), ((frame[0] & 0xff) << 8) | (frame[1] & 0xff));
		}
		return out;
	}

	private static FramemapDefinition loadFramemap(Store store, int id)
	{
		if (id < 0)
		{
			return null;
		}
		try
		{
			Index index = store.getIndex(IndexType.SKELETONS);
			Archive archive = index.getArchive(id);
			if (archive == null)
			{
				return null;
			}
			byte[] data = store.getStorage().loadArchive(archive);
			return new FramemapLoader().load(id, archive.decompress(data));
		}
		catch (Exception e)
		{
			return null;
		}
	}

	/**
	 * Animation names, so ids can be reported as the names the plan uses.
	 *
	 * <p>Read from the RuneLite API's generated {@code gameval.AnimationID} rather than
	 * the cache's own GAMEVALS index: that index is absent from a client-downloaded cache
	 * (only Jagex's full distribution carries it), and the API class is generated from the
	 * same source, ships with the client this plugin builds against, and is therefore the
	 * copy that will still be there on someone else's machine.
	 *
	 * <p>Reflection rather than a direct reference so the probe still compiles and runs
	 * with only the cache jar on the classpath — it simply reports no names.
	 */
	private static Map<Integer, String> loadAnimationNames()
	{
		Map<Integer, String> out = new HashMap<>();
		try
		{
			Class<?> ids = Class.forName("net.runelite.api.gameval.AnimationID");
			for (java.lang.reflect.Field f : ids.getDeclaredFields())
			{
				if (f.getType() == int.class
					&& java.lang.reflect.Modifier.isStatic(f.getModifiers()))
				{
					f.setAccessible(true);
					// Last name wins is wrong here — aliases exist and the first, shortest
					// name is the canonical one. Keep whichever we saw first.
					out.putIfAbsent(f.getInt(null), f.getName());
				}
			}
		}
		catch (ClassNotFoundException e)
		{
			System.out.println("  (no runelite-api on the classpath; ids will be unnamed)");
		}
		catch (Exception e)
		{
			System.out.println("  (animation names unavailable: " + e + ")");
		}
		return out;
	}

	/** Name to id, keeping every alias — the lookup the catalogue needs. */
	private static Map<String, Integer> loadAnimationIds()
	{
		Map<String, Integer> out = new HashMap<>();
		try
		{
			Class<?> ids = Class.forName("net.runelite.api.gameval.AnimationID");
			for (java.lang.reflect.Field f : ids.getDeclaredFields())
			{
				if (f.getType() == int.class
					&& java.lang.reflect.Modifier.isStatic(f.getModifiers()))
				{
					f.setAccessible(true);
					out.put(f.getName(), f.getInt(null));
				}
			}
		}
		catch (Exception ignored)
		{
			// Already reported by loadAnimationNames.
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
