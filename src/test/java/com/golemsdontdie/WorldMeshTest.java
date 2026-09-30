package com.golemsdontdie;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The shipped world mesh, format 3: a byte a tile, and water. */
public class WorldMeshTest
{
	private static final WorldMesh MESH = new WorldMesh();

	static
	{
		MESH.load();
	}

	/**
	 * A post standing where four tiles meet refuses all four diagonals across it, though every
	 * edge round it is open. The two-bit map could not say so, and golems cut through it.
	 */
	@Test
	public void aCornerPostRefusesTheDiagonal()
	{
		assertTrue("the edges round the post are open", MESH.north(1235, 3637, 0) && MESH.east(1235, 3637, 0));
		assertTrue(MESH.cornerBlocks(1235, 3637, 0, 1, 1));
		assertTrue(MESH.cornerBlocks(1236, 3638, 0, -1, -1));
		assertTrue(MESH.cornerBlocks(1236, 3637, 0, -1, 1));
		assertTrue(MESH.cornerBlocks(1235, 3638, 0, 1, -1));
		assertFalse("the step beside it is free", MESH.cornerBlocks(1234, 3637, 0, 1, 1));
	}

	/** Wyrmscraig's cave lake is water, not the sea, and none of it is ground. */
	@Test
	public void theCaveLakeIsWater()
	{
		int water = 0;
		for (int x = 2567; x <= 2587; x++)
		{
			for (int y = 8590; y <= 8617; y++)
			{
				if (MESH.isInlandWater(x, y, 0))
				{
					water++;
					assertFalse("lake at " + x + "," + y + " read as ground", MESH.isLandWalkable(x, y, 0));
					assertFalse(MESH.isOcean(x, y, 0));
				}
			}
		}
		assertEquals("the lake's tiles", 276, water);
	}

	/** Home stands on ground: something solid-free with a floor, not the void. */
	@Test
	public void homeIsGround()
	{
		assertTrue(MESH.isWalkable(GolemContent.PLINTH_X, GolemContent.PLINTH_Y, 0));
		assertFalse(MESH.isWater(GolemContent.PLINTH_X, GolemContent.PLINTH_Y, 0));
	}
}
