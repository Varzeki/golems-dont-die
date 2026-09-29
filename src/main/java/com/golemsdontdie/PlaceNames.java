package com.golemsdontdie;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.inject.*;
import lombok.extern.slf4j.*;
import net.runelite.api.coords.*;
import static com.golemsdontdie.RouteGeometry.span;

/**
 * What a place is called, so a golem's whereabouts can be said in words.
 *
 * <p>Two sources, built offline into one file. A region's curated
 * name is used first: those name a whole 64-tile region and never name a monster. Where there is
 * none — anywhere added to the game since that list was written, Wyrmscraig included — the map's own
 * labels answer instead, the nearest one within a few dozen tiles, preferring the ones the game
 * draws largest so a region beats a monster standing in it.
 *
 * <p>Region names come from the Location Display plugin by trinhc2, BSD 2-Clause; see the README.
 */
@Slf4j
@Singleton
class PlaceNames
{
	private static final String FILE = "/places.gz";

	private static final int VERSION = 2;

	/** How far a label may be from a tile and still name it, in tiles. */
	private static final int LABEL_REACH = 48;

	/** How much a larger label is favoured over a nearer one, in tiles per size. */
	private static final int SIZE_WORTH = 24;

	/** Labels: {x, y, plane, size}, and their names, in step. */
	private int[][] labels = new int[0][];
	private String[] labelNames = new String[0];

	/** Curated region names, by region id. */
	private final Map<Integer, String> regions = new HashMap<>();

	void load()
	{
		try (InputStream in = PlaceNames.class.getResourceAsStream(FILE))
		{
			if (in == null)
			{
				log.warn("No place names on the classpath; golems will be located by region number");
				return;
			}
			try (DataInputStream data = new DataInputStream(new GZIPInputStream(in)))
			{
				int version = data.readInt();
				if (version != VERSION)
				{
					log.warn("Place names are version {}, expected {} — ignoring them", version, VERSION);
					return;
				}
				int count = data.readInt();
				labels = new int[count][];
				labelNames = new String[count];
				for (int i = 0; i < count; i++)
				{
					labelNames[i] = data.readUTF();
					labels[i] = new int[]{data.readUnsignedShort(), data.readUnsignedShort(), data.readByte(),
						data.readUnsignedByte()};
				}
				int named = data.readInt();
				for (int i = 0; i < named; i++)
				{
					String name = data.readUTF();
					regions.put(data.readUnsignedByte() << 8 | data.readUnsignedByte(), name);
				}
				log.debug("Loaded {} map labels and {} named regions", count, named);
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Place names unreadable", e);
		}
	}

	/**
	 * The regions any of the given names belong to, from both sources: a region the curated list
	 * calls one of them, and the region a map label of that name stands in. Nothing is matched
	 * loosely — the name must be the whole of it — and a name that matches nothing is logged, which
	 * is how a curated list is kept honest as the game changes under it.
	 */
	Set<Integer> regionsNamed(String[] names)
	{
		Set<Integer> found = new HashSet<>();
		for (String name : names)
		{
			boolean any = false;
			for (Map.Entry<Integer, String> region : regions.entrySet())
			{
				if (region.getValue().equalsIgnoreCase(name))
				{
					found.add(region.getKey());
					any = true;
				}
			}
			for (int i = 0; i < labels.length; i++)
			{
				if (labelNames[i].equalsIgnoreCase(name))
				{
					found.add((labels[i][0] >> 6) << 8 | labels[i][1] >> 6);
					any = true;
				}
			}
			if (!any)
			{
				log.debug("No place called {}", name);
			}
		}
		return found;
	}

	/** What to call this tile, or null if nothing is known nearby. */
	String nameFor(WorldPoint at)
	{
		return at == null ? null : nameFor(at.getX(), at.getY(), at.getPlane());
	}

	String nameFor(int x, int y, int plane)
	{
		String region = regions.get((x >> 6) << 8 | (y >> 6));
		if (region != null)
		{
			return region;
		}

		String nearest = null;
		int best = Integer.MAX_VALUE;
		for (int i = 0; i < labels.length; i++)
		{
			int[] label = labels[i];
			int distance = span(label[0] - x, label[1] - y);
			if (distance > LABEL_REACH)
			{
				continue;
			}
			// A big label a little further off is the better answer: "Ape Atoll" over "Scimitar
			// monkeys", "Wyrmscraig" over whatever stands on its shore.
			int score = distance - label[3] * SIZE_WORTH + (label[2] == plane ? 0 : LABEL_REACH);
			if (score < best)
			{
				best = score;
				nearest = labelNames[i];
			}
		}
		return nearest;
	}
}
