package com.golemsdontdie;

import org.junit.Test;
import static org.junit.Assert.assertTrue;

/** Every golem on a boat stands on its deck. */
public class GolemBoatTest
{
	/**
	 * Berths are measured forward of the golem steering, which stands a tile before a tiller: each
	 * must land inside its own hull, the helmsman's included.
	 */
	@Test
	public void everyBerthIsOnItsHull()
	{
		for (GolemBoat boat : GolemBoat.values())
		{
			for (int[] berth : boat.getDeck())
			{
				// A starboard berth is model -x, measured from where the helmsman stands across.
				int across = boat.getSteerAcross() - berth[0];
				int along = boat.getSteerOffset() - berth[1];
				assertTrue(boat + " berth " + berth[0] + "," + berth[1] + " off the side",
					across >= boat.getHullMinX() && across <= boat.getHullMaxX());
				assertTrue(boat + " berth " + berth[0] + "," + berth[1] + " off the end",
					along >= boat.getHullMinZ() && along <= boat.getHullMaxZ());
			}
		}
	}
}
