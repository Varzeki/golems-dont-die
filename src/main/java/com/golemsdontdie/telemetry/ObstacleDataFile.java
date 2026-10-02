package com.golemsdontdie.telemetry;

import java.io.*;
import java.util.*;
import lombok.extern.slf4j.*;

/**
 * The obstacle data file: {@value #FILE_NAME} in the plugin's own folder.
 *
 * <p>Tab-separated text, readable in any spreadsheet. Lines starting {@code #} describe the file;
 * {@code P} lines are crossings by the player, {@code G} lines crossings by golems. Column names are
 * written into the file above each kind.
 *
 * <p>One line per distinct crossing. The same crossing again adds to its count instead of adding a
 * line; past {@link #MAX_LINES} of a kind, the one seen least recently is dropped. So the file stays
 * small however long the plugin is used.
 *
 * <p>{@code S} lines remember how many sightings of each crossing have been sent, so only what is
 * new goes next time. A reader that does not know them passes over them, as it does any line it does
 * not know.
 *
 * <p>Nothing here sends anything: it says what has not been sent, for {@link ObstacleDataSender},
 * and is told what has. See the package documentation for what is kept and why.
 */
@Slf4j
public final class ObstacleDataFile
{
	public static final String FILE_NAME = "golem-obstacle-data.tsv";

	/** Bumped whenever a column changes. A file from another schema is replaced, not misread. */
	static final int SCHEMA = 3;

	/** Lines kept of each kind. */
	static final int MAX_LINES = 2000;

	/** Points kept of any one path. A longer path is thinned evenly, keeping its first and last. */
	static final int PATH_POINTS = 60;

	/** 128ths of a tile in a tile. */
	private static final int TILE = 128;

	private static final String PLAYER_COLUMNS = "#P\tobject\tname\tmenu\tanimations\tticks\tmoveDelay\tmoveSpan"
		+ "\tfromX\tfromY\tfromPlane\ttoX\ttoY\ttoPlane\tlineX\tlineY\tinstance\tseen\tfacing"
		+ "\tanimationChanges\tpath";

	private static final String GOLEM_COLUMNS = "#G\tobject\tfromX\tfromY\tfromPlane\ttoX\ttoY\ttoPlane"
		+ "\tattempts\tworstSide\tworstLanding\tworstStart\toverStone\tpath";

	/** Where each counted column of a line is, looked up by its name in the headers above. */
	private static final int P_SEEN = column(PLAYER_COLUMNS, "seen");
	private static final int G_ATTEMPTS = column(GOLEM_COLUMNS, "attempts");
	private static final int G_WORST_SIDE = column(GOLEM_COLUMNS, "worstSide");
	private static final int G_WORST_LANDING = column(GOLEM_COLUMNS, "worstLanding");
	private static final int G_WORST_START = column(GOLEM_COLUMNS, "worstStart");
	private static final int G_OVER_STONE = column(GOLEM_COLUMNS, "overStone");

	/**
	 * Where the file is kept. In play, the plugin's own folder, through RuneLite's file utility, which
	 * is the only way a plugin touches the disk. In a test, a string in memory.
	 */
	public interface Store
	{
		boolean exists();

		BufferedReader reader() throws IOException;

		/** Replaces the whole file with this text. */
		void write(String text) throws IOException;
	}

	private final Store store;
	private final String pluginVersion;

	/** Lines by crossing, least recently seen first. The first column (P or G) is not stored. */
	private final Map<String, String[]> player = new LinkedHashMap<>(64, 0.75f, true);
	private final Map<String, String[]> golem = new LinkedHashMap<>(64, 0.75f, true);

	private boolean changed;

	/**
	 * Sightings of each crossing already sent, by kind and key. Read on the client thread, and added
	 * to from a network thread when the server takes a post, so only under this file's lock.
	 */
	private final Map<String, Integer> sent = new HashMap<>();

	/** A crossing with sightings not yet sent: what to post, and how far it will have been sent. */
	public static final class Unsent
	{
		/** P or G, the line as it is posted, and how many sightings it carries. */
		public final String kind;
		public final String row;
		public final int count;

		/** Its key in {@link #sent}, and the sightings sent once this is taken. */
		final String key;
		final int upTo;

