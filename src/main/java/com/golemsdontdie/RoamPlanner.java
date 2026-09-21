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
 * <p>A tile-accurate search is more than the answer is worth when nobody is watching: a far golem
 * aims at a transport or at open ground a few dozen tiles off, by a straight run. The inaccuracy
 * is invisible: the moment a player could see it, the golem is promoted and snapped onto ground.
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

	/** True if golems are kept on Wyrmscraig, which means no sailing at all. */
	private static boolean ambitionRestricted(RoamContext context)
	{
		return context != null && context.isAmbitionRestricted();
	}

	/**
	 * Collision harvested from the live scene, which outranks the shipped mesh: that mesh's fill
	 * spreads only through the transports shipped with it, so anywhere reached only by a taught
	 * obstacle is neither land nor reachable — Wyrmscraig's upper floors, where golems climbed a
	 * taught staircase and took it straight back down.
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

	/** Chance per plan that a golem at a port sets sail. Low: a golem plans every half-minute. */
	private static final float SAIL_CHANCE = 0.125f;

	/** How close to the quayside counts as being at the port, in tiles: at the dock, not near. */
	private static final int DOCK_REACH = 2;

	/** Plans the next leg for a golem that nobody can see; null if nowhere sensible was found. */
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
		if (memory != null && memory.getPendingPort() >= 0)
		{
			// Waiting at the dock for its crossing to be worked out: a golem that walked away here left
			// the field to be evicted before it came back, and never sailed.
			lastOutcome = Outcome.TO_DOCK;
			return Itinerary.of(single(from), from.getPlane(), tick, DOCK_WAIT_POLL);
		}

		// Standing where no walk starts — a stepping stone, a landing the map calls blocked — the
		// only way on is a transport from this very tile; sampling the area left the golem on it.
		if (!isSafe(from.getX(), from.getY(), from.getPlane()))
		{
			// Unless it can simply step off. A fairy ring puts you down on the ring, which no map calls
			// ground, beside ground that is: as a stone mid-river, golems rode the whole network in step.
			WorldPoint off = transports.isStone(from.getX(), from.getY(), from.getPlane()) ? null : safeNeighbour(from);
			if (off != null)
			{
				List<int[]> step = single(from);
				step.add(new int[]{off.getX(), off.getY()});
				lastOutcome = Outcome.WALK;
				return Itinerary.of(step, from.getPlane(), tick, 0);
			}
			GolemTransport onward = fromHere(from, tick, memory, random);
			if (onward != null)
			{
				noteTaken(onward, tick, memory);
				lastOutcome = Outcome.ONWARD;
				return Itinerary.thenTransport(single(from), from.getPlane(), tick, onward);
			}
		}

		boolean pressed = context != null && context.crowdedRegion(from.getX(), from.getY(), from.getPlane());
		Reach reach = new Reach(from, context, FAR_SEARCH_BUDGET);

		// Somewhere it knows no way out of: now and then, back out the way it came in.
		if (random.nextFloat() < HOMEWARD_CHANCE)
		{
			GolemTransport home = wayHome(from.getX(), from.getY(), from.getPlane(), tick, memory, random);
			List<int[]> path = home == null ? null : walk(from, home.getFromX(), home.getFromY(), reach);
			if (path != null)
			{
				noteTaken(home, tick + path.size() - 1, memory);
				lastOutcome = Outcome.TRANSPORT;
				return Itinerary.thenTransport(path, from.getPlane(), tick, home);
			}
		}

		// Out of view a golem only sailed if a leg ended within two tiles of a quayside, which almost
		// never happens; now and then it goes to one on purpose.
		if (random.nextFloat() < DOCK_SEEK_CHANCE)
		{
			Itinerary toDock = toNearbyDock(from, tick, memory, reach);
			if (toDock != null)
			{
				lastOutcome = Outcome.TO_DOCK;
				return toDock;
			}
		}

		// A golem in a crowd is looking for the way out, so it neither rolls for this nor rests first.
		if (pressed || random.nextFloat() < TRANSPORT_CHANCE && (memory == null || !memory.restingFromTransports(tick)))
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
	 * Chance per decision that a golem with no known way out heads back out the way it came in. Not
	 * every time: it has only just arrived, and the way back is on the way in's cooldown anyway.
	 */
	static final float HOMEWARD_CHANCE = 0.25f;

	/** The network revision the component index below was built from; -1 before the first. */
	private int componentsRevision = -1;

	/** Per mesh component: transports starting in it that go somewhere else. */
	private final java.util.Map<Integer, List<GolemTransport>> exitsFrom = new java.util.HashMap<>();

	/** Per mesh component: the way back out through each transport that comes into it from elsewhere. */
	private final java.util.Map<Integer, List<GolemTransport>> waysBackFrom = new java.util.HashMap<>();

	/**
	 * The way back out through something that led here, for a golem somewhere with no known way out;
	 * or null if it knows one, or the mesh cannot say.
	 *
	 * <p>A player can learn the way into a place without the way out — in by cave, out by teleport —
	 * and golems gathered in Wyrmscraig's cave until taught the exit. Refusing to go in would bar
	 * every such dungeon, so a golem leaves the way it came; "somewhere" is the mesh's component.
	 */
	GolemTransport wayHome(int x, int y, int plane, int tick, TransportMemory memory, Random random)
	{
		if (mesh == null || transports == null || abilities == null)
		{
			return null;
		}
		int component = componentNear(x, y, plane);
		if (component == 0)
		{
			return null;
		}
		indexComponents();
		List<GolemTransport> ways = waysHome.get(component);
		if (ways == null)
		{
			ways = findWaysHome(component);
			waysHome.put(component, ways);
		}
		if (ways.isEmpty())
		{
			return null;
		}
		GolemTransport way = ways.get(random.nextInt(ways.size()));
		return memory != null && memory.onCooldown(way, tick) ? null : way;
	}

	/** Spaces reachable by shortcuts and still shut in: the open world reaches hundreds. */
	private static final int SHUT_IN = 8;

	/** Ways home by component, worked out once per change to the network; empty where there is no need. */
	private final java.util.Map<Integer, List<GolemTransport>> waysHome = new java.util.HashMap<>();

	/**
	 * The ways back out, for a golem in this component, if everywhere it can reach from here is shut
	 * in; otherwise none.
	 *
	 * <p>Shut in is not only one room: a courtyard whose only way out is a cave whose only way out
	 * is the courtyard is two spaces leading to each other, and a simulated year found golems dozens
	 * to a tile in just that. If usable shortcuts run out within a few spaces, the golem may leave.
	 */
	private List<GolemTransport> findWaysHome(int start)
	{
		java.util.Set<Integer> reached = new java.util.HashSet<>();
		java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
		reached.add(start);
		queue.add(start);
		while (!queue.isEmpty())
		{
			List<GolemTransport> exits = exitsFrom.get(queue.poll());
			if (exits == null)
			{
				continue;
			}
			for (GolemTransport exit : exits)
			{
				if (!abilities.canUse(exit) || !landsSomewhereUseful(exit))
				{
					continue;
				}
				int to = componentNear(exit.getToX(), exit.getToY(), exit.getToPlane());
				// Somewhere the mesh knows nothing about is somewhere else entirely: not shut in.
				if (to == 0)
				{
					return java.util.Collections.emptyList();
				}
				if (reached.add(to))
				{
					if (reached.size() > SHUT_IN)
					{
						return java.util.Collections.emptyList();
					}
					queue.add(to);
				}
			}
		}

		List<GolemTransport> ways = new ArrayList<>();
		for (GolemTransport way : waysBackFrom.getOrDefault(start, java.util.Collections.emptyList()))
		{
			// Back to somewhere outside the trap, not to another room of it.
			int to = componentNear(way.getToX(), way.getToY(), way.getToPlane());
			if (!reached.contains(to))
			{
				ways.add(way);
			}
		}
		return ways;
	}

	/** The component of a tile, or of a tile beside it: a transport's end can sit on its object. */
	private int componentNear(int x, int y, int plane)
	{
		int here = mesh.componentAt(x, y, plane);
		for (int dx = -1; dx <= 1 && here == 0; dx++)
		{
			for (int dy = -1; dy <= 1 && here == 0; dy++)
			{
				here = mesh.componentAt(x + dx, y + dy, plane);
			}
		}
		return here;
	}

	/** Sorts every transport into the components it leaves and enters, once per change to the network. */
	private void indexComponents()
	{
		if (componentsRevision == transports.revision())
		{
			return;
		}
		componentsRevision = transports.revision();
		exitsFrom.clear();
		waysBackFrom.clear();
		waysHome.clear();
		for (GolemTransport t : transports.all())
		{
			if (!transports.isOffered(t))
			{
				continue;
			}
			int from = componentNear(t.getFromX(), t.getFromY(), t.getFromPlane());
			int to = componentNear(t.getToX(), t.getToY(), t.getToPlane());
			boolean elsewhere = from == 0 || to == 0 || from != to || t.getFromPlane() != t.getToPlane();
			if (!elsewhere)
			{
				continue;
			}
			if (from != 0)
			{
				exitsFrom.computeIfAbsent(from, k -> new ArrayList<>()).add(t);
			}
			if (to != 0)
			{
				waysBackFrom.computeIfAbsent(to, k -> new ArrayList<>()).add(t.backThrough());
			}
		}
	}

	/** Tiles a far golem's flood may visit: a third of a golem in view's, and the plan's only one. */
	private static final int FAR_SEARCH_BUDGET = 1000;

	// A longer flood for golems in a crowd cost two and a half times the planning and got no more of
	// them out of Meiyerditch, whose ways out are two tiles in a warren.

	/**
	 * Everywhere a golem can walk to from where it stands, within the budget — flooded at most once
	 * per plan, only after a straight line fails, and underground those almost never exist.
	 */
	private static final class Reach
	{
		private final WorldPoint from;
		private final RoamContext context;
		private final int budget;
		private TileMap cameFrom;

		Reach(WorldPoint from, RoamContext context, int budget)
		{
			this.from = from;
			this.context = context;
			this.budget = budget;
		}

		/** The flood, made on first use; empty without a pathfinder. */
		TileMap tiles()
		{
			if (cameFrom == null)
			{
				cameFrom = context == null || context.getPathfinder() == null
					? new TileMap(4)
					: context.getPathfinder().flood(from.getX(), from.getY(), from.getPlane(), budget);
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
	 * A walk somewhere else, or null: a straight leg of a few dozen tiles where there is one, else
	 * somewhere in the far half of the flood, so a golem in a corridor still goes somewhere.
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

			// And in the same connected space: a destination inside a sealed courtyard can have a
			// perfectly walkable line to it and still be unreachable on foot.
			if (!mesh.sameComponent(from.getX(), from.getY(), toX, toY, from.getPlane()))
			{
				continue;
			}
			lastCandidates++;
			List<int[]> leg = straightLine(from.getX(), from.getY(), toX, toY, from.getPlane());
			if (leg == null)
			{
				continue;
			}
			return Itinerary.of(leg, from.getPlane(), tick, 0);
		}

		TileMap tiles = reach.tiles();
		if (tiles.size() < 2)
		{
			return null;
		}
		int reached = tiles.size();
		int half = reached / 2;
		for (int attempt = 0; attempt < ATTEMPTS; attempt++)
		{
			long tile = tiles.keyAt(half + random.nextInt(reached - half));
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
	 * <p>Without it a golem with no plan asked again the next frame, and a golem with no plan is
	 * exactly one whose plans fail: a search and two dozen probes, sixty times a second.
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
	 * A walk to the quayside of the nearest open dock in reach, or null. Not while on shore leave,
	 * and not to a dock the golem is already at — that is the voyage roll's business.
	 */
	private Itinerary toNearbyDock(WorldPoint from, int tick, TransportMemory memory, Reach reach)
	{
		if (docks == null || memory == null || ambitionRestricted(reach.context) || memory.onShoreLeave())
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
	 * A walk to a tile — a straight line where there is one, otherwise through the flood — the
	 * golem's own tile first; null if there is no way within reach.
	 */
	private List<int[]> walk(WorldPoint from, int toX, int toY, Reach reach)
	{
		List<int[]> line = straightLine(from.getX(), from.getY(), toX, toY, from.getPlane());
		return line != null ? line : reach.pathTo(toX, toY);
	}

	/**
	 * Puts a transport a far golem has chosen on its cooldown, from when it will be used. Far golems
	 * never did, and at the foot of a ladder the nearest transport is the ladder: nine in ten climbs
	 * on Wyrmscraig were undone at once.
	 */
	private static void noteTaken(GolemTransport transport, int whenUsed, TransportMemory memory)
	{
		if (memory != null)
		{
			memory.used(transport, whenUsed);
		}
	}

	/**
	 * A transport starting on exactly this tile, for a golem that cannot walk off it. One not on
	 * cooldown if there is one; failing that the way back, since standing on a stone is worse.
	 */
	private GolemTransport fromHere(WorldPoint at, int tick, TransportMemory memory, Random random)
	{
		GolemTransport back = null;
		// Any of the ways on, not the first listed: always the first sent every golem the same way.
		List<GolemTransport> onward = new ArrayList<>();
		for (GolemTransport transport : transports.from(at.getX(), at.getY()))
		{
			if (transport.getFromPlane() != at.getPlane() || !abilities.canUse(transport)
				|| !landsSomewhereUseful(transport))
			{
				continue;
			}
			if (memory == null || !memory.onCooldown(transport, tick))
			{
				onward.add(transport);
			}
			else if (back == null)
			{
				back = transport;
			}
		}
		return onward.isEmpty() ? back : onward.get(random.nextInt(onward.size()));
	}

	private WorldPoint safeNeighbour(WorldPoint at)
	{
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				if ((dx != 0 || dy != 0) && isSafe(at.getX() + dx, at.getY() + dy, at.getPlane()))
				{
					return new WorldPoint(at.getX() + dx, at.getY() + dy, at.getPlane());
				}
			}
		}
		return null;
	}

	/**
	 * True if a golem arriving by this transport has somewhere to go: walkable ground, or a chain of
	 * transports onward — not this one in reverse — ending on some. See leadsToGround.
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
	 * <p>Called from two places, which is the point: a far golem plans one when its route runs out,
	 * and a golem in view rolls as it steps onto a tile, sailing having once happened only out of
	 * range. Affordable in view because crossings are cached per pair of ports.
	 *
	 * @return a crossing, or null if the golem is not at a port, is on shore leave, lost the roll,
	 *         or the route is unknown and the frame's search is spent
	 */
	Itinerary planVoyage(WorldPoint from, int tick, Random random, TransportMemory memory,
		RoamContext context)
	{
		return planVoyage(from, tick, random, memory, context, SAIL_CHANCE);
	}

	/** Chance a far golem at a dock sets sail, per plan: higher, as it plans once per leg. */
	private static final float AT_DOCK_SAIL_CHANCE = 0.6f;

	/** Ticks a far golem at a dock waits before asking again whether its crossing is ready. */
	private static final int DOCK_WAIT_POLL = 4;

	/** Chance per plan that a far golem with a dock in reach heads for it. */
	private static final float DOCK_SEEK_CHANCE = 0.1f;

	/**
	 * How near a gangplank, in tiles, draws a golem in view toward it. A golem only rolled to sail
	 * on stepping within a couple of tiles of the quayside, which at random it almost never did.
	 */
	static final int DOCK_ATTRACTION = 15;

	/** Chance, each time a golem in view picks somewhere to walk, that a dock in range is where. */
	private static final float DOCK_ATTRACTION_CHANCE = 0.25f;

	/**
	 * A dock a golem in view has chosen to walk up to, or null: the nearest open one within
	 * {@link #DOCK_ATTRACTION} tiles, not one it is at, and not on shore leave.
	 */
	SailingDocks.Dock dockToVisit(WorldPoint from, TransportMemory memory, RoamContext context, Random random)
	{
		if (docks == null || memory == null || ambitionRestricted(context) || memory.onShoreLeave())
		{
			return null;
		}
		SailingDocks.Dock nearest = null;
		int nearestDistance = Integer.MAX_VALUE;
		for (SailingDocks.Dock dock : docks.getDocks())
		{
			WorldPoint shore = dock.getShore();
			if (shore.getPlane() != from.getPlane())
			{
				continue;
			}
			int distance = Math.max(Math.abs(shore.getX() - from.getX()), Math.abs(shore.getY() - from.getY()));
			if (distance > DOCK_REACH && distance <= DOCK_ATTRACTION && distance < nearestDistance && docks.isOpen(dock))
			{
				nearest = dock;
				nearestDistance = distance;
			}
		}
		if (nearest == null || random.nextFloat() >= DOCK_ATTRACTION_CHANCE)
		{
			return null;
		}
		return nearest;
	}

	SailingDocks.Dock dockAt(WorldPoint from)
	{
		return voyage == null ? null : voyage.dockAt(from, DOCK_REACH);
	}

	boolean atDock(WorldPoint from, SailingDocks.Dock dock)
	{
		WorldPoint shore = dock.getShore();
		return shore.getPlane() == from.getPlane()
			&& Math.max(Math.abs(shore.getX() - from.getX()), Math.abs(shore.getY() - from.getY())) <= DOCK_REACH;
	}

	/** Rolls for a crossing for a golem that walked to a dock on purpose: the far golem's chance. */
	Itinerary planVoyageAtDock(WorldPoint from, int tick, Random random, TransportMemory memory, RoamContext context)
	{
		return planVoyage(from, tick, random, memory, context, AT_DOCK_SAIL_CHANCE);
	}

	private Itinerary planVoyage(WorldPoint from, int tick, Random random, TransportMemory memory,
		RoamContext context, float chance)
	{
		// A golem already waiting to sail has made its decision and does not roll again, but only at
		// the dock it is waiting at. See TransportMemory.holdPending.
		boolean waiting = memory != null && memory.getPendingPort() >= 0;
		if (memory == null || voyage == null || ambitionRestricted(context) || memory.onShoreLeave()
			|| !waiting && random.nextFloat() >= chance)
		{
			return null;
		}

		SailingDocks.Dock dock = voyage.dockAt(from, DOCK_REACH);
		if (waiting && (dock == null || dock.getRowId() != memory.getPendingFrom()))
		{
			memory.clearPending();
			if (dock == null || random.nextFloat() >= chance)
			{
				return null;
			}
		}
		if (dock == null || !docks.isOpen(dock))
		{
			return null;
		}

		Itinerary crossing = voyage.depart(dock, from, memory, tick, random, context);
		if (crossing == null)
		{
			return null;
		}

		// Ashore at the far end, the golem walks inland a while before it may think about boats
		// again; otherwise a golem that arrives at a port simply leaves it again.
		memory.beginShoreLeaveOnArrival(tick + crossing.getDuration(), tick, random);
		return crossing;
	}

	/**
	 * Chance per plan that a golem heads for a transport rather than wandering. Finding one used to
	 * cap this by itself; now every transport in range is considered, and always taking one is hops.
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
	 * A walk to a transport in range and the transport itself, or null. Every transport starting
	 * within reach is considered in a random order, which keeps the choice fair.
	 */
	private Itinerary toNearbyTransport(WorldPoint from, int tick, Random random, TransportMemory memory,
		Reach reach)
	{
		List<GolemTransport> near = new ArrayList<>();
		transports.near(from.getX(), from.getY(), TRANSPORT_SEARCH, near);
		java.util.Collections.shuffle(near, random);

		int walksTried = 0;
		// The first one it passed over for being crowded, in case nothing roomier turns up.
		GolemTransport crowdedChoice = null;
		for (GolemTransport transport : near)
		{
			// Landing judged by the same test as everywhere else in the planner: this one alone asked
			// for the shipped land fill, so the cathedral's interior never counted as somewhere to be.
			if (transport.getFromPlane() != from.getPlane()
				|| (memory != null && memory.onCooldown(transport, tick))
				|| !mesh.sameComponent(from.getX(), from.getY(), transport.getFromX(), transport.getFromY(),
					from.getPlane())
				|| !abilities.canUse(transport)
				|| !landsSomewhereUseful(transport))
			{
				continue;
			}
			// Somewhere with room in it, by preference; see RoamContext.appeal. One that turns a
			// transport down falls back on the first it passed over rather than staying put.
			float appeal = reach.context == null ? 1f
				: reach.context.appeal(from.getX(), from.getY(), from.getPlane(),
					transport.getToX(), transport.getToY(), transport.getToPlane());
			if (appeal < 1f && random.nextFloat() >= appeal)
			{
				if (crowdedChoice == null)
				{
					crowdedChoice = transport;
				}
				continue;
			}
			List<int[]> path = walk(from, transport.getFromX(), transport.getFromY(), reach);
			if (path != null)
			{
				noteTaken(transport, tick + path.size() - 1, memory);
				return Itinerary.thenTransport(path, from.getPlane(), tick, transport);
			}
			// Past the first few, each try is a line and a lookup in the flood — cheap, but a town
			// can have a hundred rows in range.
			if (++walksTried >= ATTEMPTS * 4)
			{
				break;
			}
		}
		if (crowdedChoice != null)
		{
			List<int[]> path = walk(from, crowdedChoice.getFromX(), crowdedChoice.getFromY(), reach);
			if (path != null)
			{
				noteTaken(crowdedChoice, tick + path.size() - 1, memory);
				return Itinerary.thenTransport(path, from.getPlane(), tick, crowdedChoice);
			}
		}
		return null;
	}

	/**
	 * A straight run of tiles between two points, eight-directional. Deliberately not a search: at
	 * Tier 3 nothing observes the route, only where the golem ends up and roughly how long it took.
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

			// The step, not only the tile it lands on: two walkable tiles either side of a wall are
			// both fine ground, and a line checking tiles alone walked through the wall between them.
			if (!memory.canStep(x, y, plane, stepX, stepY))
			{
				return null;
			}
			x += stepX;
			y += stepY;

			// Every tile is checked, not just the destination, which left golems standing on water in
			// Wyrmscraig's caves. Harvested ground counts too, or golems upstairs went back down.
			if (!mesh.isLandWalkable(x, y, plane) && !memory.isKnownWalkable(x, y, plane))
			{
				return null;
			}

			path.add(new int[]{x, y});

			if (path.size() > 512)
			{
				// A leg this long means nonsense: most likely an underground coordinate against a
				// surface one.
				return null;
			}
		}
		return path;
	}

	/**
	 * The nearest tile a golem could actually stand on, spiralling outward.
	 *
	 * <p>Used when a golem is promoted into view, and when one is restored onto ground that has since
	 * changed. A golem is moved, never replaced — name, id and gait kept — since golems do not die.
	 *
	 * @return a safe tile, or the original if nothing better was found within range
	 */
	WorldPoint snapToMesh(WorldPoint at)
	{
		if (isSafe(at.getX(), at.getY(), at.getPlane()))
		{
			return at;
		}
		// Standing on water no golem can walk: the nearest ground, on whatever side of the shore.
		// Kept to the golem's own component, the search found only more water and left it there.
		if (mesh.isInlandWater(at.getX(), at.getY(), at.getPlane()))
		{
			WorldPoint shore = nearestSafe(at, false);
			return shore != null ? shore : at;
		}
		WorldPoint near = nearestSafe(at, true);
		return near != null ? near : at;
	}

	/**
	 * The nearest tile a golem can stand on, up to 32 tiles out, or null; {@code sameSpace} keeps it
	 * to the connected space the golem is in, as far as the mesh knows it.
	 */
	private WorldPoint nearestSafe(WorldPoint at, boolean sameSpace)
	{

		// Where the golem is now decides where it may be put: the nearest walkable tile to one in a
		// wall is often inside the building. Zero means the mesh has nothing to say, and is a match.
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
					if (sameSpace && origin != 0)
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
		return null;
	}

	/**
	 * Walkable, and not in a pocket too small to wander out of. Ground the player has stood on
	 * counts whatever the shipped mesh says: the harvest is this client, this revision, this floor.
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
