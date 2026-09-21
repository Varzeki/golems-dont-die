package com.golemsdontdie;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/**
 * What a place is called, so a golem's whereabouts can be said in words.
 *
 * <p>Two sources, built into one file by {@code dev-tools/BuildPlaceNames.java}. A region's curated
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
			int distance = Math.max(Math.abs(label[0] - x), Math.abs(label[1] - y));
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
