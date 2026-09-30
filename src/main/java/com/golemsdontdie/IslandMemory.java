package com.golemsdontdie;

import java.io.*;
import java.util.*;
import java.util.function.*;
import java.util.zip.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;

/**
 * The plugin's memory of where a golem can walk on Wyrmscraig, in world coordinates,
 * learned by watching the scene and kept across logins.
 *
 * <p>Free roam needs passability for the whole island, but the scene only holds the
 * 104×104 tiles around the player, so every region's collision flags are copied out as it
 * loads and folded into a world-space record that outlives the scene.
 *
 * <p>Storage is the two-bit-per-tile north/east scheme Shortest Path uses. South and west
 * are not stored because they are the north and east of the neighbouring tile; halving the
 * data for free matters when it has to fit in a config value.
 *
 * <p>The <i>live</i> half of a pair: {@link WorldMesh} is the shipped read-only floor for
 * the whole reachable world, and this takes precedence wherever the two disagree, because
 * a harvest reflects the world as it is now - objects that come and go included, and the
 * window between a game update and the mesh being regenerated.
 *
 * <p>What is persisted is the island and everywhere golems have been found able to reach from
 * it: every instance the player loads, and the ground around the far end of every learned route.
 * That grows as the player plays, deliberately - the shipped data does not cover instances or
 * every shortcut yet, and until it does, what was learned is kept. The world the mesh covers
 * stays in the jar.
 */
@Slf4j
@Singleton
class IslandMemory
{
	/** Config key the serialised map is stored under. Plugin-written, so not a config item. */
	static final String MAP_KEY = "islandMap";

	/**
	 * The island map shipped with the plugin, covering the nine regions around the crafting
	 * site. Built from Shortest Path's world collision map - generated from the cache with XTEA
	 * keys the local key file lacks for these regions - and cross-checked tile by tile against
	 * live collision. Without it, free roam could only cover ground the player had walked.
	 *
	 * @see #loadBundled()
	 */
	private static final String BUNDLED_MAP = "/island-map.gz";

	private static final int REGION_SIZE = 64;
	private static final int REGION_MASK = REGION_SIZE - 1;
	private static final int FLAGS_PER_TILE = 2;
	private static final int FLAG_NORTH = 0;
	private static final int FLAG_EAST = 1;
	private static final int BITS_PER_REGION = REGION_SIZE * REGION_SIZE * FLAGS_PER_TILE;
	private static final int WORDS_PER_REGION = BITS_PER_REGION / Long.SIZE;

	/**
	 * Tiles at the edge of the loaded scene that are never harvested. The client fills a border
	 * of the scene's collision with "blocked" whatever is really there - harmless while
	 * harvesting only added open edges, but once a harvest cleared what it re-read it wrote a
	 * band of false walls, boxing the plinth into two hundred tiles.
	 */
	private static final int SCENE_MARGIN = 6;

	/**
	 * The client's flag for a tile in a map square that does not exist. RuneLite has no name for
	 * it. The client never walks onto one; read as open, whole squares of nothing were saved as
	 * ground, four of them beside Wyrmscraig's caves.
	 */
	private static final int NOT_LOADED = 0x1000000;

	/** Blocked for standing on: an object, a wall filling the tile, bad ground, or no square at all. */
	private static final int UNWALKABLE = CollisionDataFlag.BLOCK_MOVEMENT_FULL
		| CollisionDataFlag.BLOCK_MOVEMENT_OBJECT
		| CollisionDataFlag.BLOCK_MOVEMENT_FLOOR
		| CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION
		| NOT_LOADED;

	/**
	 * Passability per region-and-plane. The key packs both so that a multi-level
	 * island does not collapse its floors onto each other.
	 */
	private final Map<Long, long[]> regions = new HashMap<>();

	/**
	 * Region ids that may have an entry in {@link #regions}, on any plane. Nearly every tile a
	 * far golem asks about is somewhere the player has never been, and finding that out boxed a
	 * key several times a step; a clear bit answers with an array read. A set bit only means
	 * look, so it may outlive the entry.
	 */
	private final boolean[] mayHold = new boolean[1 << 16];

