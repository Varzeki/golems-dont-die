package com.golemsdontdie;

import java.util.*;
import java.util.function.*;
import javax.inject.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import static com.golemsdontdie.RouteGeometry.span;

/**
 * Golems that come aboard the player's own ship.
 *
 * <p>Golems standing near a boat when the player steps onto it come aboard with them, stand at the
 * rail for the voyage, and step off where the player does. The rest of the plugin sails golems on
 * boats of its own drawing; this is the one place a golem rides on something the game controls.
 *
 * <p>A ship is a world of its own: aboard, the player stands in the boat's own world view, which the
 * client moves and turns as the boat sails. A golem aboard is drawn in that same view, at a place on
 * the deck, so the client carries it with the boat exactly as it carries the player — nothing here
 * chases a moving hull frame by frame. Its simulated position is kept at the boat's place in the
 * main world, which is what the map, the sidebar and the distance to it want.
 *
 * <p>Client thread throughout.
 */
@Slf4j
@Singleton
class GolemShipmates
{
	/** How near where the player stepped aboard a golem must be to follow, in tiles. */
	private static final int BOARD_TILES = 8;

	/** Most golems aboard at once, however big the deck. */
	private static final int MOST_ABOARD = 8;

	/**
	 * How near where the ship was the player must step ashore for the golems to step ashore with
	 * them. Further, and the player left some other way — a teleport, a log out — and the golems go
	 * back to the quay they boarded at rather than appearing wherever the player landed.
	 */
	private static final int LANDING_TILES = 12;

	/** What stops a tile being deck to stand on: something on it, or no floor under it. */
	static final int NOT_DECK = CollisionDataFlag.BLOCK_MOVEMENT_FULL
		| CollisionDataFlag.BLOCK_MOVEMENT_OBJECT | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR
		| CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION;

	/**
	 * The same over the hull, where "no floor" does not count: a skiff's deck carries it down one
	 * whole side that is open deck. What over the hull is not deck is marked blocked in the tile's
	 * settings instead; see deckFlags.
	 */
	private static final int NOT_DECK_ON_HULL = CollisionDataFlag.BLOCK_MOVEMENT_OBJECT
		| CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION;

	/** A tile setting: nothing may stand here. */
	private static final int TILE_BLOCKED = 1;

	/** The four ways off a deck tile, and the way a golem at that rail faces: 0 south, 512 west. */
	private static final int[][] OUTBOARD = {{-1, 0, 512}, {1, 0, 1536}, {0, 1, 1024}, {0, -1, 0}};

	@Inject
	private Client client;

	@Inject
	private GolemsDontDieConfig config;

	/** The world view of the ship the player is aboard, or -1 ashore. */
	private int ship = -1;

	/** Where the player last stood on land, which is where they stepped aboard from. */
	private WorldPoint lastAshore;

	/** Where the ship was last seen in the main world, for telling a landing from a teleport. */
	private WorldPoint shipAt;

	/** The golems aboard now. */
	private final List<Golem> aboard = new ArrayList<>();

	/**
	 * Told of a golem whenever it goes aboard or ashore, so its drawn object can be made again: one
	 * is registered with the world it is drawn in, and cannot simply be moved into another.
	 */
	private Consumer<Golem> onMoved = golem ->
	{
	};

	void setOnMoved(Consumer<Golem> onMoved)
	{
		this.onMoved = onMoved;
	}

	/**
	 * Once a tick: notices the player stepping aboard or ashore, and brings the golems along.
	 *
	 * @param snap puts a tile onto ground a golem can stand on; see RoamPlanner.snapToMesh
	 */
	void update(List<Golem> golems, int tick, BiFunction<WorldPoint, Set<Long>, WorldPoint> snap)
	{
		// A golem removed from the roster while aboard is not aboard any more.
		aboard.removeIf(golem -> !golems.contains(golem) || !golem.isAboard());

		Player me = client.getLocalPlayer();
		WorldView top = client.getTopLevelWorldView();
		if (me == null || top == null)
		{
			return;
		}
		WorldView view = me.getWorldView();
		boolean onOwnShip = view != null && !view.isTopLevel() && isOwnShip(top, view.getId());

		if (!onOwnShip)
		{
			if (ship >= 0)
			{
				landed(me.getWorldLocation(), tick, snap);
			}
			if (view == null || view.isTopLevel())
			{
				lastAshore = me.getWorldLocation();
			}
			return;
		}

		shipAt = PlayerPosition.of(client);
		if (ship == view.getId())
		{
			return;
		}
		if (ship >= 0)
		{
			// Onto another ship without touching land: the old one's crew goes home.
			abandon(tick);
		}
		ship = view.getId();
		if (config.golemsJoinShip() && !config.restrictGolemAmbition() && lastAshore != null)
		{
			board(golems, view, me, tick);
		}
	}

