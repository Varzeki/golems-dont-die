package com.golemsdontdie;

import java.util.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.coords.*;
import static com.golemsdontdie.RouteGeometry.span;

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
	 * obstacle is neither land nor reachable - Wyrmscraig's upper floors, where golems climbed a
	 * taught staircase and took it straight back down.
	 */
	@Inject
	private IslandMemory memory;

	@Inject
	private TransportNetwork transports;

	@Inject
	private GolemAbilities abilities;

	/** Floods ground the mesh has no word for; see unmeshedWaysHome. */
	@Inject
	private GolemPathfinder pathfinder;

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
		Itinerary crossing = planVoyage(from, tick, random, memory, context,
			AT_DOCK_SAIL_CHANCE * (memory != null && memory.is(GolemTrait.SEAFARER) ? 1.5f : 1f)
				* (context == null ? 1f : context.wanderlust(memory, from.getX(), from.getY())));
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

		// Standing where no walk starts - a stepping stone, a landing the map calls blocked - the
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
		reach.memory = memory;

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
		float seeking = DOCK_SEEK_CHANCE * (memory != null && memory.is(GolemTrait.SEAFARER) ? 3f : 1f)
			* (context == null ? 1f : context.wanderlust(memory, from.getX(), from.getY()));
		if (random.nextFloat() < seeking)
		{
			Itinerary toDock = toNearbyDock(from, tick, memory, reach);
			if (toDock != null)
			{
				lastOutcome = Outcome.TO_DOCK;
				return toDock;
			}
		}

		// A golem in a crowd is looking for the way out, so it neither rolls for this nor rests first.
		// Nor does one penned into a paddock, which has nowhere to walk its rest off. A golem where
		// it wanted to be looks for a shortcut far less often: turning each one down as it comes is
		// no use when a town offers a dozen, and one roll in ten will take it.
		boolean penned = context != null
			&& context.enclosedArea(from.getX(), from.getY(), from.getPlane()) < Golem.PENNED_TILES;
		float hopping = TRANSPORT_CHANCE
			* (context == null ? 1f : context.wanderlust(memory, from.getX(), from.getY()));
		if (pressed || penned
			|| random.nextFloat() < hopping && (memory == null || !memory.restingFromTransports(tick)))
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
	private final Map<Integer, List<GolemTransport>> exitsFrom = new HashMap<>();

	/** Per mesh component: the way back out through each transport that comes into it from elsewhere. */
	private final Map<Integer, List<GolemTransport>> waysBackFrom = new HashMap<>();

	/**
	 * The way back out through something that led here, for a golem somewhere with no known way out;
	 * or null if it knows one, or the mesh cannot say.
	 *
	 * <p>A player can learn the way into a place without the way out - in by cave, out by teleport -
	 * and golems gathered in Wyrmscraig's cave until taught the exit. Refusing to go in would bar
	 * every such dungeon, so a golem leaves the way it came; "somewhere" is the mesh's component.
	 */
	GolemTransport wayHome(int x, int y, int plane, int tick, TransportMemory memory, Random random)
	{
		if (mesh == null || transports == null || abilities == null)
		{
			return null;
		}
		indexComponents();
		int component = componentNear(x, y, plane);
		List<GolemTransport> ways;
		if (component == 0)
		{
			ways = unmeshedWaysHome(x, y, plane);
		}
		else
		{
			ways = waysHome.get(component);
			if (ways == null)
			{
				ways = findWaysHome(component);
				waysHome.put(component, ways);
			}
		}
		if (ways.isEmpty())
		{
			return null;
		}
		GolemTransport way = ways.get(random.nextInt(ways.size()));
		return memory != null && memory.onCooldown(way, tick) ? null : way;
	}

	/** Tiles a flood of unmeshed ground may fill before it counts as open ground, not a room. */
	private static final int UNMESHED_ROOM = 4000;

	/** Tiles of unmeshed rooms remembered at once, before the memory is started afresh. */
	private static final int UNMESHED_REMEMBERED = 50_000;

	/** Ways home for ground the mesh has nothing on, by tile, worked out once per change to the network. */
	private final Map<Long, List<GolemTransport>> unmeshedWays = new HashMap<>();

	/**
	 * The ways back out of a room the mesh knows nothing about, for a golem in it with no known way
	 * out; otherwise none.
	 *
	 * <p>Content newer than the mesh has no components, and the rule above had nothing to say about
	 * it: the Doom of Mokhaiotl's arena is entered by jumping down a gap and left through a loot
	 * interface, a teleport or death, none of which is ever learned, and golems that followed the
	 * player in stayed for good, on every level of the delve at once. So the room is worked out from
	 * the island map's own collision instead - the floor a golem can walk from where it stands - and
	 * if nothing offered leads out of it, it leaves the way it came in. Ground that floods past a few
	 * thousand tiles is not a room, and is left to wander.
	 */
	private List<GolemTransport> unmeshedWaysHome(int x, int y, int plane)
	{
		List<GolemTransport> known = unmeshedWays.get(RoamContext.tileKey(x, y, plane));
		if (known != null || pathfinder == null)
		{
			return known == null ? Collections.emptyList() : known;
		}
		// Kept to a size: each room remembers every tile of itself, and a session wandering new
		// content could otherwise hold hundreds of thousands until the network next changed.
		if (unmeshedWays.size() > UNMESHED_REMEMBERED)
		{
			unmeshedWays.clear();
		}
		TileMap room = pathfinder.flood(x, y, plane, UNMESHED_ROOM);
		List<GolemTransport> ways = Collections.emptyList();
		if (room.size() < UNMESHED_ROOM)
		{
			ways = new ArrayList<>();
			for (GolemTransport t : transports.all())
			{
				if (!transports.isOffered(t) || !abilities.canUse(t))
				{
					continue;
				}
				boolean fromIn = t.getFromPlane() == plane && inRoom(room, t.getFromX(), t.getFromY());
				boolean toIn = t.getToPlane() == plane && inRoom(room, t.getToX(), t.getToY());
				if (fromIn && !toIn)
				{
					// A way out it already knows: not shut in.
					ways = Collections.emptyList();
					break;
				}
				// Only what leads in from outside: a way from one part of the room to another, such
				// as a learned "delve deeper" into the same room again, is no way home.
				if (toIn && !fromIn)
				{
					ways.add(t.backThrough());
				}
			}
		}
		for (int i = 0; i < room.size(); i++)
		{
			long tile = room.keyAt(i);
			unmeshedWays.put(RoamContext.tileKey(GolemPathfinder.unpackX(tile), GolemPathfinder.unpackY(tile), plane), ways);
		}
		return ways;
	}

	/** Whether a transport's end is in a room, or beside it: an end can sit on its object. */
	private static boolean inRoom(TileMap room, int x, int y)
	{
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				if (room.containsKey(GolemPathfinder.pack(x + dx, y + dy)))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Spaces reachable by shortcuts and still shut in: the open world reaches hundreds. */
	private static final int SHUT_IN = 8;

	/** Ways home by component, worked out once per change to the network; empty where there is no need. */
	private final Map<Integer, List<GolemTransport>> waysHome = new HashMap<>();

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
		Set<Integer> reached = new HashSet<>();
		ArrayDeque<Integer> queue = new ArrayDeque<>();
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
					return Collections.emptyList();
				}
				if (reached.add(to))
				{
					if (reached.size() > SHUT_IN)
					{
						return Collections.emptyList();
					}
					queue.add(to);
				}
			}
		}

		List<GolemTransport> ways = new ArrayList<>();
		for (GolemTransport way : waysBackFrom.getOrDefault(start, Collections.emptyList()))
		{
			// Back to somewhere outside the trap, not to another room of it.
			int to = componentNear(way.getToX(), way.getToY(), way.getToPlane());
			// Nor into ground cut off from home: a one-way exit turned round leads nowhere a golem
			// could come back from, as the Castle Wars tunnels' ways up to the lobby did.
			if (!reached.contains(to) && !mesh.isCutOff(way.getToX(), way.getToY(), way.getToPlane()))
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
		unmeshedWays.clear();
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
	 * Everywhere a golem can walk to from where it stands, within the budget - flooded at most once
	 * per plan, only after a straight line fails, and underground those almost never exist.
	 */
	@RequiredArgsConstructor
	private static final class Reach
	{
		private final WorldPoint from;
		private final RoamContext context;

		/** The golem's own memory, for the traits that change where it wants to go. */
		private TransportMemory memory;
		private final int budget;
		private TileMap cameFrom;

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
		boolean restless = reach.memory != null && reach.memory.is(GolemTrait.RESTLESS);

		// A golem that likes the cold, the heat, or home takes the leg that suits it best of the
		// several it finds, rather than the first that works. That is the whole of how it gets
		// there: a few dozen tiles the right way, a couple of times a minute, all year.
		GolemClimate climate = reach.context == null ? null : reach.context.getClimates();
		boolean picky = climate != null && climate.cares(reach.memory);
		List<int[]> wanted = picky ? new ArrayList<>(ATTEMPTS) : null;

		for (int attempt = 0; attempt < ATTEMPTS; attempt++)
		{
			int distance = LEG_MIN + random.nextInt(restless ? LEG_SPREAD * 2 : LEG_SPREAD);
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
			if (picky)
			{
				wanted.add(new int[]{toX, toY, (int) (climate.liking(reach.memory, toX, toY) * 10000)});
				continue;
			}
			List<int[]> leg = straightLine(from.getX(), from.getY(), toX, toY, from.getPlane());
			if (leg == null)
			{
				continue;
			}
			return Itinerary.of(leg, from.getPlane(), tick, 0);
		}

		if (picky)
		{
			// Best liked first, and the rest behind it: the pick still has to be walkable in a
			// straight line, and where nothing is liked more than anything else this is the order
			// they were found in, which is random.
			wanted.sort((one, other) -> other[2] - one[2]);
			for (int[] candidate : wanted)
			{
				List<int[]> leg = straightLine(from.getX(), from.getY(), candidate[0], candidate[1], from.getPlane());
				if (leg != null)
				{
					return Itinerary.of(leg, from.getPlane(), tick, 0);
				}
			}
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
			// In the same connected space as the straight lines above: a tile the walk reaches but
			// the mesh counts as another space is one the cut-off sweep would carry a golem home from.
			if ((x == from.getX() && y == from.getY()) || !isSafe(x, y, from.getPlane())
				|| !mesh.sameComponent(from.getX(), from.getY(), x, y, from.getPlane()))
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

	/** How far from a quayside or a landing golems spread out to find a tile each. */
	private static final int SPREAD_TILES = 3;

	/**
	 * The nearest tile to {@code at} a golem can stand on that is not in {@code taken}, joined to it
	 * on foot and at most a few tiles off; {@code at} itself if there is none. For a crew, which by
	 * its route's reckoning is all in one place: at the quayside waiting, and on landing.
	 */
	WorldPoint freeTileNear(WorldPoint at, Set<Long> taken)
	{
		return freeTileNear(at, taken, false);
	}

	/**
	 * As {@link #freeTileNear(WorldPoint, Set)}, for golems stepping off the player's ship: never
	 * the sea, and never the start of a transport. What the island memory has seen walked on reaches
	 * out along a pier and over its gangplank, and golems were set down on the water, afloat on
	 * rafts, and on the gangplank itself.
	 */
	WorldPoint landingTileNear(WorldPoint at, Set<Long> taken)
	{
		return freeTileNear(at, taken, true);
	}

	private WorldPoint freeTileNear(WorldPoint at, Set<Long> taken, boolean landing)
	{
		int plane = at.getPlane();
		for (int ring = 0; ring <= SPREAD_TILES; ring++)
		{
			for (int dx = -ring; dx <= ring; dx++)
			{
				for (int dy = -ring; dy <= ring; dy++)
				{
					if (Math.max(Math.abs(dx), Math.abs(dy)) != ring)
					{
						continue;
					}
					int x = at.getX() + dx;
					int y = at.getY() + dy;
					if (!taken.contains(RoamContext.tileKey(x, y, plane)) && isSafe(x, y, plane)
						&& (ring == 0 || mesh.sameComponent(at.getX(), at.getY(), x, y, plane))
						&& (!landing || !mesh.isOcean(x, y, plane) && !transports.hasOrigin(x, y)))
					{
						return new WorldPoint(x, y, plane);
					}
				}
			}
		}
		return at;
	}

	/** How far from its place at a quayside a golem waiting there wanders. */
	private static final int MILL_TILES = 2;

	/**
	 * Somewhere near its place at a quayside for a golem waiting there to wander to, or null if a
	 * few tries find nowhere: ground it can stand on, joined to its place on foot, and as a landing
	 * is, never the sea or the start of a transport. Not in {@code taken} either, one golem a tile.
	 */
	WorldPoint quayTileNear(WorldPoint spot, Set<Long> taken, Random random)
	{
		int plane = spot.getPlane();
		for (int attempt = 0; attempt < 4; attempt++)
		{
			int x = spot.getX() + random.nextInt(MILL_TILES * 2 + 1) - MILL_TILES;
			int y = spot.getY() + random.nextInt(MILL_TILES * 2 + 1) - MILL_TILES;
			if (!taken.contains(RoamContext.tileKey(x, y, plane)) && isSafe(x, y, plane)
				&& mesh.sameComponent(spot.getX(), spot.getY(), x, y, plane)
				&& !mesh.isOcean(x, y, plane) && !transports.hasOrigin(x, y))
			{
				return new WorldPoint(x, y, plane);
			}
		}
		return null;
	}

	/** Standing where it is for a while: a golem waiting at a quayside for a crew to make up. */
	static Itinerary stayPut(WorldPoint at, int tick, int ticks)
	{
		return Itinerary.of(single(at), at.getPlane(), tick, Math.max(1, ticks));
	}

	private static List<int[]> single(WorldPoint at)
	{
		List<int[]> path = new ArrayList<>(1);
		path.add(new int[]{at.getX(), at.getY()});
		return path;
	}

	/**
	 * A walk to the quayside of the nearest open dock in reach, or null. Not while on shore leave,
	 * and not to a dock the golem is already at - that is the voyage roll's business.
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
			int distance = span(shore.getX() - from.getX(), shore.getY() - from.getY());
			if (shore.getPlane() == from.getPlane() && distance > DOCK_REACH && distance <= TRANSPORT_SEARCH
				&& docks.isOpen(dock))
			{
				near.add(dock);
			}
		}
		near.sort((a, b) -> Integer.compare(
			span(a.getShore().getX() - from.getX(), a.getShore().getY() - from.getY()),
			span(b.getShore().getX() - from.getX(), b.getShore().getY() - from.getY())));
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
	 * A walk to a tile - a straight line where there is one, otherwise through the flood - the
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
	 * transports onward - not this one in reverse - ending on some. See leadsToGround.
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
		// A seafarer takes every chance it gets; the rest sail now and then, and a golem standing
		// where it wanted to be hardly at all.
		return planVoyage(from, tick, random, memory, context,
			SAIL_CHANCE * (memory != null && memory.is(GolemTrait.SEAFARER) ? 3f : 1f)
				* (context == null ? 1f : context.wanderlust(memory, from.getX(), from.getY())));
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
			int distance = span(shore.getX() - from.getX(), shore.getY() - from.getY());
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
			&& span(shore.getX() - from.getX(), shore.getY() - from.getY()) <= DOCK_REACH;
	}

	/** Rolls for a crossing for a golem that walked to a dock on purpose: the far golem's chance. */
	Itinerary planVoyageAtDock(WorldPoint from, int tick, Random random, TransportMemory memory, RoamContext context)
	{
		return planVoyage(from, tick, random, memory, context, AT_DOCK_SAIL_CHANCE
			* (context == null ? 1f : context.wanderlust(memory, from.getX(), from.getY())));
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

	/** How a plan came out. */
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
	 * within reach is considered in a random order, which keeps the choice fair - except for a
	 * golem with a taste in places, which considers the ones that suit it first.
	 */
	private Itinerary toNearbyTransport(WorldPoint from, int tick, Random random, TransportMemory memory,
		Reach reach)
	{
		List<GolemTransport> near = new ArrayList<>();
		transports.near(from.getX(), from.getY(), TRANSPORT_SEARCH, near);
		Collections.shuffle(near, random);
		GolemClimate climate = reach.context == null ? null : reach.context.getClimates();
		if (climate != null && climate.cares(memory))
		{
			// Shortcuts and boats are how a golem covers real distance, so a golem that wants to be
			// somewhere in particular takes the one that gets it nearest. Shuffled first, so the
			// ones it has no opinion about - which is most of them - stay in a random order.
			near.sort((one, other) -> Float.compare(climate.liking(memory, other.getToX(), other.getToY()),
				climate.liking(memory, one.getToX(), one.getToY())));
		}

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
			// What the golem itself makes of this one: a taste in places, and a taste in obstacles.
			// Turned down here it is turned down for good - unlike a crowd, which a golem would
			// rather push through than stay put in.
			float wanted = reach.context == null ? 1f
				: reach.context.desire(memory, from.getX(), from.getY(), transport.getToX(), transport.getToY());
			if (memory != null)
			{
				wanted *= memory.tasteFor(transport);
			}
			if (wanted < 1f && random.nextFloat() >= wanted)
			{
				continue;
			}
			// Somewhere with room in it, by preference; see RoamContext.appeal. One that turns a
			// transport down falls back on the first it passed over rather than staying put.
			float appeal = reach.context == null ? 1f
				: reach.context.appeal(from.getX(), from.getY(), from.getPlane(),
					transport.getToX(), transport.getToY(), transport.getToPlane(), memory);
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
			// Past the first few, each try is a line and a lookup in the flood - cheap, but a town
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
	 * changed. A golem is moved, never replaced - name, id and gait kept - since golems do not die.
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
		// wall is often inside the building.
		// Off the shore of the cave's lake the golem's own space is the water, and keeping to it
		// would find nothing but more water; that call asks for any side of the shore.
		int origin = sameSpace ? mesh.componentAt(at.getX(), at.getY(), at.getPlane()) : 0;
		if (sameSpace && origin == 0)
		{
			// The mesh has nothing to say about the tile itself - it is in a wall, or off the
			// shipped map. Then the ground it was standing beside speaks for it, because a golem
			// being picked up belongs back where it came from. Without this the search was free to
			// take the nearest walkable tile in any direction for thirty-two tiles, which across a
			// channel is another island: golems turned up on shores no golem can walk to.
			origin = beside(at);
		}
		int reach = sameSpace && origin == 0 ? UNKNOWN_REACH : 32;

		for (int radius = 1; radius <= reach; radius++)
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
		return null;
	}

	/**
	 * How far a golem may be moved when nothing at all is known about where it is standing. A
	 * rescue is nearly always a tile or two; the long reach is for a golem in a wall, which has
	 * a component to be kept to.
	 */
	private static final int UNKNOWN_REACH = 4;

	/** The space the ground around this tile belongs to, or 0 if that ground says nothing either. */
	private int beside(WorldPoint at)
	{
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				int space = mesh.componentAt(at.getX() + dx, at.getY() + dy, at.getPlane());
				if (space != 0)
				{
					return space;
				}
			}
		}
		return 0;
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