	/** Notes that {@link #regions} now holds this key. */
	private void held(long key)
	{
		int regionId = (int) (key >> 8);
		if (regionId >= 0 && regionId < mayHold.length)
		{
			mayHold[regionId] = true;
		}
	}

	/** Every region the map holds ground for, on any plane: the island and wherever else was read. */
	int[] knownRegions()
	{
		Set<Integer> ids = new TreeSet<>();
		for (long key : regions.keySet())
		{
			ids.add((int) (key >> 8));
		}
		for (int island : GolemContent.ISLAND_REGIONS)
		{
			ids.add(island);
		}
		return ids.stream().mapToInt(Integer::intValue).toArray();
	}

	/** Goes up each time a harvest reads ground, so walks between spaces can be joined afresh. */
	@lombok.Getter
	private int harvests;

	/** Empties {@link #regions}, and what is known about what it holds. */
	private void clearRegions()
	{
		regions.clear();
		Arrays.fill(mayHold, false);
	}

	/** Regions harvested this session, so a reload is not redone every scene change. */
	private final Set<Long> harvested = new HashSet<>();

	/**
	 * Which tiles of a harvested region have actually been read from a scene, one bit a tile,
	 * keyed as {@link #regions}. No entry means fully known: the shipped island map, or a save
	 * from before this was kept.
	 *
	 * <p>A region is 64 tiles square and a scene rarely shows all of one. Unread tiles were
	 * zeros, zeros read as walls, and those walls stood in front of the shipped mesh for the
	 * whole region - golems were rescued off cave ground the mesh knows perfectly well.
	 */
	private final Map<Long, long[]> seen = new HashMap<>();

	private static final int TILE_WORDS = REGION_SIZE * REGION_SIZE / Long.SIZE;

	private static void markSeen(long[] mask, int rx, int ry)
	{
		int tile = ry * REGION_SIZE + rx;
		mask[tile >> 6] |= 1L << (tile & 63);
	}

	private static boolean isSeen(long[] mask, int rx, int ry)
	{
		int tile = ry * REGION_SIZE + rx;
		return (mask[tile >> 6] >>> (tile & 63) & 1L) != 0L;
	}

	/**
	 * The region the first golem was seen in; everything the island is taken to be is measured
	 * from here. Hardcoding Wyrmscraig's region IDs is not available: the content is newer than
	 * this plugin, and a wrong constant would silently disable free roam.
	 */
	@Getter
	private int anchorRegionId = -1;

	@Getter
	private boolean dirty = false;

	@Inject
	private Client client;

	@Inject
	private WorldMesh worldMesh;

	// ---- the shipped baseline ----

	/**
	 * Loads the bundled island map without disturbing anything already learned. Merged
	 * <i>under</i> what is there, because a live harvest reflects the island as it is now. Called
	 * at start-up, before the saved map is restored, so it is the floor free roam stands on when
	 * the player has never been to Wyrmscraig.
	 */
	void loadBundled()
	{
		try (InputStream raw = IslandMemory.class.getResourceAsStream(BUNDLED_MAP))
		{
			if (raw == null)
			{
				log.warn("Bundled island map missing from the jar");
				return;
			}

			try (GZIPInputStream gz = new GZIPInputStream(raw);
				 DataInputStream data = new DataInputStream(gz))
			{
				int count = data.readInt();
				for (int i = 0; i < count; i++)
				{
					int regionId = data.readInt();
					int plane = data.readInt();
					byte[] packed = new byte[BITS_PER_REGION / 8];
					data.readFully(packed);

					long key = key(regionId, plane);
					if (regions.containsKey(key))
					{
						// A live scene is the better source, but only for the tiles it showed;
						// the rest are filled from the shipped map to make the region whole.
						long[] mask = seen.remove(key);
						if (mask != null)
						{
							fillUnseen(regions.get(key), toWords(packed), mask);
						}
						continue;
					}
					regions.put(key, toWords(packed));
					held(key);
				}

				if (anchorRegionId == -1)
				{
					anchorRegionId = GolemContent.WYRMSCRAIG_REGION;
				}
				log.debug("Loaded bundled island map: {} region-planes", count);
			}
		}
		catch (IOException | RuntimeException e)
		{
			// Survivable: golems fall back to roaming only what the player has walked.
			log.warn("Bundled island map unreadable", e);
		}
	}

