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

	/** Archetype per object id, built once with the rest of the index. */
	private final Map<Integer, Integer> archetypeByObject = new HashMap<>();

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
			shippedCount = transports.size();
			for (GolemTransport t : transports)
			{
				shippedByEndpoints.putIfAbsent(t.endpointKey(), t);
			}
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

	/**
	 * Adds routes the player has demonstrated, replacing any added before.
	 *
	 * <p>These are ordinary transports once they are in — a golem cannot tell the
	 * difference between a row that shipped and a row somebody earned by walking through
	 * an obstacle twice. The two are kept apart only so that re-learning replaces rather
	 * than accumulates.
	 *
	 * <p>Archetype is {@code NONE} on purpose. What the golem plays comes from
	 * {@link ObstacleKnowledge} by object id, which for a learned route is the animation
	 * observed at the same moment as the destination — better than any archetype guess.
	 */
	void setLearnedRoutes(java.util.List<int[]> routes)
	{
		if (!learned.isEmpty())
		{
			transports.removeAll(learned);
			learned.clear();
		}

		suppressed.clear();
		for (int[] r : routes)
		{
			// A learned route that goes exactly where a shipped row goes replaces it rather
			// than sitting beside it.
			//
			// Beside it, the golem had two rows for one journey: possibly under different
			// object ids, as on the rockslide where the table had the ids the wrong way
			// round, and so with different animations depending on which it happened to
			// roll. What was watched is the better authority on the object; the table is
			// the better authority on what the journey requires, so those carry over.
			GolemTransport twin = shippedByEndpoints.get(
				endpoints(r[1], r[2], r[3], r[4], r[5], r[6]));
			GolemTransport route;
			if (twin == null)
			{
				// The observed duration, not a placeholder. A learned row that claimed one
				// tick undercut the shipped row it was meant to improve on.
				route = new GolemTransport(r[1], r[2], r[3], r[4], r[5], r[6],
					r.length > 7 ? r[7] : 1, GolemTransport.ARCHETYPE_NONE, r[0],
					NO_REQUIREMENT, NO_REQUIREMENT, NO_REQUIREMENT, NO_REQUIREMENT);
			}
			else
			{
				route = new GolemTransport(r[1], r[2], r[3], r[4], r[5], r[6],
					r.length > 7 ? r[7] : 1,
					twin.getObjectId() == r[0] ? twin.getArchetype() : GolemTransport.ARCHETYPE_NONE,
					r[0], twin.skills(), twin.quests(), twin.varbits(), twin.varps());
				suppressed.add(twin);
			}
			route.setInstanceFlags(r.length > 8 ? r[8] : 0);
			learned.add(route);
		}
		transports.addAll(learned);

		// Indices are assigned once and never move.
		//
		// This used to re-index the whole table every time a route was learned, which is
		// on every sighting — and a transport's index is the key its cooldown is stored
		// under. Renumbering silently voided every cooldown in the game, so nothing
		// stopped a golem taking again the obstacle it had just come through. It ping-ponged
		// between the two cathedral doors, drifting sideways as it alternated between their
		// two rows, and opened the door three or four times before getting anywhere.
		//
		// Shipped rows keep the indices they were given at load. Learned rows are numbered
		// above them, so adding or replacing one cannot disturb anything already in flight.
		for (int i = 0; i < learned.size(); i++)
		{
			learned.get(i).setIndex(shippedCount + i);
		}

		byOrigin.clear();
		archetypeByObject.clear();
		for (GolemTransport t : transports)
		{
			if (suppressed.contains(t))
			{
				continue;
			}
			byOrigin.computeIfAbsent(pack(t.getFromX(), t.getFromY()),
				k -> new ArrayList<>()).add(t);
			if (t.getObjectId() > 0)
			{
				archetypeByObject.merge(t.getObjectId(), t.getArchetype(), Math::max);
			}
		}
		byOrigin.replaceAll((k, v) -> Collections.unmodifiableList(v));
		indexChunks();

		// And learned rows need their reverse resolved like any other, or a golem has
		// nothing to put on cooldown when it comes back the other way.
		link();

		log.debug("Transport network: {} rows including {} learned", transports.size(),
			learned.size());
	}

	/**
	 * How many rows shipped, fixed once at load.
	 *
	 * <p>The boundary between indices that must never change and indices that may.
	 */
	private int shippedCount;

	private final List<GolemTransport> learned = new ArrayList<>();

	/** The first shipped row for each pair of endpoints, for spotting a learned duplicate. */
	private final Map<Long, GolemTransport> shippedByEndpoints = new HashMap<>();

	/** Shipped rows a learned route has replaced. Kept in the table, never offered. */
	private final java.util.Set<GolemTransport> suppressed =
		Collections.newSetFromMap(new java.util.IdentityHashMap<>());

	private static final int[] NO_REQUIREMENT = new int[0];

	/**
	 * The archetype this object is given wherever it appears, or -1 if it appears nowhere.
	 *
	 * <p>Keyed by object rather than by tile, because the highlight overlay works from the
	 * obstacle index — which knows where every obstacle in the game stands but nothing
	 * about where any of them lead — and still needs to say whether the plugin has an
	 * animation for that kind of thing.
	 */
	int archetypeFor(int objectId)
	{
		Integer found = archetypeByObject.get(objectId);
		return found == null ? -1 : found;
	}

	/**
	 * Every transport, for callers that need to sweep the whole table.
	 *
	 * <p>Immutable and shared, like {@link #from}. Sweeping thirteen thousand rows is the
	 * cheaper way to answer "what is near me" once the radius gets large: the tile-by-tile
	 * lookup costs the square of the radius, and passes this one at about sixty tiles.
	 */
	List<GolemTransport> all()
	{
		return Collections.unmodifiableList(transports);
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

			// Highest archetype wins where an object appears under several. In practice
			// they agree; where they do not, the classified one is more informative than
			// the unclassified one and ARCHETYPE_NONE is zero.
			if (t.getObjectId() > 0)
			{
				archetypeByObject.merge(t.getObjectId(), t.getArchetype(), Math::max);
			}
		}

		// Made immutable once built. These lists are handed straight out to every golem
		// that walks past, so nothing may modify them — and saying so in the type is
		// better than saying so in a comment nobody reads.
		byOrigin.replaceAll((k, v) -> Collections.unmodifiableList(v));
		indexChunks();
	}

	/** Hops a chain of transports is followed looking for somewhere to stand. */
	static final int CHAIN_HOPS = 6;

	/** Where a golem may stand, as whoever is asking judges it. */
	interface Ground
	{
		boolean walkable(int x, int y, int plane);
	}

	/**
	 * True if a golem on this tile, having arrived from {@code came}, can get to walkable
	 * ground by transports without going straight back the way it came.
	 *
	 * <p>Followed for several hops, because a crossing is a chain. The test this replaces
	 * looked one hop ahead — another transport starts on this stone — and a stepping-stone
	 * crossing in the shipped table passed it on every stone while no stone led to a bank a
	 * golem could stand on. Golems that stepped on hopped between the two middle stones about
	 * two thousand times an hour, out of view where nobody could see it.
	 */
	boolean leadsToGround(int x, int y, int plane, int cameX, int cameY, int camePlane, int hops,
		java.util.function.Predicate<GolemTransport> usable, Ground ground)
	{
		if (ground.walkable(x, y, plane))
		{
			return true;
		}
		if (hops <= 0)
		{
			return false;
		}
		for (GolemTransport onward : from(x, y))
		{
			if (onward.getFromPlane() != plane || !usable.test(onward)
				|| (onward.getToX() == cameX && onward.getToY() == cameY && onward.getToPlane() == camePlane))
			{
				continue;
			}
			if (leadsToGround(onward.getToX(), onward.getToY(), onward.getToPlane(), x, y, plane, hops - 1,
				usable, ground))
			{
				return true;
			}
		}
		return false;
	}

	/** Chunks are 16 tiles square: a 48-tile search is a seven-by-seven block of them. */
	private static final int CHUNK_SHIFT = 4;

	/** Origin chunk to the offered transports starting in it. */
	private final Map<Long, List<GolemTransport>> byChunk = new HashMap<>();

	private void indexChunks()
	{
		byChunk.clear();
		for (List<GolemTransport> here : byOrigin.values())
		{
			for (GolemTransport t : here)
			{
				byChunk.computeIfAbsent(pack(t.getFromX() >> CHUNK_SHIFT, t.getFromY() >> CHUNK_SHIFT),
					k -> new ArrayList<>()).add(t);
			}
		}
	}

	/**
	 * Adds every offered transport starting within {@code radius} tiles of a tile, on any plane.
	 *
	 * <p>The planner used to find transports by probing random tiles for an origin. Two dozen
	 * probes in a box of nine thousand tiles finds a town's cluster of rows, and almost never
	 * the one ladder at the end of a dungeon corridor — so a golem that went down into a
	 * dungeon rarely found the next way on and never got deeper than the first floor.
	 */
	void near(int x, int y, int radius, List<GolemTransport> out)
	{
		for (int cx = (x - radius) >> CHUNK_SHIFT; cx <= (x + radius) >> CHUNK_SHIFT; cx++)
		{
			for (int cy = (y - radius) >> CHUNK_SHIFT; cy <= (y + radius) >> CHUNK_SHIFT; cy++)
			{
				List<GolemTransport> chunk = byChunk.get(pack(cx, cy));
				if (chunk == null)
				{
					continue;
				}
				for (GolemTransport t : chunk)
				{
					if (Math.abs(t.getFromX() - x) <= radius && Math.abs(t.getFromY() - y) <= radius)
					{
						out.add(t);
					}
				}
			}
		}
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
		rebuildReachedRegions();

		Map<Long, Integer> byEndpoints = new HashMap<>();
		for (GolemTransport t : transports)
		{
			if (!suppressed.contains(t))
			{
				byEndpoints.put(t.endpointKey(), t.getIndex());
			}
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

	/**
	 * Regions a transport from the island, or any learned route, leads into — with the
	 * regions around each, since an arrival rarely stays in the one region it lands in.
	 */
	private final java.util.Set<Integer> reachedRegions = new java.util.HashSet<>();

	/** True if golems can get into this region by transport, so its ground is worth mapping. */
	boolean leadsToRegion(int regionId)
	{
		return reachedRegions.contains(regionId);
	}

	private void rebuildReachedRegions()
	{
		reachedRegions.clear();
		java.util.Set<Integer> island = new java.util.HashSet<>();
		for (int region : GolemContent.ISLAND_REGIONS)
		{
			island.add(region);
		}
		for (GolemTransport t : transports)
		{
			if (suppressed.contains(t))
			{
				continue;
			}
			if (!island.contains(regionOf(t.getFromX(), t.getFromY())) && !learned.contains(t))
			{
				continue;
			}
			int rx = t.getToX() >> 6;
			int ry = t.getToY() >> 6;
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					reachedRegions.add((rx + dx) << 8 | (ry + dy));
				}
			}
		}
	}

	private static int regionOf(int x, int y)
	{
		return (x >> 6) << 8 | (y >> 6);
	}

	private static long pack(int x, int y)
	{
		return ((long) x << 32) | (y & 0xFFFFFFFFL);
	}

	static long endpoints(int fx, int fy, int fz, int tx, int ty, int tz)
	{
		// 15 bits per coordinate and 2 per plane fits a world tile pair in a long.
		return ((long) (fx & 0x7FFF) << 49) | ((long) (fy & 0x7FFF) << 34)
			| ((long) (fz & 3) << 32) | ((long) (tx & 0x7FFF) << 17)
			| ((long) (ty & 0x7FFF) << 2) | (tz & 3);
	}
}
