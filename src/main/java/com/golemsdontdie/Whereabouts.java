package com.golemsdontdie;

import javax.inject.*;
import javax.inject.Inject;
import net.runelite.api.coords.*;

/**
 * Where a golem is, in words: "Catherby", "Taverley Dungeon", "Sailing to Port Khazard".
 *
 * <p>Said the way a player would say it, from what the golem is doing rather than from its
 * coordinates alone — a golem halfway across the sea is between two ports, and naming the water it
 * is over would tell nobody anything.
 */
@Singleton
class Whereabouts
{
	@Inject
	private PlaceNames places;

	@Inject
	private Voyage voyage;

	/** How near a dock a golem must be for the dock's own name to be worth saying, in tiles. */
	private static final int AT_THE_DOCK = 6;

	String of(Golem golem, int tick)
	{
		if (golem == null)
		{
			return "";
		}
		if (golem.isAboard())
		{
			return "Aboard your ship";
		}
		if (golem.isSailing(tick))
		{
			// Where it will land, which is the useful half of a crossing: the sea itself has no name
			// worth giving, and a raft is only ever between two ports.
			SailingDocks.Dock landing = voyage == null ? null : voyage.dockAt(golem.saveTile(), AT_THE_DOCK);
			String port = landing != null ? landing.getName() : places.nameFor(golem.saveTile());
			return port == null ? "At sea" : "Sailing to " + port;
		}

		WorldPoint at = golem.currentTile();
		String place = places.nameFor(at);
		if (place == null)
		{
			return golem.getPlane() > 0 ? "Somewhere upstairs" : "Somewhere unmapped";
		}
		if (golem.isInInstance())
		{
			return "Inside " + place;
		}
		return golem.getPlane() > 0 ? place + ", upstairs" : place;
	}
}
