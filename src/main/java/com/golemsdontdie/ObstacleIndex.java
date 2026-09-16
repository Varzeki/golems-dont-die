package com.golemsdontdie;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Where every obstacle in the game is, read from the cache and shipped.
 *
 * <p>Separate from {@link TransportNetwork} on purpose, because the two answer different
 * questions and only one of them is easy. <b>Where the obstacles are</b> is a fact: it comes
 * out of the map index exactly and completely. <b>Where each one leads</b> has to be derived
 * from collision components, and that derivation was wrong by one to three tiles on every
 * Wyrmscraig shortcut until somebody measured them.
 *
 * <p>So the transport table stays as conservative as it needs to be, and this index — which
 * makes no claim about destinations — is what the highlight overlay draws. That is why the
 * overlay can honestly show an obstacle it knows nothing else about: red, meaning golems
 * cannot use this, which is exactly the obstacle worth walking over to.
 *
 * <p>Doors and gates are not here. They were excluded when the index was built, on the
 * game's own wall-versus-scenery classification: a golem cannot open a door, because opening
 * one changes an object every other player in the world can see.
 */
@Slf4j
@Singleton
class ObstacleIndex
{
	private static final String RESOURCE = "/obstacles.gz";

	/** Bumped when the file format changes; an old file is refused rather than misread. */
	private static final int VERSION = 4;

	/**
	 * Obstacles by the region they stand in.
	 *
	 * <p>Bucketed rather than held as one list because the overlay asks "what is near me"
	 * every time the player moves, and a flat sweep of eight thousand entries per rebuild
	 * is work for nothing when all but a handful are hundreds of tiles away.
	 */
	private final Map<Integer, List<Obstacle> > byRegion = new HashMap<>();

	/**
	 * Cache traversal time per object id, built once at load.
	 *
	 * <p>Held apart from the placements because it is asked for on the hot path — every
	 * time a golem starts through an obstacle — and walking twelve thousand placements to
	 * find one number would be work for nothing.
	 */
	private final Map<Integer, Integer> ticksByObject = new HashMap<>();

	/**
	 * One obstacle, at one place, with the ground it actually stands on.
	 *
	 * <p>{@code x, y} is the south-west corner, which is how the cache records a placement,
	 * and {@code sizeX, sizeY} is how far it extends from there. Both are needed: marking
	 * only the corner lit one half of every two-tile object and left the other half looking
	 * as though the plugin had never heard of it.
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
		 * True if this is mounted in a wall — a door or a gate.
		 *
		 * <p>Kept rather than dropped, because a handful of them are not doors at all. The
		 * Wyrmscraig cathedral door does not swing open: clicking it puts you on the other
		 * side, which makes it a transport wearing a door's clothes. Excluding every wall
		 * object hid it completely.
		 *
		 * <p>Drawn only once something is actually known about it, so the three and a half
		 * thousand ordinary doors stay out of the way.
		 */
		final boolean wall;

		/**
		 * How many game ticks this obstacle takes, from the cache, or 0 if it does not say.
		 *
		 * <p>The one number in this whole area that is neither measured nor guessed. Only
		 * about fifty obstacles in the game carry it — params live on newer and reworked
		 * content — but where it exists it is the game's own answer, and it lines up with
		 * Shortest Path's independently hand-measured table.
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
				log.warn("No obstacle index on the classpath; highlighting will be empty");
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

					// Bucketed by the anchor's region. An object straddling a boundary is
					// still found, because the search below widens by the largest footprint
					// any object has.
					byRegion.computeIfAbsent(region(x, y), k -> new ArrayList<>())
						.add(new Obstacle(objectId, x, y, plane, sizeX, sizeY, wall, ticks));
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
			// Cosmetic data. A golem's behaviour does not depend on it, so a failure here
			// costs the highlight overlay and nothing else.
			log.warn("Could not read the obstacle index", e);
		}
	}

	/**
	 * Every obstacle within {@code radius} tiles, on the given plane.
	 *
	 * <p>Searches only the regions the box touches. A radius of a hundred spans at most
	 * nine of them, against the two-and-a-half thousand the index holds.
	 */
	List<Obstacle> near(int x, int y, int plane, int radius)
	{
		List<Obstacle> found = new ArrayList<>();

		for (int rx = (x - radius) >> 6; rx <= (x + radius) >> 6; rx++)
		{
			for (int ry = (y - radius) >> 6; ry <= (y + radius) >> 6; ry++)
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
	 * The cache's own traversal time for an object, in ticks, or 0 if it has none.
	 *
	 * <p>By object rather than by place: the same kind of obstacle takes the same time
	 * wherever it stands, which is exactly the sort of thing a param describes.
	 */
	/**
	 * True if the cache lists this object as something you traverse.
	 *
	 * <p>The first question asked of anything the player is seen using. Watching alone
	 * cannot tell an obstacle from a tree: both are an object clicked, an animation, and the
	 * player somewhere else afterwards. The index was built from the objects whose menu
	 * offers a way across — climb, cross, squeeze, jump — so a tree or a bank booth is
	 * simply not in it.
	 */
	boolean knows(int objectId)
	{
		return known.contains(objectId);
	}

	/** Every object id in the index. */
	private final java.util.Set<Integer> known = new java.util.HashSet<>();

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
