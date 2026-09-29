package com.golemsdontdie;

/**
 * How the game lays the world out north to south, in one place.
 *
 * <p>Three layers, and the difference matters. The surface map runs up to {@link #SURFACE_TOP}:
 * the most northerly land on it, the Frozen Temple, is at 4121. Caves dug under that ground are laid
 * out {@link #CAVE_OFFSET} tiles north of what they run beneath, which is what lets the world map
 * draw a cave over its hill. And between the two is everything laid out apart from the map with no
 * ground above it that can be named - God Wars, TzHaar, Motherlode Mine and Zanaris, but also
 * Prifddinas, which is not underground at all. Nothing about a tile in that band says where it is.
 */
final class WorldLayout
{
	/** The north edge of the surface map, in tiles. */
	static final int SURFACE_TOP = 4160;

	/** How far north of the ground above it a cave is laid out, in tiles. */
	static final int CAVE_OFFSET = 6400;

	private WorldLayout()
	{
	}

	/** On the surface map. */
	static boolean isSurface(int y)
	{
		return y < SURFACE_TOP;
	}

	/** In a cave dug under the surface, whose ground above is {@link #groundAbove}. */
	static boolean isCave(int y)
	{
		return y >= CAVE_OFFSET;
	}

	/**
	 * The y of the ground this tile lies under, or of the tile itself on the surface: or -1 for
	 * somewhere laid out apart from the map, which lies under nowhere that can be said.
	 */
	static int groundAbove(int y)
	{
		return isSurface(y) ? y : isCave(y) ? y - CAVE_OFFSET : -1;
	}

	/**
	 * Whether two tiles are on the same layer, which is when the tiles between them are a walk:
	 * a cave and the ground over it are six thousand tiles apart by the numbers and one ladder by
	 * the world. Two places each laid out apart from the map are only near each other if their
	 * numbers say so and are close enough to be one place.
	 */
	static boolean sameLayer(int y1, int y2)
	{
		if (isSurface(y1) != isSurface(y2) || isCave(y1) != isCave(y2))
		{
			return false;
		}
		return isSurface(y1) || isCave(y1) || Math.abs(y1 - y2) < ELSEWHERE_REACH;
	}

	/** How near two tiles laid out apart from the map must be to count as the same place. */
	private static final int ELSEWHERE_REACH = 128;
}
