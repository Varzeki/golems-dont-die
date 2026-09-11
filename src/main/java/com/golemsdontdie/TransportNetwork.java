package com.golemsdontdie;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Quest;
import net.runelite.api.Skill;

/**
 * Every transport a golem could use, indexed by the tile you use it from.
 *
 * <p>Loaded once from {@code /transports.gz}, which is generated offline from Shortest
 * Path's tables by {@code dev-tools/BuildTransports.java}. Shipping it rather than
 * discovering it means golems can use a ladder on the other side of the world that the
 * player has never stood next to — the same reason the island map is shipped.
 *
 * <p>The index is by origin tile, because that is the only question ever asked of it: a
 * golem that has just stepped onto a tile wants to know what it could do from here and
 * from the handful of tiles around it. Nothing iterates the whole table at runtime.
 */
@Slf4j
@Singleton
class TransportNetwork
{
	private static final String RESOURCE = "/transports.gz";

	/** Operators in a varbit or varp condition, as encoded by the builder. */
	static final int OP_EQ = 0;
	static final int OP_GT = 1;
	static final int OP_AND = 2;
	static final int OP_AT = 3;

	private static final int[] NONE = new int[0];

	private final List<GolemTransport> transports = new ArrayList<>();

	/** Origin tile (x, y packed) to the transports starting there. Immutable once built. */
	private final Map<Long, List<GolemTransport>> byOrigin = new HashMap<>();

	/** Quest display name to enum, built once — the tables name quests as players do. */
	private static Map<String, Quest> questsByName;

	/**
	 * Reads the bundled table.
	 *
	 * <p>A missing or corrupt resource is survivable and deliberately not fatal: golems
	 * fall back to walking, which is exactly the behaviour of the version before this one.
	 * A plugin that refused to load because an optional table was unreadable would be a
	 * worse outcome than golems that stay on the island.
	 */
	void load()
	{
		if (!transports.isEmpty())
		{
			return;
		}

		try (InputStream raw = TransportNetwork.class.getResourceAsStream(RESOURCE))
		{
			if (raw == null)
			{
				log.warn("Bundled transport table missing from the jar");
				return;
			}

			try (GZIPInputStream gz = new GZIPInputStream(raw);
				 DataInputStream data = new DataInputStream(gz))
			{
				int count = data.readInt();
				for (int i = 0; i < count; i++)
				{
					transports.add(new GolemTransport(
						data.readShort() & 0xFFFF, data.readShort() & 0xFFFF, data.readByte(),
						data.readShort() & 0xFFFF, data.readShort() & 0xFFFF, data.readByte(),
						data.readShort() & 0xFFFF, data.readByte(), data.readInt(),
						parseSkills(data.readUTF()),
						parseQuests(data.readUTF()),
						parseConditions(data.readUTF()),
						parseConditions(data.readUTF())));
				}
			}

			index();
			link();
			log.debug("Loaded {} transports across {} origin tiles",
				transports.size(), byOrigin.size());
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Bundled transport table unreadable", e);
			transports.clear();
			byOrigin.clear();
		}
	}

	/**
	 * Transports usable from exactly this tile. Never null, never a copy.
	 *
	 * <p>Returns the network's own list rather than building one. A golem scans the tiles
	 * around it every time it enters one, and with a few hundred golems near a town that
	 * is a great many short-lived lists a second — which is precisely the kind of
	 * allocation churn that made this plugin stutter before.
	 *
	 * <p>The lists are immutable and shared. Callers must not modify them.
	 */
	List<GolemTransport> from(int x, int y)
	{
		List<GolemTransport> found = byOrigin.get(pack(x, y));
		return found == null ? Collections.emptyList() : found;
	}

	/** True if any transport starts on this tile — the cheap test, and the common one. */
	boolean hasOrigin(int x, int y)
	{
		return byOrigin.containsKey(pack(x, y));
	}

	GolemTransport get(int index)
	{
		return index < 0 || index >= transports.size() ? null : transports.get(index);
	}

	int size()
	{
		return transports.size();
	}

	// ------------------------------------------------------------------ indexing

	private void index()
	{
		for (int i = 0; i < transports.size(); i++)
		{
			GolemTransport t = transports.get(i);
			t.setIndex(i);
			byOrigin.computeIfAbsent(pack(t.getFromX(), t.getFromY()),
				k -> new ArrayList<>()).add(t);
		}

		// Made immutable once built. These lists are handed straight out to every golem
		// that walks past, so nothing may modify them — and saying so in the type is
		// better than saying so in a comment nobody reads.
		byOrigin.replaceAll((k, v) -> Collections.unmodifiableList(v));
	}

