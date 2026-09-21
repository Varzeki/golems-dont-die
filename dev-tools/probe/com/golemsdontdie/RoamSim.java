package com.golemsdontdie;

import java.io.FileReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;

/**
 * Simulates golems nobody can see, with the plugin's own planner, and reports how far into
 * dungeons and up buildings they get.
 *
 * <p>Out of view is where almost every golem is, and where nobody can watch it — so whether a
 * golem that reaches a dungeon entrance ever gets deeper than the first ladder is invisible in
 * play and cannot be judged from a journal. This runs the same {@link RoamPlanner} over the
 * same shipped mesh, transport table and the profile's learned routes, tick by tick, and counts.
 *
 * <p>Two groups start together. <b>Entrances</b> are golems placed at random on the surface end
 * of transports that lead underground, anywhere in the world: the question is whether a golem
 * that arrives at a dungeon goes in and keeps going. <b>Wyrmscraig</b> start at the plinth, for
 * the island's own floors: the ladder, the tower staircase and the cathedral basement.
 *
 * <p>Sailing is on, from the shipped docks with every port open; {@code -Dsail=0} leaves golems
 * ashore, and {@code -Drestrict=1} runs with "Restrict Golem ambition" set.
 *
 *   java com.golemsdontdie.RoamSim profile.properties [golems] [ticks] [seed]
 */
public class RoamSim
{
	/** Surface maps end here; everything north of it is underground or an instance template. */
	private static final int UNDERGROUND_Y = 4160;

	private static final int[][] WYRMSCRAIG_FLOORS = {
		// x0, y0, x1, y1, plane — the ladder top, the tower staircase top, the cathedral basement.
		{2560, 2240, 2600, 2280, 1},
		{2496, 8576, 2687, 8767, 0},
	};

