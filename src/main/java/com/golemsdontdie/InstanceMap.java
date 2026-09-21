package com.golemsdontdie;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * Between an instance and the fixed place in the world it is copied from.
 *
 * <p>An instance — a boss room behind a church pew — is built fresh on every visit, in
 * coordinates that mean nothing next time. Its template, the static copy elsewhere assembled from
 * in eight-tile chunks, does stay put, so all a golem keeps about an instance is in template
 * coordinates: golems wander the template room and are drawn in whichever instance is loaded.
 *
 * <p>Only unrotated chunks are mapped; rotation turns walls and headings as well as positions.
 */
final class InstanceMap
{
	private static final int CHUNK = 8;

	private InstanceMap()
	{
	}

	/** The template of a loaded instance tile; the tile itself outside one; null if rotated. */
	static WorldPoint templateOf(WorldView wv, WorldPoint tile)
	{
		if (tile == null || wv == null || !wv.isInstance())
		{
			return tile;
		}
		int sceneX = tile.getX() - wv.getBaseX();
		int sceneY = tile.getY() - wv.getBaseY();
		int data = chunkAt(wv, tile.getPlane(), sceneX, sceneY);
		if (data <= 0 || rotation(data) != 0)
		{
			return null;
		}
		return new WorldPoint(templateChunkX(data) * CHUNK + (sceneX & (CHUNK - 1)),
			templateChunkY(data) * CHUNK + (sceneY & (CHUNK - 1)), templatePlane(data));
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
	 * scenePlane}, keyed by {@link #chunkKey} of the template chunk. Unrotated chunks only.
	 */
	static Map<Long, int[]> sceneChunks(WorldView wv)
	{
		Map<Long, int[]> out = new HashMap<>();
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
					if (data <= 0 || rotation(data) != 0)
					{
						continue;
					}
					out.putIfAbsent(chunkKey(templateChunkX(data), templateChunkY(data), templatePlane(data)),
						new int[]{cx, cy, z});
				}
			}
		}
		return out;
	}

	/**
	 * Where a template tile appears in the loaded instance, given {@link #sceneChunks}; null if
	 * its chunk is not part of it.
	 */
	static WorldPoint instanceTileOf(WorldView wv, Map<Long, int[]> sceneChunks, int x, int y, int plane)
	{
		int[] scene = sceneChunks.get(chunkKey(x >> 3, y >> 3, plane));
		if (scene == null)
		{
			return null;
		}
		return new WorldPoint(wv.getBaseX() + (scene[0] << 3) + (x & (CHUNK - 1)),
			wv.getBaseY() + (scene[1] << 3) + (y & (CHUNK - 1)), scene[2]);
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
