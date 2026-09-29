package com.golemsdontdie;

import java.io.*;
import java.util.*;
import java.util.function.*;
import java.util.zip.*;
import javax.inject.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import static com.golemsdontdie.RouteGeometry.span;

/**
 * Every transport a golem could use, indexed by the tile you use it from.
 *
 * <p>Loaded once from {@code /transports.gz}, generated offline from Shortest Path's tables;
 * shipping it means golems can use a ladder the player
 * has never stood next to, as with the island map. Indexed by origin tile, the only question
 * asked of it.
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
	 * Reads the bundled table. A missing or corrupt resource is deliberately not fatal: golems
	 * fall back to walking rather than the plugin refusing to load.
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
				if (t.getObjectId() > 0)
				{
					shippedObjects.add(t.getObjectId());
				}
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
	 * Transports usable from exactly this tile. Never null, never a copy: golems scan the tiles
	 * around them on every step, which near a town would be hundreds of lists a second. The lists
	 * are immutable and shared.
	 */
	List<GolemTransport> from(int x, int y)
	{
		List<GolemTransport> found = byOrigin.get(pack(x, y));
		return found == null ? Collections.emptyList() : found;
	}

	/**
	 * Adds routes the player has demonstrated, replacing any added before; they are kept apart
	 * only so re-learning replaces rather than accumulates. Archetype is {@code NONE} on purpose:
	 * what the golem plays comes from {@link ObstacleKnowledge} by object id.
	 */
	void setLearnedRoutes(java.util.List<int[]> routes)
	{
		if (!learned.isEmpty())
		{
			// Learned rows are always the tail, numbered from shippedCount. removeAll searched the
			// learned list for each of thirteen thousand rows, on every obstacle the player used.
			transports.subList(shippedCount, transports.size()).clear();
			learned.clear();
		}

		suppressed.clear();
		// One row per journey. Two saved routes can drift onto the same ends as they are merged.
		Set<Long> journeys = new HashSet<>();
		for (int[] r : routes)
		{
			if (!journeys.add(endpoints(r[1], r[2], r[3], r[4], r[5], r[6])))
			{
				continue;
			}
			// A learned route going exactly where a shipped row goes replaces it: two rows for one
			// journey can carry different object ids, as on the rockslide. What was watched is the
			// better authority on the object, the table on the journey's requirements.
			GolemTransport twin = shippedByEndpoints.get(
				endpoints(r[1], r[2], r[3], r[4], r[5], r[6]));
			GolemTransport route;
			if (twin == null)
			{
				// The observed duration, not a placeholder: a learned row claiming one tick
				// undercut the shipped row it was meant to improve on.
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

		// Learned rows are numbered above the shipped ones. Nothing reads the number for cooldowns
		// any more — those are kept by a transport's two ends, which survive a rebuild — but it
		// still tells a learned row from a shipped one in a log.
		for (int i = 0; i < learned.size(); i++)
		{
			learned.get(i).setIndex(shippedCount + i);
		}

		byOrigin.clear();
		for (GolemTransport t : transports)
		{
			if (suppressed.contains(t))
			{
				continue;
			}
			byOrigin.computeIfAbsent(pack(t.getFromX(), t.getFromY()),
				k -> new ArrayList<>()).add(t);
		}
		byOrigin.replaceAll((k, v) -> Collections.unmodifiableList(v));
		indexChunks();

		// Learned rows need their reverse resolved like any other, or a golem has nothing to
		// put on cooldown coming back.
		link();

		log.debug("Transport network: {} rows including {} learned", transports.size(),
			learned.size());
	}

	/** How many rows shipped, fixed at load: the boundary between fixed and movable indices. */
	private int shippedCount;

	private final List<GolemTransport> learned = new ArrayList<>();

	/** The first shipped row for each pair of endpoints, for spotting a learned duplicate. */
	private final Map<Long, GolemTransport> shippedByEndpoints = new HashMap<>();

	/** Shipped rows a learned route has replaced. Kept in the table, never offered. */
	private final Set<GolemTransport> suppressed =
		Collections.newSetFromMap(new IdentityHashMap<>());

	private static final int[] NO_REQUIREMENT = new int[0];

	/**
	 * True if the shipped tables have a row for this object. Shipped rows only: counting learned
	 * rows too, a tree that slipped through once passed the obstacle test for good.
	 */
	boolean isShippedObstacle(int objectId)
	{
		return shippedObjects.contains(objectId);
	}

	private final Set<Integer> shippedObjects = new HashSet<>();

	/**
	 * Every transport, for callers that sweep the whole table. Immutable and shared, like
	 * {@link #from}: sweeping thirteen thousand rows beats the tile-by-tile lookup, which costs
	 * the square of the radius, from about sixty tiles out.
	 */
	List<GolemTransport> all()
	{
		return Collections.unmodifiableList(transports);
	}

	/** True unless a learned route has replaced this row; suppressed rows stay in {@link #all}. */
	boolean isOffered(GolemTransport transport)
	{
		return !suppressed.contains(transport);
	}

	/** How far a hop from a stepping stone can go, in tiles. */
	private static final int STONE_HOP = 4;

	/**
	 * True if a short hop on the same floor starts on this tile: a stepping stone or the like,
	 * which a golem leaves by the next hop rather than by stepping off.
	 */
	boolean isStone(int x, int y, int plane)
	{
		for (GolemTransport t : from(x, y))
		{
			if (t.getFromPlane() == plane && t.getToPlane() == plane
				&& span(t.getToX() - x, t.getToY() - y) <= STONE_HOP)
			{
				return true;
			}
		}
		return false;
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

	/** Goes up whenever the table changes, so anything built from it knows to build again. */
	private int revision;

	int revision()
	{
		return revision;
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

		// Made immutable once built: these lists are handed straight out to every golem that
		// walks past.
		byOrigin.replaceAll((k, v) -> Collections.unmodifiableList(v));
		indexChunks();
	}

	/**
	 * Wyrmscraig and everywhere its own transports lead: the regions a golem stays in when its
	 * ambition is restricted.
	 *
	 * <p>The island's regions, then — repeatedly, until nothing new turns up — the region every
	 * transport starting in the area lands in, which picks up caves and the floors above ladders
	 * without a list. The region either side of each landing is allowed too, a floor rarely
	 * fitting one region, but does not seed further transports, which leaks along coasts.
	 */
	Set<Integer> homeRegions()
	{
		Set<Integer> seeds = new HashSet<>();
		for (int region : GolemContent.ISLAND_REGIONS)
		{
			seeds.add(region);
		}
		boolean grew = true;
		while (grew)
		{
			grew = false;
			for (GolemTransport t : transports)
			{
				if (!suppressed.contains(t) && t.getToX() < 6400
					&& seeds.contains(regionOf(t.getFromX(), t.getFromY()))
					&& seeds.add(regionOf(t.getToX(), t.getToY())))
				{
					grew = true;
				}
			}
		}
		// Not around the island itself: its nine regions cover it, and a margin reached the next
		// island's dock.
		Set<Integer> home = new HashSet<>(seeds);
		for (int region : seeds)
		{
			if (Arrays.stream(GolemContent.ISLAND_REGIONS).anyMatch(r -> r == region))
			{
				continue;
			}
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					home.add((((region >> 8) + dx) << 8) | ((region & 0xFF) + dy));
				}
			}
		}
		return home;
	}

	/** Hops a chain of transports is followed looking for somewhere to stand. */
	static final int CHAIN_HOPS = 6;

	/** Where a golem may stand, as whoever is asking judges it. */
	interface Ground
	{
		boolean walkable(int x, int y, int plane);
	}

	/**
	 * True if a golem on this tile, having arrived from {@code came}, can reach walkable ground by
	 * transports without going straight back. Followed for several hops, because a crossing is a
	 * chain: looking one hop ahead passed on every stone of a crossing where none led to a bank.
	 */
	boolean leadsToGround(int x, int y, int plane, int cameX, int cameY, int camePlane, int hops,
		Predicate<GolemTransport> usable, Ground ground)
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
	 * The planner used to probe random tiles for an origin: two dozen probes in nine thousand
	 * tiles find a town's cluster but almost never the one ladder at the end of a corridor.
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
	 * Pairs each transport with the one that undoes it: two-way connections are authored as two
	 * rows, so a row's reverse is the one with its endpoints swapped. Turns §11's
	 * no-immediate-reversal rule into an integer comparison.
	 */
	private void link()
	{
		revision++;
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
				// A skill this client does not know. Dropping the clause makes the transport easier,
				// never harder, the safe direction here.
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
	 * {@code "4070=0;10032>0;4560&2"} to triples of (id, operator, value). The {@code @} form is a
	 * real-time countdown in wall-clock minutes; a golem has nothing to count down, so those
	 * clauses are kept and never satisfied — see {@link GolemAbilities}.
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

	/** Regions the island's transports and learned routes lead into, with the regions around
	 * each, since an arrival rarely stays in the region it lands in. */
	private final Set<Integer> reachedRegions = new HashSet<>();

	/** True if golems can get into this region by transport, so its ground is worth mapping. */
	boolean leadsToRegion(int regionId)
	{
		return reachedRegions.contains(regionId);
	}

	private void rebuildReachedRegions()
	{
		reachedRegions.clear();
		Set<Integer> island = new HashSet<>();
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
			if (!island.contains(regionOf(t.getFromX(), t.getFromY())) && t.getIndex() < shippedCount)
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