	/**
	 * Pairs each transport with the one that undoes it.
	 *
	 * <p>Two-way connections are authored as two rows, so the reverse of a row is the row
	 * whose origin and destination are this one's swapped. Matching them here turns §11's
	 * no-immediate-reversal rule into an integer comparison.
	 */
	private void link()
	{
		Map<Long, Integer> byEndpoints = new HashMap<>();
		for (int i = 0; i < transports.size(); i++)
		{
			GolemTransport t = transports.get(i);
			byEndpoints.put(endpoints(t.getFromX(), t.getFromY(), t.getFromPlane(),
				t.getToX(), t.getToY(), t.getToPlane()), i);
		}

		int linked = 0;
		for (GolemTransport t : transports)
		{
			Integer other = byEndpoints.get(endpoints(t.getToX(), t.getToY(), t.getToPlane(),
				t.getFromX(), t.getFromY(), t.getFromPlane()));
			if (other != null)
			{
				t.setReverse(other);
				linked++;
			}
		}
		log.debug("{} of {} transports have a reverse", linked, transports.size());
	}

	// ----------------------------------------------------------------- parsing

	/** {@code "25 Magic;30 Agility"} to pairs of (level, skill ordinal). */
	private static int[] parseSkills(String spec)
	{
		if (spec.isEmpty())
		{
			return NONE;
		}
		List<Integer> out = new ArrayList<>();
		for (String clause : spec.split(";"))
		{
			String[] parts = clause.trim().split("\\s+", 2);
			if (parts.length != 2)
			{
				continue;
			}
			try
			{
				int level = Integer.parseInt(parts[0]);
				Skill skill = Skill.valueOf(parts[1].trim().toUpperCase());
				out.add(level);
				out.add(skill.ordinal());
			}
			catch (IllegalArgumentException e)
			{
				// A skill this client does not know about — a new one, or a typo upstream.
				// Dropping the clause makes the transport easier, never harder, which is
				// the safe direction for something purely cosmetic.
				log.debug("Unknown skill requirement: {}", clause);
			}
		}
		return toArray(out);
	}

	/** {@code "Plague City;Song of the Elves"} to quest ordinals. */
	private static int[] parseQuests(String spec)
	{
		if (spec.isEmpty())
		{
			return NONE;
		}
		if (questsByName == null)
		{
			questsByName = new HashMap<>();
			for (Quest quest : Quest.values())
			{
				questsByName.put(quest.getName().toLowerCase(), quest);
			}
		}

		List<Integer> out = new ArrayList<>();
		for (String name : spec.split(";"))
		{
			Quest quest = questsByName.get(name.trim().toLowerCase());
			if (quest == null)
			{
				log.debug("Unknown quest requirement: {}", name);
				continue;
			}
			out.add(quest.ordinal());
		}
		return toArray(out);
	}

	/**
	 * {@code "4070=0;10032>0;4560&2"} to triples of (id, operator, value).
	 *
	 * <p>The {@code @} form is a real-time countdown in wall-clock minutes. A golem has
	 * nothing to countdown, so those clauses are kept and simply never satisfied — see
	 * {@link GolemAbilities}.
	 */
	private static int[] parseConditions(String spec)
	{
		if (spec.isEmpty())
		{
			return NONE;
		}
		List<Integer> out = new ArrayList<>();
		for (String clause : spec.split(";"))
		{
			String c = clause.trim();
			if (c.isEmpty())
			{
				continue;
			}
			int op;
			int at = -1;
			if ((at = c.indexOf('>')) >= 0)
			{
				op = OP_GT;
			}
			else if ((at = c.indexOf('&')) >= 0)
			{
				op = OP_AND;
			}
			else if ((at = c.indexOf('@')) >= 0)
			{
				op = OP_AT;
			}
			else if ((at = c.indexOf('=')) >= 0)
			{
				op = OP_EQ;
			}
			else
			{
				continue;
			}

			try
			{
				out.add(Integer.parseInt(c.substring(0, at).trim()));
				out.add(op);
				out.add(Integer.parseInt(c.substring(at + 1).trim()));
			}
			catch (NumberFormatException e)
			{
				log.debug("Unparseable condition: {}", clause);
			}
		}
		return toArray(out);
	}

	private static int[] toArray(List<Integer> values)
	{
		if (values.isEmpty())
		{
			return NONE;
		}
		int[] out = new int[values.size()];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = values.get(i);
		}
		return out;
	}

	private static long pack(int x, int y)
	{
		return ((long) x << 32) | (y & 0xFFFFFFFFL);
	}

	private static long endpoints(int fx, int fy, int fz, int tx, int ty, int tz)
	{
		// 15 bits per coordinate and 2 per plane fits a world tile pair in a long.
		return ((long) (fx & 0x7FFF) << 49) | ((long) (fy & 0x7FFF) << 34)
			| ((long) (fz & 3) << 32) | ((long) (tx & 0x7FFF) << 17)
			| ((long) (ty & 0x7FFF) << 2) | (tz & 3);
	}
}
