package com.golemsdontdie;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.CollisionData;
import net.runelite.api.GameObject;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * A real skiff's deck, as ::gdeck and ::gparts read it off one at sea: where golems may stand. A
 * skiff has eight places to stand, one of them where its helmsman stands; this one has a salvaging
 * station and a wind catcher on two, so five are left for golems. Its collision says otherwise -
 * the mast's tile blocked, the stations' open - which is why the deck is read from its objects.
 */
public class GolemSkiffDeckTest
{
	private static final int SIZE = 8;

	/** Hull 59505, 2x6 from 3,1; helm 59585 on 4,6; the player steering from 4,5. */
	private static final int HULL_ID = 59505;
	private static final int HELM_ID = 59585;

	/** Collision on the hull's floor and the deck's, by x then y, as read. */
	private static final int[][] FLOOR_0 = new int[SIZE][SIZE];
	private static final int[][] FLOOR_1 = new int[SIZE][SIZE];
	private static final byte[][][] SETTINGS = new byte[4][SIZE][SIZE];

	static
	{
		int[] ys = {6, 5, 4, 3, 2, 1};
		int[] west0 = {0, 0, 0, 0, 0x20100, 0};
		int[] east0 = {0x20100, 0x20100, 0x20100, 0x20100, 0x20100, 0};
		int[] west1 = {0x200000, 0x200000, 0x200000, 0x200000, 0x200000, 0};
		int[] east1 = {0x100, 0, 0x100, 0, 0x200100, 0};
		for (int i = 0; i < ys.length; i++)
		{
			FLOOR_0[3][ys[i]] = west0[i];
			FLOOR_0[4][ys[i]] = east0[i];
			FLOOR_1[3][ys[i]] = west1[i];
			FLOOR_1[4][ys[i]] = east1[i];
		}
		// The bow row and the helm row are no place to stand.
		SETTINGS[1][3][1] = 1;
		SETTINGS[1][4][1] = 1;
		SETTINGS[1][3][6] = 1;
		SETTINGS[1][4][6] = 1;
	}

	private static GameObject object(int id, int x, int y, int sizeX, int sizeY)
	{
		return object(id, x, y, sizeX, sizeY, 10);
	}