	/** Whether this world view is a boat that belongs to the player, rather than a ride or a rival's. */
	private static boolean isOwnShip(WorldView top, int viewId)
	{
		WorldEntity entity = top.worldEntities().byIndex(viewId);
		return entity != null && entity.getOwnerType() == WorldEntity.OWNER_TYPE_SELF_PLAYER;
	}

	/** The player has just stepped aboard: the golems nearby follow them onto the deck. */
	private void board(List<Golem> golems, WorldView deck, Player me, int tick)
	{
		LocalPoint standing = me.getLocalLocation();
		int plane = deckPlane(deck, standing);
		List<int[]> rails = rails(deck, plane, standing);
		if (rails.isEmpty() && plane != deck.getPlane())
		{
			plane = deck.getPlane();
			rails = rails(deck, plane, standing);
		}
		if (rails.isEmpty())
		{
			return;
		}

		List<Golem> coming = new ArrayList<>();
		for (Golem golem : golems)
		{
			if (golem.getRenderer() == null || golem.isDying() || golem.inTransition()
				|| golem.isSailing(tick) || golem.isCrewed() || golem.isAboard()
				|| golem.getPlane() != lastAshore.getPlane()
				|| golem.currentTile().distanceTo2D(lastAshore) > BOARD_TILES)
			{
				continue;
			}
			coming.add(golem);
		}
		// The golems a player cares about first: starred, then named, then whoever is nearest.
		coming.sort(Comparator.comparing((Golem golem) -> !golem.isFavourite())
			.thenComparing(golem -> golem.getNickname() == null || golem.getNickname().isEmpty())
			.thenComparingInt(golem -> golem.currentTile().distanceTo2D(lastAshore)));

		int berths = Math.min(MOST_ABOARD, rails.size());
		for (int i = 0; i < coming.size() && i < berths; i++)
		{
			Golem golem = coming.get(i);
			int[] rail = rails.get(i);
			golem.boardShip(deck.getId(), rail[0], rail[1], plane, rail[2]);
			aboard.add(golem);
			onMoved.accept(golem);
		}
		if (!aboard.isEmpty())
		{
			log.debug("{} golems came aboard", aboard.size());
		}
	}

	/**
	 * The floor the player is standing on: the highest plane of the ship's scene with a tile under
	 * them. Not the world view's own plane, which on a sloop is the hull's, a floor below the deck:
	 * golems were stood at the hull's level on tiles out beside it, in the sea, instead of on deck.
	 */
	static int deckPlane(WorldView deck, LocalPoint standing)
	{
		Tile[][][] tiles = deck.getScene() == null ? null : deck.getScene().getTiles();
		if (tiles == null || standing == null)
		{
			return deck.getPlane();
		}
		int x = standing.getSceneX();
		int y = standing.getSceneY();
		for (int plane = tiles.length - 1; plane >= 0; plane--)
		{
			if (x >= 0 && x < tiles[plane].length && y >= 0 && y < tiles[plane][x].length
				&& tiles[plane][x][y] != null)
			{
				return plane;
			}
		}
		return deck.getPlane();
	}

