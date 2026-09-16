import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Set;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.ScriptDefinition;
import net.runelite.cache.definitions.VarbitDefinition;
import net.runelite.cache.definitions.loaders.ScriptLoader;
import net.runelite.cache.definitions.loaders.VarbitLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;
import net.runelite.cache.script.Opcodes;

/**
 * Offline reconnaissance on Golem Crafting's game variables.
 *
 * <p>Run once, by hand, to establish what the activity's varbits are and what each one
 * is for, so {@code GOLEM_COUNT_VARBIT} can be hardcoded with the same confidence as
 * the NPC and animation IDs. Nothing here ships.
 *
 * <p>The cache does not name varbits, so a name alone proves nothing — RuneLite's
 * generated {@code VarbitID} calls 15738 {@code GOLEM_CRAFTING_COUNT}, and this is what
 * checks that the name means what it says. Three things are worth reading together:
 * <ul>
 *   <li><b>Width.</b> A varbit's bit range is the tightest statement of intent in the
 *       cache. Two plinth stations need two bits; sixteen is a tally.</li>
 *   <li><b>Neighbours.</b> Everything packed into the same varp belongs to the same
 *       feature, so the low half of varp 5709 being carving state is what identifies
 *       the high half as this activity's count rather than something else that
 *       counts.</li>
 *   <li><b>Transforms.</b> The locs that change appearance on a varbit say what the
 *       player sees it do — the plinth's carving stages, and the debris marking a side
 *       already carved.</li>
 * </ul>
 *
 * <p>The script scan closes it off: no client script reads any of these, so the count
 * is server-set and arrives with the varps. That is the property the tally needs, since
 * a golem crafted on another device has to be countable at login without the player
 * doing anything to provoke a message.
 *
 * <pre>
 * javac -cp cache-jar-with-dependencies.jar VarbitRecon.java
 * java  -cp cache-jar-with-dependencies.jar;. VarbitRecon &lt;cache-dir&gt;
 * </pre>
 */
public class VarbitRecon
{
	/** The Golem Crafting block, as named in RuneLite's generated {@code VarbitID}. */
	private static final int FIRST_VARBIT = 15728;
	private static final int LAST_VARBIT = 15746;

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

			Map<Integer, VarbitDefinition> byId = new TreeMap<>();
			Map<Integer, List<VarbitDefinition>> byVarp = new TreeMap<>();
			loadVarbits(store, byId, byVarp);

