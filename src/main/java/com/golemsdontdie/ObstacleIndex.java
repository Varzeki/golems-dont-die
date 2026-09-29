package com.golemsdontdie;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.inject.*;
import lombok.extern.slf4j.*;

/**
 * Where every obstacle in the game is, read from the cache and shipped.
 *
 * <p>Separate from {@link TransportNetwork} because only one of the two questions is easy. Where
 * the obstacles are is a fact out of the map index; where each one leads is derived from collision
 * components, and was wrong by one to three tiles on every Wyrmscraig shortcut until somebody
 * measured them. So this index claims nothing about destinations: only which objects are
 * obstacles, where they stand, and how long the cache says each takes.
 *
 * <p>Doors and gates are not here, excluded on the game's own wall-versus-scenery split: a golem
 * cannot open a door, because that changes an object every other player can see.
 */
@Slf4j
@Singleton
class ObstacleIndex
{
	private static final String RESOURCE = "/obstacles.gz";

	/** Bumped when the format changes; an old file is refused rather than misread. */
	private static final int VERSION = 4;

	/**
	 * Obstacles by the region they stand in, because "what is near here" concerns a few regions and
	 * all but a handful of obstacles are hundreds of tiles off.
	 */
	private final Map<Integer, List<Obstacle> > byRegion = new HashMap<>();

	/** The most tiles any object reaches from its anchor, which is how far a search widens. */
	private int widest;

	/** Cache traversal time per object id: asked for on the hot path, so kept apart from places. */
	private final Map<Integer, Integer> ticksByObject = new HashMap<>();

	/**
	 * One obstacle, at one place. {@code x, y} is the south-west corner, as the cache records a
	 * placement; marking only that lit one half of every two-tile object.
	 */
	static final class Obstacle
	{
		final int objectId;
		final int x;
		final int y;
		final int plane;
		final int sizeX;
		final int sizeY;

		/**
		 * True if this is mounted in a wall — a door or a gate. Kept rather than dropped, because a
		 * handful are not doors at all: the Wyrmscraig cathedral door does not swing open, it puts you
		 * on the other side.
		 */
		final boolean wall;

		/**
		 * How many game ticks this obstacle takes, from the cache, or 0 if it does not say. Neither
		 * measured nor guessed: only about fifty obstacles carry it, and it lines up with Shortest
		 * Path's hand-measured table.
		 */
		final int ticks;

		Obstacle(int objectId, int x, int y, int plane, int sizeX, int sizeY, boolean wall,
			int ticks)
		{
			this.ticks = ticks;
			this.objectId = objectId;
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.sizeX = sizeX;
			this.sizeY = sizeY;
			this.wall = wall;
		}
	}

	void load()
	{
		byRegion.clear();
		ticksByObject.clear();
		known.clear();

		try (InputStream raw = ObstacleIndex.class.getResourceAsStream(RESOURCE))
		{
			if (raw == null)
			{
				log.warn("No obstacle index on the classpath; obstacles are known from the transport tables alone");
				return;
			}

			try (DataInputStream in = new DataInputStream(new GZIPInputStream(raw)))
			{
				int version = in.readInt();
				if (version != VERSION)
				{
					log.warn("Obstacle index is version {}, expected {} — ignoring it",
						version, VERSION);
					return;
				}

				int count = in.readInt();
				for (int i = 0; i < count; i++)
				{
					int objectId = in.readInt();
					int x = in.readShort() & 0xffff;
					int y = in.readShort() & 0xffff;
					int plane = in.readByte();
					int sizeX = in.readByte() & 0xff;
					int sizeY = in.readByte() & 0xff;
					boolean wall = in.readByte() != 0;
					int ticks = in.readByte() & 0xff;

					// Bucketed by the anchor's region. An object straddling a boundary is still found,
					// because the search below widens by the largest footprint any object has.
					byRegion.computeIfAbsent(region(x, y), k -> new ArrayList<>())
						.add(new Obstacle(objectId, x, y, plane, sizeX, sizeY, wall, ticks));
					widest = Math.max(widest, Math.max(sizeX, sizeY));
					known.add(objectId);
					if (ticks > 0)
					{
						ticksByObject.put(objectId, ticks);
					}
				}

				log.debug("Loaded obstacle index: {} obstacles across {} regions",
					count, byRegion.size());
			}
		}
		catch (IOException e)
		{
			// Not fatal: obstacles are then known from the transport tables alone.
			log.warn("Could not read the obstacle index", e);
		}
	}

	/**
	 * Every obstacle within {@code radius} tiles, on the given plane. Searches only the regions the
	 * box touches: a radius of a hundred spans nine of the index's two and a half thousand.
	 */
	List<Obstacle> near(int x, int y, int plane, int radius)
	{
		List<Obstacle> found = new ArrayList<>();

		// Widened by the largest object on the westward and southward sides, where an object
		// anchored in the next region along can still reach into the radius.
		for (int rx = (x - radius - widest) >> 6; rx <= (x + radius) >> 6; rx++)
		{
			for (int ry = (y - radius - widest) >> 6; ry <= (y + radius) >> 6; ry++)
			{
				List<Obstacle> here = byRegion.get((rx << 8) | ry);
				if (here == null)
				{
					continue;
				}
				for (Obstacle o : here)
				{
					// Measured to the nearest part of the object rather than its corner,
					// so a long object half inside the radius is not dropped.
					if (o.plane == plane
						&& Math.abs(o.x + o.sizeX / 2 - x) <= radius + o.sizeX
						&& Math.abs(o.y + o.sizeY / 2 - y) <= radius + o.sizeY)
					{
						found.add(o);
					}
				}
			}
		}
		return found;
	}

	/**
	 * True if the cache lists this object as something you traverse: the first question asked of
	 * anything the player is seen using, watching alone not telling an obstacle from a tree. The
	 * index holds objects whose menu offers a way across — climb, cross, squeeze, jump.
	 */
	boolean knows(int objectId)
	{
		return known.contains(objectId);
	}

	private final Set<Integer> known = new HashSet<>();

	/** The cache's traversal time for an object, in ticks, or 0: the same wherever it stands. */
	int ticksFor(int objectId)
	{
		Integer found = ticksByObject.get(objectId);
		return found == null ? 0 : found;
	}

	int size()
	{
		int n = 0;
		for (List<Obstacle> bucket : byRegion.values())
		{
			n += bucket.size();
		}
		return n;
	}

	private static int region(int x, int y)
	{
		return ((x >> 6) << 8) | (y >> 6);
	}
}
