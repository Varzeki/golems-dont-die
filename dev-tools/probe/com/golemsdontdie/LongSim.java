package com.golemsdontdie;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Properties;
import java.util.Random;
import net.runelite.api.coords.WorldPoint;

/**
 * Golems nobody can see, left alone for a long time: do they pile up anywhere, and does the
 * planner keep working?
 *
 * <p>{@link RoamSim} steps every golem every tick, which is right for a few thousand ticks and
 * hopeless for a year (fifty million ticks). Out of view a golem does nothing between plans — its
 * itinerary is fixed — so this jumps straight from one golem's plan to the next due. Same
 * planner, same mesh and transports, same learned routes from the profile, same docks.
 *
 * <p>Golems do not interact out of view, so a run can be split across processes by shard and the
 * snapshots merged afterwards; see {@code dev-tools/longsim.py}.
 *
 * <p>Every {@code snapshotTicks} it writes where every golem is, by region and plane, with the
 * largest stack on one tile, how many have not moved in an hour, plan cost, failures and heap.
 *
 *   java com.golemsdontdie.LongSim profile.properties golems ticks snapshotTicks seed out.csv
 */
public class LongSim
{
	private static final int PLINTH_X = 2596;
	private static final int PLINTH_Y = 2256;

	/** How often golems are counted by region, as the plugin does every ten game ticks. */
	private static final int CENSUS_TICKS = 10;

	/** -Dtrace=N prints every plan of the first N golems for -DtraceTicks ticks. */
	private static final int TRACE = Integer.getInteger("trace", 0);
	private static final int TRACE_TICKS = Integer.getInteger("traceTicks", 3000);

	/** A golem that has not changed tile in this long counts as stuck: one hour. */
	private static final int STUCK_TICKS = 6000;

	public static void main(String[] args) throws Exception
	{
		Properties profile = new Properties();
		try (Reader in = new FileReader(args[0]))
		{
			profile.load(in);
		}
		int count = Integer.parseInt(args[1]);
		long ticks = Long.parseLong(args[2]);
		int snapshotTicks = Integer.parseInt(args[3]);
		long seed = Long.parseLong(args[4]);
		String outPath = args[5];

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
		mesh.admitTransportEnds(network.all());

		Method maxed = RoamSim.class.getDeclaredMethod("maxedClient");
		maxed.setAccessible(true);
		Object client = maxed.invoke(null);
		GolemAbilities abilities = new GolemAbilities();
		set(abilities, "client", client);
		set(abilities, "knowledge", knowledge);

		GolemPathfinder pathfinder = new GolemPathfinder();
		set(pathfinder, "memory", memory);

		RoamPlanner planner = new RoamPlanner();
		set(planner, "mesh", mesh);
		set(planner, "memory", memory);
		set(planner, "transports", network);
		set(planner, "abilities", abilities);
		RoamContext context = new RoamContext(memory, pathfinder, network, abilities);
		// Golems spread out by knowing where the others are, so the simulation counts them too.
		GolemCensus census = new GolemCensus();
		context.setCensus(census);

		SailingDocks docks = new SailingDocks();
		set(docks, "mesh", mesh);
		set(docks, "client", client);
		Method loadBuoys = SailingDocks.class.getDeclaredMethod("loadBuoys");
		Method snapToWater = SailingDocks.class.getDeclaredMethod("snapToWater", WorldPoint.class);
		Method quayside = SailingDocks.class.getDeclaredMethod("quayside", int.class, WorldPoint.class);
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

		final long[] now = {0};
		TransportMemory.clock = () -> now[0] * 600L;

		Random random = new Random(seed);
		List<Sim> golems = new ArrayList<>();
		PriorityQueue<Sim> due = new PriorityQueue<>((a, b) -> Long.compare(a.nextPlan, b.nextPlan));
		// -DstartRegion=region:plane starts golems on transport landings there instead of the plinth,
		// to measure how quickly golems leave a place they gather in.
		List<WorldPoint> starts = new ArrayList<>();
		String startRegion = System.getProperty("startRegion");
		if (startRegion != null)
		{
			int region = Integer.parseInt(startRegion.split(":")[0]);
			int plane = Integer.parseInt(startRegion.split(":")[1]);
			for (GolemTransport t : network.all())
			{
				WorldPoint landing = new WorldPoint(t.getToX(), t.getToY(), t.getToPlane());
				if (landing.getRegionID() == region && landing.getPlane() == plane && network.isOffered(t)
					&& abilities.canUse(t))
				{
					starts.add(landing);
				}
			}
			System.out.println("starting on " + starts.size() + " landings in region " + startRegion);
		}
		for (int i = 0; i < count; i++)
		{
			WorldPoint start = starts.isEmpty() ? new WorldPoint(PLINTH_X, PLINTH_Y, 0) : starts.get(random.nextInt(starts.size()));
			Sim g = new Sim(start, random.nextLong());
			golems.add(g);
			due.add(g);
		}

		long plans = 0, nothing = 0, exceptions = 0, voyages = 0, planNanos = 0, maxPlanNanos = 0;
		long wallStart = System.nanoTime();
		long nextSnapshot = snapshotTicks;
		long lastCensus = -CENSUS_TICKS;
		try (PrintWriter out = new PrintWriter(new FileWriter(outPath)))
		{
			out.println("# LongSim golems=" + count + " ticks=" + ticks + " seed=" + seed);
			while (true)
			{
				Sim g = due.peek();
				long tick = g.nextPlan;
				while (nextSnapshot <= Math.min(tick, ticks))
				{
					snapshot(out, golems, nextSnapshot, plans, nothing, exceptions, voyages, planNanos, maxPlanNanos,
						wallStart);
					System.out.println("  census at 3616,3296,1 = " + census.at(3616, 3296, 1)
						+ " roominess " + census.roominess(3616, 3296, 1));
					plans = nothing = exceptions = voyages = planNanos = maxPlanNanos = 0;
					nextSnapshot += snapshotTicks;
				}
				if (tick > ticks)
				{
					break;
				}
				due.poll();
				now[0] = tick;
				if (tick - lastCensus >= CENSUS_TICKS)
				{
					lastCensus = tick;
					census.begin();
					for (Sim other : golems)
					{
						WorldPoint where = other.itinerary == null ? other.at : other.itinerary.positionAt((int) tick);
						census.add(where.getX(), where.getY(), where.getPlane());
					}
				}
				// Ticks are ints in the planner; a year is fifty million, well inside.
				int t = (int) tick;
				context.setTick(t);
				context.setMaySearchSea(true);
				context.setMayPath(true);

				if (g.itinerary != null)
				{
					g.arrive(g.itinerary.destination(), t);
				}
				long started = System.nanoTime();
				Itinerary next;
				try
				{
					next = planner.plan(g.at, t, g.random, g.transportMemory, context);
				}
				catch (RuntimeException e)
				{
					if (exceptions++ < 3)
					{
						System.err.println("plan failed at " + g.at + " tick " + t);
						e.printStackTrace();
					}
					next = null;
				}
				long took = System.nanoTime() - started;
				planNanos += took;
				maxPlanNanos = Math.max(maxPlanNanos, took);
				plans++;
				if (next != null && next.isVoyage())
				{
					voyages++;
				}
				if (next == null)
				{
					nothing++;
					next = RoamPlanner.idle(g.at, t, g.random, g.failures++);
				}
				else
				{
					g.failures = 0;
				}
				if (TRACE > 0 && golems.indexOf(g) < TRACE && tick < TRACE_TICKS)
				{
					WorldPoint to = next.destination();
					System.out.printf("golem %d t=%d at %s %s -> %d,%d,%d dur %d%s%n", golems.indexOf(g), tick,
						g.at.getX() + "," + g.at.getY() + "," + g.at.getPlane(), planner.getLastOutcome(),
						to.getX(), to.getY(), to.getPlane(), next.getDuration(),
						next.transport() == null ? "" : " via obj " + next.transport().getObjectId());
				}
				g.itinerary = next;
				g.nextPlan = next.getStartTick() + Math.max(1, next.getDuration());
				due.add(g);
			}
		}
		System.out.printf("done: %d golems, %d ticks, %.0f s%n", count, ticks, (System.nanoTime() - wallStart) / 1e9);
	}

