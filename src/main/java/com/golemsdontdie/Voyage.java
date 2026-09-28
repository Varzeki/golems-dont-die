package com.golemsdontdie;

import java.util.List;
import java.util.Random;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/**
 * Decides where a golem sails, and plans the crossing.
 *
 * <p>Two rules, both chosen because they need no tuning. <b>Uniform dispersal:</b> the
 * destination is drawn evenly from every port the golem may reach, since weighting by distance
 * compounds — a golem near Catherby draws Catherby's neighbours forever. <b>One blocked port:</b>
 * a golem may not sail straight back where it came from, one field of state rather than a timer,
 * leaving home reachable again from the third hop. Wyrmscraig's population thins over a long
 * session as a result, but the island refills with every craft.
 */
@Slf4j
@Singleton
class Voyage
{
	@Inject
	private SailingDocks docks;

	@Inject
	private SeaMesh sea;

	/**
	 * Present in the client, null in the offline tools, which build fields in line. Only a marker:
	 * fields are built on {@link #builder}, a queue of sea searches here having delayed config
	 * saves.
	 */
	@Inject
	private java.util.concurrent.ScheduledExecutorService executor;

	/** How many field builds may wait for the builder; beyond it a golem asks later. */
	private static final int BUILD_QUEUE = 8;

	/** One low-priority daemon thread of its own, made on first use; see {@link #shutDown}. */
	private java.util.concurrent.ThreadPoolExecutor builder;

	private synchronized java.util.concurrent.ThreadPoolExecutor builder()
	{
		if (builder == null)
		{
			builder = new java.util.concurrent.ThreadPoolExecutor(1, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
				new java.util.concurrent.ArrayBlockingQueue<>(BUILD_QUEUE), task ->
				{
					Thread thread = new Thread(task, "golems-sea-fields");
					thread.setDaemon(true);
					thread.setPriority(Thread.MIN_PRIORITY);
					return thread;
				});
			builder.allowCoreThreadTimeOut(true);
		}
		return builder;
	}

	/**
	 * Stops the builder and forgets every field, when the plugin stops.
	 *
	 * <p>Queued builds are dropped and the thread is left to finish the one in hand, which takes a
	 * fraction of a second and touches nothing but the mesh. It is a daemon thread and times out
	 * when idle, so nothing holds the client open.
	 */
	synchronized void shutDown()
	{
		if (builder != null)
		{
			builder.getQueue().clear();
			builder.shutdown();
			builder = null;
		}
		building.clear();
		synchronized (fields)
		{
			fields.clear();
			cachedTiles = 0;
		}
	}

	/**
	 * Plans a crossing from a dock, or returns null if the golem should stay ashore.
	 *
	 * @param from   the dock the golem is leaving
	 * @param memory the golem's own memory, consulted and updated for the blocked port
	 */
	Itinerary depart(SailingDocks.Dock from, WorldPoint at, TransportMemory memory, int tick, Random random,
		RoamContext context)
	{
		List<SailingDocks.Dock> open = docks.openDocks();
		if (open.size() < 2)
		{
			return null;
		}

		// Candidates: anywhere open, except here and except where we just came from.
		List<SailingDocks.Dock> candidates = new java.util.ArrayList<>(open);
		candidates.removeIf(d -> d.getRowId() == from.getRowId()
			|| d.getRowId() == memory.getBlockedPort());

		if (candidates.isEmpty())
		{
			// Deliberately not relaxed: if the only place left is where we came from, the golem
			// roams locally rather than bouncing between two docks.
			return null;
		}

		// The port it already chose, if still waiting at this dock to sail there; waited long
		// enough, it gives up.
		SailingDocks.Dock chosen = null;
		if (memory.getPendingPort() >= 0 && memory.getPendingFrom() != from.getRowId())
		{
			memory.clearPending();
		}
		if (memory.getPendingPort() >= 0 && tick > memory.getPendingUntil())
		{
			memory.clearPending();
			return null;
		}
		for (SailingDocks.Dock candidate : candidates)
		{
			if (candidate.getRowId() == memory.getPendingPort())
			{
				chosen = candidate;
			}
		}
		if (chosen == null)
		{
			// A few tries for a port with room ashore and weather to suit, then wherever the last
			// roll lands. Ports are how a golem crosses the world, so this is where a taste for the
			// cold or the heat takes it somewhere it could never have walked — and a golem with one
			// reads the whole board rather than sampling it, three ports out of ninety being no way
			// to find the one port in the snow.
			GolemClimate climate = context == null ? null : context.getClimates();
			boolean picky = climate != null && climate.cares(memory);
			if (picky)
			{
				candidates.sort((one, other) -> Float.compare(liking(climate, memory, other),
					liking(climate, memory, one)));
			}
			for (int attempt = 0; attempt < 3 && chosen == null; attempt++)
			{
				SailingDocks.Dock candidate = picky ? candidates.get(Math.min(attempt, candidates.size() - 1))
					: candidates.get(random.nextInt(candidates.size()));
				WorldPoint shore = candidate.getShore();
				float appeal = context == null || shore == null ? 1f
					: context.roominess(shore.getX(), shore.getY(), shore.getPlane(), memory)
					* (climate == null ? 1f : climate.liking(memory, shore.getX(), shore.getY()));
				if (appeal >= 1f || random.nextFloat() < appeal)
				{
					chosen = candidate;
				}
			}
			if (chosen == null)
			{
				// A golem with a taste in places stays ashore rather than sail somewhere it does not
				// want to be. Anyone else takes whatever the last roll offered.
				if (picky)
				{
					return null;
				}
				chosen = candidates.get(random.nextInt(candidates.size()));
			}
		}

		notReady = false;
		Itinerary crossing = crossTo(from, chosen, at, memory, tick, random, context);
		// Waiting on this crossing's field, or its one search this frame: hold the choice.
		if (crossing == null && notReady)
		{
			memory.holdPending(from.getRowId(), chosen.getRowId(), tick);
		}
		else
		{
			memory.clearPending();
		}
		return crossing;
	}