	public static void main(String[] args) throws Exception
	{
		Properties profile = new Properties();
		try (Reader in = new FileReader(args[0]))
		{
			profile.load(in);
		}
		int count = args.length > 1 ? Integer.parseInt(args[1]) : 300;
		int ticks = args.length > 2 ? Integer.parseInt(args[2]) : 6000;
		long seed = args.length > 3 ? Long.parseLong(args[3]) : 1;

		WorldMesh mesh = new WorldMesh();
		mesh.load();
		IslandMemory memory = new IslandMemory();
		set(memory, "worldMesh", mesh);
		memory.deserialise(value(profile, "islandMap"));
		memory.loadBundled();

		ObstacleKnowledge knowledge = new ObstacleKnowledge();
		knowledge.deserialise(value(profile, ObstacleKnowledge.LEARNED_KEY));
		knowledge.deserialiseConfirmed(value(profile, ObstacleKnowledge.CONFIRMED_KEY));
		knowledge.deserialiseRoutes(value(profile, ObstacleKnowledge.ROUTES_KEY));
		knowledge.deserialiseCurves(value(profile, ObstacleKnowledge.CURVES_KEY));
		knowledge.deserialiseLines(value(profile, ObstacleKnowledge.LINES_KEY));
		knowledge.setRouteFilter(r -> r[1] < 6400 && r[4] < 6400);

		TransportNetwork network = new TransportNetwork();
		network.load();
		network.setLearnedRoutes(knowledge.learnedRoutes());
		boolean admitted = admitLand(mesh, network);

		GolemAbilities abilities = new GolemAbilities();
		set(abilities, "client", maxedClient());
		set(abilities, "knowledge", knowledge);

		GolemPathfinder pathfinder = new GolemPathfinder();
		set(pathfinder, "memory", memory);

		RoamPlanner planner = new RoamPlanner();
		set(planner, "mesh", mesh);
		set(planner, "memory", memory);
		set(planner, "transports", network);
		set(planner, "abilities", abilities);
		RoamContext context = new RoamContext(memory, pathfinder, network, abilities);

		// Sailing, from the shipped buoys as SailingDocks builds them at login. Every dock is
		// open: the maxed client has every level and quest.
		if (SAILING)
		{
			SailingDocks docks = new SailingDocks();
			set(docks, "mesh", mesh);
			set(docks, "client", maxedClient());
			java.lang.reflect.Method loadBuoys = SailingDocks.class.getDeclaredMethod("loadBuoys");
			java.lang.reflect.Method snapToWater = SailingDocks.class.getDeclaredMethod("snapToWater", WorldPoint.class);
			java.lang.reflect.Method quayside = SailingDocks.class.getDeclaredMethod("quayside", int.class, WorldPoint.class);
			loadBuoys.setAccessible(true);
			snapToWater.setAccessible(true);
			quayside.setAccessible(true);
			loadBuoys.invoke(docks);
			@SuppressWarnings("unchecked")
			List<WorldPoint> buoys = (List<WorldPoint>) get(docks, "buoys");
			for (int i = 0; i < buoys.size(); i++)
			{
				WorldPoint water = (WorldPoint) snapToWater.invoke(docks, buoys.get(i));
				WorldPoint shore = (WorldPoint) quayside.invoke(docks, i, buoys.get(i));
				docks.getDocks().add(new SailingDocks.Dock(i, "dock " + i, 1, -1,
					water != null ? water : buoys.get(i), water != null, shore));
				mesh.admitDockFloor(shore.getX(), shore.getY(), shore.getPlane());
			}
			SeaMesh sea = new SeaMesh();
			set(sea, "mesh", mesh);
			Voyage voyage = new Voyage();
			set(voyage, "docks", docks);
			set(voyage, "sea", sea);
			set(planner, "docks", docks);
			set(planner, "voyage", voyage);
			System.out.println("sailing on: " + docks.getDocks().size() + " docks");
		}

		MESH = mesh;
		MEMORY = memory;
		NETWORK = network;
		ABILITIES = abilities;

		System.out.println("mesh land from transport ends: " + (admitted ? "on" : "not in this build"));

		Random random = new Random(seed);
		List<Sim> golems = new ArrayList<>();
		List<GolemTransport> entrances = new ArrayList<>();
		for (GolemTransport t : network.all())
		{
			if (t.getFromPlane() == 0 && t.getFromY() < UNDERGROUND_Y && t.getToY() >= UNDERGROUND_Y
				&& t.getToX() < 6400 && abilities.canUse(t)
				&& (memory.isKnownWalkable(t.getFromX(), t.getFromY(), 0) || mesh.isLandWalkable(t.getFromX(), t.getFromY(), 0)))
			{
				entrances.add(t);
			}
		}
		System.out.println("usable surface entrances to underground: " + entrances.size());
		for (int i = 0; i < count && !entrances.isEmpty(); i++)
		{
			GolemTransport t = entrances.get(random.nextInt(entrances.size()));
			golems.add(new Sim("entrances", new WorldPoint(t.getFromX(), t.getFromY(), 0), random.nextLong()));
		}
		for (int i = 0; i < Math.max(20, count / 6); i++)
		{
			golems.add(new Sim("wyrmscraig", new WorldPoint(2596, 2256, 0), random.nextLong()));
		}

		// Shore leave is kept in real time; here, real time is simulated time.
		TransportMemory.clock = () -> SIM_TICK[0] * 600L;
		for (int tick = 0; tick < ticks; tick++)
		{
			SIM_TICK[0] = tick;
			context.setTick(tick);
			context.setMaySearchSea(true);
			context.setAmbitionRestricted("1".equals(System.getProperty("restrict")));
			for (Sim g : golems)
			{
				g.tick(tick, planner, context);
			}
		}

		// Every plan folded into one number. Two builds that print the same fingerprint for the same
		// seed made the same decisions, which is how a change meant only to be faster is checked.
		System.out.printf("plan fingerprint: %016x%n", FINGERPRINT[0]);
		report("entrances", golems, ticks);
		report("wyrmscraig", golems, ticks);
		reportForcedBack();
	}

