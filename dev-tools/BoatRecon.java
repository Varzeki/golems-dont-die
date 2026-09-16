import java.io.File;
import java.util.Arrays;
import net.runelite.cache.NpcManager;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.fs.Store;

/**
 * Finds a Sailing vessel NPC whose model can be drawn under a golem at sea.
 *
 * <p>{@code RaftFactory} looked for one at runtime by reading every integer in the boat
 * table as an NPC id and keeping any whose name contained "boat", "ship" and the like. It
 * never found one in any session. This lists what is actually there: the NPCs RuneLite's
 * generated ids call boats, and every NPC with models whose name reads like a vessel.
 *
 * <pre>
 * javac -cp cache.jar -d out dev-tools/BoatRecon.java
 * java  -cp "cache.jar;deps;out" BoatRecon [cache-dir]
 * </pre>
 */
public class BoatRecon
{
	/** Named as boats by RuneLite's generated NpcID: SAILING_BOAT_SAIL01_*, the NPC boats, the intro boats. */
	private static final int[] NAMED_BOATS = {15241, 15242, 15243, 15442, 15443, 15444, 15445,
		15446, 15447, 15448, 15449, 14958, 14963};

	private static final String[] WORDS = {"boat", "raft", "ship", "vessel", "dinghy", "sloop", "skiff",
		"cog", "barge", "cutter", "galleon", "brig", "canoe", "hull"};

	public static void main(String[] args) throws Exception
	{
		File cacheDir = new File(args.length > 0 ? args[0]
			: System.getProperty("user.home") + "/.runelite/jagexcache/oldschool/LIVE");
		try (Store store = new Store(cacheDir))
		{
			store.load();
			NpcManager npcs = new NpcManager(store);
			npcs.load();

			System.out.println("=== NPCs RuneLite names as boats ===");
			for (int id : NAMED_BOATS)
			{
				print(npcs.get(id));
			}

			System.out.println();
			System.out.println("=== NPCs with models named like a vessel ===");
			int hits = 0;
			for (NpcDefinition npc : npcs.getNpcs())
			{
				if (npc.name == null || npc.models == null || npc.models.length == 0)
				{
					continue;
				}
				String name = npc.name.toLowerCase();
				for (String word : WORDS)
				{
					if (name.contains(word))
					{
						print(npc);
						hits++;
						break;
					}
				}
			}
			System.out.println("(" + hits + ")");
		}
	}

	private static void print(NpcDefinition npc)
	{
		if (npc == null)
		{
			System.out.println("  (missing)");
			return;
		}
		System.out.printf("  id=%-6d name=%-24s size=%-2d models=%s stand=%d%n", npc.id, npc.name, npc.size,
			Arrays.toString(npc.models), npc.standingAnimation);
	}
}