	/**
	 * Plans the one crossing a crew will share.
	 *
	 * <p>The port is chosen after the crew is, and chosen for all of them: a candidate is worth no
	 * more than the least any of them thinks of it, so a crew never sails somewhere one of its
	 * members would have refused to go. Ports any of them is blocked from are not offered at all.
	 *
	 * @param crew the memories of the golems sailing, the first of which is at the helm
	 * @return the crossing, or null if none could be planned this tick
	 */
	Itinerary crewCrossing(SailingDocks.Dock from, java.util.List<TransportMemory> crew, WorldPoint at,
		int tick, Random random, RoamContext context)
	{
		if (crew.isEmpty())
		{
			return null;
		}
		List<SailingDocks.Dock> candidates = new java.util.ArrayList<>(docks.openDocks());
		candidates.removeIf(d -> d.getRowId() == from.getRowId());
		for (TransportMemory memory : crew)
		{
			candidates.removeIf(d -> d.getRowId() == memory.getBlockedPort());
		}
		if (candidates.isEmpty())
		{
			return null;
		}

		GolemClimate climate = context == null ? null : context.getClimates();
		SailingDocks.Dock best = null;
		float bestWorth = -1f;
		for (SailingDocks.Dock candidate : candidates)
		{
			WorldPoint shore = candidate.getShore();
			if (shore == null)
			{
				continue;
			}
			// The least anyone thinks of it, not the average: one golem dragged somewhere it hates
			// is the thing to avoid, and a crew is a compromise by nature.
			float worth = context == null ? 1f
				: context.roominess(shore.getX(), shore.getY(), shore.getPlane(), crew.get(0));
			if (climate != null)
			{
				for (TransportMemory memory : crew)
				{
					worth = Math.min(worth, climate.liking(memory, shore.getX(), shore.getY()));
				}
			}
			// A nudge apiece so equals do not always fall the same way.
			worth *= 0.75f + random.nextFloat() * 0.5f;
			if (worth > bestWorth)
			{
				bestWorth = worth;
				best = candidate;
			}
		}
		if (best == null)
		{
			return null;
		}
		return crossTo(from, best, at, crew.get(0), tick, random, context);
	}

	/** How much a golem likes the look of a dock's own shore; 0 for a dock with no shore. */
	private static float liking(GolemClimate climate, TransportMemory memory, SailingDocks.Dock dock)
	{
		WorldPoint shore = dock.getShore();
		return shore == null ? 0f : climate.liking(memory, shore.getX(), shore.getY());
	}

	/**
	 * Set when a crossing could not be planned only because something is not ready: a field being
	 * built, or this frame's sea search spent. Client thread only.
	 */
	private boolean notReady;

	/** Plans the crossing from one dock to a chosen other, or null if it cannot be sailed now. */
	Itinerary crossTo(SailingDocks.Dock from, SailingDocks.Dock to, WorldPoint at, TransportMemory memory, int tick,
		Random random, RoamContext context)
	{
		// A crossing that starts or ends off the open sea cannot cross the ocean mesh, the two ends
		// not being on the same water: Wyrmscraig's cave dock sits on the underground map. That is
		// a passage, not a voyage.
		boolean fromCave = isCaveDock(from);
		boolean toCave = isCaveDock(to);
		if (fromCave != toCave)
		{
			// Wyrmscraig's cave: across its lake, out through its mouth onto the sea, and on.
			return throughCave(from, to, fromCave, memory, tick, random, context);
		}
		if (!from.isOnOpenSea() || !to.isOnOpenSea())
		{
			memory.setBlockedPort(from.getRowId());
			log.debug("Golem taking the passage {} -> {}", from.getName(), to.getName());
			return passage(from, to, at, tick);
		}

		boolean searched = sea.isSearched(from.getMooring(), to.getMooring());

		if (!searched && (context == null || !context.isMaySearchSea()))
		{
			// Never computed, and the frame's one sea search is spent, so the golem tries again.
			notReady = true;
			return null;
		}

		List<int[]> route = sea.route(from.getMooring(), to.getMooring());
		if (!searched && context != null)
		{
			// Spend the budget on the attempt, not on success: a crossing with no route costs as
			// much to discover as one that has.
			context.spendSeaSearch();
		}
		if (route == null)
		{
			return null;
		}
		// From the water beside one gangplank to the other, by the shipped route between moorings.
		List<int[]> course = new java.util.ArrayList<>(route.size() + 2);
		WorldPoint setOut = from.getBerth();
		WorldPoint comeIn = to.getBerth();
		if (setOut.getX() != route.get(0)[0] || setOut.getY() != route.get(0)[1])
		{
			course.add(new int[]{setOut.getX(), setOut.getY()});
		}
		course.addAll(route);
		int[] last = route.get(route.size() - 1);
		if (comeIn.getX() != last[0] || comeIn.getY() != last[1])
		{
			course.add(new int[]{comeIn.getX(), comeIn.getY()});
		}

		SeaMesh.Field field = fieldFor(course);
		if (field == null)
		{
			// Being built. The golem waits for it.
			notReady = true;
			return null;
		}

		memory.setBlockedPort(from.getRowId());
		log.debug("Golem sailing {} -> {}", from.getName(), to.getName());

		// Gangplank to gangplank: the golem steps onto the raft on the water and off at the far
		// quayside. Beginning ashore had it gliding toward a raft not there yet.
		return steer(course, field, random, tick, to.getShore());
	}