	/** Sailing in the simulation; -Dsail=0 to leave golems ashore, as before. */
	private static final boolean SAILING = !"0".equals(System.getProperty("sail"));

	private static final long[] FINGERPRINT = {17};

	private static long fingerprint(long hash, Itinerary plan, RoamPlanner.Outcome outcome)
	{
		hash = hash * 31 + outcome.ordinal();
		if (plan == null)
		{
			return hash * 31 - 1;
		}
		WorldPoint end = plan.destination();
		hash = hash * 31 + plan.getStartTick();
		hash = hash * 31 + plan.getDuration();
		hash = hash * 31 + end.getX() * 7919L + end.getY() * 104729L + end.getPlane();
		for (int t = 0; t <= 4; t++)
		{
			int[] at = plan.fineAt(plan.getStartTick() + plan.getDuration() * t / 4);
			hash = hash * 31 + at[0];
			hash = hash * 31 + at[1];
			hash = hash * 31 + plan.planeAt(plan.getStartTick() + plan.getDuration() * t / 4);
		}
		return hash;
	}

	/** The simulated tick, for the clock shore leave is measured on. */
	private static final long[] SIM_TICK = {0};

	private static WorldMesh MESH;
	private static IslandMemory MEMORY;
	private static TransportNetwork NETWORK;
	private static GolemAbilities ABILITIES;

	/** Landing tile, and the tile the golem came from, to how often it was forced straight back. */
	private static final Map<String, Integer> FORCED_BACK = new HashMap<>();

	/**
	 * Where golems landed on ground they could not walk off except the way they came.
	 *
	 * <p>The planner accepts a landing it cannot walk on when another transport starts there.
	 * This lists what does start there, whether a golem may use it and where it goes, so a
	 * forced return can be told apart as bad data, a locked obstacle or a planner rule.
	 */
	private static void reportForcedBack()
	{
		if (FORCED_BACK.isEmpty())
		{
			return;
		}
		List<Map.Entry<String, Integer>> worst = new ArrayList<>(FORCED_BACK.entrySet());
		worst.sort((a, b) -> b.getValue() - a.getValue());
		System.out.println();
		System.out.println("== landings golems could only leave the way they came (" + FORCED_BACK.size() + " tiles)");
		for (Map.Entry<String, Integer> e : worst.subList(0, Math.min(10, worst.size())))
		{
			String[] parts = e.getKey().split(" from ");
			String[] at = parts[0].split(",");
			int x = Integer.parseInt(at[0]);
			int y = Integer.parseInt(at[1]);
			int p = Integer.parseInt(at[2]);
			System.out.printf("  %4dx landed %s, came from %s: golem map %b, mesh land %b, component %d%n",
				e.getValue(), parts[0], parts[1], MEMORY.isKnownWalkable(x, y, p), MESH.isLandWalkable(x, y, p),
				MESH.componentAt(x, y, p));
			for (GolemTransport t : NETWORK.from(x, y))
			{
				if (t.getFromPlane() != p)
				{
					continue;
				}
				String to = t.getToX() + "," + t.getToY() + "," + t.getToPlane();
				System.out.printf("         obj %d arch %d -> %s  usable %b  lands safe %b%s%n", t.getObjectId(),
					t.getArchetype(), to, ABILITIES.canUse(t), safe(t.getToX(), t.getToY(), t.getToPlane()),
					to.equals(parts[1]) ? "  (the way back)" : "");
			}
		}
	}

	/** RoamPlanner.isSafe, repeated. */
	private static boolean safe(int x, int y, int plane)
	{
		return MEMORY.isKnownWalkable(x, y, plane) || (MESH.isLandWalkable(x, y, plane) && !MESH.isIsolated(x, y, plane));
	}

	/** One golem out of view: the same state advanceFar keeps, plus what is being counted. */
	private static final class Sim
	{
		final String group;
		final Random random;
		final TransportMemory transportMemory = new TransportMemory();
		WorldPoint at;
		Itinerary itinerary;

