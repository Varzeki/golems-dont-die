package com.golemsdontdie;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Constants;

/**
 * Where a golem may walk, anywhere in the world. Read-only, shipped, never written to.
 *
 * <p>The island map this replaces covered nine regions and was small enough to keep in a
 * config value. This covers 1,158 — the ocean plus every bit of land a golem could walk
 * or climb its way to from Lumbridge — and at 440 KB it belongs in the jar and nowhere
 * else. Nothing here is ever persisted to configuration; see {@link IslandMemory} for the
 * live harvest, which is the half that changes.
 *
 * <p>Three bits per tile rather than the island map's two:
 *
 * <ul>
 *   <li><b>north</b> and <b>east</b> — the same packing Shortest Path uses, and the same
 *       one {@code IslandMemory} already reads. South and west are the north and east of
 *       the neighbouring tile.</li>
 *   <li><b>ocean</b> — part of the single connected sea. Two ports can only be sailed
 *       between if both sit on it, and the check matters because enclosed water exists:
 *       the inland body on Karamja is 26,000 tiles that lead nowhere.</li>
 *   <li><b>isolated</b> — in a component of fewer than 50 tiles. A golem left on one
 *       could never walk out of it, so the fact is computed offline and simply looked
 *       up rather than rediscovered by a flood fill the client cannot afford.</li>
 * </ul>
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

	/** Region and plane to its three packed bitmaps. */
	private final Map<Long, byte[]> collision = new HashMap<>();
	private final Map<Long, byte[]> ocean = new HashMap<>();
	private final Map<Long, byte[]> isolated = new HashMap<>();

	/**
	 * Tiles the land fill reached — ground a golem may stand on, on foot.
	 *
	 * <p>This is the exact answer, and the ocean bit was not. Ocean marks only the one
	 * connected sea, so cave water, lakes and enclosed basins read as passable and not
	 * ocean, and golems walked out across them. Shoreline edges are blocked in the source
	 * map, so a fill that starts on land can never leak onto water: what it reached is land
	 * and nothing else.
	 */
	private final Map<Long, byte[]> land = new HashMap<>();

	/**
	 * Which connected component each tile belongs to, two bytes per tile, 0 for none.
	 *
	 * <p>The one thing the mesh can say that no per-tile flag can: whether two tiles are
	 * connected <b>to each other</b>. Everything else here answers "may a golem stand
	 * there", which is a different and much weaker question — a sealed room is perfectly
	 * good ground and a golem put in one is stuck in it forever.
	 *
	 * <p>{@code isolated} was the previous approximation and only ever caught components
	 * under fifty tiles. Any larger sealed space passed it.
	 */
	private final Map<Long, byte[]> components = new HashMap<>();

	private boolean loaded;

	/**
	 * Reads the bundled mesh.
	 *
	 * <p>A missing or corrupt resource leaves every query answering "unknown", which is
	 * survivable: the live harvest still works, so golems roam what the player has walked,
	 * exactly as the island-only version did. Refusing to start over an optional table
	 * would be a worse failure than a smaller world.
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
					log.warn("World mesh is an old format; rebuild it with BuildWorldMesh");
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
					collision.put(key, flags);

					byte[] sea = new byte[DERIVED_BYTES];
					data.readFully(sea);
					ocean.put(key, sea);

					byte[] alone = new byte[DERIVED_BYTES];
					data.readFully(alone);
					isolated.put(key, alone);

					byte[] parts = new byte[COMPONENT_BYTES];
					data.readFully(parts);
					components.put(key, parts);

					byte[] ground = new byte[DERIVED_BYTES];
					data.readFully(ground);
					land.put(key, ground);
				}
				log.debug("Loaded world mesh: {} region-planes", entries);
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Bundled world mesh unreadable", e);
			collision.clear();
			ocean.clear();
			isolated.clear();
			land.clear();
			components.clear();
		}
	}

	/** True if the mesh has anything at all to say about this region and plane. */
	boolean covers(int regionId, int plane)
	{
		return collision.containsKey(key(regionId, plane));
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
		return derived(ocean, x, y, plane);
	}

	/**
	 * True if this tile sits in a component too small to wander.
	 *
	 * <p>The one question stuck detection has to answer, reduced to a bit lookup. A golem
	 * restored onto one of these is relocated before it ever gets the chance to spend the
	 * rest of its life walking into the same four walls.
	 */
	boolean isIsolated(int x, int y, int plane)
	{
		return derived(isolated, x, y, plane);
	}

	/**
	 * Which connected space this tile belongs to, or 0 if the mesh does not know.
	 *
	 * <p>Zero means "no answer" rather than "no component", and callers must treat it as
	 * unknown. A tile outside the shipped regions, on ground the player has walked but the
	 * mesh never covered, is a perfectly good place for a golem to be — refusing to move
	 * there because the mesh is silent would shrink the world to the shipped file.
	 */
	int componentAt(int x, int y, int plane)
	{
		byte[] parts = components.get(key(regionIdOf(x, y), plane));
		if (parts == null)
		{
			return 0;
		}
		int tile = (y & REGION_MASK) * REGION_SIZE + (x & REGION_MASK);
		return ((parts[tile * 2] & 0xff) << 8) | (parts[tile * 2 + 1] & 0xff);
	}

	/**
	 * True if a golem standing at the first tile could walk to the second.
	 *
	 * <p>Unknown counts as yes. The mesh covers most of the game but not all of it, and the
	 * live harvest covers ground the mesh never will — so a silent answer must not be read
	 * as a refusal, or golems would be confined to the regions that happened to ship.
	 */
	boolean sameComponent(int fromX, int fromY, int toX, int toY, int plane)
	{
		int from = componentAt(fromX, fromY, plane);
		int to = componentAt(toX, toY, plane);
		return from == 0 || to == 0 || from == to;
	}

	/** True if something could stand here — the tile can be left in some direction. */
	boolean isWalkable(int x, int y, int plane)
	{
		return north(x, y, plane) || east(x, y, plane)
			|| north(x, y - 1, plane) || east(x - 1, y, plane);
	}

	/**
	 * True if a golem could stand here <em>on foot</em>.
	 *
	 * <p>The distinction this draws is the single most important one in the file, and
	 * leaving it out put golems out on the open sea.
	 *
	 * <p>Open water is <b>passable</b> in the source collision map — that is not a bug, it
	 * is why sea navigation works at all: shoreline edges are blocked and the ocean is
	 * open, so a boat paths across it with the same flags a golem uses in a corridor. But
	 * it means the passability bits alone cannot tell walkable ground from crossable
	 * water. Both read as open.
	 *
	 * <p>The ocean bit is what separates them, and anything to do with walking has to
	 * consult it. Only {@link SeaMesh}, which is routing a boat, may treat the sea as
	 * passable.
	 */
	boolean isLandWalkable(int x, int y, int plane)
	{
		if (!derived(land, x, y, plane)
			&& (landComponents.isEmpty() && dockFloors.isEmpty()
				|| !landComponents.contains(componentAt(x, y, plane)) && !dockFloors.contains(componentAt(x, y, plane))))
		{
			return false;
		}
		return isWalkable(x, y, plane);
	}

	/**
	 * Floors counted as land because a dock stands on them.
	 *
	 * <p>Kept apart from the transport floors, which are replaced whenever a route is learned.
	 * Sailing added islands no transport table has a row on, so the land fill never reached
	 * them: twenty-two of the sixty-one docks had no land beside them at all, and a golem that
	 * sailed there would have had nowhere to step off.
	 */
	private final java.util.Set<Integer> dockFloors = new java.util.HashSet<>();

	/** Admits the floor under a dock's quayside, if it is a floor at all. */
	void admitDockFloor(int x, int y, int plane)
	{
		if (x >= 6400 || isOcean(x, y, plane))
		{
			return;
		}
		int component = componentAt(x, y, plane);
		if (component != 0)
		{
			dockFloors.add(component);
		}
	}

	/**
	 * Floors counted as land because a transport starts or ends on them, beyond what the
	 * shipped land fill reached.
	 *
	 * <p>The fill is run offline from Lumbridge and every row of the tables it was given, so a
	 * floor whose only way in is a ladder no table lists has collision, a component and every
	 * wall in place — and no land bit, which made it somewhere no golem would ever go. That was
	 * Wyrmscraig's ladder tops and the cathedral basement, and it is every place newer than the
	 * tables. Any row the plugin holds is the same evidence the fill used: a player can stand at
	 * each end. So the connected floor under each end is admitted, as the fill would have done.
	 *
	 * <p>Only the exact end tile, never a neighbour. A stepping stone's tile is blocked, and
	 * snapping to the water beside it would admit a river as land.
	 */
	private final java.util.Set<Integer> landComponents = new java.util.HashSet<>();

	/** Admits the floor under both ends of every transport. Replaces what was admitted before. */
	void admitTransportEnds(java.util.List<GolemTransport> transports)
	{
		landComponents.clear();
		for (GolemTransport t : transports)
		{
			admit(t.getFromX(), t.getFromY(), t.getFromPlane());
			admit(t.getToX(), t.getToY(), t.getToPlane());
		}
		log.debug("{} floors admitted as land from transport ends", landComponents.size());
	}

	private void admit(int x, int y, int plane)
	{
		// Instances are rebuilt each visit; their coordinates name no lasting floor.
		if (x >= 6400 || derived(land, x, y, plane) || isOcean(x, y, plane))
		{
			return;
		}
		int component = componentAt(x, y, plane);
		if (component != 0)
		{
			landComponents.add(component);
		}
	}

	private boolean flag(int x, int y, int plane, int which)
	{
		byte[] bits = collision.get(key(regionIdOf(x, y), plane));
		if (bits == null)
		{
			return false;
		}
		int bit = ((y & REGION_MASK) * REGION_SIZE + (x & REGION_MASK)) * 2 + which;
		return (bits[bit >> 3] >>> (bit & 7) & 1) != 0;
	}

	private boolean derived(Map<Long, byte[]> map, int x, int y, int plane)
	{
		byte[] bits = map.get(key(regionIdOf(x, y), plane));
		if (bits == null)
		{
			return false;
		}
		int bit = (y & REGION_MASK) * REGION_SIZE + (x & REGION_MASK);
		return (bits[bit >> 3] >>> (bit & 7) & 1) != 0;
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