	/**
	 * Places at the ship's rail, spread around the deck: deck tiles beside open water, each with the
	 * way a golem standing there faces to look out over it, in the deck's own local units. The first
	 * is the rail furthest from the player, each next the one furthest from all those already taken,
	 * so a few golems stand around the ship rather than in a queue along one side.
	 */
	static List<int[]> rails(WorldView deck, int plane, LocalPoint player)
	{
		int[][] flags = deckFlags(deck, plane);
		if (flags == null)
		{
			return new ArrayList<>();
		}
		int sizeX = Math.min(deck.getSizeX(), flags.length);
		int sizeY = flags.length == 0 ? 0 : Math.min(deck.getSizeY(), flags[0].length);
		// A tile the ship's scene has nothing on is not deck, whatever its flags say: water inside
		// the square a ship's world is drawn in must not take a golem because nothing blocks it.
		Tile[][][] tiles = deck.getScene() == null ? null : deck.getScene().getTiles();
		Tile[][] floor = tiles == null || plane >= tiles.length ? null : tiles[plane];
		Tile[][] below = tiles == null || plane < 1 || plane > tiles.length ? null : tiles[plane - 1];

		// Only over the hull. A sloop's floor runs a tile out past the hull on each side, under the
		// trim round its edge, and nothing there blocks; golems took those tiles for the rail and
		// stood out beside the ship, over the sea.
		int[] hull = hullFootprint(deck);
		Set<Long> helm = helmTiles(deck, hull);
		List<int[]> rail = new ArrayList<>();
		for (int x = 0; x < sizeX; x++)
		{
			for (int y = 0; y < sizeY; y++)
			{
				// Nor the bow's row, where the hull narrows to its point: not somewhere anyone stands.
				// The helm is at the other end, the high one, on every hull the game lays out.
				if (!onHull(hull, x, y) || hull != null && y == hull[1]
					|| helm.contains((long) x << 32 | y) || !isDeck(flags, hull, x, y, sizeX, sizeY)
					|| floor != null && !hasTile(floor, x, y) && !hasTile(below, x, y))
				{
					continue;
				}
				for (int[] way : OUTBOARD)
				{
					if (!onHull(hull, x + way[0], y + way[1]) || !isDeck(flags, hull, x + way[0], y + way[1], sizeX, sizeY))
					{
						rail.add(new int[]{x, y, way[2]});
						break;
					}
				}
			}
		}

		int playerX = player == null ? -1 : player.getSceneX();
		int playerY = player == null ? -1 : player.getSceneY();
		rail.removeIf(tile -> tile[0] == playerX && tile[1] == playerY);

		List<int[]> spread = new ArrayList<>(rail.size());
		List<int[]> taken = new ArrayList<>();
		if (player != null)
		{
			taken.add(new int[]{playerX, playerY});
		}
		while (!rail.isEmpty())
		{
			int best = 0;
			int bestGap = -1;
			for (int i = 0; i < rail.size(); i++)
			{
				int gap = Integer.MAX_VALUE;
				for (int[] other : taken)
				{
					gap = Math.min(gap, span(rail.get(i)[0] - other[0], rail.get(i)[1] - other[1]));
				}
				if (gap > bestGap)
				{
					bestGap = gap;
					best = i;
				}
			}
			int[] chosen = rail.remove(best);
			taken.add(chosen);
			// Tile to the middle of the tile, in local units.
			spread.add(new int[]{chosen[0] * Golem.TILE + Golem.TILE / 2, chosen[1] * Golem.TILE + Golem.TILE / 2, chosen[2]});
		}
		return spread;
	}