	/** Copies both edges of every tile the mask has not seen from {@code source} into {@code target}. */
	private static void fillUnseen(long[] target, long[] source, long[] mask)
	{
		for (int ry = 0; ry < REGION_SIZE; ry++)
		{
			for (int rx = 0; rx < REGION_SIZE; rx++)
			{
				if (isSeen(mask, rx, ry))
				{
					continue;
				}
				for (int flag = 0; flag < FLAGS_PER_TILE; flag++)
				{
					int bit = bitIndex(rx, ry, flag);
					long one = 1L << (bit & 63);
					target[bit >> 6] = (target[bit >> 6] & ~one) | (source[bit >> 6] & one);
				}
			}
		}
	}

	/** Repacks the bundled byte layout into the long words used at runtime. */
	private static long[] toWords(byte[] packed)
	{
		long[] words = new long[WORDS_PER_REGION];
		for (int i = 0; i < packed.length; i++)
		{
			words[i >> 3] |= (packed[i] & 0xFFL) << ((i & 7) * 8);
		}
		return words;
	}

	// ---- learning ----

	/**
	 * Records where the golems live, if it is not already known. Called the first
	 * time a golem is seen.
	 */
	void anchorAt(WorldPoint point)
	{
		if (anchorRegionId == -1)
		{
			anchorRegionId = point.getRegionID();
			dirty = true;
			log.debug("Island anchored at region {}", anchorRegionId);
		}
	}

	/**
	 * True once there is an anchor. With the bundled map loaded this is true from
	 * start-up, so golems can roam before the player has ever seen one made.
	 */
	boolean hasAnchor()
	{
		return anchorRegionId != -1;
	}

	/**
	 * True if a region is part of the island, or somewhere golems can get to from it. The nine are a
	 * fixed list rather than a radius, as the bundled map defines them; the rest are wherever the
	 * network says a transport leads, so walking the rest of Gielinor harvests nothing.
	 */
	private boolean withinIsland(int regionId)
	{
		for (int island : GolemContent.ISLAND_REGIONS)
		{
			if (island == regionId)
			{
				return true;
			}
		}
		return alsoIsland != null && alsoIsland.test(regionId);
	}

	/**
	 * Regions beyond the nine around the plinth that golems can reach and so need mapping:
	 * wherever a transport from the island leads. Set by the plugin, which has the network. The
	 * cathedral's basement is under the island but in another region, and golems that went down
	 * found nowhere to walk.
	 */
	private IntPredicate alsoIsland;

	void setAlsoIsland(IntPredicate alsoIsland)
	{
		this.alsoIsland = alsoIsland;
	}

	/**
	 * Asks for one more harvest pass. Called when the scene is rebuilt, because nothing about
	 * the scene changes between ticks: harvesting every tick re-scanned in full, sixty times a
	 * minute, every region clipped by the scene edge, which can never have all 4096 of its
	 * tiles in the scene at once.
	 */
	void sceneChanged()
	{
		pendingHarvest = true;
	}

	private boolean pendingHarvest = true;

	/**
	 * Copies passability for every island region currently loaded out of the scene. Bounded to
	 * regions near the anchor, so walking across the rest of Gielinor does not accumulate a
	 * world map in the player's config.
	 */
	void harvestLoadedRegions()
	{
		if (!pendingHarvest)
		{
			return;
		}
		pendingHarvest = false;

		WorldView wv = client.getTopLevelWorldView();
		if (wv == null || !hasAnchor())
		{
			return;
		}

		CollisionData[] maps = wv.getCollisionMaps();
		int plane = wv.getPlane();
		if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null)
		{
			return;
		}
		int[][] live = maps[plane].getFlags();
		if (live == null)
		{
			return;
		}
		// A copy, so the void can be marked in it without touching the client's own collision.
		int[][] flags = new int[live.length][];
		for (int x = 0; x < live.length; x++)
		{
			flags[x] = live[x] == null ? new int[0] : live[x].clone();
		}