	/** Crossings whose distance field is being built right now, so each is built once. */
	private final java.util.Set<Long> building = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/**
	 * The distance field for a route, or null while it is built in the background. Safe off the
	 * client thread: it reads only the shipped mesh, fixed after load, and its route.
	 */
	private SeaMesh.Field fieldFor(List<int[]> route)
	{
		return fieldFor(route, sea.ocean);
	}

	/** As {@link #fieldFor(List)}, over a given body of water. */
	private SeaMesh.Field fieldFor(List<int[]> route, SeaMesh.Water water)
	{
		int[] start = route.get(0);
		int[] goal = route.get(route.size() - 1);
		long key = ((long) start[0] << 45) ^ ((long) start[1] << 30) ^ ((long) goal[0] << 15) ^ goal[1]
			^ (water == sea.ocean ? 0L : 0x5A5A5AL << 40);
		synchronized (fields)
		{
			SeaMesh.Field known = fields.get(key);
			if (known != null)
			{
				return known;
			}
		}
		if (executor == null)
		{
			SeaMesh.Field built = buildField(route, water);
			remember(key, built);
			return built;
		}
		if (building.add(key))
		{
			try
			{
				builder().execute(() ->
				{
					try
					{
						remember(key, buildField(route, water));
					}
					finally
					{
						building.remove(key);
					}
				});
			}
			catch (java.util.concurrent.RejectedExecutionException e)
			{
				// Queue full, or stopping. Nothing is building it, so the next golem to ask may try.
				building.remove(key);
			}
		}
		return null;
	}

	/** How far off the shortest route a golem may wander, in tiles. */
	private static final int BAND = 40;

	/** Tiles of open sea a raft keeps all round it where the water allows. */
	private static final int SHORE_CLEARANCE = 2;

	/** How near either gangplank's water a raft may hug the shore, to get in and out. */
	private static final int OPEN_NEAR_BERTH = 12;

	/**
	 * A route's field, kept off the shore where it can be: two tiles of sea all round, then one,
	 * then none, whichever first joins the two ends.
	 */
	private SeaMesh.Field buildField(List<int[]> route, SeaMesh.Water water)
	{
		int[] start = route.get(0);
		SeaMesh.Field field = null;
		for (int clearance = SHORE_CLEARANCE; clearance >= 0; clearance--)
		{
			field = sea.fieldAlong(route, BAND, clearance, OPEN_NEAR_BERTH, water);
			if (field.distance(start[0], start[1]) >= 0)
			{
				return field;
			}
		}
		return field;
	}

	/**
	 * How near the far gangplank, in tiles, a raft puts its golem ashore: a player can step off a
	 * boat this far out.
	 */
	private static final int DISEMBARK_RANGE = 10;

	/**
	 * How far the raft's bow reaches ahead of the golem, in 128ths of a tile: the golem is at the
	 * helm on the stern tile, the middle a tile ahead.
	 */
	private static final int BOW_REACH = 340;

	/** Half the raft's width, near the bow. */
	private static final int BOW_HALF_WIDTH = 80;

	/**
	 * Distance fields for the crossings most recently planned, least recently used first, so a
	 * busy port does not refill one per golem. Bounded by tiles held, not count: eight fields was
	 * far fewer than sixty ports make.
	 */
	private final java.util.LinkedHashMap<Long, SeaMesh.Field> fields = new java.util.LinkedHashMap<>(64, 0.75f, true);

	/** Tiles across every cached field. Guarded by {@link #fields}. */
	private long cachedTiles;

	/** Tiles the cache may hold, about fifty megabytes, beyond the {@link #MIN_FIELDS} kept. */
	private static final long FIELD_TILE_BUDGET = 1_250_000;

	/** Fields kept whatever their size. */
	private static final int MIN_FIELDS = 8;

	private void remember(long key, SeaMesh.Field field)
	{
		synchronized (fields)
		{
			SeaMesh.Field old = fields.put(key, field);
			cachedTiles += field.size() - (old == null ? 0 : old.size());
			java.util.Iterator<SeaMesh.Field> oldest = fields.values().iterator();
			while (fields.size() > MIN_FIELDS && cachedTiles > FIELD_TILE_BUDGET && oldest.hasNext())
			{
				cachedTiles -= oldest.next().size();
				oldest.remove();
			}
		}
	}

	/*
	 * How a boat moves, from the Turning Circles plugin; not measured against a raft here.
	 *
	 *  - It faces one of sixteen compass points and turns one point, 128 of 2048, per tick, moving
	 *    as it turns, which makes a turn an arc.
	 *  - It only moves the way it faces, speed changing toward the chosen speed by a fixed
	 *    acceleration each tick, each axis rounded to a quarter tile.
	 */

