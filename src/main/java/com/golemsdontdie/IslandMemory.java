package com.golemsdontdie;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Constants;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * The plugin's memory of where a golem can walk on Wyrmscraig, in world
 * coordinates, learned by watching the scene and kept across logins.
 *
 * <p>Free roam needs passability for the whole island at once, and the scene only
 * ever holds the 104×104 tiles around the player. A golem three regions away has to
 * keep walking somewhere the client cannot currently be asked about. So the map is
 * harvested: every time a region loads, its collision flags are copied out and
 * folded into a world-space record that outlives the scene.
 *
 * <p>The storage is the two-bit-per-tile north/east scheme the Shortest Path plugin
 * uses, and that the Brainless Ourania plugin loads its bundled map with. South and
 * west are not stored because they are the north and east of the neighbouring tile;
 * halving the data for free matters when it has to fit in a config value.
 *
 * <p>This is now the <i>live</i> half of a pair. {@link WorldMesh} is the shipped,
 * read-only floor covering the whole reachable world; this is what the client has actually
 * been asked about, and it takes precedence everywhere the two disagree. A harvested
 * region reflects the world as it is right now, including objects that come and go, where
 * the mesh is a static export — which also means this covers the window between a game
 * update and the mesh being regenerated.
 *
 * <p>Only the island is persisted to configuration. Nine regions of base64 in a properties
 * file is reasonable; the 1,158 the mesh covers would be an abuse of one, so world-scale
 * passability lives in the jar and the live harvest beyond the island is session-scoped.
 */
@Slf4j
@Singleton
class IslandMemory
{
	/** Config key the serialised map is stored under. Plugin-written, so not a config item. */
	static final String MAP_KEY = "islandMap";

	/**
	 * The island map shipped with the plugin, covering the nine regions around the
	 * crafting site.
	 *
	 * <p>Built from the Shortest Path project's world collision map — generated from
	 * the game cache with XTEA keys, which the local cache's key file lacks for these
	 * regions — and cross-checked tile by tile against collision recorded live before
	 * being trusted. Without it, free roam could only cover ground the player had
	 * personally walked.
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
	 * Tiles at the edge of the loaded scene that are never harvested.
	 *
	 * <p>The client fills a border of the scene's collision with "blocked" whatever is really
	 * there. Harvesting only ever added open edges once, so the border did no harm; once a
	 * harvest cleared what it re-read — needed so a shut door stops counting as open — every
	 * harvest wrote a band of false walls wherever the edge of the scene happened to be. The
	 * plinth ended up boxed into two hundred tiles of its own island.
	 */
	private static final int SCENE_MARGIN = 6;

	/** Blocked for standing on: an object, a wall filling the tile, or bad ground. */
	private static final int UNWALKABLE = CollisionDataFlag.BLOCK_MOVEMENT_FULL
		| CollisionDataFlag.BLOCK_MOVEMENT_OBJECT
		| CollisionDataFlag.BLOCK_MOVEMENT_FLOOR
		| CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION;

	/**
	 * Passability per region-and-plane. The key packs both so that a multi-level
	 * island does not collapse its floors onto each other.
	 */
	private final Map<Long, long[]> regions = new HashMap<>();

	/** Regions harvested this session, so a reload is not redone every scene change. */
	private final Set<Long> harvested = new HashSet<>();