		int stuckTicks;
		int undergroundTicks;
		int upstairsTicks;
		int wyrmscraigFloorTicks;
		int transportsTaken;
		int reversals;
		int depth;
		int maxDepth;
		long lastJourney = -1;
		final Set<Long> journeys = new HashSet<>();
		final Set<Integer> undergroundRegions = new HashSet<>();

		Sim(String group, WorldPoint at, long seed)
		{
			this.group = group;
			this.at = at;
			this.random = new Random(seed);
			this.home = at;
			if (!SAILING)
			{
				transportMemory.setShoreLeaveUntil(Long.MAX_VALUE);
			}
		}

		final WorldPoint home;
		/** Tick the last crossing landed, or -1; and ticks ashore before each later departure. */
		int landedTick = -1;
		final List<Integer> ashoreTicks = new ArrayList<>();
		int seaTicks;
		boolean leftHome;
		final Set<Integer> surfaceRegions = new HashSet<>();

		/** True while the golem is standing out a pause because no plan was found. */
		boolean idle;

		/** Plans by outcome, and of the failures how many were underground or found no ground at all. */
		final int[] outcomes = new int[RoamPlanner.Outcome.values().length];
		int nothingUnderground;
		int nothingUnsafe;

		/** What Golem.advanceFar does, step for step. */
		void tick(int tick, RoamPlanner planner, RoamContext context)
		{
			if (itinerary != null && !itinerary.isFinished(tick))
			{
				stuckTicks += idle ? 1 : 0;
				seaTicks += itinerary.isVoyage() ? 1 : 0;
				count(itinerary.positionAt(tick));
				return;
			}
			if (itinerary != null)
			{
				at = itinerary.destination();
				noteJourney(itinerary);
				if (itinerary.isVoyage())
				{
					landedTick = tick;
				}
			}
			long started = System.nanoTime();
			itinerary = planner.plan(at, tick, random, transportMemory, context);
			long took = System.nanoTime() - started;
			planNanos += took;
			maxPlanNanos = Math.max(maxPlanNanos, took);
			plans++;
			RoamPlanner.Outcome outcome = planner.getLastOutcome();
			FINGERPRINT[0] = fingerprint(FINGERPRINT[0], itinerary, outcome);
			if (outcome == RoamPlanner.Outcome.VOYAGE && landedTick >= 0)
			{
				ashoreTicks.add(tick - landedTick);
			}
			legOutcome = outcome;
			outcomes[outcome.ordinal()]++;
			if (outcome == RoamPlanner.Outcome.NOTHING)
			{
				nothingUnderground += at.getY() >= UNDERGROUND_Y ? 1 : 0;
				nothingUnsafe += planner.getLastCandidates() == 0 ? 1 : 0;
			}
			idle = itinerary == null;
			if (idle)
			{
				itinerary = RoamPlanner.idle(at, tick, random, failures++);
				stuckTicks++;
			}
			else
			{
				failures = 0;
			}
			count(at);
		}

		int failures;
		RoamPlanner.Outcome legOutcome;
		long planNanos;
		long maxPlanNanos;
		int plans;
		int lastArrival = Integer.MIN_VALUE / 2;
		int quickReversals;
		int quickOnwardReversals;

		private void count(WorldPoint where)
		{
			if (where.getY() < UNDERGROUND_Y && where.getPlane() == 0)
			{
				surfaceRegions.add(where.getRegionID());
				leftHome |= Math.max(Math.abs(where.getX() - home.getX()), Math.abs(where.getY() - home.getY())) > 200;
			}
			if (where.getY() >= UNDERGROUND_Y)
			{
				undergroundTicks++;
				undergroundRegions.add(where.getRegionID());
			}
			if (where.getPlane() > 0)
			{
				upstairsTicks++;
			}
			for (int[] f : WYRMSCRAIG_FLOORS)
			{
				if (where.getX() >= f[0] && where.getX() <= f[2] && where.getY() >= f[1] && where.getY() <= f[3]
					&& where.getPlane() == f[4])
				{
					wyrmscraigFloorTicks++;
				}
			}
		}

