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

	/**
	 * Collision harvested from the live scene, which outranks the shipped mesh.
	 *
	 * <p>Needed here because the shipped mesh is incomplete in a way that matters: its
	 * reachability fill spreads through the transports that shipped with it, so anywhere
	 * whose only way in is an obstacle nobody published a row for comes out as neither land
	 * nor reachable. Wyrmscraig's upper floors are exactly that.
	 *
	 * <p>The effect was golems climbing a staircase they had been taught, finding that the
	 * floor they arrived on was not somewhere the planner would consider going, and taking
	 * the stairs straight back down — over and over, for the one reason that looked least
	 * like a bug from the outside.
	 */
	@Inject
	private IslandMemory memory;

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
		lastCandidates = 0;
		Itinerary crossing = planVoyage(from, tick, random, memory, context, AT_DOCK_SAIL_CHANCE);
		if (crossing != null)
		{
			lastOutcome = Outcome.VOYAGE;
			return crossing;
		}

		// Standing where no walk starts — a stepping stone, a landing the map calls blocked —
		// the only way on is a transport from this very tile. Sampling the area found rows a
		// straight line could not reach from a rock, and the golem stood on it for good.
		if (!isSafe(from.getX(), from.getY(), from.getPlane()))
		{
			GolemTransport onward = fromHere(from, tick, memory);
			if (onward != null)
			{
				noteTaken(onward, tick, memory);
				lastOutcome = Outcome.ONWARD;
				return Itinerary.thenTransport(single(from), from.getPlane(), tick, onward);
			}
		}

		// Where the golem can walk to, flooded at most once and only once a straight line fails.
		Reach reach = new Reach(from, context);

		// Out of view a golem only sailed if a leg happened to end within two tiles of a
		// quayside, which almost never happens. Now and then it goes to one on purpose.
		if (random.nextFloat() < DOCK_SEEK_CHANCE)
		{
			Itinerary toDock = toNearbyDock(from, tick, memory, reach);
			if (toDock != null)
			{
				lastOutcome = Outcome.TO_DOCK;
				return toDock;
			}
		}

		if (random.nextFloat() < TRANSPORT_CHANCE)
		{
			Itinerary toTransport = toNearbyTransport(from, tick, random, memory, reach);
			if (toTransport != null)
			{
				lastOutcome = Outcome.TRANSPORT;
				return toTransport;
			}
		}

		Itinerary leg = wander(from, tick, random, reach);
		lastOutcome = leg != null ? Outcome.WALK : Outcome.NOTHING;
		return leg;
	}

	/**
	 * Tiles a far golem's flood may visit.
	 *
	 * <p>A third of what a golem in view gets for one search, and this is the only one the plan
	 * makes. Out of view golems plan every few dozen ticks each, a thousand of them; a corridor
	 * to the next ladder is a few hundred tiles of flood, and an open field too big for it has
	 * a straight line across it anyway.
	 */
	private static final int FAR_SEARCH_BUDGET = 1000;

	/**
	 * Everywhere a golem can walk to from where it stands, within the budget — flooded at
	 * most once per plan, and only when a straight line has already failed.
	 *
	 * <p>Straight lines were the whole of far planning, and underground they almost never
	 * exist. A dungeon is corridors, so a golem at the foot of a ladder found no straight line
	 * to anything and stood there, or took the ladder back up because that was the one thing
	 * in reach. A path search per destination was the next attempt, and spent its budget on
	 * the first unreachable one. One flood answers every question the plan asks.
	 */
	private static final class Reach
	{
		private final WorldPoint from;
		private final RoamContext context;
		private java.util.LinkedHashMap<Long, Long> cameFrom;

		Reach(WorldPoint from, RoamContext context)
		{
			this.from = from;
			this.context = context;
		}

		/** The flood, made on first use; empty without a pathfinder. */
		java.util.LinkedHashMap<Long, Long> tiles()
		{
			if (cameFrom == null)
			{
				cameFrom = context == null || context.getPathfinder() == null
					? new java.util.LinkedHashMap<>()
					: context.getPathfinder().flood(from.getX(), from.getY(), from.getPlane(), FAR_SEARCH_BUDGET);
			}
			return cameFrom;
		}

		/** The walk to a tile the flood reached, the golem's own tile first; null if not reached. */
		List<int[]> pathTo(int x, int y)
		{
			java.util.Deque<int[]> found = GolemPathfinder.pathFrom(tiles(), from.getX(), from.getY(), x, y);
			if (found == null)
			{
				return null;
			}
			List<int[]> path = new ArrayList<>(found.size() + 1);
			path.add(new int[]{from.getX(), from.getY()});
			path.addAll(found);
			return path;
		}
	}

	/**
	 * A walk somewhere else, or null.
	 *
	 * <p>A straight leg of a few dozen tiles where there is one. Where there is not — a
	 * corridor, a cellar, a tower top smaller than any leg — somewhere the flood reached, from
	 * its far half, so the golem goes somewhere rather than shuffling a tile.
	 */
	private Itinerary wander(WorldPoint from, int tick, Random random, Reach reach)
	{
		for (int attempt = 0; attempt < ATTEMPTS; attempt++)
		{
			int distance = LEG_MIN + random.nextInt(LEG_SPREAD);
			double angle = random.nextDouble() * Math.PI * 2;
			int toX = from.getX() + (int) Math.round(Math.cos(angle) * distance);
			int toY = from.getY() + (int) Math.round(Math.sin(angle) * distance);

			if (!isSafe(toX, toY, from.getPlane()))
			{
				continue;
			}

			// And in the same connected space. The straight line below already refuses to
			// cross ground the golem could not walk, which stops it wading through the
			// sea — but a destination inside a sealed courtyard can have a perfectly
			// walkable line to it and still be somewhere the golem could never reach on
			// foot. Only the component test catches that.
			if (!mesh.sameComponent(from.getX(), from.getY(), toX, toY, from.getPlane()))
			{
				continue;
			}
			lastCandidates++;
			List<int[]> leg = straightLine(from.getX(), from.getY(), toX, toY, from.getPlane());
			if (leg == null)
			{
				// The line crosses ground the golem could not walk. Try another angle.
				continue;
			}
			return Itinerary.of(leg, from.getPlane(), tick, 0);
		}

		java.util.LinkedHashMap<Long, Long> tiles = reach.tiles();
		if (tiles.size() < 2)
		{
			return null;
		}
		Long[] reached = tiles.keySet().toArray(new Long[0]);
		int half = reached.length / 2;
		for (int attempt = 0; attempt < ATTEMPTS; attempt++)
		{
			long tile = reached[half + random.nextInt(reached.length - half)];
			int x = GolemPathfinder.unpackX(tile);
			int y = GolemPathfinder.unpackY(tile);
			if ((x == from.getX() && y == from.getY()) || !isSafe(x, y, from.getPlane()))
			{
				continue;
			}
			lastCandidates++;
			List<int[]> path = reach.pathTo(x, y);
			if (path != null)
			{
				return Itinerary.of(path, from.getPlane(), tick, 0);
			}
		}
		return null;
	}

	/** Ticks a golem out of view stands before looking again, when nowhere was found. */
	private static final int IDLE_MIN = 8;
	private static final int IDLE_SPREAD = 16;

	/**
	 * A pause on the spot, for a golem that found nowhere to go.
	 *
	 * <p>Without it a golem with no plan asked for one again the next frame, and a golem with
	 * no plan is exactly one whose plans fail — a search and two dozen probes, per stuck
	 * golem, sixty times a second. Longer with each failure in a row, up to eight times, so a
	 * golem that is truly stuck costs almost nothing while it waits for the watchdog.
	 *
	 * @param failures plans in a row that found nothing, before this one
	 */
	static Itinerary idle(WorldPoint at, int tick, Random random, int failures)
	{
		int ticks = (IDLE_MIN + random.nextInt(IDLE_SPREAD)) << Math.min(Math.max(0, failures), 3);
		return Itinerary.of(single(at), at.getPlane(), tick, ticks);
	}

	private static List<int[]> single(WorldPoint at)
	{
		List<int[]> path = new ArrayList<>(1);
		path.add(new int[]{at.getX(), at.getY()});
		return path;
	}

	/**
	 * A walk to the quayside of the nearest open dock in reach, or null.
	 *
	 * <p>Not while on shore leave, and not to a dock the golem is already at — that is the
	 * voyage roll's business.
	 */
	private Itinerary toNearbyDock(WorldPoint from, int tick, TransportMemory memory, Reach reach)
	{
		if (docks == null || memory == null || memory.onShoreLeave())
		{
			return null;
		}
		List<SailingDocks.Dock> near = new ArrayList<>();
		for (SailingDocks.Dock dock : docks.getDocks())
		{
			WorldPoint shore = dock.getShore();
			int distance = Math.max(Math.abs(shore.getX() - from.getX()), Math.abs(shore.getY() - from.getY()));
			if (shore.getPlane() == from.getPlane() && distance > DOCK_REACH && distance <= TRANSPORT_SEARCH
				&& docks.isOpen(dock))
			{
				near.add(dock);
			}
		}
		near.sort((a, b) -> Integer.compare(
			Math.max(Math.abs(a.getShore().getX() - from.getX()), Math.abs(a.getShore().getY() - from.getY())),
			Math.max(Math.abs(b.getShore().getX() - from.getX()), Math.abs(b.getShore().getY() - from.getY()))));
		for (SailingDocks.Dock dock : near)
		{
			List<int[]> path = walk(from, dock.getShore().getX(), dock.getShore().getY(), reach);
			if (path != null)
			{
				return Itinerary.of(path, from.getPlane(), tick, 0);
			}
		}
		return null;
	}

	/**
	 * A walk to a tile: a straight line where there is one, otherwise through the flood.
	 *
	 * @return the tiles to walk, the golem's own first; null if there is no way within reach
	 */
	private List<int[]> walk(WorldPoint from, int toX, int toY, Reach reach)
	{
		List<int[]> line = straightLine(from.getX(), from.getY(), toX, toY, from.getPlane());
		return line != null ? line : reach.pathTo(toX, toY);
	}

	/**
	 * Puts a transport a far golem has chosen on its cooldown, from when it will be used.
	 *
	 * <p>Far golems never did, so nothing stopped the next plan choosing the way straight
	 * back: at the foot of a ladder the ladder is the nearest transport there is, and nine in
	 * ten climbs on Wyrmscraig were undone by the very next one.
	 */
	private static void noteTaken(GolemTransport transport, int whenUsed, TransportMemory memory)
	{
		if (memory != null)
		{
			memory.used(transport, whenUsed);
		}
	}

	/**
	 * A transport starting on exactly this tile, for a golem that cannot walk off it.
	 *
	 * <p>One not on cooldown if there is one; failing that, the way back, because a golem
	 * left standing on a stone is worse than one that goes back to the bank.
	 */
	private GolemTransport fromHere(WorldPoint at, int tick, TransportMemory memory)
	{
		GolemTransport back = null;
		for (GolemTransport transport : transports.from(at.getX(), at.getY()))
		{
			if (transport.getFromPlane() != at.getPlane() || !abilities.canUse(transport)
				|| !landsSomewhereUseful(transport))
			{
				continue;
			}
			if (memory == null || !memory.onCooldown(transport, tick))
			{
				return transport;
			}
			if (back == null)
			{
				back = transport;
			}
		}
		return back;
	}

	/**
	 * True if a golem arriving by this transport has somewhere to go from the landing.
	 *
	 * <p>Walkable ground, or a chain of transports onward — not simply this one in reverse —
	 * that ends on some. The middle stone of a crossing is blocked ground, and the crossing is
	 * still a perfectly good way over the river; a crossing that never reaches a bank is not.
	 * The same rule golems in view follow. See {@link TransportNetwork#leadsToGround}.
	 */
	private boolean landsSomewhereUseful(GolemTransport transport)
	{
		return transports.leadsToGround(transport.getToX(), transport.getToY(), transport.getToPlane(),
			transport.getFromX(), transport.getFromY(), transport.getFromPlane(), TransportNetwork.CHAIN_HOPS,
			abilities::canUse, this::isSafe);
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
		return planVoyage(from, tick, random, memory, context, SAIL_CHANCE);
	}

	/**
	 * Chance a far golem at a dock sets sail, per plan.
	 *
	 * <p>Much higher than the in-view roll, which is made on every tile a golem enters and so
	 * adds up quickly. A far golem plans once per leg, and one that walked to a dock on purpose
	 * and then only sailed one time in eight mostly walked away again.
	 */
	private static final float AT_DOCK_SAIL_CHANCE = 0.6f;

	/** Chance per plan that a far golem with a dock in reach heads for it. */
	private static final float DOCK_SEEK_CHANCE = 0.1f;

	private Itinerary planVoyage(WorldPoint from, int tick, Random random, TransportMemory memory,
		RoamContext context, float chance)
	{
		if (memory == null || voyage == null || memory.onShoreLeave() || random.nextFloat() >= chance)
		{
			return null;
		}

		SailingDocks.Dock dock = voyage.dockAt(from, DOCK_REACH);
		if (dock == null || !docks.isOpen(dock))
		{
			return null;
		}

		Itinerary crossing = voyage.depart(dock, from, memory, tick, random, context);
		if (crossing == null)
		{
			return null;
		}

		// Ashore at the far end, the golem walks inland for a while before it is allowed
		// to think about boats again. Otherwise a golem that arrives at a port simply
		// leaves it, and no golem is ever seen anywhere except on the water.
		memory.beginShoreLeaveOnArrival(tick + crossing.getDuration(), tick, random);
		return crossing;
	}

	/**
	 * Chance per plan that a golem heads for a transport rather than wandering.
	 *
	 * <p>Finding one used to be rare enough to cap this by itself. Now that every transport in
	 * range is considered, a golem that always took one would do nothing but hop, and never
	 * walk the floor it had just arrived on.
	 */
	private static final float TRANSPORT_CHANCE = 0.5f;

	/** How a plan came out, for the journal and the roaming simulation. */
	enum Outcome
	{
		VOYAGE,
		/** Walked toward a dock, to sail from it. */
		TO_DOCK,
		/** Took the only way off a tile that cannot be walked from. */
		ONWARD,
		TRANSPORT,
		WALK,
		NOTHING,
	}

	@lombok.Getter
	private Outcome lastOutcome = Outcome.NOTHING;

	/** Wander destinations the last plan found to be real ground in the golem's own space. */
	@lombok.Getter
	private int lastCandidates;

	/**
	 * A walk to a transport in range and the transport itself, or null.
	 *
	 * <p>Every transport starting within reach is considered, in a random order, and the
	 * first one the golem can use and walk to is taken. Random order keeps the choice fair;
	 * the cooldown stops it being the way the golem just came.
	 */
	private Itinerary toNearbyTransport(WorldPoint from, int tick, Random random, TransportMemory memory,
		Reach reach)
	{
		List<GolemTransport> near = new ArrayList<>();
		transports.near(from.getX(), from.getY(), TRANSPORT_SEARCH, near);
		java.util.Collections.shuffle(near, random);

		int walksTried = 0;
		for (GolemTransport transport : near)
		{
			// Landing judged by the same test as everywhere else in the planner.
			//
			// This one alone asked for the shipped land fill, which only reaches ground
			// connected by shipped transports. The cathedral's interior and the floors
			// above ladders are reached only by obstacles the player taught, so a golem
			// out of view would never choose them — and one already inside could never
			// choose the way back out either, because that route's origin was fine but
			// nothing about the interior counted as somewhere to be.
			if (transport.getFromPlane() != from.getPlane()
				|| (memory != null && memory.onCooldown(transport, tick))
				|| !mesh.sameComponent(from.getX(), from.getY(), transport.getFromX(), transport.getFromY(),
					from.getPlane())
				|| !abilities.canUse(transport)
				|| !landsSomewhereUseful(transport))
			{
				continue;
			}
			List<int[]> path = walk(from, transport.getFromX(), transport.getFromY(), reach);
			if (path != null)
			{
				noteTaken(transport, tick + path.size() - 1, memory);
				return Itinerary.thenTransport(path, from.getPlane(), tick, transport);
			}
			// Past the first few, each try is a straight line and a lookup in the flood that
			// already exists — cheap, but a town can have a hundred rows in range.
			if (++walksTried >= ATTEMPTS * 4)
			{
				break;
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
			int stepX = Integer.signum(toX - x);
			int stepY = Integer.signum(toY - y);

			// The step, not only the tile it lands on. Two walkable tiles either side of a
			// wall are both fine ground, and a line that only checked tiles walked straight
			// through the wall between them — which is how golems out of view got into the
			// sealed boss room behind the cathedral pew, and out onto the water past a
			// shoreline. The golems' own step test is the same one the pathfinder uses.
			if (!memory.canStep(x, y, plane, stepX, stepY))
			{
				return null;
			}
			x += stepX;
			y += stepY;

			// Every tile is checked, not just the destination.
			//
			// Checking only the far end was how golems ended up standing on water inside
			// Wyrmscraig's caves: the destination was good ground, the line to it was not,
			// and a far golem's position comes straight off the line. A route nobody
			// watches still has to be a route the golem could have walked.
			// Harvested ground counts here too. A route across a floor the mesh never
			// found a way onto is perfectly walkable; refusing it was why golems upstairs
			// could not plan a single leg and went straight back down the way they came.
			if (!mesh.isLandWalkable(x, y, plane) && !memory.isKnownWalkable(x, y, plane))
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

		// Where the golem is now decides where it may be put. Without this the nearest
		// walkable tile wins outright, and the nearest walkable tile to a golem standing
		// in a wall is very often the inside of the building — so the rescue dropped
		// golems into sealed rooms and the watchdog spent the rest of the session
		// discovering they could not get out again.
		//
		// Zero means the mesh has nothing to say about this tile, and is treated as a
		// match: the live harvest covers ground the mesh never will, and refusing to move
		// a golem there would shrink the world to whatever happened to ship.
		int origin = mesh.componentAt(at.getX(), at.getY(), at.getPlane());

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
					if (origin != 0)
					{
						int here = mesh.componentAt(x, y, at.getPlane());
						if (here != 0 && here != origin)
						{
							continue;
						}
					}
					if (isSafe(x, y, at.getPlane()))
					{
						return new WorldPoint(x, y, at.getPlane());
					}
				}
			}
		}
		return at;
	}

	/**
	 * Walkable, and not in a pocket too small to wander out of.
	 *
	 * <p>Ground the player has actually stood on counts regardless of what the shipped mesh
	 * says about it. The harvest is the better authority — it is this client, this
	 * revision, this floor — and the mesh cannot describe somewhere it never found a way
	 * into.
	 */
	private boolean isSafe(int x, int y, int plane)
	{
		if (memory.isKnownWalkable(x, y, plane))
		{
			return true;
		}
		return mesh.isLandWalkable(x, y, plane) && !mesh.isIsolated(x, y, plane);
	}
}
