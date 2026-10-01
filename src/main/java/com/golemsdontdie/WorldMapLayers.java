package com.golemsdontdie;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.inject.*;
import lombok.extern.slf4j.*;
import net.runelite.api.worldmap.WorldMapData;

/**
 * Where each world map other than the surface draws the game's tiles, read from the cache once and
 * shipped as world-map.gz.
 *
 * <p>A map is pieces: a map square or one zone of one, taken from some floors and drawn somewhere on
 * the map. A dungeon is drawn away from its own coordinates, and not all in one piece: Waterbirth's
 * eight hundred pieces are moved by many different amounts. Some maps draw an upper floor as an inset
 * beside the floor below, and Cam Torum is a map of its own over Neypotzli. So where a golem goes is
 * worked out from its own tile and floor, piece by piece; the surface draws every tile where it is,
 * on every floor, and has no pieces here.
 *
 * <p>The client says which map is open only by whether it draws a tile, so each map has a test tile
 * and the list of test tiles it draws, and the open map is the one whose list matches.
 */
@Slf4j
@Singleton
class WorldMapLayers
{
	private static final String RESOURCE = "/world-map.gz";
	private static final int VERSION = 1;

	private final List<Layer> layers = new ArrayList<>();

	/** The map last asked about, and which layer it is; null for the surface or a map not known. */
	private WorldMapData identifiedFor;
	private Layer open;

	void load()
	{
		layers.clear();
		identifiedFor = null;
		try (InputStream raw = WorldMapLayers.class.getResourceAsStream(RESOURCE))
		{
			if (raw == null)
			{
				log.warn("No world map layers on the classpath; golems in dungeons stay off their maps");
				return;
			}
			try (DataInputStream in = new DataInputStream(new GZIPInputStream(raw)))
			{
				if (in.readInt() != VERSION)
				{
					log.warn("World map layers are another version; ignoring them");
					return;
				}
				for (int maps = in.readShort(); maps > 0; maps--)
				{
					Layer layer = new Layer();
					in.readShort();
					layer.name = in.readUTF();
					layer.testX = in.readInt();
					layer.testY = in.readInt();
					for (int tests = in.readShort(); tests > 0; tests--)
					{
						layer.tests.set(in.readShort());
					}
					int pieces = in.readInt();
					layer.pieces = new short[pieces * PIECE];
					Map<Integer, List<Integer>> bySquare = new HashMap<>();
					for (int i = 0; i < pieces; i++)
					{
						int at = i * PIECE;
						layer.pieces[at] = (short) in.readUnsignedByte();
						layer.pieces[at + 1] = (short) in.readUnsignedByte();
						layer.pieces[at + 2] = in.readShort();
						layer.pieces[at + 3] = in.readShort();
						layer.pieces[at + 4] = (short) in.readUnsignedByte();
						layer.pieces[at + 5] = in.readShort();
						layer.pieces[at + 6] = in.readShort();
						bySquare.computeIfAbsent(square(layer.pieces[at + 2] >> 3, layer.pieces[at + 3] >> 3),
							k -> new ArrayList<>()).add(at);
					}
					for (Map.Entry<Integer, List<Integer>> e : bySquare.entrySet())
					{
						layer.bySquare.put(e.getKey(), e.getValue().stream().mapToInt(Integer::intValue).toArray());
					}
					layers.add(layer);
				}
			}
			log.debug("Loaded {} world map layers", layers.size());
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("World map layers unreadable", e);
			layers.clear();
		}
	}

	/**
	 * The layer the open map is, or null for the surface, which draws every tile where it is, and for
	 * a map newer than the table. Worked out once per map: the client keeps one object per map.
	 */
	Layer layerOf(WorldMapData showing)
	{
		if (showing != identifiedFor)
		{
			identifiedFor = showing;
			open = identify(showing);
		}
		return open;
	}

	/** The layer of the map with this name, or null. */
	Layer named(String name)
	{
		for (Layer layer : layers)
		{
			if (layer.name.equals(name))
			{
				return layer;
			}
		}
		return null;
	}

	private Layer identify(WorldMapData showing)
	{
		if (showing == null || layers.isEmpty())
		{
			return null;
		}
		BitSet drawn = new BitSet(layers.size());
		for (int i = 0; i < layers.size(); i++)
		{
			Layer layer = layers.get(i);
			if (showing.surfaceContainsPosition(layer.testX, layer.testY))
			{
				drawn.set(i);
			}
		}
		for (Layer layer : layers)
		{
			if (layer.tests.equals(drawn))
			{
				log.debug("The world map open is {}", layer.name);
				return layer;
			}
		}
		return null;
	}

	/** Shorts per piece: lowest floor, floors, source zone x and y, size in zones, zones moved x and y. */
	private static final int PIECE = 7;

	private static int square(int x, int y)
	{
		return x << 16 | y;
	}

	/** One map: its pieces, found by the map square they are taken from. */
	static final class Layer
	{
		private String name;
		private int testX;
		private int testY;
		private final BitSet tests = new BitSet();
		private short[] pieces;
		private final Map<Integer, int[]> bySquare = new HashMap<>();

		/**
		 * Where this map draws a tile, as {x, y} in the map's own coordinates, into {@code out}; false
		 * if the map does not draw that tile on that floor, which means it is somewhere else.
		 */
		boolean place(int x, int y, int plane, int[] out)
		{
			int[] here = bySquare.get(square(x >> 6, y >> 6));
			if (here == null)
			{
				return false;
			}
			int zoneX = x >> 3;
			int zoneY = y >> 3;
			for (int at : here)
			{
				int lowest = pieces[at];
				int size = pieces[at + 4];
				if (plane < lowest || plane >= lowest + pieces[at + 1]
					|| zoneX < pieces[at + 2] || zoneX >= pieces[at + 2] + size
					|| zoneY < pieces[at + 3] || zoneY >= pieces[at + 3] + size)
				{
					continue;
				}
				out[0] = x + pieces[at + 5] * 8;
				out[1] = y + pieces[at + 6] * 8;
				return true;
			}
			return false;
		}
	}
}