		private void noteJourney(Itinerary leg)
		{
			if (!leg.endsInTransport())
			{
				return;
			}
			WorldPoint o = leg.walkEnd();
			WorldPoint d = leg.destination();
			transportsTaken++;
			long journey = TransportNetwork.endpoints(o.getX(), o.getY(), o.getPlane(), d.getX(), d.getY(), d.getPlane());
			long back = TransportNetwork.endpoints(d.getX(), d.getY(), d.getPlane(), o.getX(), o.getY(), o.getPlane());
			if (lastJourney == back)
			{
				reversals++;
				// Back within half a minute of arriving: it did not look around first.
				if (leg.getStartTick() - lastArrival <= 30)
				{
					quickReversals++;
					if (legOutcome == RoamPlanner.Outcome.ONWARD)
					{
						quickOnwardReversals++;
						FORCED_BACK.merge(o.getX() + "," + o.getY() + "," + o.getPlane() + " from "
							+ d.getX() + "," + d.getY() + "," + d.getPlane(), 1, Integer::sum);
					}
				}
			}
			lastArrival = leg.getStartTick() + leg.getDuration();
			lastJourney = journey;
			journeys.add(journey);

			boolean surface = d.getPlane() == 0 && d.getY() < UNDERGROUND_Y;
			depth = surface ? 0 : depth + 1;
			maxDepth = Math.max(maxDepth, depth);
		}
	}

