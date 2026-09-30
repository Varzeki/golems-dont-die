package com.golemsdontdie;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;

/**
 * Where a golem may walk, anywhere in the world. Read-only, shipped, never written to.
 *
 * <p>The island map this replaces covered nine regions and fitted in a config value; this
 * covers 2,424 region-planes, the ocean and every floor of the world the collision data holds,
 * and at about 1.5 MB belongs in the jar.
 *
 * <p>A byte per tile, from the game cache by the client's own rules: <b>north</b> and <b>east</b>
 * edges as {@code IslandMemory} keeps them (south and west are the neighbouring tile's), whether
 * the tile can be stood on and has ground under it, the two corners that can refuse a diagonal
 * step, and which shut edges are doors. Then a bit per tile for each derived map, and <b>water</b>
 * for every sea and lake the textures say is one.
 * <b>ocean</b> is the single connected sea: two ports are only sailable between if both sit
 * on it, and enclosed water exists - Karamja's inland body is 26,000 tiles leading nowhere.
 * <b>isolated</b> marks components under 50 tiles, computed offline rather than by a flood
 * fill the client cannot afford.
 *
 * @see IslandMemory the live harvest, which takes precedence over this everywhere
 */
@Slf4j
@Singleton
class WorldMesh
{
	private static final String RESOURCE = "/world-mesh.gz";

	private static final int REGION_SIZE = Constants.REGION_SIZE;
	private static final int REGION_MASK = REGION_SIZE - 1;
	private static final int TILES_PER_PLANE = REGION_SIZE * REGION_SIZE;

	/** A byte per tile for passability and what the tile is; a bit per tile for each derived map. */
	private static final int COLLISION_BYTES = TILES_PER_PLANE;
	private static final int DERIVED_BYTES = TILES_PER_PLANE / 8;

	/** Two bytes a tile: there are 18,634 components, which does not fit in one. */
	private static final int COMPONENT_BYTES = TILES_PER_PLANE * 2;

	/** Impossible as an entry count, so an old file is refused rather than misread. */
	private static final int MAGIC = -0x60137;

	/** 2 added the per-tile component map; 3 a byte a tile (standable, ground, corners, doors) and water. */
	private static final int VERSION = 4;

	static final int FLAG_NORTH = 0;
	static final int FLAG_EAST = 1;

	/** The tile can be stood on: nothing solid fills it. */
	static final int FLAG_STANDABLE = 2;

	/** The tile has ground under it: not the void round an upper floor. */
	static final int FLAG_GROUND = 3;

	/**
	 * A corner blocks the diagonal north-east out of the tile, or into it from the north-east:
	 * a wall's corner post, a diagonal wall. The two-bit map could not say so, and golems cut
	 * corners the game refuses.
	 */
	static final int FLAG_CORNER_NE = 4;

	/** The same for the diagonal north-west. */
	static final int FLAG_CORNER_NW = 5;

	/** The north edge is shut only by something that opens: a door or a gate. */
	static final int FLAG_DOOR_NORTH = 6;

	/** The same for the east edge. */
	static final int FLAG_DOOR_EAST = 7;

	/**
	 * Everything the mesh holds for one region and plane. One object rather than five maps
	 * keyed alike: a walkability check touches several, planning asks millions of times,
	 * and the boxed lookups were most of the cost.
	 */
	@AllArgsConstructor
	private static final class Region
	{
		/** A byte per tile: the FLAG_ bits. */
		final byte[] collision;

		/** The single connected sea. */
		final byte[] ocean;

		/** Components too small to wander. */
		final byte[] isolated;

		/**
		 * Which connected component each tile belongs to, 0 for none, as an index into
		 * {@link #palette}; null where the whole region-plane is one. Shipped as two bytes a tile
		 * (there are 18,630 components), held as one: no region-plane has more than 172, and the
		 * two bytes were more than half the mesh in memory.
		 * The one thing no per-tile flag can say: whether two tiles are connected <b>to each
		 * other</b>. Everything else answers "may a golem stand there", and a sealed room is
		 * good ground. {@code isolated} only caught components under fifty tiles.
		 */
		final byte[] components;

		/** The component ids a region-plane's tiles index into. */
		final int[] palette;

		/**
		 * Each tile's component id outright, for the rare region-plane with more than 256 of them,
		 * which no palette of a byte can index; null otherwise. None does today (the most is 172).
		 */
		final char[] wide;

		/**
		 * Tiles the land fill reached - ground a golem may stand on, on foot. The ocean bit
		 * marks only the one connected sea, so cave water, lakes and enclosed basins read as
		 * passable and golems walked out across them. Shoreline edges are blocked, so a fill
		 * starting on land cannot leak.
		 */
		final byte[] land;

