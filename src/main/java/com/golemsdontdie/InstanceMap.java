package com.golemsdontdie;

import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;

/**
 * Between an instance and the fixed place in the world it is copied from.
 *
 * <p>An instance - a boss room behind a church pew - is built fresh on every visit, in
 * coordinates that mean nothing next time. Its template, the static copy elsewhere assembled from
 * in eight-tile chunks, does stay put, so all a golem keeps about an instance is in template
 * coordinates: golems wander the template room and are drawn in whichever instance is loaded.
 *
 * <p>Rotated chunks are mapped too. A golem walks the template as it is, where the walls are
 * where the collision says, and only its drawing is turned: its place in the chunk and the way
 * it faces. The live harvest still reads only unrotated chunks, as a turned edge would be
 * recorded on the wrong side; the shipped mesh has every template.
 */
final class InstanceMap
{
	private static final int CHUNK = 8;

	private InstanceMap()
	{
	}

	/** The template of a loaded instance tile; the tile itself outside one; null where no chunk is. */
	static WorldPoint templateOf(WorldView wv, WorldPoint tile)
	{
		if (tile == null || wv == null || !wv.isInstance())
		{
			return tile;
		}
		int sceneX = tile.getX() - wv.getBaseX();
		int sceneY = tile.getY() - wv.getBaseY();
		int data = chunkAt(wv, tile.getPlane(), sceneX, sceneY);
		if (data <= 0)
		{
			return null;
		}
		// Turned back the way the chunk was turned, as the client's own WorldPoint.fromLocalInstance.
		int[] at = turn(sceneX & (CHUNK - 1), sceneY & (CHUNK - 1), 4 - rotation(data));
		return new WorldPoint(templateChunkX(data) * CHUNK + at[0], templateChunkY(data) * CHUNK + at[1],
			templatePlane(data));
	}

	/**
	 * A tile's place in its eight-tile chunk after a number of quarter turns, as the client turns
	 * an instance chunk: each turn takes (x, y) to (y, 7 - x), east to south. A template tile at
	 * (x, y) is drawn in a chunk of rotation r at turn(x, y, r).
	 */
	static int[] turn(int x, int y, int quarters)
	{
		switch (quarters & 3)
		{
			case 1:
				return new int[]{y, CHUNK - 1 - x};
			case 2:
				return new int[]{CHUNK - 1 - x, CHUNK - 1 - y};
			case 3:
				return new int[]{CHUNK - 1 - y, x};
			default:
				return new int[]{x, y};
		}
	}

	/** The raw chunk data under a scene tile, or -1 outside the scene. */
	static int chunkAt(WorldView wv, int plane, int sceneX, int sceneY)
	{
		int[][][] chunks = wv.getInstanceTemplateChunks();
		if (chunks == null || plane < 0 || plane >= chunks.length || sceneX < 0 || sceneY < 0)
		{
			return -1;
		}
		int cx = sceneX / CHUNK;
		int cy = sceneY / CHUNK;
		if (cx >= chunks[plane].length || cy >= chunks[plane][cx].length)
		{
			return -1;
		}
		return chunks[plane][cx][cy];
	}

	/**
	 * Where each template chunk sits in the loaded instance, as {sceneChunkX, sceneChunkY,
	 * scenePlane, rotation}, keyed by {@link #chunkKey} of the template chunk. Rotated chunks too:
	 * a golem walks the template as it is and is drawn turned. See turn. A template chunk the
	 * instance uses more than once has every copy listed; see nearest.
	 */
	static Map<Long, List<int[]>> sceneChunks(WorldView wv)
	{
		Map<Long, List<int[]>> out = new HashMap<>();
		int[][][] chunks = wv.getInstanceTemplateChunks();
		if (chunks == null)
		{
			return out;
		}
		for (int z = 0; z < chunks.length; z++)
		{
			for (int cx = 0; cx < chunks[z].length; cx++)
			{
				for (int cy = 0; cy < chunks[z][cx].length; cy++)
				{
					int data = chunks[z][cx][cy];
					if (data <= 0)
					{
						continue;
					}
					out.computeIfAbsent(chunkKey(templateChunkX(data), templateChunkY(data), templatePlane(data)),
						k -> new ArrayList<>(1)).add(new int[]{cx, cy, z, rotation(data)});
				}
			}
		}
		return out;
	}

	/**
	 * The copy of a template chunk nearest a scene chunk, for a golem drawn in an instance built
	 * from the same chunk twice or more: where the player is, so a golem is drawn where it can be
	 * seen. Null for no copies.
	 */
	static int[] nearest(List<int[]> copies, int sceneChunkX, int sceneChunkY)
	{
		if (copies == null || copies.isEmpty())
		{
			return null;
		}
		int[] best = copies.get(0);
		int bestSpan = Integer.MAX_VALUE;
		for (int[] copy : copies)
		{
			int span = Math.max(Math.abs(copy[0] - sceneChunkX), Math.abs(copy[1] - sceneChunkY));
			if (span < bestSpan)
			{
				best = copy;
				bestSpan = span;
			}
		}
		return best;
	}

	static long chunkKey(int chunkX, int chunkY, int plane)
	{
		return ((long) plane << 40) | ((long) chunkX << 20) | chunkY;
	}

	static int rotation(int data)
	{
		return data >> 1 & 0x3;
	}

	static int templateChunkX(int data)
	{
		return data >> 14 & 0x3FF;
	}

	static int templateChunkY(int data)
	{
		return data >> 3 & 0x7FF;
	}

	static int templatePlane(int data)
	{
		return data >> 24 & 0x3;
	}
}