	/**
	 * What stands in the way on each tile of a deck. Off the hull, its collision. Over the hull, the
	 * ship itself says, and not in its collision, which on a skiff marks the mast's tile blocked and
	 * leaves the salvaging station's open: a tile is deck unless the deck's settings mark it blocked,
	 * as the bow and the helm's row are, or something that fills its tile stands on it — an object
	 * placed with bit 0x100 of its config, which the stations, the wind catcher and the flag are
	 * and the mast, the sail, the helm and the cleats are not. Read so, a skiff's deck is the five
	 * places a player can walk to, where the collision gave golems the stations to stand in.
	 */
	static int[][] deckFlags(WorldView deck, int plane)
	{
		CollisionData[] maps = deck.getCollisionMaps();
		if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null)
		{
			return null;
		}
		int[][] own = maps[plane].getFlags();
		int[] hull = hullFootprint(deck);
		if (hull == null)
		{
			return own;
		}
		byte[][][] settings = deck.getTileSettings();
		boolean[][] filled = filledTiles(deck, plane, own.length, own.length == 0 ? 0 : own[0].length);
		int[][] out = new int[own.length][];
		for (int x = 0; x < own.length; x++)
		{
			out[x] = own[x].clone();
			for (int y = 0; y < own[x].length; y++)
			{
				if (!onHull(hull, x, y))
				{
					continue;
				}
				boolean blocked = settings != null && plane < settings.length && x < settings[plane].length
					&& y < settings[plane][x].length && (settings[plane][x][y] & TILE_BLOCKED) != 0;
				out[x][y] = blocked || filled[x][y] ? CollisionDataFlag.BLOCK_MOVEMENT_OBJECT : 0;
			}
		}
		return out;
	}

	/** An object's config bit for one that fills the tile it stands on. See deckFlags. */
	private static final int FILLS_TILE = 0x100;

	/** The tiles of one floor of a ship that an object filling its tile stands on. */
	private static boolean[][] filledTiles(WorldView deck, int plane, int sizeX, int sizeY)
	{
		boolean[][] out = new boolean[sizeX][sizeY];
		Tile[][][] tiles = deck.getScene() == null ? null : deck.getScene().getTiles();
		if (tiles == null || plane >= tiles.length || tiles[plane] == null)
		{
			return out;
		}
		for (Tile[] column : tiles[plane])
		{
			for (Tile tile : column == null ? new Tile[0] : column)
			{
				if (tile == null || tile.getGameObjects() == null)
				{
					continue;
				}
				for (GameObject object : tile.getGameObjects())
				{
					if (object == null || (object.getConfig() & FILLS_TILE) == 0 || isHull(object.getId()))
					{
						continue;
					}
					int x0 = object.getSceneMinLocation().getX();
					int y0 = object.getSceneMinLocation().getY();
					for (int x = x0; x < x0 + Math.max(1, object.sizeX()) && x < sizeX; x++)
					{
						for (int y = y0; y < y0 + Math.max(1, object.sizeY()) && y < sizeY; y++)
						{
							if (x >= 0 && y >= 0)
							{
								out[x][y] = true;
							}
						}
					}
				}
			}
		}
		return out;
	}

	private static boolean hasTile(Tile[][] floor, int x, int y)
	{
		return floor != null && x < floor.length && y < floor[x].length && floor[x][y] != null;
	}

	/**
	 * The tiles the hull covers, as {minX, minY, maxX, maxY} in the ship's scene, or null if no hull
	 * is found, when every tile counts. Found by the hull objects' own ids: the largest object on a
	 * ship is not its hull but the trim round the edge of it.
	 */
	static int[] hullFootprint(WorldView deck)
	{
		Tile[][][] tiles = deck.getScene() == null ? null : deck.getScene().getTiles();
		if (tiles == null)
		{
			return null;
		}
		for (Tile[][] plane : tiles)
		{
			for (Tile[] column : plane == null ? new Tile[0][] : plane)
			{
				for (Tile tile : column == null ? new Tile[0] : column)
				{
					if (tile == null || tile.getGameObjects() == null)
					{
						continue;
					}
					for (GameObject object : tile.getGameObjects())
					{
						if (object != null && isHull(object.getId()))
						{
							int x = object.getSceneMinLocation().getX();
							int y = object.getSceneMinLocation().getY();
							return new int[]{x, y, x + object.sizeX() - 1, y + object.sizeY() - 1};
						}
					}
				}
			}
		}
		return null;
	}

	/**
	 * Where the player stands to steer, as x << 32 | y: the tile before the helm, toward the bow, which
	 * on a sloop, whose helm stands off the end of the hull, is the hull's last tile. Only that one: a golem
	 * was stood at the wheel, but keeping clear of every tile round it left a skiff with five open
	 * places taking two golems.
	 */
	static Set<Long> helmTiles(WorldView deck, int[] hull)
	{
		Set<Long> out = new HashSet<>();
		Tile[][][] tiles = deck.getScene() == null ? null : deck.getScene().getTiles();
		if (tiles == null)
		{
			return out;
		}
		for (Tile[][] plane : tiles)
		{
			for (Tile[] column : plane == null ? new Tile[0][] : plane)
			{
				for (Tile tile : column == null ? new Tile[0] : column)
				{
					if (tile == null || tile.getGameObjects() == null)
					{
						continue;
					}
					for (GameObject object : tile.getGameObjects())
					{
						if (object != null && isHelm(object.getId()))
						{
							int x = object.getSceneMinLocation().getX();
							int y = object.getSceneMinLocation().getY();
							// The player steers from the tile before the wheel, a step toward the bow,
							// which is the low end of every hull's own grid.
							y -= 1;
							if (hull != null)
							{
								x = Math.max(hull[0], Math.min(hull[2], x));
								y = Math.max(hull[1], Math.min(hull[3], y));
							}
							out.add((long) x << 32 | y);
						}
					}
				}
			}
		}
		return out;
	}

	/** Every Kandarin helm, the wheel and the tutorial's, and the pirates'. */
	private static boolean isHelm(int objectId)
	{
		return objectId >= 59554 && objectId <= 59623 || objectId == 29536;
	}

	/** The Kandarin hulls in every wood, raft to sloop, the colossal one and the pirates'. */
	private static boolean isHull(int objectId)
	{
		return objectId >= 59494 && objectId <= 59515 || objectId == 29472;
	}

	private static boolean onHull(int[] hull, int x, int y)
	{
		return hull == null || x >= hull[0] && y >= hull[1] && x <= hull[2] && y <= hull[3];
	}

	private static boolean isDeck(int[][] flags, int[] hull, int x, int y, int sizeX, int sizeY)
	{
		int mask = hull != null && onHull(hull, x, y) ? NOT_DECK_ON_HULL : NOT_DECK;
		return x >= 0 && y >= 0 && x < sizeX && y < sizeY && (flags[x][y] & mask) == 0;
	}

	/**
	 * Keeps a golem aboard at the ship's place in the main world, for everything that asks where it
	 * is. Its drawn place is on the deck, and the client moves that with the boat.
	 */
	void carry(Golem golem)
	{
		WorldView top = client.getTopLevelWorldView();
		if (top == null)
		{
			return;
		}
		WorldEntity boat = top.worldEntities().byIndex(golem.getAboardView());
		if (boat == null)
		{
			return;
		}
		LocalPoint atSea = boat.transformToMainWorld(new LocalPoint(golem.getDeckX(), golem.getDeckY(), golem.getAboardView()));
		if (atSea != null)
		{
			golem.followShip(atSea.getX() + top.getBaseX() * Golem.TILE, atSea.getY() + top.getBaseY() * Golem.TILE,
				top.getPlane());
		}
	}

	/**
	 * The player is off the ship. Near where it was, and the golems step off around them; anywhere
	 * else, and each goes back to the quay it boarded from.
	 */
	private void landed(WorldPoint ashore, int tick, BiFunction<WorldPoint, Set<Long>, WorldPoint> place)
	{
		boolean withPlayer = ashore != null && shipAt != null && ashore.getPlane() == shipAt.getPlane()
			&& ashore.distanceTo2D(shipAt) <= LANDING_TILES;
		// A tile each, on land, around where the player stepped off; not the player's own.
		Set<Long> taken = new HashSet<>();
		if (ashore != null)
		{
			taken.add(RoamContext.tileKey(ashore.getX(), ashore.getY(), ashore.getPlane()));
		}
		for (Golem golem : aboard)
		{
			WorldPoint to = golem.getAboardFrom();
			if (withPlayer && place != null)
			{
				WorldPoint ground = place.apply(ashore, taken);
				if (ground != null && !ground.equals(ashore))
				{
					to = ground;
					taken.add(RoamContext.tileKey(ground.getX(), ground.getY(), ground.getPlane()));
				}
			}
			golem.leaveShip(to, tick);
			onMoved.accept(golem);
		}
		aboard.clear();
		ship = -1;
		shipAt = null;
	}

	/**
	 * Sends every golem aboard back to the quay it boarded from: the voyage is over for a reason the
	 * golems were not part of — a world hop, a log out, the plugin stopping.
	 */
	void abandon(int tick)
	{
		for (Golem golem : aboard)
		{
			golem.leaveShip(golem.getAboardFrom(), tick);
			onMoved.accept(golem);
		}
		aboard.clear();
		ship = -1;
		shipAt = null;
		// The next ship boarded is boarded from wherever the player next stands ashore, not from a
		// quay left before a hop or a log out.
		lastAshore = null;
	}
}