		Unsent(String kind, String row, int count, String key, int upTo)
		{
			this.kind = kind;
			this.row = row;
			this.count = count;
			this.key = key;
			this.upTo = upTo;
		}
	}

	public ObstacleDataFile(Store store, String pluginVersion)
	{
		this.store = store;
		this.pluginVersion = pluginVersion;
	}

	// ------------------------------------------------------------------ reading and writing

	/** Reads the file, if there is one. An unreadable file or another schema starts afresh. */
	public synchronized void load()
	{
		player.clear();
		golem.clear();
		sent.clear();
		changed = false;
		try
		{
			if (!store.exists())
			{
				return;
			}
		}
		catch (RuntimeException e)
		{
			log.warn("Could not look for the obstacle data file", e);
			return;
		}
		try (BufferedReader in = store.reader())
		{
			boolean sameSchema = false;
			String line;
			while ((line = in.readLine()) != null)
			{
				String[] columns = line.split("\t", -1);
				if (columns[0].equals("#schema"))
				{
					sameSchema = columns.length > 1 && columns[1].equals(String.valueOf(SCHEMA));
				}
				else if (sameSchema && columns[0].equals("P") && columns.length == columnCount(PLAYER_COLUMNS))
				{
					String[] row = withoutKind(columns);
					player.put(playerKey(row), row);
				}
				else if (sameSchema && columns[0].equals("G") && columns.length == columnCount(GOLEM_COLUMNS))
				{
					String[] row = withoutKind(columns);
					golem.put(golemKey(row), row);
				}
				else if (sameSchema && columns[0].equals("S") && columns.length == 3)
				{
					sent.put(columns[1], number(columns[2]));
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			player.clear();
			golem.clear();
			sent.clear();
			log.warn("Obstacle data unreadable; starting a fresh file", e);
		}
	}

	/** Writes the file, if anything has been added or sent since it was last written. */
	public synchronized void save()
	{
		if (!changed)
		{
			return;
		}
		changed = false;
		StringBuilder out = new StringBuilder();
		out.append("# Golems Don't Die obstacle data. Kept on this computer; sent only if Share obstacle data\n");
		out.append("# is turned on in the plugin's settings.\n");
		out.append("# P lines: crossings of obstacles by the player. G lines: crossings by golems.\n");
		out.append("# S lines: how many sightings of a crossing have been sent.\n");
		out.append("# Distances in 128ths of a tile, times in 20 ms client cycles unless named otherwise.\n");
		out.append("# A path is cycle:along:side, measured from the start tile towards the end tile.\n");
		out.append("#schema\t").append(SCHEMA).append('\n');
		out.append("#plugin\t").append(pluginVersion).append('\n');
		out.append(PLAYER_COLUMNS).append('\n');
		for (String[] row : player.values())
		{
			out.append("P\t").append(String.join("\t", row)).append('\n');
		}
		out.append(GOLEM_COLUMNS).append('\n');
		for (String[] row : golem.values())
		{
			out.append("G\t").append(String.join("\t", row)).append('\n');
		}
		// Only for crossings still kept: one dropped from the file is forgotten here too.
		for (Map.Entry<String, Integer> entry : sent.entrySet())
		{
			String key = entry.getKey();
			if ((key.startsWith("P") ? player : golem).containsKey(key.substring(1)))
			{
				out.append("S\t").append(key).append('\t').append(entry.getValue()).append('\n');
			}
		}
		try
		{
			store.write(out.toString());
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not write the obstacle data file", e);
		}
	}

	// ------------------------------------------------------------------ adding crossings

	/** Adds a crossing by the player, or counts another of one already kept. */
	public synchronized void add(PlayerCrossing c)
	{
		String[] row = {
			String.valueOf(c.objectId), text(c.name), text(c.menu), list(c.animations),
			String.valueOf(c.ticks), String.valueOf(c.moveDelay), String.valueOf(c.moveSpan),
			String.valueOf(c.fromX), String.valueOf(c.fromY), String.valueOf(c.fromPlane),
			String.valueOf(c.toX), String.valueOf(c.toY), String.valueOf(c.toPlane),
			String.valueOf(c.lineX), String.valueOf(c.lineY), c.instance ? "1" : "0",
			"1", String.valueOf(c.facing), pairs(c.animationChanges), path(c.path),
		};
		// The newest recording of a crossing replaces the old one; only the count carries over.
		String[] kept = player.get(playerKey(row));
		if (kept != null)
		{
			row[P_SEEN] = String.valueOf(number(kept[P_SEEN]) + 1);
		}
		put(player, playerKey(row), row);
	}

	/** Adds a crossing by a golem, or counts another on a route already kept. */
	public synchronized void add(GolemCrossing c)
	{
		// The route is the straight line between the middles of its two tiles.
		int fromX = c.fromX * TILE + TILE / 2;
		int fromY = c.fromY * TILE + TILE / 2;
		int toX = c.toX * TILE + TILE / 2;
		int toY = c.toY * TILE + TILE / 2;

		int[][] measured = new int[c.positions.length][];
		int worstSide = 0;
		for (int i = 0; i < c.positions.length; i++)
		{
			int[] p = c.positions[i];
			int[] alongSide = alongAndSide(p[1] - fromX, p[2] - fromY, toX - fromX, toY - fromY);
			measured[i] = new int[]{p[0], alongSide[0], alongSide[1]};
			worstSide = Math.max(worstSide, Math.abs(alongSide[1]));
		}
		int[] first = c.positions.length > 0 ? c.positions[0] : new int[]{0, fromX, fromY};
		int[] last = c.positions.length > 0 ? c.positions[c.positions.length - 1] : first;
		int start = distance(first[1] - fromX, first[2] - fromY);
		int landing = distance(last[1] - toX, last[2] - toY);

		String[] row = {
			String.valueOf(c.objectId),
			String.valueOf(c.fromX), String.valueOf(c.fromY), String.valueOf(c.fromPlane),
			String.valueOf(c.toX), String.valueOf(c.toY), String.valueOf(c.toPlane),
			"1", String.valueOf(worstSide), String.valueOf(landing), String.valueOf(start),
			c.overStone ? "1" : "0", path(measured),
		};
		// The newest path replaces the old; counts add up, and the worst of each measure is kept,
		// because the worst crossing is the one that needs fixing.
		String[] kept = golem.get(golemKey(row));
		if (kept != null)
		{
			row[G_ATTEMPTS] = String.valueOf(number(kept[G_ATTEMPTS]) + 1);
			row[G_WORST_SIDE] = String.valueOf(Math.max(worstSide, number(kept[G_WORST_SIDE])));
			row[G_WORST_LANDING] = String.valueOf(Math.max(landing, number(kept[G_WORST_LANDING])));
			row[G_WORST_START] = String.valueOf(Math.max(start, number(kept[G_WORST_START])));
			row[G_OVER_STONE] = String.valueOf(number(kept[G_OVER_STONE]) + (c.overStone ? 1 : 0));
		}
		put(golem, golemKey(row), row);
	}

	public int playerLines()
	{
		return player.size();
	}

	public int golemLines()
	{
		return golem.size();
	}

	// ------------------------------------------------------------------ sending

	/**
	 * Up to this many crossings with sightings not yet sent, players' first. Each is its line as the
	 * file has it, but with its counts as plain marks (seen once, crossed once, over a stone or not),
	 * so the same crossing posts the same line however often it has been seen; how many sightings it
	 * carries is the count beside it. Nothing is marked sent until {@link #markSent} is told so.
	 */
	public synchronized List<Unsent> unsent(int most)
	{
		List<Unsent> found = new ArrayList<>();
		collect(found, most, "P", player, P_SEEN);
		collect(found, most, "G", golem, G_ATTEMPTS);
		return found;
	}

	private void collect(List<Unsent> into, int most, String kind, Map<String, String[]> lines, int counted)
	{
		// Over the entries, not by looking each up: a look-up would reorder the least recently seen.
		for (Map.Entry<String, String[]> entry : lines.entrySet())
		{
			if (into.size() >= most)
			{
				return;
			}
			String key = kind + entry.getKey();
			int seen = number(entry.getValue()[counted]);
			int already = sent.getOrDefault(key, 0);
			if (seen > already)
			{
				String[] row = entry.getValue().clone();
				row[counted] = "1";
				if (kind.equals("G"))
				{
					row[G_OVER_STONE] = number(row[G_OVER_STONE]) > 0 ? "1" : "0";
				}
				into.add(new Unsent(kind, String.join("\t", row), seen - already, key, seen));
			}
		}
	}

	/**
	 * Marks crossings as sent, once the server has taken them, and writes the file so that is kept:
	 * the last post comes back after the file was written on stopping, and, not written, would be
	 * sent again next time. From a network thread.
	 */
	public synchronized void markSent(List<Unsent> taken)
	{
		for (Unsent u : taken)
		{
			sent.merge(u.key, u.upTo, Math::max);
		}
		changed = true;
		save();
	}

	// ------------------------------------------------------------------ helpers

	private void put(Map<String, String[]> lines, String key, String[] row)
	{
		lines.put(key, row);
		while (lines.size() > MAX_LINES)
		{
			// Dropped, it is forgotten as sent too: seen again, it starts its count afresh.
			String dropped = lines.keySet().iterator().next();
			lines.remove(dropped);
			sent.remove((lines == player ? "P" : "G") + dropped);
		}
		changed = true;
	}

	/** A player crossing is the object, what it played, and its two tiles. */
	private static String playerKey(String[] row)
	{
		return row[0] + "|" + row[3] + "|" + row[7] + "," + row[8] + "," + row[9] + ">" + row[10] + "," + row[11]
			+ "," + row[12];
	}

	/** A golem crossing is the object and its two tiles. */
	private static String golemKey(String[] row)
	{
		return row[0] + "|" + row[1] + "," + row[2] + "," + row[3] + ">" + row[4] + "," + row[5] + "," + row[6];
	}

	/**
	 * A point measured along a line and to its side: {along, side}. Along is the distance towards
	 * the line's end, side the distance off it, negative to the left.
	 */
	static int[] alongAndSide(int x, int y, int lineX, int lineY)
	{
		double length = Math.hypot(lineX, lineY);
		if (length == 0)
		{
			return new int[]{0, distance(x, y)};
		}
		double ux = lineX / length;
		double uy = lineY / length;
		return new int[]{(int) Math.round(x * ux + y * uy), (int) Math.round(x * uy - y * ux)};
	}

	private static int distance(int x, int y)
	{
		return (int) Math.round(Math.hypot(x, y));
	}

	/** A path as cycle:along:side entries, thinned evenly to {@link #PATH_POINTS}. */
	private static String path(int[][] points)
	{
		StringBuilder sb = new StringBuilder();
		if (points == null)
		{
			return "";
		}
		int step = Math.max(1, (points.length + PATH_POINTS - 1) / PATH_POINTS);
		for (int i = 0; i < points.length; i++)
		{
			if (i % step == 0 || i == points.length - 1)
			{
				sb.append(sb.length() == 0 ? "" : ",")
					.append(points[i][0]).append(':').append(points[i][1]).append(':').append(points[i][2]);
			}
		}
		return sb.toString();
	}

	/** {a, b} pairs as a:b entries. */
	private static String pairs(int[][] values)
	{
		StringBuilder sb = new StringBuilder();
		if (values != null)
		{
			for (int[] pair : values)
			{
				sb.append(sb.length() == 0 ? "" : ",").append(pair[0]).append(':').append(pair[1]);
			}
		}
		return sb.toString();
	}

	private static String list(int[] values)
	{
		StringBuilder sb = new StringBuilder();
		if (values != null)
		{
			for (int value : values)
			{
				sb.append(sb.length() == 0 ? "" : ",").append(value);
			}
		}
		return sb.toString();
	}

	/** Game text with anything that would break a line or a column taken out. */
	private static String text(String value)
	{
		return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
	}

	private static int number(String value)
	{
		try
		{
			return Integer.parseInt(value);
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	/** A named column's place in a stored line, which leaves out the header's first column (#P or #G). */
	private static int column(String header, String name)
	{
		String[] names = header.split("	");
		for (int i = 1; i < names.length; i++)
		{
			if (names[i].equals(name))
			{
				return i - 1;
			}
		}
		throw new IllegalArgumentException("no column " + name);
	}

	private static int columnCount(String header)
	{
		return header.split("\t").length;
	}

	private static String[] withoutKind(String[] columns)
	{
		String[] row = new String[columns.length - 1];
		System.arraycopy(columns, 1, row, 0, row.length);
		return row;
	}
}
