import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

/**
 * Turns Shortest Path's transport tables into the golem-usable subset, and reports what
 * is in it.
 *
 * <p>Shortest Path's tables describe what a <i>player</i> can do: 6,894 rows covering
 * doors, ladders, shortcuts, boats, spells, teleport items and charges. A golem can do
 * much less. It has no inventory, casts nothing, and pays for nothing, so every row
 * gated on an item is out — except the two the plan grants it, coins and a dramen staff,
 * which exist to express "a golem may take a ferry" without modelling a purse.
 *
 * <p>Rows with no Origin are out too, and for the same reason: a row with no origin is a
 * teleport usable from anywhere, which is a thing you do with an item or a spellbook.
 * A golem travels by walking to a place and using what is there.
 *
 * <p>What survives is written as a flat table the plugin reads at startup, and every row
 * carries an <b>archetype</b> — which of the nine animation sets to play. That is decided
 * here, offline, by reading the menu text, because the alternative is string matching on
 * a hot path at runtime for a fact that never changes.
 *
 * <pre>
 * javac -d out dev-tools/BuildTransports.java
 * java  -cp out BuildTransports &lt;transports-dir&gt; [out.gz]
 * </pre>
 */
public class BuildTransports
{
	/** Item ids and named tokens a golem is granted. Everything else disqualifies a row. */
	private static final int COINS = 995;
	private static final int MAX_COINS = 500_000;
	private static final String[] GRANTED_NAMES = {"COINS", "DRAMEN_STAFF", "DRAMEN_BRANCH"};

	/** Files with no Origin column at all — teleports from anywhere. Skipped wholesale. */
	private static final String[] SKIP_FILES = {
		"seasonal_transports.tsv",
		"teleportation_spells.tsv",
		"teleportation_items.tsv",
		"teleportation_minigames.tsv",
		"quetzal_whistle.tsv",
	};

	// Archetypes. Must match GolemTransport.ARCHETYPE_* in the plugin.
	static final int ARCH_NONE = 0;
	static final int ARCH_DOOR = 1;
	static final int ARCH_LADDER = 2;
	static final int ARCH_CLIMB = 3;
	static final int ARCH_DITCH = 4;
	static final int ARCH_JUMP = 5;
	static final int ARCH_GANGPLANK = 6;
	static final int ARCH_CLIMB_OVER = 7;
	static final int ARCH_SQUEEZE = 8;
	static final int ARCH_BALANCE = 9;

	// Appended, never renumbered: these values are a shipped file format, and changing one
	// silently remaps every row already written with it.
	static final int ARCH_STILE = 10;
	static final int ARCH_TIGHTROPE = 11;

	private static final String[] ARCH_NAMES = {
		"none", "door", "ladder", "climb", "ditch", "jump",
		"gangplank", "climb-over", "squeeze", "balance", "stile", "tightrope",
	};

	public static void main(String[] args) throws IOException
	{
		File dir = new File(args.length > 0 ? args[0]
			: "R:/RunelitePluginDevelopment/References/shortest-path/src/main/resources/transports");
		if (!dir.isDirectory())
		{
			System.err.println("usage: BuildTransports <transports-dir> [out.gz]");
			System.err.println("not a directory: " + dir);
			System.exit(1);
		}

		List<Row> all = new ArrayList<>();
		Map<String, int[]> perFile = new TreeMap<>();

		// Local additions are read alongside the upstream tables, in the same format and
		// through the same parser. Wyrmscraig has no rows in any published table — not at
		// the commit this pins, not upstream — so its shortcuts are derived here (see
		// BuildWyrmscraigTransports) and dropped in a directory of our own rather than
		// edited into a vendored reference repo.
		List<File> files = new ArrayList<>();
		collect(dir, files);
		if (args.length > 2)
		{
			collect(new File(args[2]), files);
		}

		for (File f : files)
		{
			int[] counts = new int[3]; // rows with origin, item-free, kept
			if (skip(f.getName()))
			{
				perFile.put(f.getName() + " (skipped)", counts);
				continue;
			}
			read(f, all, counts);
			perFile.put(f.getName(), counts);
		}

		fillMissingDurations(all);
		report(perFile, all);

		if (args.length > 1)
		{
			write(new File(args[1]), all);
		}
	}