		if (wv.isInstance())
		{
			markVoid(wv, plane, flags);
			harvestInstance(wv, plane, flags);
			return;
		}

		markVoid(wv, plane, flags);

		int baseX = wv.getBaseX();
		int baseY = wv.getBaseY();

		for (int regionId : wv.getMapRegions())
		{
			long key = key(regionId, plane);
			if (!withinIsland(regionId) || harvested.contains(key))
			{
				continue;
			}

			if (!regions.containsKey(key))
			{
				seen.put(key, new long[TILE_WORDS]);
			}
			long[] bits = regions.computeIfAbsent(key, k -> new long[WORDS_PER_REGION]);
			held(key);
			long[] seenHere = seen.get(key);
			int regionBaseX = (regionId >> 8) << 6;
			int regionBaseY = (regionId & 0xFF) << 6;
			int tilesSeen = 0;

			for (int rx = 0; rx < REGION_SIZE; rx++)
			{
				for (int ry = 0; ry < REGION_SIZE; ry++)
				{
					int sceneX = regionBaseX + rx - baseX;
					int sceneY = regionBaseY + ry - baseY;
					if (sceneX < SCENE_MARGIN || sceneY < SCENE_MARGIN
						|| sceneX >= Constants.SCENE_SIZE - SCENE_MARGIN
						|| sceneY >= Constants.SCENE_SIZE - SCENE_MARGIN
						|| sceneX >= flags.length || sceneY >= flags[sceneX].length)
					{
						continue;
					}

					tilesSeen++;

					// Cleared before being set: a harvest that only added open edges left a door
					// open in memory after it was shut. An edge is only rewritten where the
					// tile it leads to is inside the harvested area; beyond that the scene's
					// border would answer.
					boolean northKnown = sceneY + 1 < Constants.SCENE_SIZE - SCENE_MARGIN;
					boolean eastKnown = sceneX + 1 < Constants.SCENE_SIZE - SCENE_MARGIN;
					// A tile on the harvest's far edge has an edge nobody could read; counting
					// it seen would make that edge a wall, so the mesh answers instead.
					if (seenHere != null && northKnown && eastKnown)
					{
						markSeen(seenHere, rx, ry);
					}
					int here = flags[sceneX][sceneY];
					boolean blocked = (here & UNWALKABLE) != 0;
					if (northKnown || blocked)
					{
						clear(bits, rx, ry, FLAG_NORTH);
					}
					if (eastKnown || blocked)
					{
						clear(bits, rx, ry, FLAG_EAST);
					}
					if (blocked)
					{
						continue;
					}

					// North and east are blocked by a wall here or by the destination being
					// unstandable. At a region edge the destination may be outside the scene:
					// leave the bit clear for the neighbour's harvest.
					if (northKnown && (here & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) == 0
						&& standable(flags, sceneX, sceneY + 1))
					{
						set(bits, rx, ry, FLAG_NORTH);
					}
					if (eastKnown && (here & CollisionDataFlag.BLOCK_MOVEMENT_EAST) == 0
						&& standable(flags, sceneX + 1, sceneY))
					{
						set(bits, rx, ry, FLAG_EAST);
					}
				}
			}

			if (tilesSeen > 0)
			{
				dirty = true;
				harvests++;
			}

			// Only done when every tile was actually in the scene: the scene is 104 tiles
			// square and regions are 64, so edge regions are always clipped, and marking one
			// complete would freeze it half-scanned with holes that never fill.
			if (tilesSeen == REGION_SIZE * REGION_SIZE)
			{
				harvested.add(key);
				seen.remove(key);
				log.debug("Harvested island region {} plane {}", regionId, plane);
			}
		}
	}

	/**
	 * Records an instance's ground under its template's coordinates, which is where golems in an
	 * instance are simulated. The template room is sealed off - nothing leads into it on foot,
	 * which is why golems that wandered in were stuck - so its floor can only be learned from an
	 * instance built on it. An edge is only recorded where both tiles come from the same
	 * template, side by side: chunks are stitched together as the instance is built, so
	 * neighbours there need not be neighbours in the world.
	 */
	private void harvestInstance(WorldView wv, int plane, int[][] flags)
	{
		int edgeX = Math.min(flags.length, wv.getSizeX()) - SCENE_MARGIN;
		for (int sceneX = SCENE_MARGIN; sceneX < edgeX; sceneX++)
		{
			int edgeY = Math.min(flags[sceneX].length, wv.getSizeY()) - SCENE_MARGIN;
			for (int sceneY = SCENE_MARGIN; sceneY < edgeY; sceneY++)
			{
				int[] here = template(wv, plane, sceneX, sceneY);
				if (here == null)
				{
					continue;
				}
				long key = key(regionIdOf(here[0], here[1]), here[2]);
				if (!regions.containsKey(key))
				{
					seen.put(key, new long[TILE_WORDS]);
				}
				long[] bits = regions.computeIfAbsent(key, k -> new long[WORDS_PER_REGION]);
				held(key);
				int rx = here[0] & REGION_MASK;
				int ry = here[1] & REGION_MASK;
				long[] seenHere = seen.get(key);
				if (seenHere != null)
				{
					markSeen(seenHere, rx, ry);
				}
				boolean northKnown = sceneY + 1 < edgeY;
				boolean eastKnown = sceneX + 1 < edgeX;
				boolean blocked = (flags[sceneX][sceneY] & UNWALKABLE) != 0;
				if (northKnown || blocked)
				{
					clear(bits, rx, ry, FLAG_NORTH);
				}
				if (eastKnown || blocked)
				{
					clear(bits, rx, ry, FLAG_EAST);
				}
				if (blocked)
				{
					continue;
				}
				int[] north = template(wv, plane, sceneX, sceneY + 1);
				if (northKnown && (flags[sceneX][sceneY] & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) == 0
					&& standable(flags, sceneX, sceneY + 1)
					&& north != null && north[0] == here[0] && north[1] == here[1] + 1 && north[2] == here[2])
				{
					set(bits, rx, ry, FLAG_NORTH);
				}
				int[] east = template(wv, plane, sceneX + 1, sceneY);
				if (eastKnown && (flags[sceneX][sceneY] & CollisionDataFlag.BLOCK_MOVEMENT_EAST) == 0
					&& standable(flags, sceneX + 1, sceneY)
					&& east != null && east[0] == here[0] + 1 && east[1] == here[1] && east[2] == here[2])
				{
					set(bits, rx, ry, FLAG_EAST);
				}
			}
		}
		dirty = true;
	}

	/**
	 * Flags every tile of this plane with no ground drawn as not loaded, in the copy being read.
	 * The client does not block the void beside an upper floor, where there is nothing to stand
	 * on, and read as it comes about seven thousand edges of Wyrmscraig's first floor were ground.
	 */
	private void markVoid(WorldView wv, int plane, int[][] flags)
	{
		Scene scene = wv.getScene();
		Tile[][][] tiles = scene == null ? null : scene.getTiles();
		if (tiles == null || plane >= tiles.length)
		{
			return;
		}
		for (int x = 0; x < flags.length && x < tiles[plane].length; x++)
		{
			for (int y = 0; y < flags[x].length && y < tiles[plane][x].length; y++)
			{
				Tile tile = tiles[plane][x][y];
				if (tile == null || tile.getSceneTilePaint() == null && tile.getSceneTileModel() == null)
				{
					flags[x][y] |= NOT_LOADED;
				}
			}
		}
	}

	/** {x, y, plane} of the template tile under a scene tile, or null. Unrotated chunks only. */
	private static int[] template(WorldView wv, int plane, int sceneX, int sceneY)
	{
		int data = InstanceMap.chunkAt(wv, plane, sceneX, sceneY);
		if (data <= 0 || InstanceMap.rotation(data) != 0)
		{
			return null;
		}
		return new int[]{InstanceMap.templateChunkX(data) * 8 + (sceneX & 7),
			InstanceMap.templateChunkY(data) * 8 + (sceneY & 7), InstanceMap.templatePlane(data)};
	}

	private static boolean standable(int[][] flags, int sceneX, int sceneY)
	{
		if (sceneX < 0 || sceneY < 0
			|| sceneX >= flags.length || flags[sceneX] == null || sceneY >= flags[sceneX].length)
		{
			return false;
		}
		return (flags[sceneX][sceneY] & UNWALKABLE) == 0;
	}

	// ---- querying ----

	/** True if the tile has been harvested and something may stand on it. */
	boolean isKnownWalkable(int worldX, int worldY, int plane)
	{
		// Walkable if it can be left in any direction; an isolated tile with no exits is
		// of no use to a wandering golem.
		return north(worldX, worldY, plane) || east(worldX, worldY, plane)
			|| north(worldX, worldY - 1, plane) || east(worldX - 1, worldY, plane);
	}

	boolean north(int x, int y, int plane)
	{
		return get(x, y, plane, FLAG_NORTH);
	}

	boolean east(int x, int y, int plane)
	{
		return get(x, y, plane, FLAG_EAST);
	}

	/** The four cardinals, named as the collision format stores them. */
	private boolean south(int x, int y, int plane)
	{
		return north(x, y - 1, plane);
	}

	private boolean west(int x, int y, int plane)
	{
		return east(x - 1, y, plane);
	}

	/**
	 * Can something step one tile from (x, y), cardinal or diagonal? A diagonal is a corner and
	 * the game will not cut one: both ways round must be clear, or a golem would slip between
	 * two walls meeting at a point. The four-term tests below are Shortest Path's; the client adds
	 * the corner of a wall standing at the point, which the edges cannot show, so that comes from
	 * the mesh, even where the edges are the live harvest's: walls do not move. Diagonals matter -
	 * Port Tasks' sailing routes are 854 perfect diagonals against 817 axis-aligned segments.
	 */
	boolean canStep(int x, int y, int plane, int dx, int dy)
	{
		if (dx == 0 && dy == 1)
		{
			return north(x, y, plane);
		}
		if (dx == 0 && dy == -1)
		{
			return south(x, y, plane);
		}
		if (dx == 1 && dy == 0)
		{
			return east(x, y, plane);
		}
		if (dx == -1 && dy == 0)
		{
			return west(x, y, plane);
		}
		if (dx == 1 && dy == 1)
		{
			return north(x, y, plane) && east(x, y + 1, plane)
				&& east(x, y, plane) && north(x + 1, y, plane)
				&& !worldMesh.cornerBlocks(x, y, plane, 1, 1);
		}
		if (dx == -1 && dy == 1)
		{
			return north(x, y, plane) && west(x, y + 1, plane)
				&& west(x, y, plane) && north(x - 1, y, plane)
				&& !worldMesh.cornerBlocks(x, y, plane, -1, 1);
		}
		if (dx == 1 && dy == -1)
		{
			return south(x, y, plane) && east(x, y - 1, plane)
				&& east(x, y, plane) && south(x + 1, y, plane)
				&& !worldMesh.cornerBlocks(x, y, plane, 1, -1);
		}
		if (dx == -1 && dy == -1)
		{
			return south(x, y, plane) && west(x, y - 1, plane)
				&& west(x, y, plane) && south(x - 1, y, plane)
				&& !worldMesh.cornerBlocks(x, y, plane, -1, -1);
		}
		return false;
	}

	/**
	 * Passability for one tile and direction, live harvest first: a harvest reflects the world
	 * as it is now where the mesh was only true when generated, which also covers the window
	 * between a game update and the mesh being rebuilt. The mesh below it is what lets a golem
	 * walk ground the player has never stood on.
	 */
	private boolean get(int x, int y, int plane, int flag)
	{
		// The client does not block the open sea - boats sail it - so a harvest reads it as
		// open ground, and the shipped island map lists the water off the cathedral as
		// walkable. The mesh's ocean bit marks exactly that sea, so it overrules every source.
		int toX = flag == FLAG_EAST ? x + 1 : x;
		int toY = flag == FLAG_NORTH ? y + 1 : y;
		if (worldMesh.isWater(x, y, plane) || worldMesh.isWater(toX, toY, plane))
		{
			return false;
		}

		int regionId = regionIdOf(x, y);
		long key = key(regionId, plane);
		long[] bits = regionId >= 0 && regionId < mayHold.length && !mayHold[regionId] ? null : regions.get(key);
		long[] mask = bits == null ? null : seen.get(key);
		if (bits != null && (mask == null || isSeen(mask, x & REGION_MASK, y & REGION_MASK)))
		{
			int bit = bitIndex(x & REGION_MASK, y & REGION_MASK, flag);
			return (bits[bit >> 6] >>> (bit & 63) & 1L) != 0L;
		}

		// The mesh says water is passable, because it is - to a boat. A step is refused unless
		// both ends are ground the land fill reached; both, because land beside water has a
		// perfectly open edge leading off the beach.
		//
		// The land mask, not the ocean bit: ocean marks only the one connected sea, so cave
		// water, lakes and enclosed basins passed the old check, most visibly inside
		// Wyrmscraig's caves. The live harvest needs no such check, except that the client
		// does not block water a boat can sail - which is why the sea and the underground lake
		// are refused first. See WorldMesh.isWater.
		if (flag == FLAG_NORTH)
		{
			return worldMesh.isLandWalkable(x, y, plane)
				&& worldMesh.isLandWalkable(x, y + 1, plane)
				&& worldMesh.north(x, y, plane);
		}
		return worldMesh.isLandWalkable(x, y, plane)
			&& worldMesh.isLandWalkable(x + 1, y, plane)
			&& worldMesh.east(x, y, plane);
	}

	private static void clear(long[] bits, int rx, int ry, int flag)
	{
		int bit = bitIndex(rx, ry, flag);
		bits[bit >> 6] &= ~(1L << (bit & 63));
	}

	/**
	 * Something that changes passability was added or removed - a door opening or shutting - so
	 * its region is read again on the next harvest. A region was harvested once a session and
	 * then trusted, which a door makes wrong: open when read, shut a minute later, and golems
	 * still walking through.
	 */
	void passabilityChanged(WorldPoint at)
	{
		if (at == null || !withinIsland(at.getRegionID()))
		{
			return;
		}
		harvested.remove(key(at.getRegionID(), at.getPlane()));
		pendingHarvest = true;
	}

	private static void set(long[] bits, int rx, int ry, int flag)
	{
		int bit = bitIndex(rx, ry, flag);
		bits[bit >> 6] |= 1L << (bit & 63);
	}

	private static int bitIndex(int rx, int ry, int flag)
	{
		return (ry * REGION_SIZE + rx) * FLAGS_PER_TILE + flag;
	}

	private static int regionIdOf(int worldX, int worldY)
	{
		return ((worldX >> 6) << 8) | (worldY >> 6);
	}

	private static long key(int regionId, int plane)
	{
		return ((long) regionId << 8) | (plane & 0xFF);
	}

	// ---- persistence ----

	/**
	 * Encodes the whole memory as one string: anchor, then each region-plane's bits, gzipped
	 * and base64'd. Compression is not a nicety - a region is 1KB of flags and a passability
	 * map is overwhelmingly long runs of "walkable", so it deflates to a fraction, which is the
	 * difference between a config value and an abuse of one.
	 */
	String serialise()
	{
		try
		{
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (GZIPOutputStream gz = new GZIPOutputStream(out);
				 DataOutputStream data = new DataOutputStream(gz))
			{
				data.writeInt(anchorRegionId);
				data.writeInt(regions.size());
				for (Map.Entry<Long, long[]> entry : regions.entrySet())
				{
					data.writeLong(entry.getKey());
					for (long word : entry.getValue())
					{
						data.writeLong(word);
					}
				}
				// Which tiles of each partly harvested region were read; after the regions,
				// so a save without this section still loads.
				data.writeInt(seen.size());
				for (Map.Entry<Long, long[]> entry : seen.entrySet())
				{
					data.writeLong(entry.getKey());
					for (long word : entry.getValue())
					{
						data.writeLong(word);
					}
				}
			}
			dirty = false;
			return Base64.getEncoder().encodeToString(out.toByteArray());
		}
		catch (IOException e)
		{
			log.warn("Could not save island map", e);
			return "";
		}
	}

	/** Restores a memory previously written by {@link #serialise()}. */
	void deserialise(String encoded)
	{
		clearRegions();
		harvested.clear();
		seen.clear();
		anchorRegionId = -1;
		dirty = false;

		if (encoded == null || encoded.isEmpty())
		{
			return;
		}

		try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
			 DataInputStream data = new DataInputStream(gz))
		{
			anchorRegionId = data.readInt();
			int count = data.readInt();
			for (int i = 0; i < count; i++)
			{
				long key = data.readLong();
				long[] words = new long[WORDS_PER_REGION];
				boolean everyEdgeOpen = true;
				for (int w = 0; w < WORDS_PER_REGION; w++)
				{
					words[w] = data.readLong();
					everyEdgeOpen &= words[w] == -1L;
				}
				// Open on every edge of every tile is no real square: it is one that does not exist,
				// saved as ground before the not-loaded flag was read. Dropped, to be read again.
				if (everyEdgeOpen)
				{
					continue;
				}
				regions.put(key, words);
				held(key);
				// Deliberately not added to `harvested`: a restored region is re-scanned
				// once when it next loads, so map edits heal themselves.
			}

			boolean masked;
			try
			{
				int masks = data.readInt();
				for (int i = 0; i < masks; i++)
				{
					long key = data.readLong();
					long[] words = new long[TILE_WORDS];
					for (int w = 0; w < TILE_WORDS; w++)
					{
						words[w] = data.readLong();
					}
					if (regions.containsKey(key))
					{
						seen.put(key, words);
					}
				}
				masked = true;
			}
			catch (EOFException e)
			{
				masked = false;
			}

			// A save from before seen tiles were kept cannot tell a wall from a tile it never
			// read. Best guess: a tile with an open edge was read, one with none is left to
			// the mesh, which keeps ground only a harvest knows. Island regions have the
			// shipped map filled in under the guess.
			if (!masked)
			{
				for (Map.Entry<Long, long[]> entry : regions.entrySet())
				{
					long[] words = entry.getValue();
					long[] mask = new long[TILE_WORDS];
					for (int ry = 0; ry < REGION_SIZE; ry++)
					{
						for (int rx = 0; rx < REGION_SIZE; rx++)
						{
							for (int flag = 0; flag < FLAGS_PER_TILE; flag++)
							{
								int bit = bitIndex(rx, ry, flag);
								if ((words[bit >> 6] >>> (bit & 63) & 1L) != 0L)
								{
									markSeen(mask, rx, ry);
								}
							}
						}
					}
					seen.put(entry.getKey(), mask);
				}
				dirty = true;
			}
			log.debug("Loaded island map: anchor {}, {} region-planes, {} partly read", anchorRegionId,
				regions.size(), seen.size());
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Island map unreadable, starting fresh", e);
			clearRegions();
			anchorRegionId = -1;
		}
	}

	/** Throws away everything learned. */
	void forget()
	{
		clearRegions();
		harvested.clear();
		seen.clear();
		anchorRegionId = -1;
		dirty = true;
	}
}