			Set<Integer> varps = dumpBlock(byId);
			dumpVarps(byVarp, varps);
			dumpTransforms(store, varps);
			dumpScriptReaders(store);
		}
	}

	/** Every varbit in the cache, by id and grouped by the varp it is packed into. */
	private static void loadVarbits(Store store,
		Map<Integer, VarbitDefinition> byId,
		Map<Integer, List<VarbitDefinition>> byVarp) throws Exception
	{
		Storage storage = store.getStorage();
		Index index = store.getIndex(IndexType.CONFIGS);
		Archive archive = index.getArchive(ConfigType.VARBIT.getId());
		ArchiveFiles files = archive.getFiles(storage.loadArchive(archive));

		VarbitLoader loader = new VarbitLoader();
		for (FSFile file : files.getFiles())
		{
			VarbitDefinition varbit = loader.load(file.getFileId(), file.getContents());
			byId.put(varbit.getId(), varbit);
			byVarp.computeIfAbsent(varbit.getIndex(), k -> new ArrayList<>()).add(varbit);
		}
		System.out.printf("Varbits in cache: %d%n%n", byId.size());
	}

	/** The block itself. Returns the varps it is packed into. */
	private static Set<Integer> dumpBlock(Map<Integer, VarbitDefinition> byId)
	{
		System.out.printf("== Golem Crafting varbits %d..%d ==%n", FIRST_VARBIT, LAST_VARBIT);
		Set<Integer> varps = new TreeSet<>();
		for (int id = FIRST_VARBIT; id <= LAST_VARBIT; id++)
		{
			VarbitDefinition varbit = byId.get(id);
			if (varbit == null)
			{
				System.out.printf("  varbit %-6d absent from this cache%n", id);
				continue;
			}
			print(varbit);
			varps.add(varbit.getIndex());
		}
		System.out.println();
		return varps;
	}

	/** Everything sharing those varps, in bit order — the feature's whole footprint. */
	private static void dumpVarps(Map<Integer, List<VarbitDefinition>> byVarp, Set<Integer> varps)
	{
		for (int varp : varps)
		{
			List<VarbitDefinition> packed = new ArrayList<>(byVarp.getOrDefault(varp, List.of()));
			packed.sort(Comparator.comparingInt(VarbitDefinition::getLeastSignificantBit));
			System.out.printf("== varp %d holds %d varbit(s) ==%n", varp, packed.size());
			for (VarbitDefinition varbit : packed)
			{
				print(varbit);
			}
			System.out.println();
		}
	}

	/** Locs that change appearance on one of these varbits, and what they change to. */
	private static void dumpTransforms(Store store, Set<Integer> varps) throws Exception
	{
		System.out.println("== locs transformed by a Golem Crafting varbit ==");
		ObjectManager objects = new ObjectManager(store);
		objects.load();
		for (ObjectDefinition object : objects.getObjects())
		{
			boolean byVarbit = object.getVarbitID() >= FIRST_VARBIT && object.getVarbitID() <= LAST_VARBIT;
			if (!byVarbit && !varps.contains(object.getVarpID()))
			{
				continue;
			}
			System.out.printf("  loc %-6d %-22s varbit=%-6d varp=%-5d impostors=%s%n",
				object.getId(), quote(object.getName()),
				object.getVarbitID(), object.getVarpID(),
				java.util.Arrays.toString(object.getConfigChangeDest()));
		}
		System.out.println();
	}

	/**
	 * Client scripts that read any of them.
	 *
	 * <p>Expected to find nothing, which is the point: a varbit no script reads is not
	 * driving an interface, so its value is whatever the server last sent.
	 */
	private static void dumpScriptReaders(Store store) throws Exception
	{
		System.out.println("== client scripts reading a Golem Crafting varbit ==");

		Map<Integer, String> opcodeNames = new HashMap<>();
		for (Field field : Opcodes.class.getDeclaredFields())
		{
			if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class)
			{
				field.setAccessible(true);
				opcodeNames.putIfAbsent(field.getInt(null), field.getName());
			}
		}

		Storage storage = store.getStorage();
		Index index = store.getIndex(IndexType.CLIENTSCRIPT);
		ScriptLoader loader = new ScriptLoader();
		int scanned = 0;
		int hits = 0;

		for (Archive archive : index.getArchives())
		{
			ScriptDefinition script;
			try
			{
				byte[] contents = archive.decompress(storage.loadArchive(archive));
				if (contents == null)
				{
					continue;
				}
				script = loader.load(archive.getArchiveId(), contents);
			}
			catch (Exception e)
			{
				// A script this tool cannot read is not a script that reads our varbit.
				continue;
			}
			scanned++;

			int[] instructions = script.getInstructions();
			int[] operands = script.getIntOperands();
			if (instructions == null || operands == null)
			{
				continue;
			}

			for (int i = 0; i < operands.length; i++)
			{
				if (operands[i] < FIRST_VARBIT || operands[i] > LAST_VARBIT)
				{
					continue;
				}
				hits++;
				System.out.printf("  script %d instruction %d: %s %d%n",
					script.getId(), i,
					opcodeNames.getOrDefault(instructions[i], "op" + instructions[i]),
					operands[i]);
			}
		}

		System.out.printf("  %d script(s) scanned, %d reference(s) found%n", scanned, hits);
	}

	private static void print(VarbitDefinition varbit)
	{
		int width = varbit.getMostSignificantBit() - varbit.getLeastSignificantBit() + 1;
		System.out.printf("  varbit %-6d varp=%-5d bits %2d..%-2d width=%-2d max=%d%n",
			varbit.getId(), varbit.getIndex(),
			varbit.getLeastSignificantBit(), varbit.getMostSignificantBit(),
			width, (1L << width) - 1);
	}

	private static String quote(String name)
	{
		return name == null || name.equals("null") ? "(unnamed)" : '"' + name + '"';
	}
}
