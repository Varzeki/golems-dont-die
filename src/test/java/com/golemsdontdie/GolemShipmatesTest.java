package com.golemsdontdie;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Where golems stand on the player's ship. A ship's world is a small square with the deck in the
 * middle and water round it; golems belong at the rail, looking out, and never in the water.
 */
public class GolemShipmatesTest
{
	/** The ship's world: seven by nine, water everywhere but a deck three wide and five long. */
	private static final int SIZE_X = 7;
	private static final int SIZE_Y = 9;
	private static final int DECK_WEST = 2;
	private static final int DECK_EAST = 4;
	private static final int DECK_SOUTH = 2;
	private static final int DECK_NORTH = 6;

	private static boolean onDeck(int x, int y)
	{
		return x >= DECK_WEST && x <= DECK_EAST && y >= DECK_SOUTH && y <= DECK_NORTH;
	}

	/** Stands in for the few parts of a world view the rail finder reads. */
	private static WorldView ship()
	{
		int[][] flags = new int[SIZE_X][SIZE_Y];
		Tile[][][] tiles = new Tile[1][SIZE_X][SIZE_Y];
		Tile somewhere = (Tile) Proxy.newProxyInstance(Tile.class.getClassLoader(), new Class<?>[]{Tile.class},
			(proxy, method, args) -> null);
		for (int x = 0; x < SIZE_X; x++)
		{
			for (int y = 0; y < SIZE_Y; y++)
			{
				flags[x][y] = onDeck(x, y) ? 0 : CollisionDataFlag.BLOCK_MOVEMENT_FLOOR;
				tiles[0][x][y] = somewhere;
			}
		}
		CollisionData collision = (CollisionData) Proxy.newProxyInstance(CollisionData.class.getClassLoader(),
			new Class<?>[]{CollisionData.class}, (proxy, method, args) ->
				"getFlags".equals(method.getName()) ? flags : null);
		Scene scene = (Scene) Proxy.newProxyInstance(Scene.class.getClassLoader(), new Class<?>[]{Scene.class},
			(proxy, method, args) -> "getTiles".equals(method.getName()) ? tiles : null);
		return (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[]{WorldView.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getCollisionMaps":
						return new CollisionData[]{collision};
					case "getSizeX":
						return SIZE_X;
					case "getSizeY":
						return SIZE_Y;
					case "getScene":
						return scene;
					case "getId":
						return 7;
					default:
						return null;
				}
			});
	}

	private static LocalPoint at(int x, int y)
	{
		return new LocalPoint(x * Golem.TILE + Golem.TILE / 2, y * Golem.TILE + Golem.TILE / 2, 7);
	}

	@Test
	public void everyPlaceIsOnTheRailAndNoneIsInTheWater()
	{
		List<int[]> rails = GolemShipmates.rails(ship(), 0, at(3, 4));
		// The deck's edge: three by five has twelve tiles round it, and the player is in the middle.
		assertEquals(12, rails.size());
		for (int[] rail : rails)
		{
			int x = rail[0] / Golem.TILE;
			int y = rail[1] / Golem.TILE;
			assertTrue("on the deck: " + x + "," + y, onDeck(x, y));
			assertTrue("at its edge: " + x + "," + y,
				x == DECK_WEST || x == DECK_EAST || y == DECK_SOUTH || y == DECK_NORTH);
		}
	}

	@Test
	public void eachLooksOutOverTheWaterBesideIt()
	{
		for (int[] rail : GolemShipmates.rails(ship(), 0, at(3, 4)))
		{
			int x = rail[0] / Golem.TILE;
			int y = rail[1] / Golem.TILE;
			// 0 south, 512 west, 1024 north, 1536 east: the way it faces is off the deck.
			int dx = rail[2] == 512 ? -1 : rail[2] == 1536 ? 1 : 0;
			int dy = rail[2] == 1024 ? 1 : rail[2] == 0 ? -1 : 0;
			assertFalse("faces the water at " + x + "," + y, onDeck(x + dx, y + dy));
		}
	}

	@Test
	public void thePlayersOwnPlaceIsNotTaken()
	{
		for (int[] rail : GolemShipmates.rails(ship(), 0, at(DECK_WEST, 4)))
		{
			assertFalse(rail[0] / Golem.TILE == DECK_WEST && rail[1] / Golem.TILE == 4);
		}
	}

	private static final WorldPoint QUAY = new WorldPoint(3029, 3217, 0);

	/** Golems step off onto tiles of their own around the player while there are any. */
	@Test
	public void eachStepsOffOntoATileOfItsOwn()
	{
		Set<Long> taken = new HashSet<>();
		taken.add(RoamContext.tileKey(QUAY.getX(), QUAY.getY(), QUAY.getPlane()));
		// Room for one beside the player, then none.
		BiFunction<WorldPoint, Set<Long>, WorldPoint> place = (at, stood) ->
		{
			WorldPoint beside = at.dx(1);
			return stood.contains(RoamContext.tileKey(beside.getX(), beside.getY(), beside.getPlane())) ? at : beside;
		};
		assertEquals(QUAY.dx(1), GolemShipmates.stepOff(QUAY, taken, place));
		// A narrow quay: the next shares the player's tile, not the port it boarded at.
		assertEquals(QUAY, GolemShipmates.stepOff(QUAY, taken, place));
		assertEquals(QUAY, GolemShipmates.stepOff(QUAY, taken, (at, stood) -> null));
	}

	/** A few golems stand round the ship, not in a queue down one side of it. */
	@Test
	public void theFirstFewAreSpreadOut()
	{
		List<int[]> rails = GolemShipmates.rails(ship(), 0, at(3, 4));
		for (int i = 0; i < 4; i++)
		{
			for (int j = i + 1; j < 4; j++)
			{
				int apart = Math.max(Math.abs(rails.get(i)[0] - rails.get(j)[0]),
					Math.abs(rails.get(i)[1] - rails.get(j)[1])) / Golem.TILE;
				assertTrue("places " + i + " and " + j + " are " + apart + " apart", apart >= 2);
			}
		}
	}
}
