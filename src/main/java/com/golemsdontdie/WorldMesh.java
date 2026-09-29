package com.golemsdontdie;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.inject.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;

/**
 * Where a golem may walk, anywhere in the world. Read-only, shipped, never written to.
 *
 * <p>The island map this replaces covered nine regions and fitted in a config value; this
 * covers 1,158, the ocean plus every bit of land reachable from Lumbridge, and at 440 KB
 * belongs in the jar.
 *
 * <p>Three bits per tile rather than two. <b>north</b> and <b>east</b> use the packing
 * Shortest Path and {@code IslandMemory} use; south and west are the neighbouring tile's.
 * <b>ocean</b> is the single connected sea: two ports are only sailable between if both sit
 * on it, and enclosed water exists — Karamja's inland body is 26,000 tiles leading nowhere.
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

	/** Two bits per tile for passability, one each for the two derived maps. */
	private static final int COLLISION_BYTES = TILES_PER_PLANE * 2 / 8;
	private static final int DERIVED_BYTES = TILES_PER_PLANE / 8;

	/** Two bytes a tile: there are 14,288 components, which does not fit in one. */
	private static final int COMPONENT_BYTES = TILES_PER_PLANE * 2;

	/** Impossible as an entry count, so an old file is refused rather than misread. */
	private static final int MAGIC = -0x60137;

	/** 2 added the per-tile component map. */
	private static final int VERSION = 2;

	static final int FLAG_NORTH = 0;
	static final int FLAG_EAST = 1;

	/**
	 * Everything the mesh holds for one region and plane. One object rather than five maps
	 * keyed alike: a walkability check touches several, planning asks millions of times,
	 * and the boxed lookups were most of the cost.
	 */
	private static final class Region
	{
		/** Two bits per tile for passability. */
		final byte[] collision;

		/** The single connected sea. */
		final byte[] ocean;

		/** Components too small to wander. */
		final byte[] isolated;

		/**
		 * Which connected component each tile belongs to, two bytes per tile, 0 for none.
		 * The one thing no per-tile flag can say: whether two tiles are connected <b>to each
		 * other</b>. Everything else answers "may a golem stand there", and a sealed room is
		 * good ground. {@code isolated} only caught components under fifty tiles.
		 */
		final byte[] components;

		/**
		 * Tiles the land fill reached — ground a golem may stand on, on foot. The ocean bit
		 * marks only the one connected sea, so cave water, lakes and enclosed basins read as
		 * passable and golems walked out across them. Shoreline edges are blocked, so a fill
		 * starting on land cannot leak.
		 */
		final byte[] land;

		Region(byte[] collision, byte[] ocean, byte[] isolated, byte[] components, byte[] land)
		{
			this.collision = collision;
			this.ocean = ocean;
			this.isolated = isolated;
			this.components = components;
			this.land = land;
		}
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
					long key = key(regionId, plane);

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

					Region region = new Region(flags, sea, alone, parts, ground);
					regions.put(key, region);
					if (regionId >= 0 && regionId < FAST_REGIONS && (plane & 0xF) < 4)
					{
						byRegion[regionId << 2 | (plane & 0xF)] = region;
					}
				}
				log.debug("Loaded world mesh: {} region-planes", entries);
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Bundled world mesh unreadable", e);
			regions.clear();
			Arrays.fill(byRegion, null);
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
	 * True if this tile sits in a component too small to wander — the question stuck
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
		byte[] parts = region.components;
		int tile = (y & REGION_MASK) * REGION_SIZE + (x & REGION_MASK);
		return ((parts[tile * 2] & 0xff) << 8) | (parts[tile * 2 + 1] & 0xff);
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
	 * True if this tile is water a boat sails but nobody walks, and is not the sea:
	 * Wyrmscraig's underground lake, which has no bit of its own. It is passable because
	 * boats cross it and the client does not block it, so the golems' map read it as ground
	 * and golems walked on the water. Known by its connected component, bounds-checked
	 * first so the rest of the world pays one comparison.
	 */
	boolean isInlandWater(int x, int y, int plane)
	{
		if (plane != 0)
		{
			return false;
		}
		if (lakeComponent < 0)
		{
			findLake();
		}
		return lakeComponent != 0 && x >= lakeMinX && x <= lakeMaxX && y >= lakeMinY && y <= lakeMaxY
			&& componentAt(x, y, 0) == lakeComponent;
	}

	/** The underground lake's component and bounds; -1 until looked for, 0 if the mesh has none. */
	private int lakeComponent = -1;
	private int lakeMinX;
	private int lakeMaxX;
	private int lakeMinY;
	private int lakeMaxY;

	/** How far from its mouth the lake is looked for, in tiles. It is a few dozen across. */
	private static final int LAKE_SEARCH = 96;

	private synchronized void findLake()
	{
		if (lakeComponent >= 0)
		{
			return;
		}
		int lake = componentAt(GolemContent.CAVE_LAKE_MOUTH_X, GolemContent.CAVE_LAKE_MOUTH_Y, 0);
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		if (lake != 0)
		{
			for (int x = GolemContent.CAVE_LAKE_MOUTH_X - LAKE_SEARCH; x <= GolemContent.CAVE_LAKE_MOUTH_X + LAKE_SEARCH; x++)
			{
				for (int y = GolemContent.CAVE_LAKE_MOUTH_Y - LAKE_SEARCH; y <= GolemContent.CAVE_LAKE_MOUTH_Y + LAKE_SEARCH; y++)
				{
					if (componentAt(x, y, 0) == lake)
					{
						minX = Math.min(minX, x);
						maxX = Math.max(maxX, x);
						minY = Math.min(minY, y);
						maxY = Math.max(maxY, y);
					}
				}
			}
		}
		lakeMinX = minX;
		lakeMaxX = maxX;
		lakeMinY = minY;
		lakeMaxY = maxY;
		lakeComponent = lake;
	}

	/** True if something could stand here — the tile can be left in some direction. */
	boolean isWalkable(int x, int y, int plane)
	{
		return north(x, y, plane) || east(x, y, plane)
			|| north(x, y - 1, plane) || east(x - 1, y, plane);
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
			if (!landComponents[component] && !dockFloors[component])
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
	 * cannot get out again is the stuck watchdog's business, not this one's.
	 */
	private boolean[] fromHome;

	/** Every space a dock stands in. The sea joins them to each other. */
	private final Set<Integer> ports = new HashSet<>();

	/** Every space a transport touches, at either end. Rebuilt with the graph. */
	private final Set<Integer> touched = new HashSet<>();

	/** Space to the spaces a transport leads to. */
	private final Map<Integer, java.util.List<Integer>> leadsTo = new HashMap<>();

	/**
	 * True if this tile is in a pocket too small to wander, that no route touches and no dock
	 * stands in.
	 *
	 * <p>The plainest trap there is, and the one answer here that barely leans on the tables: the
	 * pocket is under fifty tiles by the shipped mesh's own reckoning, so a golem in it is walking
	 * in circles, and with nothing starting or ending there it has neither a shortcut to take nor
	 * one to reverse back out of. Judged on its own so that it still holds when the wider question
	 * — does this ground join up with home — has been answered by a world the plugin has misread.
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
	 * but no land bit — Wyrmscraig's ladder tops, the cathedral basement, every place newer
	 * than the tables. A transport row is the same evidence the fill used. The exact end
	 * tile only: a stepping stone's tile is blocked, and snapping to the water beside it
	 * would admit a river as land.
	 */
	private final boolean[] landComponents = new boolean[1 << 16];

	/** Admits the floor under both ends of every transport. Replaces what was admitted before. */
	void admitTransportEnds(java.util.List<GolemTransport> transports)
	{
		Arrays.fill(landComponents, false);
		leadsTo.clear();
		touched.clear();
		for (GolemTransport t : transports)
		{
			for (int from : spacesAt(t.getFromX(), t.getFromY(), t.getFromPlane()))
			{
				touched.add(from);
				for (int to : spacesAt(t.getToX(), t.getToY(), t.getToPlane()))
				{
					touched.add(to);
					if (from != to)
					{
						leadsTo.computeIfAbsent(from, space -> new ArrayList<>()).add(to);
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
		for (GolemTransport t : transports)
		{
			admit(t.getFromX(), t.getFromY(), t.getFromPlane());
			admit(t.getToX(), t.getToY(), t.getToPlane());
		}
		log.debug("{} floors admitted as land from transport ends", transportFloors);
	}

	/**
	 * The spaces a transport's end touches: its own, or — where it stands on blocked ground, as a
	 * door, a stile or a stepping stone does — the spaces around it. Nearly a third of the ends in
	 * the tables have no component of their own, and reading those as "leads nowhere" cut whole
	 * floors off from home.
	 *
	 * <p>Generous on purpose. Joining two spaces that a golem cannot really walk between leaves a
	 * golem where it stands; refusing to join two that it can carries one home from somewhere it
	 * belonged.
	 */
	private java.util.List<Integer> spacesAt(int x, int y, int plane)
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
		byte[] bits = region.collision;
		int bit = ((y & REGION_MASK) * REGION_SIZE + (x & REGION_MASK)) * 2 + which;
		return (bits[bit >> 3] >>> (bit & 7) & 1) != 0;
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
			return byRegion[regionId << 2 | (plane & 0xF)];
		}
		return regions.get(key(regionId, plane));
	}

	private static boolean bit(byte[] bits, int x, int y)
	{
		int bit = (y & REGION_MASK) * REGION_SIZE + (x & REGION_MASK);
		return (bits[bit >> 3] >>> (bit & 7) & 1) != 0;
	}

	private static int componentOf(Region region, int x, int y)
	{
		int tile = (y & REGION_MASK) * REGION_SIZE + (x & REGION_MASK);
		return ((region.components[tile * 2] & 0xff) << 8) | (region.components[tile * 2 + 1] & 0xff);
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