		/**
		 * Water, by the cache's own textures: the ocean and every other sea and lake of a hundred
		 * tiles or more. Passable in the client, never ground.
		 */
		final byte[] water;
	}

	/** Region and plane to what the mesh holds for it. */
	private final Map<Long, Region> regions = new HashMap<>();

	/**
	 * The same regions by region id and plane for planes 0 to 3, so the common case is an
	 * array read with no key boxed. Anything else goes through {@link #regions}.
	 */
	private final Region[] byRegion = new Region[FAST_REGIONS << 2];

	private static final int FAST_REGIONS = 1 << 16;

	private boolean loaded;

	/**
	 * Reads the bundled mesh. A missing or corrupt resource leaves every query answering
	 * "unknown", which is survivable: the live harvest still works, so golems roam what the
	 * player has walked.
	 */
	void load()
	{
		if (loaded)
		{
			return;
		}
		loaded = true;

		try (InputStream raw = WorldMesh.class.getResourceAsStream(RESOURCE))
		{
			if (raw == null)
			{
				log.warn("Bundled world mesh missing from the jar");
				return;
			}

			try (GZIPInputStream gz = new GZIPInputStream(raw);
				 DataInputStream data = new DataInputStream(gz))
			{
				// A negative first field is the version marker. An entry count cannot be
				// negative, so a file without one is the old format and is refused rather
				// than read as though its component map were region ids.
				int first = data.readInt();
				if (first != MAGIC)
				{
					log.warn("World mesh is an old format; it needs rebuilding");
					return;
				}
				int version = data.readInt();
				if (version != VERSION)
				{
					log.warn("World mesh is version {}, expected {}", version, VERSION);
					return;
				}

				int entries = data.readInt();
				for (int i = 0; i < entries; i++)
				{
					int regionId = data.readInt();
					int plane = data.readByte();
					put(regionId, plane, readRecord(data));
				}

				// The rest of the world, packed: read the first time a golem asks about it.
				int packedEntries = data.readInt();
				for (int i = 0; i < packedEntries; i++)
				{
					int regionId = data.readInt();
					int plane = data.readByte();
					byte[] blob = new byte[data.readInt()];
					data.readFully(blob);
					packed.put(key(regionId, plane), blob);
					if (regionId >= 0 && regionId < FAST_REGIONS && (plane & 0xF) < 4)
					{
						packedAt[regionId << 2 | (plane & 0xF)] = true;
					}
				}
				log.debug("Loaded world mesh: {} region-planes, {} more packed", entries, packedEntries);
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Bundled world mesh unreadable", e);
			regions.clear();
			Arrays.fill(byRegion, null);
			packed.clear();
			Arrays.fill(packedAt, false);
		}
	}

	boolean north(int x, int y, int plane)
	{
		return flag(x, y, plane, FLAG_NORTH);
	}

	boolean east(int x, int y, int plane)
	{
		return flag(x, y, plane, FLAG_EAST);
	}

	/** True if this tile is part of the single connected sea. */
	boolean isOcean(int x, int y, int plane)
	{
		Region region = region(regionIdOf(x, y), plane);
		return region != null && bit(region.ocean, x, y);
	}

	/**
	 * True if this tile sits in a component too small to wander - the question stuck
	 * detection asks, as a bit lookup. A golem restored onto one is relocated rather than
	 * left walking into the same four walls forever.
	 */
	boolean isIsolated(int x, int y, int plane)
	{
		Region region = region(regionIdOf(x, y), plane);
		return region != null && bit(region.isolated, x, y);
	}

	/**
	 * Which connected space this tile belongs to, or 0 if the mesh does not know. Zero means
	 * "no answer", not "no component": refusing a tile outside the shipped regions would
	 * shrink the world to the shipped file.
	 */
	int componentAt(int x, int y, int plane)
	{
		Region region = region(regionIdOf(x, y), plane);
		if (region == null)
		{
			return 0;
		}
		return componentOf(region, x, y);
	}

	/**
	 * True if a golem standing at the first tile could walk to the second. Unknown counts as
	 * yes: the live harvest covers ground the mesh never will, so a silent answer must not
	 * be read as a refusal.
	 */
	boolean sameComponent(int fromX, int fromY, int toX, int toY, int plane)
	{
		int from = componentAt(fromX, fromY, plane);
		int to = componentAt(toX, toY, plane);
		return from == 0 || to == 0 || from == to;
	}

	/**
	 * True if this tile is water a boat sails but nobody walks, and is not the sea: Wyrmscraig's
	 * underground lake, and every other lake. Passable because boats cross it and the client does
	 * not block it, so the golems' map read it as ground and golems walked on the water. The lake
	 * used to be found by its component from a hard-coded mouth; the mesh now carries water.
	 */
	boolean isInlandWater(int x, int y, int plane)
	{
		return isWater(x, y, plane) && !isOcean(x, y, plane);
	}

	/** True if this tile is water by the cache's textures: the ocean, a sea, a lake. Never ground. */
	boolean isWater(int x, int y, int plane)
	{
		Region region = region(regionIdOf(x, y), plane);
		return region != null && bit(region.water, x, y);
	}

	/**
	 * True if a corner stops the diagonal step (dx, dy) from this tile, as the client's own corner
	 * test does: a wall's corner post, a diagonal wall. The step's four edges are the caller's.
	 */
	boolean cornerBlocks(int x, int y, int plane, int dx, int dy)
	{
		if (dy == 1)
		{
			return flag(x, y, plane, dx == 1 ? FLAG_CORNER_NE : FLAG_CORNER_NW);
		}
		// Southward, the same corner seen from the tile it arrives at.
		return flag(x + dx, y + dy, plane, dx == 1 ? FLAG_CORNER_NW : FLAG_CORNER_NE);
	}

	/** True if this tile's north (or east) edge is shut only by something that opens. */
	boolean isDoorEdge(int x, int y, int plane, boolean northEdge)
	{
		return flag(x, y, plane, northEdge ? FLAG_DOOR_NORTH : FLAG_DOOR_EAST);
	}

	/**
	 * True if something could walk here: nothing solid fills it, there is ground under it, and it
	 * can be left in some direction. All three: a tile walled on four sides - the one tile a
	 * ladder puts you on, atop a platform - is ground to stand on but nowhere to walk, and a golem
	 * put down there must take the next hop rather than plan a walk; the edges alone let the void
	 * round an upper floor through.
	 */
	boolean isWalkable(int x, int y, int plane)
	{
		return flag(x, y, plane, FLAG_STANDABLE) && flag(x, y, plane, FLAG_GROUND)
			&& (north(x, y, plane) || east(x, y, plane) || north(x, y - 1, plane) || east(x - 1, y, plane));
	}

	/**
	 * True if a golem could stand here <em>on foot</em>. Leaving this distinction out put
	 * golems on the open sea: water is <b>passable</b> in the source collision map, which is
	 * why sea navigation works, so the passability bits alone cannot tell walkable ground
	 * from crossable water. Only {@link SeaMesh}, routing a boat, may treat the sea as
	 * passable.
	 */
	boolean isLandWalkable(int x, int y, int plane)
	{
		if (isInlandWater(x, y, plane))
		{
			return false;
		}
		Region region = region(regionIdOf(x, y), plane);
		if (region == null)
		{
			// Nothing known here: no land bit and no component that could have been admitted.
			return false;
		}
		if (!bit(region.land, x, y))
		{
			// Only worth reading the component when some floor has been admitted at all.
			if (admittedFloors == 0)
			{
				return false;
			}
			int component = componentOf(region, x, y);
			if (!landComponents[component] && !dockFloors[component] && !instanceFloors[component])
			{
				return false;
			}
		}
		return isWalkable(x, y, plane);
	}

	/**
	 * Floors counted as land because a dock stands on them. Kept apart from the transport
	 * floors, which are replaced whenever a route is learned. Sailing added islands no
	 * transport table has a row on: twenty-two of the sixty-one docks had no land beside
	 * them, so a golem sailing there could not step off.
	 */
	private final boolean[] dockFloors = new boolean[1 << 16];

	/**
	 * Floors counted as land because the instance the player stands in is built from them: the
	 * template rooms golems in it walk, in template coordinates. Only the room a learned route led
	 * into was admitted before, and a golem that wandered further into the instance found nowhere
	 * to walk and was carried home in front of the player. Kept until the next instance.
	 */
	private final boolean[] instanceFloors = new boolean[1 << 16];

	/** The components admitted as {@link #instanceFloors}, to take back when the instance changes. */
	private final Set<Integer> instanceFloorList = new HashSet<>();

	/** Admits these components as the loaded instance's floors, in place of the last instance's. */
	void admitInstanceFloors(Set<Integer> components)
	{
		for (int component : instanceFloorList)
		{
			instanceFloors[component] = false;
			admittedFloors -= landComponents[component] || dockFloors[component] ? 0 : 1;
		}
		instanceFloorList.clear();
		for (int component : components)
		{
			if (component != 0 && !instanceFloors[component])
			{
				instanceFloors[component] = true;
				instanceFloorList.add(component);
				admittedFloors += landComponents[component] || dockFloors[component] ? 0 : 1;
			}
		}
	}

	/** How many floors either kind of admission holds, so a check can skip reading the component. */
	private int admittedFloors;

	/** How many of them came from transports, for the log. */
	private int transportFloors;

	/**
	 * Which spaces can be walked to from home, and which can be walked home from.
	 *
	 * <p>A golem walks only inside the space it is standing in; a transport or a crossing is the
	 * only way into another. So the spaces make a graph with the transports for edges, and a golem
	 * belongs in the part of it home can reach and that can reach home. Anywhere else is a trap
	 * whatever its size: a space of a million tiles with no route into it is a million tiles no
	 * golem can arrive at, and only matters at all because one was put there.
	 *
	 * <p>One direction only, deliberately. Asking for a way back as well looked right and was
	 * wrong: the tables hold one-way rows, and a pocket of Wyrmscraig entered by a drop with no
	 * row back out had golems carried to the plinth and walking straight back into it, twice in
	 * fifteen seconds. Ground a golem can reach is ground a golem may stand on; a golem that
	 * cannot get out again is the stuck watchdog's business, not this one's. Not walking into
	 * such a pocket in the first place is WaysBack's, over only what golems may use.
	 */
	private boolean[] fromHome;

	/** Every space a dock stands in. The sea joins them to each other. */
	private final Set<Integer> ports = new HashSet<>();

	/** Every space a transport touches, at either end. Rebuilt with the graph. */
	private final Set<Integer> touched = new HashSet<>();

	/** Space to the spaces a transport leads to. */
	private final Map<Integer, java.util.List<Integer>> leadsTo = new HashMap<>();

	/** Space to the spaces the island map walks into from it, both ways. See joinWhereWalked. */
	private final Map<Integer, java.util.List<Integer>> walkedTo = new HashMap<>();

	/** Goes up whenever the joins above are rebuilt, so anything built from them knows to build again. */
	private int linkRevision;

	int linkRevision()
	{
		return linkRevision;
	}

	/** Space to the spaces the island map walks into from it. */
	Map<Integer, java.util.List<Integer>> walkedTo()
	{
		return Collections.unmodifiableMap(walkedTo);
	}

	/**
	 * True if this tile is in a pocket too small to wander, that no route touches and no dock
	 * stands in.
	 *
	 * <p>The plainest trap there is, and the one answer here that barely leans on the tables: the
	 * pocket is under fifty tiles by the shipped mesh's own reckoning, so a golem in it is walking
	 * in circles, and with nothing starting or ending there it has neither a shortcut to take nor
	 * one to reverse back out of. Judged on its own so that it still holds when the wider question
	 * - does this ground join up with home - has been answered by a world the plugin has misread.
	 */
	boolean isSealedPocket(int x, int y, int plane)
	{
		int component = componentAt(x, y, plane);
		return component != 0 && isIsolated(x, y, plane)
			&& !touched.contains(component) && !ports.contains(component);
	}

	/**
	 * True if a golem here is somewhere it could not have walked to, or could not walk back from.
	 * A tile with no component is left alone, and so is everywhere until the spaces are linked.
	 */
	boolean isCutOff(int x, int y, int plane)
	{
		int component = componentAt(x, y, plane);
		if (component == 0 || fromHome == null)
		{
			return false;
		}
		return !fromHome[component];
	}

	/**
	 * Works out which spaces home can reach and which can reach home, over the transports and the
	 * docks known right now. Run once the tables are loaded, and again whenever a route is
	 * learned: a learned route is a way through that did not exist a moment ago.
	 */
	void linkSpaces(int homeX, int homeY, int homePlane)
	{
		int home = componentAt(homeX, homeY, homePlane);
		if (home == 0)
		{
			return;
		}

		boolean[] out = new boolean[1 << 16];
		walkSpaces(home, leadsTo, out);
		fromHome = out;

		int reached = 0;
		for (boolean space : out)
		{
			reached += space ? 1 : 0;
		}
		log.debug("{} spaces are home's own, of the {} the transports name", reached, leadsTo.size());
	}

	/** Spreads out from home over one direction of the graph, the sea counting as one hop. */
	private void walkSpaces(int home, Map<Integer, java.util.List<Integer>> edges, boolean[] seen)
	{
		java.util.Deque<Integer> queue = new ArrayDeque<>();
		seen[home] = true;
		queue.add(home);
		boolean sailed = false;

		while (!queue.isEmpty())
		{
			int at = queue.poll();

			// One port reached is every port reached: a golem that can board can land wherever a
			// boat goes, and the same backwards.
			if (!sailed && ports.contains(at))
			{
				sailed = true;
				for (int port : ports)
				{
					if (!seen[port])
					{
						seen[port] = true;
						queue.add(port);
					}
				}
			}

			java.util.List<Integer> next = edges.get(at);
			if (next == null)
			{
				continue;
			}
			for (int to : next)
			{
				if (!seen[to])
				{
					seen[to] = true;
					queue.add(to);
				}
			}
		}
	}

	/** Marks the space a dock's quayside stands in as a port, which the sea joins to every other. */
	void admitDockExit(int x, int y, int plane)
	{
		int component = componentAt(x, y, plane);
		if (component != 0)
		{
			ports.add(component);
		}
	}

	/** Admits the floor under a dock's quayside, if it is a floor at all. */
	void admitDockFloor(int x, int y, int plane)
	{
		if (x >= 6400 || isOcean(x, y, plane))
		{
			return;
		}
		int component = componentAt(x, y, plane);
		if (component != 0 && !dockFloors[component])
		{
			dockFloors[component] = true;
			admittedFloors += landComponents[component] ? 0 : 1;
		}
	}

	/**
	 * Floors counted as land because a transport starts or ends on them, beyond what the
	 * shipped land fill reached.
	 *
	 * <p>The fill was run offline from Lumbridge over the tables it was given, so a floor
	 * reachable only by a ladder no table lists has collision, a component and every wall
	 * but no land bit - Wyrmscraig's ladder tops, the cathedral basement, every place newer
	 * than the tables. A transport row is the same evidence the fill used. The exact end
	 * tile only: a stepping stone's tile is blocked, and snapping to the water beside it
	 * would admit a river as land.
	 */
	private final boolean[] landComponents = new boolean[1 << 16];

	/** Admits the floor under both ends of every transport. Replaces what was admitted before. */
	void admitTransportEnds(java.util.List<GolemTransport> transports)
	{
		Arrays.fill(landComponents, false);
		transportLeads.clear();
		transportTouched.clear();
		Map<Long, java.util.List<GolemTransport>> starting = new HashMap<>();
		for (GolemTransport t : transports)
		{
			starting.computeIfAbsent(tileKey(t.getFromX(), t.getFromY(), t.getFromPlane()),
				k -> new ArrayList<>()).add(t);
		}
		for (GolemTransport t : transports)
		{
			for (int from : spacesAt(t.getFromX(), t.getFromY(), t.getFromPlane()))
			{
				transportTouched.add(from);
				for (int to : spacesOnward(t.getToX(), t.getToY(), t.getToPlane(),
					t.getFromX(), t.getFromY(), t.getFromPlane(), TransportNetwork.CHAIN_HOPS, starting))
				{
					transportTouched.add(to);
					if (from != to)
					{
						transportLeads.computeIfAbsent(from, space -> new ArrayList<>()).add(to);
					}
				}
			}
		}
		transportFloors = 0;
		admittedFloors = 0;
		for (boolean dock : dockFloors)
		{
			admittedFloors += dock ? 1 : 0;
		}
		// Only ever asked whether it is nought, so an instance floor that is also a dock's counts twice.
		admittedFloors += instanceFloorList.size();
		for (GolemTransport t : transports)
		{
			admit(t.getFromX(), t.getFromY(), t.getFromPlane());
			admit(t.getToX(), t.getToY(), t.getToPlane());
		}
		// The walks between spaces are the island map's, not the tables': kept as they were, and laid
		// back over the rebuilt graph, so a route learned does not mean scanning the map again.
		composeLinks();
		log.debug("{} floors admitted as land from transport ends", transportFloors);
	}

	/** Space to the spaces a transport leads to, and every space one touches: the tables' half. */
	private final Map<Integer, java.util.List<Integer>> transportLeads = new HashMap<>();
	private final Set<Integer> transportTouched = new HashSet<>();

	/**
	 * The pairs of spaces each region's walks join, as {@link #pair} keys, and how many regions join
	 * each pair: a region read again replaces what it said, so a door found shut again un-joins its
	 * two spaces. Kept only in addition, a doorway seen open once stayed a way through all session.
	 */
	private final Map<Integer, Set<Long>> joinsByRegion = new HashMap<>();
	private final Map<Long, Integer> joinCount = new HashMap<>();

	private static long pair(int one, int other)
	{
		return (long) Math.min(one, other) << 16 | Math.max(one, other);
	}

	/**
	 * Lays the tables' links and the island map's walks together into {@link #leadsTo} and
	 * {@link #touched}, which linkSpaces reads, and {@link #walkedTo}, which WaysBack reads.
	 */
	private void composeLinks()
	{
		walkedTo.clear();
		for (long joined : joinCount.keySet())
		{
			int one = (int) (joined >> 16);
			int other = (int) (joined & 0xFFFF);
			walkedTo.computeIfAbsent(one, space -> new ArrayList<>()).add(other);
			walkedTo.computeIfAbsent(other, space -> new ArrayList<>()).add(one);
		}
		leadsTo.clear();
		for (Map.Entry<Integer, java.util.List<Integer>> e : transportLeads.entrySet())
		{
			leadsTo.put(e.getKey(), new ArrayList<>(e.getValue()));
		}
		touched.clear();
		touched.addAll(transportTouched);
		// Judged by the walks alone: a one-way transport already leading from one to the other says
		// nothing about the way back, which the walk does.
		for (Map.Entry<Integer, java.util.List<Integer>> walked : walkedTo.entrySet())
		{
			touched.add(walked.getKey());
			for (int to : walked.getValue())
			{
				addOnce(leadsTo.computeIfAbsent(walked.getKey(), space -> new ArrayList<>()), to);
			}
		}
		linkRevision++;
	}

	/**
	 * Joins spaces the island map walks between. Golems at home walk by the island map, which is
	 * live collision and has some doorways open that the shipped collision has shut; a golem walked
	 * through one into a room the mesh counts as a space of its own, and the cut-off sweep carried
	 * it home from a room beside the plinth. Wherever the island map steps from one space into
	 * another, the two are joined, both ways, as a transport would join them. Run over the regions
	 * just read, each of which replaces what it joined before.
	 *
	 * @return how many pairs of spaces were joined or parted
	 */
	int joinWhereWalked(IslandMemory memory, int[] regions)
	{
		int changed = 0;
		for (int region : regions)
		{
			int baseX = (region >> 8) << 6;
			int baseY = (region & 0xFF) << 6;
			Set<Long> now = new HashSet<>();
			for (int plane = 0; plane < 4; plane++)
			{
				// Where the island map holds nothing, it answers from this mesh, whose own edges made
				// the spaces: nothing there can join two.
				if (!memory.holds(region, plane))
				{
					continue;
				}
				for (int x = baseX; x < baseX + 64; x++)
				{
					for (int y = baseY; y < baseY + 64; y++)
					{
						int here = componentAt(x, y, plane);
						if (here == 0)
						{
							continue;
						}
						int north = memory.north(x, y, plane) ? componentAt(x, y + 1, plane) : 0;
						if (north != 0 && north != here)
						{
							now.add(pair(here, north));
						}
						int east = memory.east(x, y, plane) ? componentAt(x + 1, y, plane) : 0;
						if (east != 0 && east != here)
						{
							now.add(pair(here, east));
						}
					}
				}
			}
			Set<Long> before = joinsByRegion.getOrDefault(region, Collections.emptySet());
			for (long joined : now)
			{
				if (!before.contains(joined) && joinCount.merge(joined, 1, Integer::sum) == 1)
				{
					changed++;
				}
			}
			for (long joined : before)
			{
				if (!now.contains(joined) && joinCount.merge(joined, -1, Integer::sum) == 0)
				{
					joinCount.remove(joined);
					changed++;
				}
			}
			if (now.isEmpty())
			{
				joinsByRegion.remove(region);
			}
			else
			{
				joinsByRegion.put(region, now);
			}
		}
		// Only when something changed: run after every harvest, most runs change nothing, and each
		// change rebuilds what golems can get back from.
		if (changed > 0)
		{
			composeLinks();
		}
		log.debug("{} pairs of spaces joined or parted where the island map walks between them", changed);
		return changed;
	}

	/** Forgets every walk joined, for a fresh start from the whole island map. */
	void forgetWalks()
	{
		joinsByRegion.clear();
		joinCount.clear();
		composeLinks();
	}

	private static void addOnce(java.util.List<Integer> spaces, int space)
	{
		if (!spaces.contains(space))
		{
			spaces.add(space);
		}
	}

	/**
	 * The spaces a transport's end touches: its own, or - where it stands on blocked ground, as a
	 * door, a stile or a stepping stone does - the spaces around it. Nearly a third of the ends in
	 * the tables have no component of their own, and reading those as "leads nowhere" cut whole
	 * floors off from home.
	 *
	 * <p>Generous on purpose. Joining two spaces that a golem cannot really walk between leaves a
	 * golem where it stands; refusing to join two that it can carries one home from somewhere it
	 * belonged.
	 */
	/**
	 * The spaces a transport's end touches, following a chain on from an end that touches none -
	 * a stepping stone mid-river, all water round it - as TransportNetwork.leadsToGround does.
	 */
	java.util.List<Integer> spacesOnward(int x, int y, int plane, int cameX, int cameY, int camePlane,
		int hops, Map<Long, java.util.List<GolemTransport>> starting)
	{
		java.util.List<Integer> here = spacesAt(x, y, plane);
		if (!here.isEmpty() || hops <= 0)
		{
			return here;
		}
		java.util.List<Integer> found = new ArrayList<>(2);
		for (GolemTransport onward : starting.getOrDefault(tileKey(x, y, plane), Collections.emptyList()))
		{
			if (onward.getToX() == cameX && onward.getToY() == cameY && onward.getToPlane() == camePlane)
			{
				continue;
			}
			for (int space : spacesOnward(onward.getToX(), onward.getToY(), onward.getToPlane(), x, y, plane,
				hops - 1, starting))
			{
				if (!found.contains(space))
				{
					found.add(space);
				}
			}
		}
		return found;
	}

	static long tileKey(int x, int y, int plane)
	{
		return ((long) plane << 32) | ((long) x << 16) | y;
	}

	java.util.List<Integer> spacesAt(int x, int y, int plane)
	{
		int own = componentAt(x, y, plane);
		if (own != 0)
		{
			return Collections.singletonList(own);
		}
		java.util.List<Integer> around = new ArrayList<>(4);
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				int space = componentAt(x + dx, y + dy, plane);
				if (space != 0 && !around.contains(space))
				{
					around.add(space);
				}
			}
		}
		return around;
	}

	private void admit(int x, int y, int plane)
	{
		// Instances are rebuilt each visit; their coordinates name no lasting floor.
		if (x >= 6400 || isLand(x, y, plane) || isOcean(x, y, plane))
		{
			return;
		}
		int component = componentAt(x, y, plane);
		if (component != 0 && !landComponents[component])
		{
			landComponents[component] = true;
			transportFloors++;
			admittedFloors += dockFloors[component] ? 0 : 1;
		}
	}

	private boolean flag(int x, int y, int plane, int which)
	{
		Region region = region(regionIdOf(x, y), plane);
		if (region == null)
		{
			return false;
		}
		return (region.collision[(y & REGION_MASK) * REGION_SIZE + (x & REGION_MASK)] >>> which & 1) != 0;
	}

	/** The land fill's bit for this tile. */
	private boolean isLand(int x, int y, int plane)
	{
		Region region = region(regionIdOf(x, y), plane);
		return region != null && bit(region.land, x, y);
	}

	private Region region(int regionId, int plane)
	{
		if (regionId >= 0 && regionId < FAST_REGIONS && (plane & 0xF) < 4)
		{
			int at = regionId << 2 | (plane & 0xF);
			Region region = byRegion[at];
			return region != null || !packedAt[at] ? region : unpack(regionId, plane);
		}
		Region region = regions.get(key(regionId, plane));
		return region != null || !packed.containsKey(key(regionId, plane)) ? region : unpack(regionId, plane);
	}

	/**
	 * Region-planes no fill reached, each deflated on its own, by key: most of the world, and
	 * nowhere a golem goes until a learned route leads there. Unpacked on first use and kept.
	 */
	private final Map<Long, byte[]> packed = new HashMap<>();

	/** Whether {@link #packed} holds a region-plane, indexed as {@link #byRegion}. */
	private final boolean[] packedAt = new boolean[FAST_REGIONS << 2];

	/** True once the mesh holds any square at all: a mesh that failed to load knows nothing. */
	boolean holdsAnySquare()
	{
		return !regions.isEmpty() || !packed.isEmpty();
	}

	/**
	 * True if the game has this map square on any floor: it is in the mesh, read or packed. Under the
	 * lock unpack holds, which moves a region-plane from packed to read: asked mid-move, a square
	 * was in neither, and the saved map dropped real ground as a square that does not exist.
	 */
	synchronized boolean hasSquare(int regionId)
	{
		for (int plane = 0; plane < 4; plane++)
		{
			long key = key(regionId, plane);
			if (regions.containsKey(key) || packed.containsKey(key))
			{
				return true;
			}
		}
		return false;
	}

	/** Unpacks a packed region-plane into the mesh, once; null if it will not read. */
	private synchronized Region unpack(int regionId, int plane)
	{
		long key = key(regionId, plane);
		Region region = regions.get(key);
		byte[] blob = packed.remove(key);
		if (region != null || blob == null)
		{
			return region;
		}
		try (DataInputStream data = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(blob))))
		{
			region = readRecord(data);
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("World mesh region {} plane {} unreadable", regionId, plane, e);
			region = null;
		}
		if (region != null)
		{
			put(regionId, plane, region);
		}
		if (regionId >= 0 && regionId < FAST_REGIONS && (plane & 0xF) < 4)
		{
			packedAt[regionId << 2 | (plane & 0xF)] = false;
		}
		return region;
	}

	/** One region-plane's record: the tile bytes, ocean, isolated, components, land, water. */
	private static Region readRecord(DataInputStream data) throws IOException
	{
		byte[] flags = new byte[COLLISION_BYTES];
		data.readFully(flags);
		byte[] sea = new byte[DERIVED_BYTES];
		data.readFully(sea);
		byte[] alone = new byte[DERIVED_BYTES];
		data.readFully(alone);
		byte[] parts = new byte[COMPONENT_BYTES];
		data.readFully(parts);
		byte[] ground = new byte[DERIVED_BYTES];
		data.readFully(ground);
		byte[] wet = new byte[DERIVED_BYTES];
		data.readFully(wet);
		int[] palette = palette(parts);
		if (palette == null)
		{
			char[] wide = new char[TILES_PER_PLANE];
			for (int tile = 0; tile < TILES_PER_PLANE; tile++)
			{
				wide[tile] = (char) (((parts[tile * 2] & 0xFF) << 8) | (parts[tile * 2 + 1] & 0xFF));
			}
			return new Region(flags, sea, alone, null, new int[1], wide, ground, wet);
		}
		return new Region(flags, sea, alone, indexInto(parts, palette), palette, null, ground, wet);
	}

	private void put(int regionId, int plane, Region region)
	{
		regions.put(key(regionId, plane), region);
		if (regionId >= 0 && regionId < FAST_REGIONS && (plane & 0xF) < 4)
		{
			byRegion[regionId << 2 | (plane & 0xF)] = region;
		}
	}

	private static boolean bit(byte[] bits, int x, int y)
	{
		int bit = (y & REGION_MASK) * REGION_SIZE + (x & REGION_MASK);
		return (bits[bit >> 3] >>> (bit & 7) & 1) != 0;
	}

	private static int componentOf(Region region, int x, int y)
	{
		int tile = (y & REGION_MASK) * REGION_SIZE + (x & REGION_MASK);
		if (region.wide != null)
		{
			return region.wide[tile];
		}
		return region.palette[region.components == null ? 0 : region.components[tile] & 0xFF];
	}

	/** The distinct component ids in a region-plane's two-byte map, in order of first use; null past 256. */
	private static int[] palette(byte[] parts)
	{
		int[] ids = new int[256];
		int count = 0;
		int last = -1;
		for (int tile = 0; tile < TILES_PER_PLANE; tile++)
		{
			int id = ((parts[tile * 2] & 0xFF) << 8) | (parts[tile * 2 + 1] & 0xFF);
			// Neighbours along a row are nearly always one space: the scan is for the change.
			if (id == last)
			{
				continue;
			}
			last = id;
			int i = 0;
			while (i < count && ids[i] != id)
			{
				i++;
			}
			if (i == count)
			{
				if (count == ids.length)
				{
					// More than a byte can index: this region-plane keeps its ids outright.
					return null;
				}
				ids[count++] = id;
			}
		}
		return Arrays.copyOf(ids, count);
	}

	/** Each tile's index into the palette; null when there is only one id. */
	private static byte[] indexInto(byte[] parts, int[] palette)
	{
		if (palette.length == 1)
		{
			return null;
		}
		byte[] index = new byte[TILES_PER_PLANE];
		int last = palette[0];
		int at = 0;
		for (int tile = 0; tile < TILES_PER_PLANE; tile++)
		{
			int id = ((parts[tile * 2] & 0xFF) << 8) | (parts[tile * 2 + 1] & 0xFF);
			if (id != last)
			{
				at = 0;
				while (palette[at] != id)
				{
					at++;
				}
				last = id;
			}
			index[tile] = (byte) at;
		}
		return index;
	}

	private static int regionIdOf(int x, int y)
	{
		return ((x >> 6) << 8) | (y >> 6);
	}

	private static long key(int regionId, int plane)
	{
		return ((long) regionId << 4) | (plane & 0xF);
	}
}
