package com.golemsdontdie;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/**
 * Plans where a far-away golem goes next, without pathing on tiles.
 *
 * <p>Tier 3 covers most of the world and most of the golems, and a tile-accurate search
 * across a region is far more than the answer is worth when nobody is watching. What it
 * needs is somewhere plausible to be heading and a believable time to arrive.
 *
 * <p>So a far golem aims at a transport it could reach, or failing that at open ground a
 * few dozen tiles away, and the route between is a straight run rather than a search. The
 * inaccuracy is invisible by construction: the moment a player gets close enough for the
 * difference to show, the golem is promoted and snapped onto real, walkable ground.
 */
@Slf4j
@Singleton
class RoamPlanner
{
	/** How far a far-roaming golem will wander in one leg, in tiles. */
	private static final int LEG_MIN = 12;
	private static final int LEG_SPREAD = 40;

	/** Tries at finding a destination before the golem simply stays put for a while. */
	private static final int ATTEMPTS = 6;

	/** How far out to look for a transport worth heading to. */
	private static final int TRANSPORT_SEARCH = 48;

	@Inject
	private WorldMesh mesh;

	@Inject
	private TransportNetwork transports;

	@Inject
	private GolemAbilities abilities;

	@Inject
	private Voyage voyage;

	@Inject
	private SailingDocks docks;

	/**
	 * Chance per planning decision that a golem at a port sets sail.
	 *
	 * <p>Low on purpose. A golem plans a new leg every half-minute or so, so even at one
	 * in eight a golem that reaches a dock leaves within a few minutes — while a golem
	 * that merely walks past one usually keeps walking.
	 */
	private static final float SAIL_CHANCE = 0.125f;

	/**
	 * How close to the quayside counts as being at the port, in tiles.
	 *
	 * <p>Tight, because boarding is something a golem walks up to and does. Wide enough
	 * that it need not land on one exact tile, narrow enough that it is plainly at the
	 * dock rather than merely somewhere in the harbour.
	 */
	private static final int DOCK_REACH = 2;

	/**
	 * Plans the next leg for a golem that nobody can see.
	 *
	 * @return an itinerary, or null if nowhere sensible was found
	 */
	Itinerary plan(WorldPoint from, int tick, Random random, TransportMemory memory,
		RoamContext context)
	{
		Itinerary crossing = planVoyage(from, tick, random, memory, context);
		if (crossing != null)
		{
			return crossing;
		}

		GolemTransport target = nearbyTransport(from, random);
		if (target != null)
		{
			// Walk to the transport, then take it. The transport's own duration is added
			// as extra time so that a long ferry is not walked across in four ticks.
			List<int[]> path = straightLine(from.getX(), from.getY(),
				target.getFromX(), target.getFromY(), from.getPlane());
			if (path != null)
			{
				path.add(new int[]{target.getToX(), target.getToY()});
				return Itinerary.of(path, target.getToPlane(), tick, target.getDuration());
			}
		}

		for (int attempt = 0; attempt < ATTEMPTS; attempt++)
		{
			int distance = LEG_MIN + random.nextInt(LEG_SPREAD);
			double angle = random.nextDouble() * Math.PI * 2;
			int toX = from.getX() + (int) Math.round(Math.cos(angle) * distance);
			int toY = from.getY() + (int) Math.round(Math.sin(angle) * distance);

			if (!mesh.isLandWalkable(toX, toY, from.getPlane())
				|| mesh.isIsolated(toX, toY, from.getPlane()))
			{
				continue;
			}
			List<int[]> leg = straightLine(from.getX(), from.getY(), toX, toY,
				from.getPlane());
			if (leg == null)
			{
				// The line crosses ground the golem could not walk. Try another angle.
				continue;
			}
			return Itinerary.of(leg, from.getPlane(), tick, 0);
		}
		return null;
	}

	/**
	 * Rolls for a crossing, for a golem standing at a port.
	 *
	 * <p>Called from two places, and that is the point: a far golem plans one when its
	 * route runs out, and a golem in view rolls for one as it steps onto a tile. Sailing
	 * used to happen only out of range, which meant nobody ever watched a golem leave.
	 *
	 * <p>Affordable in view because crossings are cached per pair of ports and new
	 * searches are rationed to one a frame. A golem refused the budget just does not sail
	 * this time — indistinguishable from one that chose to stay.
	 *
	 * @return a crossing, or null if the golem is not at a port, is on shore leave, lost
	 *         the roll, or the route is unknown and the frame's search is spent
	 */
	Itinerary planVoyage(WorldPoint from, int tick, Random random, TransportMemory memory,
		RoamContext context)
	{
		if (memory == null || memory.onShoreLeave(tick) || random.nextFloat() >= SAIL_CHANCE)
		{
			return null;
		}

		SailingDocks.Dock dock = voyage.dockAt(from, DOCK_REACH);
		if (dock == null || !docks.isOpen(dock))
		{
			return null;
		}

		Itinerary crossing = voyage.depart(dock, memory, tick, random, context);
		if (crossing == null)
		{
			return null;
		}

		// Ashore at the far end, the golem walks inland for a while before it is allowed
		// to think about boats again. Otherwise a golem that arrives at a port simply
		// leaves it, and no golem is ever seen anywhere except on the water.
		memory.beginShoreLeaveOnArrival(tick + crossing.getDuration());
		return crossing;
	}