	/** One compass point, in the client's 2048ths of a turn: the most a boat turns in a tick. */
	private static final int TURN_PER_TICK = 128;

	/** A raft at full sail, 1.5 tiles a tick, and at half speed, 1 tile a tick, in 128ths of a tile. */
	private static final int FULL_SPEED = 192;
	private static final int HALF_SPEED = 128;

	/** Speed gained or lost in a tick. Not in the cache, and a guess until it is measured. */
	private static final int ACCELERATION = 64;

	/** Movement snaps to quarter tiles on each axis. */
	private static final int QUARTER_TILE = 32;

	/** How far ahead a golem looks when it picks a heading, in tiles. */
	private static final int LOOK_AHEAD = 6;

	/** Ticks a golem holds a chosen heading before choosing again. */
	private static final int HOLD_MIN = 4;
	private static final int HOLD_SPREAD = 7;

	/**
	 * How strongly a golem prefers getting closer, in tiles of progress over the look ahead;
	 * lower is more decided.
	 */
	private static final double TEMPERATURE = 1.6;

	/** How much a golem dislikes each compass point of turn from where it faces. */
	private static final double TURN_RELUCTANCE = 0.35;

	/** Steps from the mooring by sea at which a golem stops wandering and comes in. */
	private static final int ARRIVE = 8;

	/** Ticks without getting nearer before a wandering golem is taken in hand. */
	private static final int STALL_TICKS = 40;

	/** Ticks spent working a raft carefully out of a tight spot before wandering again. */
	private static final int CAREFUL_TICKS = 12;

	/** Chance a crossing stops along the way, and how long it sits, in ticks. */
	private static final float REST_CHANCE = 0.5f;
	private static final int REST_MIN = 8;
	private static final int REST_SPREAD = 20;

	/** Chance a golem takes the whole crossing at half speed. */
	private static final float HALF_SPEED_CHANCE = 0.15f;

	/**
	 * A golem's own way across, sailed as a raft sails: a heading, a speed and a turn of one
	 * compass point a tick, worked out when the crossing is planned and replayed afterward, so a
	 * golem nobody is watching costs nothing on the way.
	 *
	 * <p>Every few ticks it picks a heading at random from the compass, weighted toward getting
	 * nearer and away from turning hard — nearer by sea, the distance being filled outward from
	 * the destination round every headland, so the way out of a bay is the way closer. It turns
	 * toward that heading a point a tick while moving, and where its next move would ground it,
	 * edges out a tile at a time down the travel distance, which is also how it moors.
	 *
	 * @return a tick-by-tick recording, or null if the mooring is not in the field
	 */
	private Itinerary steer(List<int[]> route, SeaMesh.Field field, Random random, int tick, WorldPoint landing)
	{
		Wake wake = new Wake();
		if (!sailLeg(route, field, random, wake, landing, false))
		{
			return null;
		}
		return Itinerary.steered(wake.xs.toArray(), wake.ys.toArray(), wake.facings.toArray(), 0, tick, landing);
	}

	/** A crossing's track so far, and how the raft is moving at the end of it. */
	private static final class Wake
	{
		final IntList xs = new IntList();
		final IntList ys = new IntList();
		final IntList facings = new IntList();
		int speed;

		boolean isEmpty()
		{
			return xs.size() == 0;
		}
	}

