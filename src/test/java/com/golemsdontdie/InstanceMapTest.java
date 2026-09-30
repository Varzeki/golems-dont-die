package com.golemsdontdie;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;

/** Instance chunks turned as the client turns them. */
public class InstanceMapTest
{
	/**
	 * The client's own rule (WorldPoint.fromLocalInstance): an instance tile's template is the
	 * tile turned back, 4 - r quarter turns. So a template tile drawn in a chunk turned r times and
	 * turned back again is where it started, for every tile and every turn.
	 */
	@Test
	public void turningThereAndBackIsNoTurn()
	{
		for (int r = 0; r < 4; r++)
		{
			for (int x = 0; x < 8; x++)
			{
				for (int y = 0; y < 8; y++)
				{
					int[] drawn = InstanceMap.turn(x, y, r);
					assertArrayEquals(new int[]{x, y}, InstanceMap.turn(drawn[0], drawn[1], 4 - r));
				}
			}
		}
	}

	/** One quarter turn takes the chunk's east edge to its south edge, as the client does. */
	@Test
	public void aQuarterTurnTakesEastToSouth()
	{
		assertArrayEquals(new int[]{3, 0}, InstanceMap.turn(7, 3, 1));
		assertArrayEquals(new int[]{0, 7}, InstanceMap.turn(0, 0, 1));
	}
}
