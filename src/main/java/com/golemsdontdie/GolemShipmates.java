package com.golemsdontdie;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Player;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

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

	/** What stops a tile being deck to stand on. */
	static final int NOT_DECK = CollisionDataFlag.BLOCK_MOVEMENT_FULL
		| CollisionDataFlag.BLOCK_MOVEMENT_OBJECT | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR
		| CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION;

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
	void update(List<Golem> golems, int tick, UnaryOperator<WorldPoint> snap)
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
		int plane = deck.getPlane();
		List<int[]> rails = rails(deck, plane, standing);
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
	 * Places at the ship's rail, spread around the deck: deck tiles beside open water, each with the
	 * way a golem standing there faces to look out over it, in the deck's own local units. The first
	 * is the rail furthest from the player, each next the one furthest from all those already taken,
	 * so a few golems stand around the ship rather than in a queue along one side.
	 */
	static List<int[]> rails(WorldView deck, int plane, LocalPoint player)
	{
		CollisionData[] maps = deck.getCollisionMaps();
		if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null)
		{
			return new ArrayList<>();
		}
		int[][] flags = maps[plane].getFlags();
		int sizeX = Math.min(deck.getSizeX(), flags.length);
		int sizeY = flags.length == 0 ? 0 : Math.min(deck.getSizeY(), flags[0].length);
		// A tile the ship's scene has nothing on is not deck, whatever its flags say: water inside
		// the square a ship's world is drawn in must not take a golem because nothing blocks it.
		net.runelite.api.Tile[][][] tiles = deck.getScene() == null ? null : deck.getScene().getTiles();
		net.runelite.api.Tile[][] floor = tiles == null || plane >= tiles.length ? null : tiles[plane];

		List<int[]> rail = new ArrayList<>();
		for (int x = 0; x < sizeX; x++)
		{
			for (int y = 0; y < sizeY; y++)
			{
				if (!isDeck(flags, x, y, sizeX, sizeY)
					|| floor != null && (x >= floor.length || y >= floor[x].length || floor[x][y] == null))
				{
					continue;
				}
				for (int[] way : OUTBOARD)
				{
					if (!isDeck(flags, x + way[0], y + way[1], sizeX, sizeY))
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
					gap = Math.min(gap, Math.max(Math.abs(rail.get(i)[0] - other[0]), Math.abs(rail.get(i)[1] - other[1])));
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

	private static boolean isDeck(int[][] flags, int x, int y, int sizeX, int sizeY)
	{
		return x >= 0 && y >= 0 && x < sizeX && y < sizeY && (flags[x][y] & NOT_DECK) == 0;
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
	private void landed(WorldPoint ashore, int tick, UnaryOperator<WorldPoint> snap)
	{
		boolean withPlayer = ashore != null && shipAt != null && ashore.getPlane() == shipAt.getPlane()
			&& ashore.distanceTo2D(shipAt) <= LANDING_TILES;
		int i = 0;
		for (Golem golem : aboard)
		{
			WorldPoint to = withPlayer ? besideLanding(ashore, i++, snap) : golem.getAboardFrom();
			golem.leaveShip(to, tick);
			onMoved.accept(golem);
		}
		aboard.clear();
		ship = -1;
		shipAt = null;
	}

	/** The places around a landing, in the order golems take them: the player's own tile last. */
	private static final int[][] AROUND = {
		{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2},
	};

	private static WorldPoint besideLanding(WorldPoint ashore, int index, UnaryOperator<WorldPoint> snap)
	{
		int[] step = AROUND[index % AROUND.length];
		WorldPoint beside = ashore.dx(step[0]).dy(step[1]);
		WorldPoint ground = snap == null ? null : snap.apply(beside);
		return ground != null ? ground : ashore;
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

	/** Whether any golem is aboard now. */
	boolean isAnyAboard()
	{
		return !aboard.isEmpty();
	}
}