	/**
	 * Sails one leg of a crossing onto the end of a wake. An empty wake sets out from the route's
	 * first tile at a standstill; otherwise the leg starts where the wake ends, heading and speed
	 * and all.
	 *
	 * @param landing  the gangplank to come in to and stop by, or null to end at the route's end
	 * @param throughMouth true to end the moment the raft reaches the route's end, still under way,
	 *                     because it goes through a cave mouth there
	 * @return false if the leg's start is not in the field
	 */
	private boolean sailLeg(List<int[]> route, SeaMesh.Field field, Random random, Wake wake, WorldPoint landing,
		boolean throughMouth)
	{
		int[] start = route.get(0);
		int[] goal = route.get(route.size() - 1);
		IntList xs = wake.xs;
		IntList ys = wake.ys;
		IntList facings = wake.facings;

		int x;
		int y;
		int facing;
		int speed;
		if (wake.isEmpty())
		{
			x = centre(start[0]);
			y = centre(start[1]);
			facing = pickHeading(field, x, y, -1, random, true);
			speed = 0;
		}
		else
		{
			x = xs.get(xs.size() - 1);
			y = ys.get(ys.size() - 1);
			facing = facings.get(facings.size() - 1);
			speed = wake.speed;
		}
		int startDistance = field.distance(Math.floorDiv(x, Golem.TILE), Math.floorDiv(y, Golem.TILE));
		if (startDistance < 0)
		{
			return false;
		}
		int cruise = random.nextFloat() < HALF_SPEED_CHANCE ? HALF_SPEED : FULL_SPEED;
		int turnSign = -1;
		int wanted = facing;
		int holdUntil = 0;

		int restAt = random.nextFloat() < REST_CHANCE && startDistance > ARRIVE * 4
			? ARRIVE * 2 + random.nextInt(startDistance - ARRIVE * 3) : -1;
		int restLeft = REST_MIN + random.nextInt(REST_SPREAD);
		boolean resting = false;

		boolean docking = false;
		boolean docked = false;

		int careful = 0;
		int best = startDistance;
		int bestAt = 0;
		int wanderBudget = startDistance * 8 + 300;
		int hardLimit = startDistance * 16 + 600;

		if (wake.isEmpty())
		{
			xs.add(x);
			ys.add(y);
			facings.add(facing);
		}
		for (int t = 1; t <= hardLimit; t++)
		{
			int tileX = Math.floorDiv(x, Golem.TILE);
			int tileY = Math.floorDiv(y, Golem.TILE);
			int here = field.distance(tileX, tileY);

			if (here == 0 && x == centre(goal[0]) && y == centre(goal[1]))
			{
				docked = true;
				break;
			}
			if (throughMouth && here >= 0 && here <= 1)
			{
				// At the cave mouth, and through it. Not off the field, whose distance is -1, which
				// put rafts through the mouth from mid-sea.
				docked = true;
				break;
			}
			if (docking && speed == 0)
			{
				docked = true;
				break;
			}
			if (!docking && t > 1 && landing != null
				&& Math.max(Math.abs(tileX - landing.getX()), Math.abs(tileY - landing.getY())) <= DISEMBARK_RANGE)
			{
				// In range of the gangplank: bring the raft to a stop, and the golem steps ashore.
				docking = true;
			}
			if (here < best)
			{
				best = here;
				bestAt = t;
			}

			if (docking)
			{
				// Coasting to a stop, straight on; stopped short if the way ahead closes.
				speed = Math.max(0, speed - ACCELERATION);
				int[] v = velocity(facing, speed);
				if (speed > 0 && clear(field, x, y, x + v[0], y + v[1]) && bowClear(field, x + v[0], y + v[1], facing))
				{
					x += v[0];
					y += v[1];
				}
				else
				{
					speed = 0;
				}
			}
			else if (resting)
			{
				// Sails down and sitting. It comes to a stop first, then waits.
				if (speed > 0)
				{
					speed = Math.max(0, speed - ACCELERATION);
					int[] v = velocity(facing, speed);
					if (clear(field, x, y, x + v[0], y + v[1]) && bowClear(field, x + v[0], y + v[1], facing))
					{
						x += v[0];
						y += v[1];
					}
					else
					{
						speed = 0;
					}
				}
				else if (--restLeft <= 0)
				{
					resting = false;
					bestAt = t;
				}
			}
			else if (here < 0 || here <= ARRIVE && !throughMouth || careful > 0 || t > wanderBudget)
			{
				// Carefully: down the travel distance a tile at a time, turning on the spot when the
				// way on is over a point off the bow.
				careful = Math.max(0, careful - 1);
				if (here < 0)
				{
					// Off the field: halfway through a diagonal step, on the corner tile between two
					// in it. There is no way downhill from a tile with no distance.
					int[] back = nearestInField(field, tileX, tileY);
					if (back != null)
					{
						x = centre(back[0]);
						y = centre(back[1]);
						speed = 0;
					}
					xs.add(x);
					ys.add(y);
					facings.add(facing);
					continue;
				}
				int[] next = here == 0 ? goal : field.downhill(tileX, tileY);
				int targetX = next == null ? centre(tileX) : centre(next[0]);
				int targetY = next == null ? centre(tileY) : centre(next[1]);
				boolean centred = x == centre(tileX) && y == centre(tileY);
				if (!centred && !clear(field, x, y, targetX, targetY))
				{
					// Off the middle of the tile, and the corner is land: straighten up first. Only
					// when off the middle, the field already allowing the step from the middle.
					targetX = centre(tileX);
					targetY = centre(tileY);
				}
				int dx = targetX - x;
				int dy = targetY - y;
				int gap = Math.max(Math.abs(dx), Math.abs(dy));
				if (gap > 0)
				{
					int toward = compassPoint(dx, dy);
					int off = offBy(facing, toward);
					facing = turn(facing, toward, turnSign);
					turnSign = turning(facing, toward, turnSign);
					if (Math.abs(off) <= TURN_PER_TICK)
					{
						speed = Math.min(speed + ACCELERATION, HALF_SPEED);
						if (speed >= gap)
						{
							x = targetX;
							y = targetY;
						}
						else
						{
							x += (int) Math.round(dx * (double) speed / gap);
							y += (int) Math.round(dy * (double) speed / gap);
						}
					}
					else
					{
						speed = 0;
					}
				}
				else
				{
					speed = 0;
				}
			}
			else
			{
				if (restAt >= 0 && here <= restAt)
				{
					restAt = -1;
					resting = true;
				}

				if (t - bestAt > STALL_TICKS)
				{
					careful = CAREFUL_TICKS;
					bestAt = t;
				}

				if (t >= holdUntil || !clearAhead(field, x, y, wanted, LOOK_AHEAD / 2))
				{
					wanted = pickHeading(field, x, y, facing, random, false);
					holdUntil = t + HOLD_MIN + random.nextInt(HOLD_SPREAD);
				}
				if (wanted < 0)
				{
					// Nowhere worth heading from here: work out of it carefully.
					wanted = facing;
					careful = CAREFUL_TICKS;
					speed = 0;
				}
				else
				{
					facing = turn(facing, wanted, turnSign);
					turnSign = turning(facing, wanted, turnSign);

					int lookTiles = Math.max(2, speed * 3 / Golem.TILE);
					int[] look = velocity(facing, lookTiles * Golem.TILE);
					int target = clearAhead(field, x, y, facing, lookTiles) && bowClear(field, x + look[0], y + look[1], facing)
						? cruise : HALF_SPEED;
					speed = speed < target ? Math.min(target, speed + ACCELERATION) : Math.max(target, speed - ACCELERATION);

					int[] v = velocity(facing, speed);
					if (!clear(field, x, y, x + v[0], y + v[1]) || !bowClear(field, x + v[0], y + v[1], facing))
					{
						speed = Math.min(speed, HALF_SPEED);
						v = velocity(facing, speed);
					}
					if (clear(field, x, y, x + v[0], y + v[1]) && bowClear(field, x + v[0], y + v[1], facing))
					{
						x += v[0];
						y += v[1];
					}
					else
					{
						// About to ground. Stop, and pick the way out.
						speed = 0;
						careful = CAREFUL_TICKS;
					}
				}
			}

			xs.add(x);
			ys.add(y);
			facings.add(facing);
		}

		int last = xs.size() - 1;
		if (!docked && (xs.get(last) != centre(goal[0]) || ys.get(last) != centre(goal[1])))
		{
			// Out of time, which careful sailing should never allow. Finish at the mooring rather
			// than not at all.
			log.debug("Crossing ran out of ticks {} tiles from its mooring", field.distance(
				Math.floorDiv(xs.get(last), Golem.TILE), Math.floorDiv(ys.get(last), Golem.TILE)));
			xs.add(centre(goal[0]));
			ys.add(centre(goal[1]));
			facings.add(facings.get(last));
			speed = 0;
		}
		wake.speed = speed;
		return true;
	}

