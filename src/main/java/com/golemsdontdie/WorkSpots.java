package com.golemsdontdie;

import java.util.*;
import lombok.*;
import net.runelite.api.*;

/**
 * Where golems in view could work: every tree, rock, range, wheel, patch and fishing spot in the
 * loaded scene, by what the game offers a player there. See {@link GolemWork}.
 *
 * <p>Read from the scene rather than shipped: only golems the player can see work, and the scene
 * already holds everything they could work at. Nothing is ever taken from it - a golem chopping a
 * tree leaves it standing - so the scene is only read again when the game changes it: a tree a
 * player fells becomes a stump, which offers nothing, and drops out at the next read.
 */
final class WorkSpots
{
	/** One thing to work at, by its south-west tile and size. */
	@Getter
	@EqualsAndHashCode
	@RequiredArgsConstructor
	static final class Spot
	{
		private final int x;
		private final int y;
		private final int plane;
		private final int width;
		private final int height;
		private final GolemWork work;

		/**
		 * The tiles a golem may stand on to work here, {x, y} each: beside a side of it, open, and with
		 * no wall between it and the spot - a range inside a house is worked from inside, not through
		 * the wall. Read from the scene's collision when the spot was found; null if it was not, when
		 * any tile beside a side will do.
		 */
		@EqualsAndHashCode.Exclude
		@Setter(AccessLevel.PACKAGE)
		private int[][] stands;

		/** True if this tile is one the spot may be worked from. */
		boolean isStand(int tileX, int tileY)
		{
			if (stands == null)
			{
				return isBeside(tileX, tileY)
					&& (tileX >= x && tileX < x + width || tileY >= y && tileY < y + height);
			}
			for (int[] stand : stands)
			{
				if (stand[0] == tileX && stand[1] == tileY)
				{
					return true;
				}
			}
			return false;
		}

		/** True if this tile is beside the spot, one of the ring of tiles round it: where a golem stands to work. */
		boolean isBeside(int tileX, int tileY)
		{
			boolean inX = tileX >= x - 1 && tileX <= x + width;
			boolean inY = tileY >= y - 1 && tileY <= y + height;
			boolean inside = tileX >= x && tileX < x + width && tileY >= y && tileY < y + height;
			return inX && inY && !inside;
		}
	}

	/** Ticks between reading the scene again once something in it has changed. */
	private static final int RESCAN_TICKS = 5;

	/** Ticks between finding the fishing spots, which are NPCs and move. */
	private static final int FISHING_TICKS = 3;

	private final Client client;

	private List<Spot> objects = Collections.emptyList();
	private List<Spot> fishing = Collections.emptyList();
	/** Every spot now known, to itself: the latest reading of a spot, its stands included. */
	private Map<Spot, Spot> present = Collections.emptyMap();

	private boolean dirty = true;
	private int scannedTick = Integer.MIN_VALUE;
	private int fishedTick = Integer.MIN_VALUE;

	/**
	 * Tiles golems have claimed to work from, by {@link RoamContext#tileKey}, to {claiming golem's id,
	 * tick the claim lapses}. Claimed as a golem sets off and again as it starts, so two never pick the
	 * same tile, even arriving in the same frame; the frame's occupancy only counts where golems stood
	 * as it began. A claim lapses by itself, so a golem moved off or gone never holds a tile for good.
	 */
	private final Map<Long, long[]> claims = new HashMap<>();

	/** Longer than any claim is made for, so one from before the tick counter started again lapses. */
	private static final int MOST_CLAIM_TICKS = 600;

	/** Claims kept before the lapsed ones are swept out. */
	private static final int CLAIMS_BEFORE_SWEEP = 512;

	/** Per object id, what is done there; ids that offer nothing are kept too, as null. */
	private final Map<Integer, GolemWork> byObject = new HashMap<>();

	WorkSpots(Client client)
	{
		this.client = client;
	}

	/** The scene was rebuilt or an object in it changed: read it again soon. */
	void sceneChanged()
	{
		dirty = true;
	}

	/** Forgets everything, for a logout or the plugin stopping. */
	void clear()
	{
		objects = Collections.emptyList();
		fishing = Collections.emptyList();
		present = Collections.emptyMap();
		byObject.clear();
		claims.clear();
		dirty = true;
		scannedTick = Integer.MIN_VALUE;
		fishedTick = Integer.MIN_VALUE;
	}

	/** Brings the spots up to date with the scene, at most as often as the reads above allow. */
	void update(WorldView wv, int tick)
	{
		// An instance's scene coordinates name no lasting place, and golems in one are in its template.
		if (wv == null || wv.isInstance())
		{
			if (!objects.isEmpty() || !fishing.isEmpty())
			{
				objects = Collections.emptyList();
				fishing = Collections.emptyList();
				present = Collections.emptyMap();
			}
			return;
		}
		boolean changed = false;
		if (dirty && due(tick, scannedTick, RESCAN_TICKS))
		{
			dirty = false;
			scannedTick = tick;
			objects = scanObjects(wv);
			changed = true;
		}
		if (due(tick, fishedTick, FISHING_TICKS))
		{
			fishedTick = tick;
			fishing = scanFishing(wv);
			changed = true;
		}
		if (changed)
		{
			Map<Spot, Spot> all = new HashMap<>();
			for (Spot spot : objects)
			{
				all.put(spot, spot);
			}
			for (Spot spot : fishing)
			{
				all.put(spot, spot);
			}
			present = all;
		}
	}