	private static GameObject object(int id, int x, int y, int sizeX, int sizeY, int config)
	{
		return (GameObject) Proxy.newProxyInstance(GameObject.class.getClassLoader(), new Class<?>[]{GameObject.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getId":
						return id;
					case "getSceneMinLocation":
						return new Point(x, y);
					case "sizeX":
						return sizeX;
					case "sizeY":
						return sizeY;
					case "getConfig":
						return config;
					default:
						return null;
				}
			});
	}

	private static Tile tile(GameObject... objects)
	{
		return (Tile) Proxy.newProxyInstance(Tile.class.getClassLoader(), new Class<?>[]{Tile.class},
			(proxy, method, args) -> "getGameObjects".equals(method.getName()) ? objects : null);
	}

	private static CollisionData collision(int[][] flags)
	{
		return (CollisionData) Proxy.newProxyInstance(CollisionData.class.getClassLoader(),
			new Class<?>[]{CollisionData.class}, (proxy, method, args) ->
				"getFlags".equals(method.getName()) ? flags : null);
	}

	private static WorldView skiff()
	{
		Tile[][][] tiles = new Tile[4][SIZE][SIZE];
		for (int x = 3; x <= 4; x++)
		{
			for (int y = 1; y <= 6; y++)
			{
				tiles[0][x][y] = tile();
				tiles[1][x][y] = tile();
			}
		}
		tiles[0][3][1] = tile(object(HULL_ID, 3, 1, 2, 6, 266));
		// The deck's own things, with their configs as read: 0x100 set on what fills a tile.
		tiles[1][3][1] = tile(object(59704, 3, 1, 1, 1, 266));
		tiles[1][4][1] = tile(object(60263, 4, 1, 1, 1, 266));
		tiles[1][3][2] = tile(object(60493, 3, 2, 1, 1, 74));
		tiles[1][3][3] = tile(object(59700, 3, 3, 1, 1, 458));
		tiles[1][4][4] = tile(object(59541, 4, 4, 1, 1, 10));
		tiles[1][3][5] = tile(object(59683, 3, 5, 1, 1, 266));
		tiles[1][4][5] = tile(object(29525, 4, 5, 1, 1, 10));
		tiles[1][3][6] = tile(object(59488, 3, 6, 1, 1, 266));
		tiles[1][4][6] = tile(object(HELM_ID, 4, 6, 1, 1, 10));
		CollisionData[] maps = {collision(FLOOR_0), collision(FLOOR_1), collision(new int[SIZE][SIZE]),
			collision(new int[SIZE][SIZE])};
		Scene scene = (Scene) Proxy.newProxyInstance(Scene.class.getClassLoader(), new Class<?>[]{Scene.class},
			(proxy, method, args) -> "getTiles".equals(method.getName()) ? tiles : null);
		return (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[]{WorldView.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getCollisionMaps":
						return maps;
					case "getTileSettings":
						return SETTINGS;
					case "getSizeX":
					case "getSizeY":
						return SIZE;
					case "getScene":
						return scene;
					case "getId":
						return 316;
					case "getPlane":
						return 1;
					default:
						return null;
				}
			});
	}

	@Test
	public void fiveGolemsFitOnThisSkiff()
	{
		LocalPoint steering = new LocalPoint(4 * Golem.TILE + Golem.TILE / 2, 5 * Golem.TILE + Golem.TILE / 2, 316);
		List<int[]> rails = GolemShipmates.rails(skiff(), 1, steering);
		Set<String> places = new HashSet<>();
		for (int[] rail : rails)
		{
			places.add(rail[0] / Golem.TILE + "," + rail[1] / Golem.TILE);
		}
		// Down the helm's side, and the two either side of the salvaging station.
		assertEquals(new HashSet<>(java.util.Arrays.asList("4,2", "4,3", "4,4", "3,2", "3,4")), places);
	}

	/**
	 * A raft as ::gdeck read one: 1x3, a chest at the bow, the mast in the middle, the helm at the
	 * stern with the player standing on it to steer. One golem, in the middle.
	 */
	private static WorldView raft()
	{
		int[][] floor0 = new int[SIZE][SIZE];
		int[][] floor1 = new int[SIZE][SIZE];
		for (int y = 2; y <= 4; y++)
		{
			floor1[3][y] = 0x200000;
		}
		Tile[][][] tiles = new Tile[4][SIZE][SIZE];
		for (int y = 2; y <= 4; y++)
		{
			tiles[0][3][y] = tile();
		}
		tiles[0][3][2] = tile(object(59494, 3, 2, 1, 3, 266));
		tiles[1][3][2] = tile(object(60245, 3, 2, 1, 1, 266));
		tiles[1][3][3] = tile(object(59530, 3, 3, 1, 1, 10));
		tiles[1][3][4] = tile(object(59554, 3, 4, 1, 1, 10));
		tiles[1][3][5] = tile(object(29506, 3, 5, 1, 1, 10));
		byte[][][] settings = new byte[4][SIZE][SIZE];
		CollisionData[] maps = {collision(floor0), collision(floor1), collision(new int[SIZE][SIZE]),
			collision(new int[SIZE][SIZE])};
		Scene scene = (Scene) Proxy.newProxyInstance(Scene.class.getClassLoader(), new Class<?>[]{Scene.class},
			(proxy, method, args) -> "getTiles".equals(method.getName()) ? tiles : null);
		return (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[]{WorldView.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getCollisionMaps":
						return maps;
					case "getTileSettings":
						return settings;
					case "getSizeX":
					case "getSizeY":
						return SIZE;
					case "getScene":
						return scene;
					case "getId":
						return 321;
					case "getPlane":
						return 1;
					default:
						return null;
				}
			});
	}

	@Test
	public void oneGolemFitsOnARaftInTheMiddle()
	{
		LocalPoint steering = new LocalPoint(3 * Golem.TILE + Golem.TILE / 2, 4 * Golem.TILE + Golem.TILE / 2, 321);
		List<int[]> rails = GolemShipmates.rails(raft(), 1, steering);
		assertEquals(1, rails.size());
		assertEquals(3, rails.get(0)[0] / Golem.TILE);
		assertEquals(3, rails.get(0)[1] / Golem.TILE);
	}
}