	/**
	 * A transport the golem could plausibly walk to and use, or null.
	 *
	 * <p>Sampled rather than enumerated. Scanning a 96-tile box for every far golem that
	 * needs a new leg would be tens of thousands of lookups; a few dozen random probes
	 * finds the dense clusters — towns, dungeon entrances — which is where a golem ought
	 * to end up anyway.
	 */
	private GolemTransport nearbyTransport(WorldPoint from, Random random)
	{
		for (int attempt = 0; attempt < ATTEMPTS * 4; attempt++)
		{
			int x = from.getX() + random.nextInt(TRANSPORT_SEARCH * 2 + 1) - TRANSPORT_SEARCH;
			int y = from.getY() + random.nextInt(TRANSPORT_SEARCH * 2 + 1) - TRANSPORT_SEARCH;
			if (!transports.hasOrigin(x, y))
			{
				continue;
			}
			for (GolemTransport transport : transports.from(x, y))
			{
				if (transport.getFromPlane() != from.getPlane()
					|| !abilities.canUse(transport)
					|| !mesh.isLandWalkable(transport.getToX(), transport.getToY(),
						transport.getToPlane())
					|| mesh.isIsolated(transport.getToX(), transport.getToY(),
						transport.getToPlane()))
				{
					continue;
				}
				return transport;
			}
		}
		return null;
	}

	/**
	 * A straight run of tiles between two points, eight-directional.
	 *
	 * <p>Deliberately not a search. At Tier 3 nothing observes the route — only where the
	 * golem ends up and roughly how long it took — and the destination has already been
	 * checked for being real walkable ground. Paying for a pathfind here would buy
	 * accuracy in the one place it cannot be seen.
	 */
	private List<int[]> straightLine(int fromX, int fromY, int toX, int toY, int plane)
	{
		List<int[]> path = new ArrayList<>();
		int x = fromX;
		int y = fromY;
		path.add(new int[]{x, y});

		while (x != toX || y != toY)
		{
			x += Integer.signum(toX - x);
			y += Integer.signum(toY - y);

			// Every tile is checked, not just the destination.
			//
			// Checking only the far end was how golems ended up standing on water inside
			// Wyrmscraig's caves: the destination was good ground, the line to it was not,
			// and a far golem's position comes straight off the line. A route nobody
			// watches still has to be a route the golem could have walked.
			if (!mesh.isLandWalkable(x, y, plane))
			{
				return null;
			}

			path.add(new int[]{x, y});

			if (path.size() > 512)
			{
				// A leg this long means the destination was nonsense — most likely an
				// underground coordinate compared against a surface one.
				return null;
			}
		}
		return path;
	}

	/**
	 * The nearest tile a golem could actually stand on, spiralling outward.
	 *
	 * <p>Used when a golem is promoted into view, and when one is restored from a save
	 * onto ground that has since changed. A golem is moved, never replaced: it keeps its
	 * name, its id and its gait, because the entire premise of the plugin is that golems
	 * do not die and being quietly deleted to tidy up a map problem would be a death.
	 *
	 * @return a safe tile, or the original if nothing better was found within range
	 */
	WorldPoint snapToMesh(WorldPoint at)
	{
		if (isSafe(at.getX(), at.getY(), at.getPlane()))
		{
			return at;
		}

		for (int radius = 1; radius <= 32; radius++)
		{
			for (int dx = -radius; dx <= radius; dx++)
			{
				for (int dy = -radius; dy <= radius; dy++)
				{
					// Only the shell of the square, so each ring is tested once.
					if (Math.abs(dx) != radius && Math.abs(dy) != radius)
					{
						continue;
					}
					int x = at.getX() + dx;
					int y = at.getY() + dy;
					if (isSafe(x, y, at.getPlane()))
					{
						return new WorldPoint(x, y, at.getPlane());
					}
				}
			}
		}
		return at;
	}

	/** Walkable, and not in a pocket too small to wander out of. */
	private boolean isSafe(int x, int y, int plane)
	{
		return mesh.isLandWalkable(x, y, plane) && !mesh.isIsolated(x, y, plane);
	}
}