	/**
	 * Gives every row a duration, using rows of the same kind that already have one.
	 *
	 * <p>Shortcuts are not interchangeable, and the tables know it: a rock climb is
	 * authored anywhere from four to nine ticks, a stepping stone from two to nine. That
	 * per-shortcut timing is the best answer available and is used wherever it exists.
	 *
	 * <p>Where it does not — 154 agility rows carry no duration at all — the gap is filled
	 * with the median of the same archetype rather than a flat one tick. A default of one
	 * asserts the obstacle is instant, which is how golems ended up scaling cliffs faster
	 * than a player can; the median of comparable shortcuts asserts only that this one is
	 * probably like its neighbours, which is true often enough to look right and is at
	 * least drawn from the data rather than invented.
	 */
	private static void fillMissingDurations(List<Row> rows)
	{
		Map<Integer, List<Integer>> known = new TreeMap<>();
		for (Row row : rows)
		{
			if (row.duration > 0)
			{
				known.computeIfAbsent(row.archetype, k -> new ArrayList<>()).add(row.duration);
			}
		}

		Map<Integer, Integer> median = new TreeMap<>();
		for (Map.Entry<Integer, List<Integer>> e : known.entrySet())
		{
			List<Integer> values = e.getValue();
			values.sort(Integer::compare);
			median.put(e.getKey(), values.get(values.size() / 2));
		}

		int filled = 0;
		for (Row row : rows)
		{
			if (row.duration <= 0)
			{
				row.duration = median.getOrDefault(row.archetype, 1);
				filled++;
			}
		}

		System.out.println();
		System.out.println("=== Durations ===");
		System.out.println("  archetype     median  from rows");
		for (Map.Entry<Integer, Integer> e : median.entrySet())
		{
			System.out.println("  " + pad(ARCH_NAMES[e.getKey()], 14) + pad(e.getValue(), 8)
				+ known.get(e.getKey()).size());
		}
		System.out.println("  " + filled + " rows had none and took their archetype's median");
	}

	/** Adds every .tsv in a directory, sorted, so runs are reproducible. */
	private static void collect(File dir, List<File> into)
	{
		File[] found = dir.listFiles((d, n) -> n.endsWith(".tsv"));
		if (found == null)
		{
			System.err.println("no .tsv files in " + dir);
			return;
		}
		Arrays.sort(found);
		into.addAll(Arrays.asList(found));
	}

