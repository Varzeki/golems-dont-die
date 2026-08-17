import java.io.File;
import java.util.List;
import java.util.Map;
import net.runelite.cache.EntityOpsDefinition;
import net.runelite.cache.NpcManager;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.fs.Store;

/**
 * Dumps every right-click option and parameter on the golem.
 *
 * <p>The first recon pass reported the golem as having no options at all, which the
 * game contradicts. The reason is that newer caches carry four kinds of op —
 * unconditional, sub, conditional, and conditional-sub — and only the first was being
 * read. A conditional op is one the client shows or hides based on a varbit or varp,
 * which is exactly how an option that only appears for the player who made the golem
 * would be authored.
 *
 * <p>Examine text is looked for in the definition's params, where per-entity strings
 * live.
 *
 * <pre>
 * java GolemOps &lt;cache-dir&gt; [npcId...]
 * </pre>
 */
public class GolemOps
{
	private static final int DEFAULT_NPC = 16304;

	public static void main(String[] args) throws Exception
	{
		File cacheDir = new File(args.length > 0
			? args[0]
			: System.getProperty("user.home") + "/.runelite/jagexcache/oldschool/LIVE");

		int[] ids;
		if (args.length > 1)
		{
			ids = new int[args.length - 1];
			for (int i = 1; i < args.length; i++)
			{
				ids[i - 1] = Integer.parseInt(args[i]);
			}
		}
		else
		{
			// The crafted golem, plus the two static ones nearby for comparison.
			ids = new int[]{DEFAULT_NPC, 16321, 16322};
		}

		try (Store store = new Store(cacheDir))
		{
			store.load();
			NpcManager npcs = new NpcManager(store);
			npcs.load();

			for (int id : ids)
			{
				NpcDefinition npc = npcs.get(id);
				if (npc == null)
				{
					System.out.println("npc " + id + ": not in cache");
					continue;
				}
				dump(npc);
			}
		}
	}

	private static void dump(NpcDefinition npc)
	{
		System.out.println();
		System.out.println("=== npc " + npc.id + "  \"" + npc.name + "\" ===");

		EntityOpsDefinition ops = npc.ops;
		if (ops == null)
		{
			System.out.println("  (no ops object at all)");
		}
		else
		{
			dumpOps(ops);
		}

		System.out.println("  params:");
		Map<Integer, Object> params = npc.params;
		if (params == null || params.isEmpty())
		{
			System.out.println("    (none)");
		}
		else
		{
			for (Map.Entry<Integer, Object> entry : params.entrySet())
			{
				System.out.printf("    %-8d %s%n", entry.getKey(), entry.getValue());
			}
		}

		System.out.println("  category=" + npc.category
			+ " interactable=" + npc.isInteractable
			+ " minimapVisible=" + npc.isMinimapVisible
			+ " combatLevel=" + npc.combatLevel);
	}

	private static void dumpOps(EntityOpsDefinition ops)
	{
		System.out.println("  unconditional ops:");
		List<EntityOpsDefinition.Op> plain = ops.getOps();
		if (plain == null || plain.isEmpty())
		{
			System.out.println("    (none)");
		}
		else
		{
			for (int i = 0; i < plain.size(); i++)
			{
				EntityOpsDefinition.Op op = plain.get(i);
				System.out.printf("    slot %d: %s%n", i, op == null ? "-" : quote(op.text));
			}
		}

		System.out.println("  conditional ops:");
		List<List<EntityOpsDefinition.ConditionalOp>> conditional = ops.getConditionalOps();
		boolean anyConditional = false;
		if (conditional != null)
		{
			for (int slot = 0; slot < conditional.size(); slot++)
			{
				List<EntityOpsDefinition.ConditionalOp> forSlot = conditional.get(slot);
				if (forSlot == null)
				{
					continue;
				}
				for (EntityOpsDefinition.ConditionalOp op : forSlot)
				{
					if (op == null)
					{
						continue;
					}
					anyConditional = true;
					System.out.printf("    slot %d: %-24s when varbit=%d varp=%d in [%d..%d]%n",
						slot, quote(op.text), op.varbitID, op.varpID, op.minValue, op.maxValue);
				}
			}
		}
		if (!anyConditional)
		{
			System.out.println("    (none)");
		}

		System.out.println("  sub ops:");
		List<List<EntityOpsDefinition.SubOp>> subs = ops.getSubOps();
		boolean anySub = false;
		if (subs != null)
		{
			for (int slot = 0; slot < subs.size(); slot++)
			{
				List<EntityOpsDefinition.SubOp> forSlot = subs.get(slot);
				if (forSlot == null)
				{
					continue;
				}
				for (EntityOpsDefinition.SubOp op : forSlot)
				{
					if (op == null)
					{
						continue;
					}
					anySub = true;
					System.out.printf("    slot %d sub %d: %s%n", slot, op.subID, quote(op.text));
				}
			}
		}
		if (!anySub)
		{
			System.out.println("    (none)");
		}
	}

	private static String quote(String text)
	{
		return text == null ? "null" : '"' + text + '"';
	}
}