	/**
	 * Which tiles of a harvested region have actually been read from a scene, one bit a
	 * tile, by the same key as {@link #regions}. A region with no entry here is fully known —
	 * the shipped island map, or a save from before this was kept.
	 *
	 * <p>A region is 64 tiles square and a scene rarely shows all of one. Its unread tiles
	 * were zeros, and zeros read as walls — which then stood in front of the shipped mesh for
	 * the whole region. Golems walking the cave, on ground the mesh knows perfectly well, were
	 * rescued off it because a glimpse of the basement next door had claimed the region.
	 * Unread tiles now fall back to the mesh as if the region had never been seen.
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
	 * The region the first golem was seen in. Everything the island is taken to be
	 * is measured from here.
	 *
	 * <p>The alternative — hardcoding Wyrmscraig's region IDs — is not available:
	 * the content is newer than this plugin, and a wrong constant would silently
	 * disable free roam with no way for the player to tell why.
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
	 * Loads the bundled island map, without disturbing anything already learned.
	 *
	 * <p>Merged rather than assigned, and merged <i>under</i> what is already there:
	 * a region harvested from a live scene reflects the island as it is right now,
	 * including objects that come and go, where the bundled map is a static export.
	 * Where both have an opinion the live one is kept.
	 *
	 * <p>Called at start-up, before the saved map is restored, so it acts as the floor
	 * that free roam stands on when the player has never been to Wyrmscraig.
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
				 java.io.DataInputStream data = new java.io.DataInputStream(gz))
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
						// Already known from a live scene, which is the better source — for the
						// tiles that scene showed. Any it did not are filled from the shipped map,
						// which makes the region whole.
						long[] mask = seen.remove(key);
						if (mask != null)
						{
							fillUnseen(regions.get(key), toWords(packed), mask);
						}
						continue;
					}
					regions.put(key, toWords(packed));
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
			// A missing or corrupt baseline is survivable — golems fall back to
			// roaming only what the player has walked.
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
	 * True if the player is close enough to the island for golems to be worth
	 * simulating. Away from Wyrmscraig the whole plugin should cost nothing.
	 */
	boolean playerNearIsland()
	{
		if (!hasAnchor())
		{
			return false;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return false;
		}
		for (int regionId : wv.getMapRegions())
		{
			if (withinIsland(regionId))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * True if a region is part of the island.
	 *
	 * <p>A fixed list rather than a radius around the anchor. The bundled map defines
	 * exactly which regions the golems have, so harvesting anything outside it would
	 * accumulate ground they can never reach — and a radius was only ever a guess at
	 * the shape the list now states outright.
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
	 * Regions beyond the nine around the plinth that golems can get to and so need mapping:
	 * wherever a transport from the island leads. Set by the plugin, which has the network.
	 *
	 * <p>The cathedral's basement is under the island but in another region, reached only
	 * by a staircase the player taught. Its ground was never harvested, the shipped mesh
	 * knows nothing of it, and golems that went down found nowhere to walk.
	 */
	private java.util.function.IntPredicate alsoIsland;

	void setAlsoIsland(java.util.function.IntPredicate alsoIsland)
	{
		this.alsoIsland = alsoIsland;
	}

	/**
	 * Copies passability for every island region currently loaded out of the scene.
	 *
	 * <p>Bounded to regions near the anchor so that walking the golems' owner across
	 * the rest of Gielinor does not accumulate a world map in their config.
	 */
	/**
	 * Asks for one more harvest pass. Called when the scene is rebuilt.
	 *
	 * <p>Harvesting used to run every game tick. A region only becomes "done" once
	 * every one of its 4096 tiles has been in the scene at once, which never happens
	 * for the regions clipped by the edge of the scene — so those were re-scanned in
	 * full, several times over, sixty times a minute, forever. Nothing about the scene
	 * changes between ticks, so once per scene load is all it can ever be worth.
	 */
	void sceneChanged()
	{
		pendingHarvest = true;
	}

	private boolean pendingHarvest = true;

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
		int[][] flags = maps[plane].getFlags();
		if (flags == null)
		{
			return;
		}

		if (wv.isInstance())
		{
			harvestInstance(wv, plane, flags);
			return;
		}

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

					// Cleared before being set. A harvest only ever added open edges, so a
					// door recorded open stayed open in memory after it was shut, and golems
					// walked straight through it.
					// An edge is only rewritten where the tile it leads to is inside the
					// harvested area too; beyond that the scene's border would answer.
					boolean northKnown = sceneY + 1 < Constants.SCENE_SIZE - SCENE_MARGIN;
					boolean eastKnown = sceneX + 1 < Constants.SCENE_SIZE - SCENE_MARGIN;
					// A tile on the far edge of the harvest has an edge nobody could read, and
					// counting it seen would make that edge a wall. Left unseen, the mesh answers.
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

					// North and east are each blocked by a wall on this tile or by the
					// destination tile being unstandable. The destination may be outside
					// the scene at a region edge, in which case leave the bit clear and
					// let the neighbouring region's harvest fill it in later.
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
			}

			// Only call a region done when every one of its tiles was actually in the
			// scene. The scene is 104 tiles square and regions are 64, so the ones at
			// the edge are always clipped — marking those complete on the first pass
			// would freeze a half-scanned region and leave the golems a map with holes
			// in it that never fill, however often the player walks over them.
			if (tilesSeen == REGION_SIZE * REGION_SIZE)
			{
				harvested.add(key);
				seen.remove(key);
				log.debug("Harvested island region {} plane {}", regionId, plane);
			}
		}
	}

	/**
	 * Records an instance's ground under its template's coordinates.
	 *
	 * <p>Golems in an instance are simulated in the template, so that is where they need to
	 * know what is walkable. The template room in the world is sealed off — nothing leads
	 * into it on foot, which is exactly why golems that wandered in were stuck — so the only
	 * way to learn its floor is from an instance built on it.
	 *
	 * <p>An edge is only recorded where both tiles come from the same template, side by side.
	 * Chunks are stitched together as the instance is built, and two tiles next to each
	 * other in the instance need not be next to each other in the world.
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
		// A tile is walkable if it can be left in any direction. An isolated tile with
		// no exits is of no use to a wandering golem anyway.
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
	 * Can something step one tile from (x, y), cardinal or diagonal?
	 *
	 * <p>A diagonal is not one move but a corner, and the game will not cut one: both
	 * ways round have to be clear, or a golem would slip between two walls that meet at a
	 * point. The four-term tests below are the game's own rule, taken from Shortest Path's
	 * {@code CollisionMap} so that golems corner exactly the way a player does.
	 *
	 * <p>Diagonals matter more than they look. Sailing runs in long straight
	 * eight-directional legs — Port Tasks' own routes are 854 perfect diagonals against
	 * 817 axis-aligned segments — so a cardinal-only pathfinder makes every crossing a
	 * staircase.
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
				&& east(x, y, plane) && north(x + 1, y, plane);
		}
		if (dx == -1 && dy == 1)
		{
			return north(x, y, plane) && west(x, y + 1, plane)
				&& west(x, y, plane) && north(x - 1, y, plane);
		}
		if (dx == 1 && dy == -1)
		{
			return south(x, y, plane) && east(x, y - 1, plane)
				&& east(x, y, plane) && south(x + 1, y, plane);
		}
		if (dx == -1 && dy == -1)
		{
			return south(x, y, plane) && west(x, y - 1, plane)
				&& west(x, y, plane) && south(x - 1, y, plane);
		}
		return false;
	}

	/**
	 * Passability for one tile and direction, live harvest first.
	 *
	 * <p>A region harvested from a loaded scene reflects the world as it is right now,
	 * including objects that come and go; the shipped mesh is a static export that was
	 * true when it was generated. Where both have an opinion the live one wins, which is
	 * also what covers the window between a game update and the mesh being rebuilt.
	 *
	 * <p>Falling through to the mesh is what lets a golem walk ground the player has never
	 * stood on — which, once golems leave the island, is nearly all of it.
	 */
	private boolean get(int x, int y, int plane, int flag)
	{
		// The client does not block the open sea either — boats sail it — so a harvest reads
		// it as open ground, and the shipped island map, itself a harvest, lists the water off
		// the cathedral as walkable. Golems strolled out across it. The mesh's ocean bit marks
		// exactly that sea and nothing a player can stand on, so it overrules every source.
		int toX = flag == FLAG_EAST ? x + 1 : x;
		int toY = flag == FLAG_NORTH ? y + 1 : y;
		if (worldMesh.isOcean(x, y, plane) || worldMesh.isOcean(toX, toY, plane))
		{
			return false;
		}

		long key = key(regionIdOf(x, y), plane);
		long[] bits = regions.get(key);
		long[] mask = bits == null ? null : seen.get(key);
		if (bits != null && (mask == null || isSeen(mask, x & REGION_MASK, y & REGION_MASK)))
		{
			int bit = bitIndex(x & REGION_MASK, y & REGION_MASK, flag);
			return (bits[bit >> 6] >>> (bit & 63) & 1L) != 0L;
		}

		// The mesh says water is passable, because it is — to a boat. A walking golem must
		// not be offered it, so a step is refused unless both ends are ground the land
		// fill actually reached. Both ends, not just the tile being left: land beside
		// water has a perfectly open edge leading straight off the beach.
		//
		// This tests the land mask rather than the ocean bit. Ocean marks only the one
		// connected sea, so cave water, lakes and enclosed basins passed the old check and
		// golems walked out onto them — most visibly on the water inside Wyrmscraig's own
		// caves.
		//
		// The live harvest above needs no such check. It comes from the client, which
		// blocks water for the same reason it blocks a wall.
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
	 * Something that changes passability was added or removed — a door opening or
	 * shutting — so its region is read again from the scene on the next harvest.
	 *
	 * <p>A region was harvested once a session and then trusted. A door is exactly the
	 * thing that makes that wrong: open when the region was read, shut a minute later, and
	 * golems walking through a closed door because memory still said it was open.
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
	 * Encodes the whole memory as one string: anchor, then each region-plane's bits,
	 * gzipped and base64'd.
	 *
	 * <p>Compression is not a nicety. A region is 1KB of flags, an island is several
	 * regions, and a passability map is overwhelmingly long runs of "walkable" — it
	 * deflates to a fraction of that, which is the difference between a config value
	 * and an abuse of one.
	 */
	String serialise()
	{
		try
		{
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (GZIPOutputStream gz = new GZIPOutputStream(out);
				 java.io.DataOutputStream data = new java.io.DataOutputStream(gz))
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
				// Which tiles of each partly harvested region were read, after the regions so
				// that a save without this section still loads.
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
		regions.clear();
		harvested.clear();
		seen.clear();
		anchorRegionId = -1;
		dirty = false;

		if (encoded == null || encoded.isEmpty())
		{
			return;
		}

		try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
			 java.io.DataInputStream data = new java.io.DataInputStream(gz))
		{
			anchorRegionId = data.readInt();
			int count = data.readInt();
			for (int i = 0; i < count; i++)
			{
				long key = data.readLong();
				long[] words = new long[WORDS_PER_REGION];
				for (int w = 0; w < WORDS_PER_REGION; w++)
				{
					words[w] = data.readLong();
				}
				regions.put(key, words);
				// Deliberately not added to `harvested`: a restored region is re-scanned
				// once when it next loads, so map edits by Jagex heal themselves.
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
					seen.put(key, words);
				}
				masked = true;
			}
			catch (java.io.EOFException e)
			{
				masked = false;
			}

			// A save from before seen tiles were kept cannot tell a wall from a tile it never
			// read. The best guess it allows: a tile with an open edge was read, and a tile with
			// none is left to the mesh, which calls water and walls blocked all the same. That
			// keeps ground only a harvest knows, like the cathedral basement, which dropping the
			// save would lose until the player went back down. Island regions have the shipped
			// map filled in under the guess when it loads.
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
			regions.clear();
			anchorRegionId = -1;
		}
	}

	/** Throws away everything learned. */
	void forget()
	{
		regions.clear();
		harvested.clear();
		seen.clear();
		anchorRegionId = -1;
		dirty = true;
	}
}
