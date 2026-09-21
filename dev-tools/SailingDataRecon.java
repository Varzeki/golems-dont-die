import java.io.File;
import java.util.Arrays;
import java.util.TreeMap;
import net.runelite.cache.DBRowManager;
import net.runelite.cache.definitions.DBRowDefinition;
import net.runelite.cache.fs.Store;

/**
 * Sailing's boat tables, row by row: what the game stores about boat speed and steering.
 *
 * <p>RuneLite names the tables and rows but not their columns, so every column of every row is
 * printed as it decodes. Used to find how fast boats go and how quickly they turn.
 *
 * <pre>
 * javac -cp cache.jar -d out dev-tools/SailingDataRecon.java
 * java  -cp "cache.jar;deps;out" SailingDataRecon [cache-dir]
 * </pre>
 */
public class SailingDataRecon
{
	/** Rows named in RuneLite: raft base stats (wood..), raft mast stats, NPC boat base stats and steering. */
	private static final int[] ROWS = {8161, 8162, 8167, 8182, 16317, 16318, 16321, 16322};

	public static void main(String[] args) throws Exception
	{
		File cacheDir = new File(args.length > 0 ? args[0]
			: System.getProperty("user.home") + "/.runelite/jagexcache/oldschool/LIVE");
		try (Store store = new Store(cacheDir))
		{
			store.load();
			DBRowManager rows = new DBRowManager(store);
			rows.load();
			TreeMap<Integer, Integer> tableOf = new TreeMap<>();
			for (int id : ROWS)
			{
				DBRowDefinition row = rows.get(id);
				if (row == null)
				{
					System.out.println("row " + id + " missing");
					continue;
				}
				tableOf.put(id, row.getTableId());
				System.out.println("row " + id + " table " + row.getTableId());
				Object[][] columns = row.getColumnValues();
				for (int c = 0; columns != null && c < columns.length; c++)
				{
					if (columns[c] != null)
					{
						System.out.println("    col " + c + ": " + Arrays.deepToString(columns[c]));
					}
				}
			}
			// Every row sharing a table with the raft's base stats, to see all hull sizes side by side.
			int raftTable = tableOf.getOrDefault(8161, -1);
			int steeringTable = tableOf.getOrDefault(16321, -1);
			System.out.println("=== all rows of table " + raftTable + " (boat base stats) and " + steeringTable + " (NPC steering)");
			for (DBRowDefinition row : rows.getRows())
			{
				if (row.getTableId() == raftTable || row.getTableId() == steeringTable)
				{
					StringBuilder sb = new StringBuilder("  row " + row.getId() + " t" + row.getTableId() + ":");
					Object[][] columns = row.getColumnValues();
					for (int c = 0; columns != null && c < columns.length; c++)
					{
						if (columns[c] != null)
						{
							sb.append(" [").append(c).append("]=").append(Arrays.deepToString(columns[c]));
						}
					}
					System.out.println(sb);
				}
			}
		}
	}
}
