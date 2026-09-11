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
		return derived(land, x, y, plane) && isWalkable(x, y, plane);
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
