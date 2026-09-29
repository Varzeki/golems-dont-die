package com.golemsdontdie;

import javax.inject.*;
import net.runelite.api.coords.*;

/**
 * Where a golem is, in words: "Catherby", "Taverley Dungeon", "Sailing to Port Khazard".
 *
 * <p>Said the way a player would say it, from what the golem is doing rather than from its
 * coordinates alone - a golem halfway across the sea is between two ports, and naming the water it
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
			return unnamed(at);
		}
		if (golem.isInInstance())
		{
			return "Inside " + place;
		}
		return golem.getPlane() > 0 ? place + ", upstairs" : place;
	}

	/**
	 * Somewhere no place name reaches: near the nearest one, or beneath the ground above a cave.
	 * "Somewhere unmapped" said nothing a player could go looking with.
	 */
	private String unnamed(WorldPoint at)
	{
		String upstairs = at.getPlane() > 0 ? ", upstairs" : "";
		if (WorldLayout.isCave(at.getY()))
		{
			int above = WorldLayout.groundAbove(at.getY());
			String over = above < 0 ? null : places.nameFor(at.getX(), above, 0);
			if (over == null && above >= 0)
			{
				over = places.nearest(at.getX(), above, 0);
			}
			if (over != null)
			{
				return "Beneath " + over;
			}
		}
		String near = places.nearest(at.getX(), at.getY(), at.getPlane());
		return near != null ? "Near " + near + upstairs
			: at.getPlane() > 0 ? "Somewhere upstairs" : "Somewhere unmapped";
	}
}