	// ------------------------------------------------------------------ the cave

	/**
	 * True if a dock is on the sailable cave's lake: its berth is on the water joined to the
	 * cave mouth underground.
	 */
	private boolean isCaveDock(SailingDocks.Dock dock)
	{
		if (dock.isOnOpenSea())
		{
			return false;
		}
		int lake = sea.componentAt(GolemContent.CAVE_LAKE_MOUTH_X, GolemContent.CAVE_LAKE_MOUTH_Y);
		WorldPoint berth = dock.getBerth();
		return lake != 0 && berth.getPlane() == 0 && sea.componentAt(berth.getX(), berth.getY()) == lake;
	}

	/**
	 * A crossing to or from the cave dock, sailed as the game sails it: a boat there sails its
	 * lake to the cave mouth and is put out at the mouth on the island's coast, on the real sea.
	 * The sea leg joins the shipped routes at Wyrmscraig's own dock, the nearest port to the
	 * mouth; only the short way between the two is searched live, once.
	 */
	private Itinerary throughCave(SailingDocks.Dock from, SailingDocks.Dock to, boolean fromCave, TransportMemory memory,
		int tick, Random random, RoamContext context)
	{
		SailingDocks.Dock caveDock = fromCave ? from : to;
		SailingDocks.Dock seaDock = fromCave ? to : from;
		SailingDocks.Dock gateway = nearestSeaDock(GolemContent.CAVE_SEA_MOUTH_X, GolemContent.CAVE_SEA_MOUTH_Y);
		if (gateway == null)
		{
			return null;
		}

		WorldPoint seaMouth = new WorldPoint(GolemContent.CAVE_SEA_MOUTH_X, GolemContent.CAVE_SEA_MOUTH_Y, 0);
		int[] lakeMouth = {GolemContent.CAVE_LAKE_MOUTH_X, GolemContent.CAVE_LAKE_MOUTH_Y};
		int[] caveBerth = {caveDock.getBerth().getX(), caveDock.getBerth().getY()};

		// The sea between the cave mouth and the sea dock, by way of the gateway.
		List<int[]> mouthToGateway = searchedRoute(seaMouth, gateway.getMooring(), context);
		if (mouthToGateway == null)
		{
			return null;
		}
		List<int[]> seaCourse = new java.util.ArrayList<>(mouthToGateway);
		if (seaDock.getRowId() != gateway.getRowId())
		{
			List<int[]> onward = searchedRoute(gateway.getMooring(), seaDock.getMooring(), context);
			if (onward == null)
			{
				return null;
			}
			seaCourse.addAll(onward.subList(1, onward.size()));
		}
		seaCourse.add(new int[]{seaDock.getBerth().getX(), seaDock.getBerth().getY()});
		if (!fromCave)
		{
			java.util.Collections.reverse(seaCourse);
		}

		List<int[]> lakeCourse = new java.util.ArrayList<>();
		lakeCourse.add(fromCave ? caveBerth : lakeMouth);
		lakeCourse.add(fromCave ? lakeMouth : caveBerth);

		SeaMesh.Water lake = sea.lake(sea.componentAt(lakeMouth[0], lakeMouth[1]));
		SeaMesh.Field lakeField = fieldFor(lakeCourse, lake);
		SeaMesh.Field seaField = fieldFor(seaCourse);
		if (lakeField == null || seaField == null)
		{
			// Being built. The golem waits for it.
			notReady = true;
			return null;
		}

		Wake wake = new Wake();
		if (fromCave)
		{
			if (!sailLeg(lakeCourse, lakeField, random, wake, null, true))
			{
				return null;
			}
			putThrough(wake, seaMouth.getX(), seaMouth.getY(), GolemContent.CAVE_SEA_MOUTH_OUTWARD);
			if (!sailLeg(seaCourse, seaField, random, wake, to.getShore(), false))
			{
				return null;
			}
		}
		else
		{
			if (!sailLeg(seaCourse, seaField, random, wake, null, true))
			{
				return null;
			}
			putThrough(wake, lakeMouth[0], lakeMouth[1], GolemContent.CAVE_LAKE_MOUTH_INWARD);
			if (!sailLeg(lakeCourse, lakeField, random, wake, to.getShore(), false))
			{
				return null;
			}
		}

		memory.setBlockedPort(from.getRowId());
		log.debug("Golem sailing {} -> {} through the cave", from.getName(), to.getName());
		return Itinerary.steered(wake.xs.toArray(), wake.ys.toArray(), wake.facings.toArray(), 0, tick, to.getShore());
	}