	private static boolean skip(String name)
	{
		for (String s : SKIP_FILES)
		{
			if (s.equals(name))
			{
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ reading

	private static void read(File file, List<Row> out, int[] counts) throws IOException
	{
		try (BufferedReader r = new BufferedReader(new FileReader(file)))
		{
			String[] header = null;

			// Permutation rows: origins with no destination pair with destinations that
			// have no origin, which is how fairy rings encode 56 x 56 without 3,136 lines.
			List<String[]> originOnly = new ArrayList<>();
			List<String[]> destOnly = new ArrayList<>();

			String line;
			while ((line = r.readLine()) != null)
			{
				if (line.trim().isEmpty())
				{
					continue;
				}
				if (line.startsWith("#"))
				{
					if (header == null)
					{
						header = line.substring(1).trim().split("\t");
					}
					continue;
				}
				if (header == null)
				{
					header = line.split("\t");
					continue;
				}

				String[] fields = line.split("\t", -1);
				String origin = col(header, fields, "Origin");
				String dest = col(header, fields, "Destination");

				if (origin.isEmpty() && dest.isEmpty())
				{
					continue;
				}
				if (dest.isEmpty())
				{
					originOnly.add(fields);
					continue;
				}
				if (origin.isEmpty())
				{
					destOnly.add(fields);
					continue;
				}

				accept(header, fields, origin, dest, out, counts);
			}

			// Cross-product the permutation halves. The origin row carries the object and
			// its requirements; the destination row carries where it lands.
			for (String[] o : originOnly)
			{
				String origin = col(header, o, "Origin");
				for (String[] d : destOnly)
				{
					accept(header, o, origin, col(header, d, "Destination"), out, counts);
				}
			}
		}
	}

	private static void accept(String[] header, String[] fields, String origin, String dest,
		List<Row> out, int[] counts)
	{
		counts[0]++;

		String items = col(header, fields, "Items");
		if (!itemsAllowed(items))
		{
			return;
		}
		counts[1]++;

		int[] from = coord(origin);
		int[] to = coord(dest);
		if (from == null || to == null)
		{
			return;
		}

		Row row = new Row();
		row.fromX = from[0];
		row.fromY = from[1];
		row.fromZ = from[2];
		row.toX = to[0];
		row.toY = to[1];
		row.toZ = to[2];
		// Zero means "the table does not say". Filled in afterwards from the median of
		// shortcuts of the same kind that do say, rather than defaulting to one tick —
		// a default of one is a claim that the obstacle is instant, and 154 agility rows
		// have no duration at all.
		row.duration = intOr(col(header, fields, "Duration"), 0);
		row.skills = col(header, fields, "Skills");
		row.quests = col(header, fields, "Quests");
		row.varbits = col(header, fields, "Varbits");
		row.varps = col(header, fields, "VarPlayers");
		row.menu = col(header, fields, "menuOption menuTarget objectID");
		row.objectId = objectIdOf(row.menu);
		row.archetype = classify(row.menu);

		out.add(row);
		counts[2]++;
	}

	/**
	 * True if a golem may use a row given its item requirement.
	 *
	 * <p>Empty passes. Otherwise every alternative in the expression is inspected, and the
	 * row is kept if any single alternative is satisfied by the grant — an OR group means
	 * one of them is enough. An AND group has to be satisfied in full.
	 */
	private static boolean itemsAllowed(String items)
	{
		String expr = items.replace(" ", "").toUpperCase().replace("&&", "&").replace("||", "|");
		if (expr.isEmpty())
		{
			return true;
		}
		for (String alternative : expr.split("\\|"))
		{
			boolean all = true;
			for (String term : alternative.split("&"))
			{
				if (!termGranted(term))
				{
					all = false;
					break;
				}
			}
			if (all)
			{
				return true;
			}
		}
		return false;
	}

	private static boolean termGranted(String term)
	{
		if (term.isEmpty())
		{
			return true;
		}
		int eq = term.indexOf('=');
		String name = eq < 0 ? term : term.substring(0, eq);
		int qty = eq < 0 ? 1 : intOr(term.substring(eq + 1), 1);

		for (String granted : GRANTED_NAMES)
		{
			if (granted.equals(name))
			{
				return true;
			}
		}
		try
		{
			return Integer.parseInt(name) == COINS;
		}
		catch (NumberFormatException e)
		{
			return false;
		}
	}

	/**
	 * Which animation set the menu text calls for.
	 *
	 * <p>Ordered most specific first. "Climb-over Stile" has to be caught before the bare
	 * "climb", and a squeeze before a crevice-as-jump, or everything collapses into one
	 * bucket.
	 */
	static int classify(String menu)
	{
		String m = menu.toLowerCase();
		if (m.isEmpty())
		{
			return ARCH_NONE;
		}
		if (m.contains("door") || m.contains("gate") || m.contains("open "))
		{
			return ARCH_DOOR;
		}
		if (m.contains("squeeze") || m.contains("pipe") || m.contains("railing")
			|| m.contains("cart tunnel") || m.contains("crevice") || m.contains("crevasse"))
		{
			// A crevice is squeezed through whichever verb the menu uses for it. The
			// tables spell the same obstacle "Squeeze-through Crevice", "Climb-down
			// Crevice" and "Jump-down Crevice", and without the noun here the last two
			// were being hauled up as a cliff and leapt off as a gap respectively.
			return ARCH_SQUEEZE;
		}
		if (m.contains("stile"))
		{
			// A stile is stepped over deliberately, one leg then the other, where a
			// broken wall is vaulted. Different clips, so different archetypes.
			return ARCH_STILE;
		}
		if (m.contains("obstacle net") || m.contains("broken wall")
			|| m.contains("climb-over") || m.contains("climb over"))
		{
			return ARCH_CLIMB_OVER;
		}
		if (m.contains("wall") && !m.contains("under")
			&& (m.contains("climb") || m.contains("jump")))
		{
			// Getting over a wall, whatever the menu calls it. Only the three obstacles
			// spelled "Climb-over" were reaching the clip above; "Climb Wall", "Jump
			// Wall", "Climb-up Wall" and the rest scattered across climb, jump and
			// balance on the strength of their verb alone.
			//
			// The exclusions are not tidiness. Going *under* a wall is a different motion
			// from going over one, and pushing, searching or grappling a wall are not
			// traversals at all — they fall through to their own archetypes, and
			// "Balance Wall" stays a balance, because walking along the top of a wall is
			// exactly what it sounds like.
			return ARCH_CLIMB_OVER;
		}
		if (m.contains("tightrope"))
		{
			return ARCH_TIGHTROPE;
		}
		if (m.contains("log balance") || m.contains("rope bridge")
			|| m.contains("balance"))
		{
			return ARCH_BALANCE;
		}
		if (m.contains("ditch"))
		{
			return ARCH_DITCH;
		}
		if (m.contains("gangplank") || m.contains("board ") || m.contains("shipplank"))
		{
			return ARCH_GANGPLANK;
		}
		if (m.contains("jump") || m.contains("stepping stone") || m.contains("gap")
			|| m.contains("pillar") || m.contains("leap") || m.contains("floorboard"))
		{
			return ARCH_JUMP;
		}
		if (m.contains("ladder") || m.contains("staircase") || m.contains("stairs")
			|| m.contains("trapdoor") || m.contains("rope"))
		{
			return ARCH_LADDER;
		}
		if (m.contains("climb") || m.contains("rocks") || m.contains("scramble"))
		{
			return ARCH_CLIMB;
		}
		return ARCH_NONE;
	}

	// ----------------------------------------------------------------- reporting

	private static void report(Map<String, int[]> perFile, List<Row> all)
	{
		System.out.println("=== Per file ===");
		System.out.println("  file                              with-origin  item-ok     kept");
		int totalOrigin = 0;
		int totalItemOk = 0;
		for (Map.Entry<String, int[]> e : perFile.entrySet())
		{
			int[] c = e.getValue();
			System.out.println("  " + pad(e.getKey(), 34) + pad(c[0], 13) + pad(c[1], 11) + c[2]);
			totalOrigin += c[0];
			totalItemOk += c[1];
		}
		System.out.println();
		System.out.println("  rows with an origin : " + totalOrigin);
		System.out.println("  usable by a golem   : " + totalItemOk);
		System.out.println("  kept (parsed ok)    : " + all.size());

		System.out.println();
		System.out.println("=== Archetypes ===");
		Map<Integer, Integer> byArch = new TreeMap<>();
		int planeChanging = 0;
		Map<Integer, Integer> distances = new LinkedHashMap<>();
		for (Row r : all)
		{
			byArch.merge(r.archetype, 1, Integer::sum);
			if (r.fromZ != r.toZ)
			{
				planeChanging++;
			}
			// Underground maps sit ~6,400 tiles north of their surface, so raw distance is
			// meaningless for anything going below. Bucket by magnitude instead.
			int d = Math.abs(r.fromX - r.toX) + Math.abs(r.fromY - r.toY);
			String bucket = d <= 2 ? "in place" : d <= 8 ? "translate 3-8"
				: d <= 64 ? "local hop 9-64" : "relocate >64";
			distances.merge(bucket.hashCode(), 1, Integer::sum);
		}
		for (Map.Entry<Integer, Integer> e : byArch.entrySet())
		{
			System.out.println("  " + pad(ARCH_NAMES[e.getKey()], 14) + e.getValue());
		}
		System.out.println();
		System.out.println("  plane-changing rows : " + planeChanging);

		System.out.println();
		System.out.println("=== Requirements ===");
		int skill = 0;
		int quest = 0;
		int varbit = 0;
		int varp = 0;
		int free = 0;
		for (Row r : all)
		{
			boolean any = false;
			if (!r.skills.isEmpty())
			{
				skill++;
				any = true;
			}
			if (!r.quests.isEmpty())
			{
				quest++;
				any = true;
			}
			if (!r.varbits.isEmpty())
			{
				varbit++;
				any = true;
			}
			if (!r.varps.isEmpty())
			{
				varp++;
				any = true;
			}
			if (!any)
			{
				free++;
			}
		}
		System.out.println("  skill-gated  : " + skill);
		System.out.println("  quest-gated  : " + quest);
		System.out.println("  varbit-gated : " + varbit);
		System.out.println("  varp-gated   : " + varp);
		System.out.println("  no gate      : " + free);

		System.out.println();
		System.out.println("=== Origin spread ===");
		Map<Long, Integer> origins = new HashMap<>();
		for (Row r : all)
		{
			origins.merge(((long) r.fromX << 32) | (r.fromY & 0xFFFFFFFFL), 1, Integer::sum);
		}
		System.out.println("  distinct origin tiles: " + origins.size());
	}

	// ------------------------------------------------------------------ writing

	/**
	 * A flat binary table: count, then one fixed record per row.
	 *
	 * <p>Requirements are written as the raw expression strings. Parsing them is the
	 * plugin's job and happens once at startup; encoding them here would mean two parsers
	 * to keep in step for no gain.
	 */
	private static void write(File out, List<Row> rows) throws IOException
	{
		try (DataOutputStream d = new DataOutputStream(
			new GZIPOutputStream(new FileOutputStream(out))))
		{
			d.writeInt(rows.size());
			for (Row r : rows)
			{
				d.writeShort(r.fromX);
				d.writeShort(r.fromY);
				d.writeByte(r.fromZ);
				d.writeShort(r.toX);
				d.writeShort(r.toY);
				d.writeByte(r.toZ);
				d.writeShort(Math.min(r.duration, 0xFFFF));
				d.writeByte(r.archetype);
				d.writeInt(r.objectId);
				d.writeUTF(r.skills);
				d.writeUTF(r.quests);
				d.writeUTF(r.varbits);
				d.writeUTF(r.varps);
			}
		}
		System.out.println();
		System.out.println("Wrote " + out + " (" + out.length() + " bytes, "
			+ rows.size() + " rows)");
	}

	// ------------------------------------------------------------------ helpers

	private static String col(String[] header, String[] fields, String name)
	{
		for (int i = 0; i < header.length; i++)
		{
			if (header[i].trim().equalsIgnoreCase(name) && i < fields.length)
			{
				return fields[i].trim();
			}
		}
		return "";
	}

	private static int[] coord(String s)
	{
		String[] parts = s.trim().split("\\s+");
		if (parts.length != 3)
		{
			return null;
		}
		try
		{
			return new int[]{
				Integer.parseInt(parts[0]),
				Integer.parseInt(parts[1]),
				Integer.parseInt(parts[2]),
			};
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/**
	 * The object id at the end of a menu string, or -1.
	 *
	 * <p>The column reads like {@code "Cross Gangplank 12164"} — verb, target, id. The id
	 * is what lets the plugin find the real object's model and animation, which is how a
	 * gangplank can be drawn lowering while the golem walks up it.
	 */
	private static int objectIdOf(String menu)
	{
		if (menu == null || menu.isEmpty())
		{
			return -1;
		}
		String[] parts = menu.trim().split("\\s+");
		try
		{
			return Integer.parseInt(parts[parts.length - 1]);
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	private static int intOr(String s, int fallback)
	{
		try
		{
			return Integer.parseInt(s.trim());
		}
		catch (NumberFormatException e)
		{
			return fallback;
		}
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

	private static final class Row
	{
		int fromX, fromY, fromZ, toX, toY, toZ;
		int duration;
		int archetype;
		int objectId = -1;
		String skills = "";
		String quests = "";
		String varbits = "";
		String varps = "";
		String menu = "";
	}
}