	/**
	 * True if it has been at least {@code every} ticks since {@code last}. In longs: before the first
	 * read {@code last} is Integer.MIN_VALUE, and in ints the difference wrapped round negative, so
	 * the scene was never read at all. A tick count gone backwards, as at a login, is due too.
	 */
	static boolean due(int tick, int last, int every)
	{
		long since = (long) tick - last;
		return since < 0 || since >= every;
	}

	private List<Spot> scanObjects(WorldView wv)
	{
		Scene scene = wv.getScene();
		Tile[][][] tiles = scene == null ? null : scene.getTiles();
		if (tiles == null)
		{
			return Collections.emptyList();
		}
		int baseX = wv.getBaseX();
		int baseY = wv.getBaseY();
		CollisionData[] collision = wv.getCollisionMaps();
		List<Spot> found = new ArrayList<>();
		for (int plane = 0; plane < tiles.length; plane++)
		{
			for (int sx = 0; sx < tiles[plane].length; sx++)
			{
				for (int sy = 0; sy < tiles[plane][sx].length; sy++)
				{
					Tile tile = tiles[plane][sx][sy];
					if (tile == null || tile.getGameObjects() == null)
					{
						continue;
					}
					for (GameObject object : tile.getGameObjects())
					{
						if (object == null)
						{
							continue;
						}
						// A large object sits on every tile it covers; counted once, from its corner.
						Point min = object.getSceneMinLocation();
						if (min == null || min.getX() != sx || min.getY() != sy)
						{
							continue;
						}
						GolemWork work = workAt(object.getId());
						if (work != null)
						{
							Spot spot = new Spot(baseX + sx, baseY + sy, plane,
								Math.max(1, object.sizeX()), Math.max(1, object.sizeY()), work);
							spot.setStands(standsFor(spot, collision, baseX, baseY));
							found.add(spot);
						}
					}
				}
			}
		}
		return found;
	}

	private GolemWork workAt(int objectId)
	{
		if (byObject.containsKey(objectId))
		{
			return byObject.get(objectId);
		}
		ObjectComposition composition = client.getObjectDefinition(objectId);
		boolean varies = composition != null && composition.getImpostorIds() != null;
		if (varies)
		{
			// A farming patch is one object whose look follows a varbit: weeds, then a crop. Not kept,
			// since what it is now is not what it will be.
			composition = composition.getImpostor();
		}
		GolemWork work = composition == null ? null
			: GolemWork.forObject(composition.getName(), composition.getActions());
		if (!varies)
		{
			byObject.put(objectId, work);
		}
		return work;
	}

	/**
	 * The tiles beside each side of a spot that a golem could stand on and work from, by the scene's
	 * collision: open, and with no wall on the edge between it and the spot. Null if the collision
	 * cannot be read, which leaves every side open.
	 */
	static int[][] standsFor(Spot spot, CollisionData[] collision, int baseX, int baseY)
	{
		if (collision == null || spot.plane < 0 || spot.plane >= collision.length || collision[spot.plane] == null)
		{
			return null;
		}
		int[][] flags = collision[spot.plane].getFlags();
		return flags == null ? null : standsFor(spot, flags, baseX, baseY);
	}

	/** As above, from one floor's collision flags, indexed [scene x][scene y]. */
	static int[][] standsFor(Spot spot, int[][] flags, int baseX, int baseY)
	{
		List<int[]> stands = new ArrayList<>();
		for (int x = spot.x; x < spot.x + spot.width; x++)
		{
			// Below the spot facing north into it, and above it facing south.
			addStand(stands, flags, baseX, baseY, x, spot.y - 1, x, spot.y,
				CollisionDataFlag.BLOCK_MOVEMENT_NORTH, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH);
			addStand(stands, flags, baseX, baseY, x, spot.y + spot.height, x, spot.y + spot.height - 1,
				CollisionDataFlag.BLOCK_MOVEMENT_SOUTH, CollisionDataFlag.BLOCK_MOVEMENT_NORTH);
		}
		for (int y = spot.y; y < spot.y + spot.height; y++)
		{
			// West of it facing east, and east of it facing west.
			addStand(stands, flags, baseX, baseY, spot.x - 1, y, spot.x, y,
				CollisionDataFlag.BLOCK_MOVEMENT_EAST, CollisionDataFlag.BLOCK_MOVEMENT_WEST);
			addStand(stands, flags, baseX, baseY, spot.x + spot.width, y, spot.x + spot.width - 1, y,
				CollisionDataFlag.BLOCK_MOVEMENT_WEST, CollisionDataFlag.BLOCK_MOVEMENT_EAST);
		}
		return stands.toArray(new int[0][]);
	}