	/** Moves the raft through a cave mouth: next tick it is at the other, facing out, under way. */
	private static void putThrough(Wake wake, int tileX, int tileY, int facing)
	{
		wake.xs.add(centre(tileX));
		wake.ys.add(centre(tileY));
		wake.facings.add(facing);
	}

	/** The open-sea dock whose mooring is nearest a tile. */
	private SailingDocks.Dock nearestSeaDock(int x, int y)
	{
		SailingDocks.Dock nearest = null;
		int best = Integer.MAX_VALUE;
		for (SailingDocks.Dock dock : docks.getDocks())
		{
			if (!dock.isOnOpenSea())
			{
				continue;
			}
			int distance = Math.max(Math.abs(dock.getMooring().getX() - x), Math.abs(dock.getMooring().getY() - y));
			if (distance < best)
			{
				best = distance;
				nearest = dock;
			}
		}
		return nearest;
	}

	/** A sea route, searched now if never before and this frame's search is free; else null. */
	private List<int[]> searchedRoute(WorldPoint from, WorldPoint to, RoamContext context)
	{
		boolean searched = sea.isSearched(from, to);
		if (!searched && context != null && !context.isMaySearchSea())
		{
			notReady = true;
			return null;
		}
		List<int[]> route = sea.route(from, to);
		if (!searched && context != null)
		{
			context.spendSeaSearch();
		}
		return route;
	}


	/**
	 * A compass point to steer for, weighted toward progress by sea and against turning.
	 *
	 * @param facing   the way the raft faces now, or -1 if it has not set off
	 * @param decisive take the best rather than drawing at random
	 * @return a compass point, or -1 if no direction is clear
	 */
	private int pickHeading(SeaMesh.Field field, int x, int y, int facing, Random random, boolean decisive)
	{
		int here = field.distance(Math.floorDiv(x, Golem.TILE), Math.floorDiv(y, Golem.TILE));
		double[] weights = new double[16];
		double sum = 0;
		int bestPoint = -1;
		for (int h = 0; h < 16; h++)
		{
			int point = h * TURN_PER_TICK;
			int[] ahead = velocity(point, LOOK_AHEAD * Golem.TILE);
			int tx = x + ahead[0];
			int ty = y + ahead[1];
			int there = field.distance(Math.floorDiv(tx, Golem.TILE), Math.floorDiv(ty, Golem.TILE));
			if (there < 0 || !clear(field, x, y, tx, ty) || !bowClear(field, tx, ty, point))
			{
				continue;
			}
			double turn = facing < 0 ? 0 : Math.abs(offBy(facing, point)) / (double) TURN_PER_TICK;
			weights[h] = Math.exp((here - there) / TEMPERATURE - turn * TURN_RELUCTANCE);
			sum += weights[h];
			if (bestPoint < 0 || weights[h] > weights[bestPoint / TURN_PER_TICK])
			{
				bestPoint = point;
			}
		}
		if (sum <= 0)
		{
			if (facing < 0)
			{
				// Setting off from a mooring with no open water in sight: face the way on.
				int[] next = field.downhill(Math.floorDiv(x, Golem.TILE), Math.floorDiv(y, Golem.TILE));
				return next == null ? 0 : compassPoint(centre(next[0]) - x, centre(next[1]) - y);
			}
			return -1;
		}
		if (decisive)
		{
			return bestPoint;
		}
		double pick = random.nextDouble() * sum;
		for (int h = 0; h < 16; h++)
		{
			pick -= weights[h];
			if (weights[h] > 0 && pick <= 0)
			{
				return h * TURN_PER_TICK;
			}
		}
		return bestPoint;
	}

	/**
	 * True if the raft's bow is over open water with the golem here, facing this way: the tip and
	 * both sides behind it.
	 */
	private static boolean bowClear(SeaMesh.Field field, int x, int y, int facing)
	{
		int[] tip = velocity(facing, BOW_REACH);
		int[] shoulder = velocity(facing, BOW_REACH * 3 / 4);
		int[] side = velocity((facing + 512) & 2047, BOW_HALF_WIDTH);
		return water(field, x + tip[0], y + tip[1])
			&& water(field, x + shoulder[0] + side[0], y + shoulder[1] + side[1])
			&& water(field, x + shoulder[0] - side[0], y + shoulder[1] - side[1]);
	}

	private static boolean water(SeaMesh.Field field, int fineX, int fineY)
	{
		return field.isWater(Math.floorDiv(fineX, Golem.TILE), Math.floorDiv(fineY, Golem.TILE));
	}