	private static void report(String group, List<Sim> golems, int ticks)
	{
		List<Sim> in = new ArrayList<>();
		for (Sim g : golems)
		{
			if (g.group.equals(group))
			{
				in.add(g);
			}
		}
		if (in.isEmpty())
		{
			return;
		}
		long stuck = 0, under = 0, up = 0, floors = 0, taken = 0, reversals = 0;
		int wentUnder = 0, wentUp = 0, reachedFloors = 0, depth3 = 0;
		List<Integer> depths = new ArrayList<>();
		List<Integer> regions = new ArrayList<>();
		for (Sim g : in)
		{
			stuck += g.stuckTicks;
			under += g.undergroundTicks;
			up += g.upstairsTicks;
			floors += g.wyrmscraigFloorTicks;
			taken += g.transportsTaken;
			reversals += g.reversals;
			wentUnder += g.undergroundTicks > 0 ? 1 : 0;
			wentUp += g.upstairsTicks > 0 ? 1 : 0;
			reachedFloors += g.wyrmscraigFloorTicks > 0 ? 1 : 0;
			depth3 += g.maxDepth >= 3 ? 1 : 0;
			depths.add(g.maxDepth);
			regions.add(g.undergroundRegions.size());
		}
		int[] outcomes = new int[RoamPlanner.Outcome.values().length];
		long nothingUnder = 0, nothingUnsafe = 0, quick = 0, quickOnward = 0, nanos = 0, maxNanos = 0, planCount = 0;
		for (Sim g : in)
		{
			quick += g.quickReversals;
			quickOnward += g.quickOnwardReversals;
			nanos += g.planNanos;
			maxNanos = Math.max(maxNanos, g.maxPlanNanos);
			planCount += g.plans;
			for (int i = 0; i < outcomes.length; i++)
			{
				outcomes[i] += g.outcomes[i];
			}
			nothingUnder += g.nothingUnderground;
			nothingUnsafe += g.nothingUnsafe;
		}
		depths.sort(null);
		regions.sort(null);
		StringBuilder plans = new StringBuilder("  plans:");
		for (RoamPlanner.Outcome o : RoamPlanner.Outcome.values())
		{
			plans.append(' ').append(o.name().toLowerCase()).append(' ').append(outcomes[o.ordinal()]);
		}
		int nothing = outcomes[RoamPlanner.Outcome.NOTHING.ordinal()];
		plans.append(String.format("; of %d with nothing: %d underground, %d found no ground to walk to at all",
			nothing, nothingUnder, nothingUnsafe));
		long total = (long) in.size() * ticks;
		System.out.println();
		System.out.println("== " + group + ": " + in.size() + " golems, " + ticks + " ticks each");
		System.out.printf("  ticks with no plan (standing, replanning): %.1f%%%n", 100.0 * stuck / total);
		System.out.printf("  time underground: %.1f%%   upstairs: %.1f%%   on Wyrmscraig's floors: %.1f%%%n",
			100.0 * under / total, 100.0 * up / total, 100.0 * floors / total);
		System.out.printf("  golems that went underground: %d   upstairs: %d   onto Wyrmscraig's floors: %d%n",
			wentUnder, wentUp, reachedFloors);
		System.out.printf("  transports taken: %d (%.1f per golem), straight back the way they came: %d (%.0f%%)%n",
			taken, taken / (double) in.size(), reversals, taken == 0 ? 0.0 : 100.0 * reversals / taken);
		System.out.printf("  depth (transports in a row off the surface): median %d, 90th %d, max %d; %d golems 3 or deeper%n",
			depths.get(depths.size() / 2), depths.get(depths.size() * 9 / 10), depths.get(depths.size() - 1), depth3);
		System.out.printf("  underground regions per golem: median %d, 90th %d, max %d%n",
			regions.get(regions.size() / 2), regions.get(regions.size() * 9 / 10), regions.get(regions.size() - 1));
		System.out.println(plans);
		List<Integer> ashore = new ArrayList<>();
		for (Sim g : in)
		{
			ashore.addAll(g.ashoreTicks);
		}
		ashore.sort(null);
		System.out.println(ashore.isEmpty() ? "  time ashore between crossings: no golem sailed twice"
			: String.format("  time ashore between crossings: %d times, shortest %.1f min, median %.1f min",
				ashore.size(), ashore.get(0) * 0.6 / 60, ashore.get(ashore.size() / 2) * 0.6 / 60));
		long sea = 0;
		int wentFar = 0;
		List<Integer> spread = new ArrayList<>();
		for (Sim g : in)
		{
			sea += g.seaTicks;
			wentFar += g.leftHome ? 1 : 0;
			spread.add(g.surfaceRegions.size());
		}
		spread.sort(null);
		System.out.printf("  at sea: %.1f%% of ticks; golems more than 200 tiles from where they started: %d; "
				+ "surface regions per golem: median %d, max %d%n",
			100.0 * sea / total, wentFar, spread.get(spread.size() / 2), spread.get(spread.size() - 1));
		System.out.printf("  back the way they came within 30 ticks of arriving: %d (%d of them the only way off the tile)%n",
			quick, quickOnward);
		System.out.printf("  planning cost: %d plans, %.0f us each on average, slowest %.1f ms%n",
			planCount, planCount == 0 ? 0.0 : nanos / 1000.0 / planCount, maxNanos / 1e6);
	}

	/** Calls the mesh's transport-land hook where this build has one. */
	private static boolean admitLand(WorldMesh mesh, TransportNetwork network)
	{
		try
		{
			WorldMesh.class.getDeclaredMethod("admitTransportEnds", List.class).invoke(mesh, network.all());
			return true;
		}
		catch (NoSuchMethodException e)
		{
			return false;
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException(e);
		}
	}

	/** A client for an account with every level and quest, and every varbit at zero. */
	private static Client maxedClient()
	{
		return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, arguments) ->
			{
				switch (method.getName())
				{
					case "getRealSkillLevel":
						return 99;
					case "getIntStack":
						return new int[]{2};
					default:
						Class<?> type = method.getReturnType();
						if (type == int.class)
						{
							return 0;
						}
						if (type == boolean.class)
						{
							return false;
						}
						return null;
				}
			});
	}

	private static String value(Properties profile, String key)
	{
		return profile.getProperty(GolemsDontDieConfig.GROUP + "." + key);
	}

	private static void set(Object target, String name, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static Object get(Object target, String name) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}
}