	private static void snapshot(PrintWriter out, List<Sim> golems, long tick, long plans, long nothing,
		long exceptions, long voyages, long planNanos, long maxPlanNanos, long wallStart)
	{
		Map<Long, Integer> regions = new HashMap<>();
		Map<Long, Integer> tiles = new HashMap<>();
		int stuck = 0, underground = 0, upstairs = 0, atSea = 0;
		for (Sim g : golems)
		{
			WorldPoint p = g.itinerary == null ? g.at : g.itinerary.positionAt((int) Math.min(tick, Integer.MAX_VALUE));
			regions.merge(((long) p.getRegionID() << 2) | p.getPlane(), 1, Integer::sum);
			tiles.merge(RoamContext.tileKey(p.getX(), p.getY(), p.getPlane()), 1, Integer::sum);
			stuck += tick - g.lastMoved > STUCK_TICKS ? 1 : 0;
			underground += p.getY() >= 4160 ? 1 : 0;
			upstairs += p.getPlane() > 0 ? 1 : 0;
			atSea += g.itinerary != null && g.itinerary.isVoyage() && !g.itinerary.isFinished((int) tick) ? 1 : 0;
		}
		int maxStack = 0;
		long busiest = 0;
		for (Map.Entry<Long, Integer> e : tiles.entrySet())
		{
			if (e.getValue() > maxStack)
			{
				maxStack = e.getValue();
				busiest = e.getKey();
			}
		}
		out.printf("T,%d,%d,%d,%d,%d%n", tick, (int) ((busiest >> 20) & 0xFFFFF), (int) (busiest & 0xFFFFF),
			(int) (busiest >> 40), maxStack);
		Runtime rt = Runtime.getRuntime();
		long heapMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
		out.printf("S,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.1f,%.1f,%d,%.0f%n", tick, plans, nothing, exceptions, voyages,
			stuck, underground, upstairs, atSea, maxStack, plans == 0 ? 0.0 : planNanos / 1000.0 / plans,
			maxPlanNanos / 1e6, heapMb, (System.nanoTime() - wallStart) / 1e9);
		StringBuilder line = new StringBuilder("R,").append(tick);
		for (Map.Entry<Long, Integer> e : regions.entrySet())
		{
			line.append(',').append(e.getKey() >> 2).append(':').append(e.getKey() & 3).append(':').append(e.getValue());
		}
		out.println(line);
		out.flush();
	}

	private static final class Sim
	{
		final Random random;
		final TransportMemory transportMemory = new TransportMemory();
		WorldPoint at;
		Itinerary itinerary;
		long nextPlan;
		int failures;
		long lastMoved;

		Sim(WorldPoint at, long seed)
		{
			this.at = at;
			this.random = new Random(seed);
		}

		void arrive(WorldPoint where, int tick)
		{
			if (where.getX() != at.getX() || where.getY() != at.getY() || where.getPlane() != at.getPlane())
			{
				lastMoved = tick;
			}
			at = where;
		}
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