	/** The neighbouring tile in the field nearest the destination, or null if none is. */
	private static int[] nearestInField(SeaMesh.Field field, int tileX, int tileY)
	{
		int[] best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				int distance = field.distance(tileX + dx, tileY + dy);
				if (distance >= 0 && distance < bestDistance)
				{
					bestDistance = distance;
					best = new int[]{tileX + dx, tileY + dy};
				}
			}
		}
		return best;
	}

	/** One tick's movement at this heading and speed, each axis on a quarter tile. */
	static int[] velocity(int facing, int speed)
	{
		// 0 south, 512 west, 1024 north, 1536 east.
		double angle = (facing - 1024) * Math.PI / 1024;
		return new int[]{
			quarter(Math.sin(angle) * speed),
			quarter(Math.cos(angle) * speed),
		};
	}

	private static int quarter(double fine)
	{
		return (int) (Math.signum(fine) * Math.round(Math.abs(fine) / QUARTER_TILE)) * QUARTER_TILE;
	}

	/** The compass point nearest a direction. */
	static int compassPoint(int dx, int dy)
	{
		int exact = (int) (1024 + Math.round(Math.atan2(dx, dy) / Math.PI * 1024)) & 2047;
		return ((exact + TURN_PER_TICK / 2) / TURN_PER_TICK * TURN_PER_TICK) & 2047;
	}

	/** Signed turn from one heading to another, the short way; exactly about is -1024. */
	private static int offBy(int from, int to)
	{
		return ((to - from + 1024) & 2047) - 1024;
	}

	/**
	 * A tick's turn toward a heading. A turn exactly about keeps going the way the raft was
	 * already turning, rather than flipping between the two ways.
	 */
	private static int turn(int facing, int toward, int turnSign)
	{
		int off = offBy(facing, toward);
		if (off == 0)
		{
			return facing;
		}
		int sign = off == -1024 ? turnSign : Integer.signum(off);
		return (facing + sign * Math.min(Math.abs(off), TURN_PER_TICK)) & 2047;
	}

	/** Which way the raft is turning after a tick's turn, kept when it is not turning. */
	private static int turning(int facing, int toward, int turnSign)
	{
		int off = offBy(facing, toward);
		return off == 0 || off == -1024 ? turnSign : Integer.signum(off);
	}

	/** True if the raft could move straight between these points without leaving the field. */
	private static boolean clear(SeaMesh.Field field, int fromX, int fromY, int toX, int toY)
	{
		int span = Math.max(Math.abs(toX - fromX), Math.abs(toY - fromY));
		int samples = Math.max(1, span / (QUARTER_TILE / 2));
		for (int s = 0; s <= samples; s++)
		{
			int px = fromX + (int) Math.round((toX - fromX) * (double) s / samples);
			int py = fromY + (int) Math.round((toY - fromY) * (double) s / samples);
			if (field.distance(Math.floorDiv(px, Golem.TILE), Math.floorDiv(py, Golem.TILE)) < 0)
			{
				return false;
			}
		}
		return true;
	}

	/** True if the water is open for this many tiles along a heading. */
	private static boolean clearAhead(SeaMesh.Field field, int x, int y, int facing, int tiles)
	{
		if (facing < 0)
		{
			return false;
		}
		int[] ahead = velocity(facing, tiles * Golem.TILE);
		return clear(field, x, y, x + ahead[0], y + ahead[1]);
	}

	private static int centre(int tile)
	{
		return tile * Golem.TILE + Golem.TILE / 2;
	}

	/** A growable int array, so a few hundred ticks are not a few hundred boxes. */
	private static final class IntList
	{
		private int[] values = new int[256];
		private int size;

		void add(int value)
		{
			if (size == values.length)
			{
				values = java.util.Arrays.copyOf(values, size * 2);
			}
			values[size++] = value;
		}

		int get(int index)
		{
			return values[index];
		}

		int size()
		{
			return size;
		}

		int[] toArray()
		{
			return java.util.Arrays.copyOf(values, size);
		}
	}

	/**
	 * A crossing with no drawn route: the golem leaves one dock and arrives at the other, timed by
	 * straight-line distance, floored so a short one is not instant.
	 */
	private Itinerary passage(SailingDocks.Dock from, SailingDocks.Dock to, WorldPoint at, int tick)
	{
		WorldPoint a = from.getMooring();
		WorldPoint b = to.getMooring();

		// Underground maps sit thousands of tiles from the surface above them, so the separation
		// between a cave dock and a sea one is meaningless. Capped, not used directly.
		int spread = Math.min(400,
			Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()));

		// Held at the quayside and then put ashore, not walked: as a two-point route the golem
		// slid six thousand tiles from the cave to the surface.
		return Itinerary.passage(at != null ? at : from.getShore(), to.getShore(), tick, Math.max(50, spread));
	}

	/**
	 * The dock a golem standing here could embark from, or null. Generous about distance, the
	 * mooring being water beside the dock and the golem on land.
	 */
	SailingDocks.Dock dockAt(WorldPoint at, int radius)
	{
		for (SailingDocks.Dock dock : docks.getDocks())
		{
			// Measured to the quayside, not the buoy out on the water, which had golems boarding
			// from wherever the radius caught them.
			WorldPoint shore = dock.getShore();
			if (shore.getPlane() == at.getPlane()
				&& Math.abs(shore.getX() - at.getX()) <= radius
				&& Math.abs(shore.getY() - at.getY()) <= radius)
			{
				return dock;
			}
		}
		return null;
	}
}