	/**
	 * Adds a tile to stand on if it is open and no wall stands between it and the spot's tile beside
	 * it: neither a wall on the stand's edge facing the spot, nor one on the spot's edge facing back.
	 */
	private static void addStand(List<int[]> stands, int[][] flags, int baseX, int baseY,
		int x, int y, int spotX, int spotY, int towardSpot, int towardStand)
	{
		int sx = x - baseX;
		int sy = y - baseY;
		int tx = spotX - baseX;
		int ty = spotY - baseY;
		if (sx < 0 || sy < 0 || tx < 0 || ty < 0 || sx >= flags.length || tx >= flags.length
			|| sy >= flags[sx].length || ty >= flags[tx].length)
		{
			return;
		}
		int stand = flags[sx][sy];
		int there = flags[tx][ty];
		if ((stand & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0 || (stand & towardSpot) != 0
			|| (there & towardStand) != 0)
		{
			return;
		}
		stands.add(new int[]{x, y});
	}

	private static List<Spot> scanFishing(WorldView wv)
	{
		CollisionData[] collision = wv.getCollisionMaps();
		int baseX = wv.getBaseX();
		int baseY = wv.getBaseY();
		List<Spot> found = new ArrayList<>();
		for (NPC npc : wv.npcs())
		{
			NPCComposition composition = npc.getTransformedComposition();
			if (composition == null || !"Fishing spot".equals(composition.getName()) && !"Rod Fishing spot".equals(composition.getName()))
			{
				continue;
			}
			// One way per spot, so two golems at it fish it the same way, and the same way next time.
			GolemWork work = GolemWork.forFishingSpot(composition.getActions(), npc.getIndex());
			net.runelite.api.coords.WorldPoint at = npc.getWorldLocation();
			if (work != null && at != null)
			{
				Spot spot = new Spot(at.getX(), at.getY(), at.getPlane(), 1, 1, work);
				spot.setStands(standsFor(spot, collision, baseX, baseY));
				found.add(spot);
			}
		}
		return found;
	}

	/** The spots on this floor within reach of a tile, added to {@code out}. */
	List<Spot> near(int x, int y, int plane, int reach, List<Spot> out)
	{
		addNear(objects, x, y, plane, reach, out);
		addNear(fishing, x, y, plane, reach, out);
		return out;
	}

	private static void addNear(List<Spot> spots, int x, int y, int plane, int reach, List<Spot> out)
	{
		for (Spot spot : spots)
		{
			if (spot.plane == plane
				&& x >= spot.x - reach && x < spot.x + spot.width + reach
				&& y >= spot.y - reach && y < spot.y + spot.height + reach)
			{
				out.add(spot);
			}
		}
	}

	/** True while this spot is still in the scene as it was: a tree not yet felled, a fishing spot not moved on. */
	boolean stillThere(Spot spot)
	{
		return present.containsKey(spot);
	}

	/**
	 * True while this spot is still in the scene and this tile is still one it may be worked from, by the
	 * latest reading: a door shut since the golem chose its side can leave it working through a wall.
	 * A spot is the same spot across readings, but its stands are read afresh each time.
	 */
	boolean workableFrom(Spot spot, int tileX, int tileY)
	{
		Spot latest = present.get(spot);
		return latest != null && latest.isStand(tileX, tileY);
	}

	/**
	 * Claims a tile for a golem to work from until a tick, unless another golem holds it. A golem may
	 * claim a tile again, as it does on starting work there.
	 *
	 * @return true if the golem holds the tile now
	 */
	boolean claim(int x, int y, int plane, long owner, int tick, int until)
	{
		long key = RoamContext.tileKey(x, y, plane);
		long[] held = claims.get(key);
		if (held != null && held[0] != owner && !lapsed(held, tick))
		{
			return false;
		}
		if (claims.size() >= CLAIMS_BEFORE_SWEEP)
		{
			claims.values().removeIf(claim -> lapsed(claim, tick));
		}
		claims.put(key, new long[]{owner, until});
		return true;
	}

	/** True if a golem other than this one holds the tile. */
	boolean claimedByOther(int x, int y, int plane, long owner, int tick)
	{
		long[] held = claims.get(RoamContext.tileKey(x, y, plane));
		return held != null && held[0] != owner && !lapsed(held, tick);
	}

	/** Lets go of a tile, if this golem holds it. */
	void release(int x, int y, int plane, long owner)
	{
		long key = RoamContext.tileKey(x, y, plane);
		long[] held = claims.get(key);
		if (held != null && held[0] == owner)
		{
			claims.remove(key);
		}
	}

	/** Past its tick, or further off than any claim is made for: the tick counter started again. */
	private static boolean lapsed(long[] claim, int tick)
	{
		return tick >= claim[1] || claim[1] - tick > MOST_CLAIM_TICKS;
	}

	/** For tests: spots as given, with no scene behind them. */
	static WorkSpots of(List<Spot> spots)
	{
		WorkSpots made = new WorkSpots(null);
		made.objects = new ArrayList<>(spots);
		made.present = new HashMap<>();
		for (Spot spot : spots)
		{
			made.present.put(spot, spot);
		}
		made.dirty = false;
		return made;
	}
}
