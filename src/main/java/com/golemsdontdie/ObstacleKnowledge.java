package com.golemsdontdie;

import java.util.*;
import java.util.function.*;
import javax.inject.*;
import lombok.extern.slf4j.*;
import static com.golemsdontdie.RouteGeometry.span;

/**
 * What is actually known about how each obstacle is traversed, and what golems may use.
 *
 * <p>The shipped guess at each obstacle's animation comes from the menu text and is <b>often
 * wrong</b>: a basalt stepping stone is named "stepping stone" and there is a
 * {@code HUMAN_STEPPINGSTONEJUMP}, but the game plays {@code HUMAN_SPOT_JUMP}.
 *
 * <p>So two questions are kept apart: may a golem go through here, nearly always yes, and do we
 * know what it looks like, where a no means it plays nothing, as at a door. Locking one direction
 * of a two-way transport would strand golems upstairs.
 *
 * <p>{@link ObstacleObserver} watches the player, and two consistent sightings unlock an obstacle
 * permanently - two rather than one because a single animation after a click proves nothing.
 */
@Slf4j
@Singleton
class ObstacleKnowledge
{
	/** Consistent sightings before an obstacle is trusted enough for a golem to use. */
	private static final int SIGHTINGS_TO_UNLOCK = 2;

	/** Where learned obstacles are kept between sessions. */
	static final String LEARNED_KEY = "learnedObstacles";

	/** Where the individual obstacles the player has personally used are kept. */
	static final String CONFIRMED_KEY = "confirmedObstacles";

	/** Where routes learned by watching the player are kept. */
	static final String ROUTES_KEY = "learnedRoutes";

	/** Where recorded motions are kept. */
	static final String CURVES_KEY = "learnedCurves";

	/** Where the line each obstacle moves the player along is kept. */
	static final String LINES_KEY = "learnedLines";

	/**
	 * Tiles either side of a transport's origin that still count as the same obstacle. A sighting
	 * records where the player <i>stood</i>, the index where the object <i>is</i>, and on
	 * Wyrmscraig's rocks those are three apart. Measured from the nearest tile of the object's
	 * footprint, not its south-west corner, which put a three-tile cave nowhere near itself.
	 */
	private static final int CONFIRM_TOLERANCE = 3;

	/**
	 * What the player has been seen doing, by object id. Outranks everything shipped: the live
	 * game on this client beats anything measured elsewhere months ago.
	 */
	private final Map<Integer, Learned> learned = new LinkedHashMap<>();

	/**
	 * The individual obstacles the player has personally used. Kept apart from {@link #learned}
	 * because what a <i>kind</i> of object does applies everywhere, while a <i>particular</i>
	 * ladder somebody stood on only gets coloured in.
	 */
	private final Map<Integer, List<int[]>> confirmed = new LinkedHashMap<>();

	/**
	 * Where an obstacle actually took the player, by the tile they used it from: the other half of
	 * learning, since an animation alone cannot make an obstacle usable and for most of the game
	 * nothing says where it comes out. Keyed by object <i>and</i> origin, one ladder id being two
	 * thousand obstacles that each come out somewhere different.
	 */
	//
	// Insertion-ordered, as every map here is: a HashMap's order shifts as keys are added, and
	// routes become transports in that order, so each sighting renumbered every learned transport.
	private final Map<String, List<int[]>> routes = new LinkedHashMap<>();

	/**
	 * The recent recordings of each kind of obstacle, by object rather than by place: what a
	 * traversal looks like is a property of the obstacle, so one recording serves every copy.
	 * Several are kept, since keeping only the latest let one bad traversal replace good ones; the
	 * one performed is the one the others most agree with, see {@link #consensus}.
	 */
	private final Map<Integer, List<MotionCurve>> curves = new LinkedHashMap<>();

	/**
	 * The line each obstacle moves the player along, as counts of each direction seen - "0,1" for
	 * a stile crossed north or south. Counted, not taken from the first sighting, so one odd
	 * traversal cannot fix a line for good; see {@link RouteGeometry}.
	 */
	private final Map<Integer, Map<String, Integer>> lines = new LinkedHashMap<>();

	/**
	 * Which saved routes may be offered at all, as {objectId, fromX, fromY, fromPlane, toX, toY,
	 * toPlane}. Set by the plugin, so that what was learned before a rule existed is held to it:
	 * trees recorded as obstacles, routes in the coordinates of a vanished instance.
	 */
	@lombok.Setter
	private Predicate<int[]> routeFilter;

	/** The recording performed for each object, chosen from {@link #curves}. */
	private final Map<Integer, MotionCurve> chosenCurves = new HashMap<>();

	/**
	 * Recent {ticks, delay, span} per object, for the same reason. Only in memory: the saved value
	 * is itself a previous session's consensus, and seeds the history when next added to.
	 */
	private final Map<Integer, List<int[]>> timings = new HashMap<>();

	/** Recordings kept per object. Odd, so there is always a middle. */
	private static final int CURVES_KEPT = 5;

	/**
	 * How far two arrivals can differ and still count as the same route. Small, because a stepping
	 * stone mid-line leads two ways and those must stay apart.
	 */
	private static final int DESTINATION_TOLERANCE = 2;

	/** One obstacle as observed here, and how sure of it we are. */
	static final class Learned
	{
		final int[] clips;
		final int ticks;

		/** Cycles of stillness before the movement, and how long the movement lasts. */
		final int moveDelay;
		final int moveSpan;

		int sightings;

		Learned(int[] clips, int ticks, int sightings, int moveDelay, int moveSpan)
		{
			this.clips = clips;
			this.ticks = ticks;
			this.sightings = sightings;
			this.moveDelay = moveDelay;
			this.moveSpan = moveSpan;
		}

		boolean unlocked()
		{
			return sightings >= SIGHTINGS_TO_UNLOCK;
		}
	}

	// ------------------------------------------------------------------- the policy

	/**
	 * True if a golem may use this transport. The shipped defaults below are the archetypes whose
	 * animation has been measured or corroborated well enough to act on; the rest stay locked.
	 */
	boolean isUnlocked(GolemTransport transport)
	{
		if (isLearned(transport.getObjectId()))
		{
			return true;
		}
		if (MeasuredShortcuts.animationFor(transport.getObjectId()) != -1)
		{
			// Somebody stood in front of this exact object and watched it.
			return true;
		}
		return shippedConfidence(transport);
	}

	/**
	 * Whether a golem may traverse this at all, on the shipped data alone.
	 *
	 * <p>Locked means routing around the obstacle as though it were not there, reserved for cases
	 * where using it without the animation would look broken rather than plain: jumps over two
	 * tiles, only the short hop having been measured, and balances, tightropes, squeezes and
	 * stiles, which have plausible names and no measurements. Ladders going down are deliberately
	 * not locked despite the disputed animation, since blocking one direction of a two-way
	 * transport strands a golem upstairs; it traverses silently instead, see {@link #clipsFor}.
	 */
	private boolean shippedConfidence(GolemTransport transport)
	{
		switch (transport.getArchetype())
		{
			case GolemTransport.ARCHETYPE_JUMP:
			{
				int span = transport.travelDistance();
				return span >= 0 && span <= 2;
			}

			case GolemTransport.ARCHETYPE_BALANCE:
			case GolemTransport.ARCHETYPE_TIGHTROPE:
			case GolemTransport.ARCHETYPE_SQUEEZE:
			case GolemTransport.ARCHETYPE_STILE:
				return false;

			case GolemTransport.ARCHETYPE_DOOR:
				// A golem cannot open a door: that changes a real object every other player can see.
				// The tables carry door rows because a *player* can open them; walking through an
				// open doorway needs no row at all.
				return false;

			default:
				// Gangplanks, ditches, climbs, ladders and the great mass of unclassified rows:
				// measured, corroborated, or animating nothing.
				return true;
		}
	}

	/**
	 * True if the shipped archetype's animation is trustworthy enough to play. Separate from
	 * {@link #shippedConfidence}: not knowing what an obstacle looks like means playing nothing
	 * rather than inventing an animation.
	 */
	private boolean shippedAnimationKnown(GolemTransport transport)
	{
		switch (transport.getArchetype())
		{
			case GolemTransport.ARCHETYPE_NONE:
			case GolemTransport.ARCHETYPE_DOOR:
			case GolemTransport.ARCHETYPE_GANGPLANK:
				// Nothing is played on the golem, so nothing can be played wrongly.
				return true;

			case GolemTransport.ARCHETYPE_DITCH:
			case GolemTransport.ARCHETYPE_CLIMB_OVER:
			case GolemTransport.ARCHETYPE_CLIMB:
				return true;

			case GolemTransport.ARCHETYPE_LADDER:
				// Up is 828 and agreed everywhere. Down is 833 here, never measured, and three
				// server reimplementations say 827, so it plays silently until somebody is seen.
				return transport.getToPlane() >= transport.getFromPlane();

			case GolemTransport.ARCHETYPE_JUMP:
			{
				int span = transport.travelDistance();
				return span >= 0 && span <= 2;
			}

			default:
				return false;
		}
	}

	private boolean isConfirmedAt(int objectId, int x, int y, int plane,
		int sizeX, int sizeY)
	{
		List<int[]> places = confirmed.get(objectId);
		if (places == null)
		{
			return false;
		}
		for (int[] at : places)
		{
			if (at[2] != plane)
			{
				continue;
			}
			// Distance to the nearest tile the object occupies, not to its corner.
			int dx = Math.max(Math.max(x - at[0], at[0] - (x + sizeX - 1)), 0);
			int dy = Math.max(Math.max(y - at[1], at[1] - (y + sizeY - 1)), 0);
			if (dx <= CONFIRM_TOLERANCE && dy <= CONFIRM_TOLERANCE)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Records where an obstacle led, when that is worth anything. Instances are refused: rebuilt
	 * with fresh coordinates each time, so a destination inside one describes a vanished room.
	 */
	private void noteRoute(ObstacleSighting sighting)
	{
		if (sighting.instance || !sighting.moved())
		{
			return;
		}

		String key = routeKey(sighting.objectId, sighting.fromX, sighting.fromY,
			sighting.fromPlane);
		List<int[]> here = routes.computeIfAbsent(key, k -> new ArrayList<>());

		int flags = sighting.toInstance && !sighting.fromInstance ? GolemTransport.INTO_INSTANCE
			: sighting.fromInstance && !sighting.toInstance ? GolemTransport.OUT_OF_INSTANCE : 0;

		for (int[] seen : here)
		{
			// Near enough is the same route: a rock climb landed on 2555 one time and 2556 the next,
			// and demanding the same tile reset the count after every use.
			if (seen[2] == sighting.toPlane
				&& Math.abs(seen[0] - sighting.toX) <= DESTINATION_TOLERANCE
				&& Math.abs(seen[1] - sighting.toY) <= DESTINATION_TOLERANCE)
			{
				// The same route, and the newest sighting's end is kept: keeping the first left the
				// way out of the stile's pen saved ending on the stile itself, so golems went in and
				// never came out.
				seen[0] = sighting.toX;
				seen[1] = sighting.toY;
				seen[3]++;
				seen[4] = flags;
				return;
			}
		}

		// Genuinely somewhere else, so a second route from this tile rather than a contradiction
		// of the first: the middle of a line of stepping stones really does lead two ways.
		here.add(new int[]{sighting.toX, sighting.toY, sighting.toPlane, 1, flags});
	}

	/** Sightings of one route past which another teaches nothing, and it is no longer recorded. */
	static final int MOST_SIGHTINGS = 15;

	/**
	 * True if this route has been seen more than {@link #MOST_SIGHTINGS} times already. Past that a
	 * sighting only rewrote what was known, and on an agility course that was everything golems had
	 * learned, saved again at every obstacle.
	 */
	boolean wellKnown(ObstacleSighting sighting)
	{
		return sightingsFor(sighting.objectId, sighting.fromX, sighting.fromY, sighting.fromPlane,
			new int[]{sighting.toX, sighting.toY, sighting.toPlane}) > MOST_SIGHTINGS;
	}

	/** How far apart two origins of the same move can be and still be one obstacle. */
	private static final int NEIGHBOUR_ORIGIN = 2;

	/**
	 * Sightings behind a route, counting its neighbours. A church pew is climbed from whichever end
	 * is nearer, so its routes, the same hop a tile apart, were each seen once and never reached
	 * two.
	 *
	 * @param to {toX, toY, toPlane, count, flags}
	 */
	private int sightingsFor(int objectId, int fromX, int fromY, int fromPlane, int[] to)
	{
		int total = 0;
		for (Map.Entry<String, List<int[]>> e : routes.entrySet())
		{
			String[] key = e.getKey().split(",");
			int otherX = Integer.parseInt(key[1]);
			int otherY = Integer.parseInt(key[2]);
			if (Integer.parseInt(key[0]) != objectId || Integer.parseInt(key[3]) != fromPlane
				|| Math.abs(otherX - fromX) > NEIGHBOUR_ORIGIN || Math.abs(otherY - fromY) > NEIGHBOUR_ORIGIN)
			{
				continue;
			}
			for (int[] other : e.getValue())
			{
				if (other[2] == to[2] && other[0] - otherX == to[0] - fromX && other[1] - otherY == to[1] - fromY)
				{
					total += other[3];
				}
			}
		}
		return total;
	}

	/**
	 * A route's flags, taken with its neighbours' as its sightings are: the Mad Angel's pew was
	 * learned from two tiles a row apart, and the route that did not know it led into the instance
	 * was unlocked without the flag.
	 */
	private int flagsFor(int objectId, int fromX, int fromY, int fromPlane, int[] to)
	{
		int flags = to[4];
		for (Map.Entry<String, List<int[]>> e : routes.entrySet())
		{
			String[] key = e.getKey().split(",");
			int otherX = Integer.parseInt(key[1]);
			int otherY = Integer.parseInt(key[2]);
			if (Integer.parseInt(key[0]) != objectId || Integer.parseInt(key[3]) != fromPlane
				|| Math.abs(otherX - fromX) > NEIGHBOUR_ORIGIN || Math.abs(otherY - fromY) > NEIGHBOUR_ORIGIN)
			{
				continue;
			}
			for (int[] other : e.getValue())
			{
				if (other[2] == to[2] && other[0] - otherX == to[0] - fromX && other[1] - otherY == to[1] - fromY)
				{
					flags |= other[4];
				}
			}
		}
		return flags;
	}

	private static String routeKey(int objectId, int x, int y, int plane)
	{
		return objectId + "," + x + "," + y + "," + plane;
	}

	/**
	 * Every route seen often enough to act on, as {objectId, fromX, fromY, fromPlane, toX, toY,
	 * toPlane, ticks}. The ticks matter as much as the coordinates: a duration of one compressed
	 * a rockslide's four ticks into one tick of gliding.
	 */
	List<int[]> learnedRoutes()
	{
		// Every tile a learned route along its obstacle's line starts or ends on; see jumpsOver.
		// Routes off the line are no evidence, an old diagonal stile route ending beside the right
		// one.
		Set<Long> ends = new HashSet<>();
		// Each key read as numbers once, not once per use.
		List<int[]> keys = new ArrayList<>(routes.size());
		for (String key : routes.keySet())
		{
			String[] parts = key.split(",");
			keys.add(new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
				Integer.parseInt(parts[3])});
		}
		int entry = 0;
		for (Map.Entry<String, List<int[]>> e : routes.entrySet())
		{
			int[] from = keys.get(entry++);
			int objectId = from[0];
			int fromX = from[1];
			int fromY = from[2];
			int fromPlane = from[3];
			for (int[] to : e.getValue())
			{
				if (followsObjectLine(objectId, fromX, fromY, fromPlane, to))
				{
					ends.add(RoamContext.tileKey(fromX, fromY, fromPlane));
					ends.add(RoamContext.tileKey(to[0], to[1], to[2]));
				}
			}
		}

		List<int[]> out = new ArrayList<>();
		entry = 0;
		for (Map.Entry<String, List<int[]>> e : routes.entrySet())
		{
			int[] from = keys.get(entry++);
			for (int[] to : e.getValue())
			{
				if (jumpsOver(from[1], from[2], from[3], to, ends))
				{
					continue;
				}
				if (to[3] < SIGHTINGS_TO_UNLOCK && sightingsFor(from[0], from[1], from[2], from[3], to) < SIGHTINGS_TO_UNLOCK)
				{
					continue;
				}
				// A route saved before its obstacle's line was known is held to it now, so the
				// diagonal stile routes already in a profile stop being used once the stile is
				// crossed, without anybody deleting them.
				if (!followsObjectLine(from[0], from[1], from[2], from[3], to))
				{
					continue;
				}
				Learned known = learned.get(from[0]);
				int[] route = {
					from[0], from[1], from[2], from[3],
					to[0], to[1], to[2],
					known == null ? 1 : Math.max(1, known.ticks),
					flagsFor(from[0], from[1], from[2], from[3], to),
				};
				if (routeFilter == null || routeFilter.test(route))
				{
					out.add(route);
				}
			}
		}
		return out;
	}

	/**
	 * True if a route leaps over a tile some other learned route starts or ends on - a stepping
	 * stone. A hop recorded wrongly came out two tiles long, over the stone the true hops use;
	 * held out rather than deleted, the next correct crossing overwriting it in place.
	 */
	private static boolean jumpsOver(int fromX, int fromY, int plane, int[] to, Set<Long> ends)
	{
		int dx = to[0] - fromX;
		int dy = to[1] - fromY;
		int span = span(dx, dy);
		if (to[2] != plane || span < 2 || dx != 0 && dy != 0 && Math.abs(dx) != Math.abs(dy))
		{
			return false;
		}
		for (int step = 1; step < span; step++)
		{
			if (ends.contains(RoamContext.tileKey(fromX + Integer.signum(dx) * step, fromY + Integer.signum(dy) * step, plane)))
			{
				return true;
			}
		}
		return false;
	}

	private void noteLine(ObstacleSighting sighting)
	{
		if (sighting.fromPlane != sighting.toPlane || !RouteGeometry.local(sighting.lineX, sighting.lineY))
		{
			return;
		}
		int[] direction = RouteGeometry.canonical(sighting.lineX, sighting.lineY);
		lines.computeIfAbsent(sighting.objectId, k -> new LinkedHashMap<>())
			.merge(direction[0] + "," + direction[1], 1, Integer::sum);
	}

	/** The line this obstacle most often moves players along, or null if none is known. */
	int[] lineOf(int objectId)
	{
		Map<String, Integer> seen = lines.get(objectId);
		if (seen == null || seen.isEmpty())
		{
			return null;
		}
		String best = null;
		for (Map.Entry<String, Integer> e : seen.entrySet())
		{
			if (best == null || e.getValue() > seen.get(best))
			{
				best = e.getKey();
			}
		}
		String[] parts = best.split(",");
		return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
	}

	/** Whether this sighting's route lies along its own movement and its obstacle's line. */
	private boolean followsLine(ObstacleSighting sighting)
	{
		if (sighting.fromPlane != sighting.toPlane)
		{
			return true;
		}
		int routeX = sighting.toX - sighting.fromX;
		int routeY = sighting.toY - sighting.fromY;
		int[] line = lineOf(sighting.objectId);
		return RouteGeometry.follows(routeX, routeY, sighting.lineX, sighting.lineY)
			&& (line == null || RouteGeometry.follows(routeX, routeY, line[0], line[1]));
	}

	/** Whether a stored route, {toX, toY, toPlane, count}, lies along its obstacle's line. */
	private boolean followsObjectLine(int objectId, int fromX, int fromY, int fromPlane, int[] to)
	{
		int[] line = lineOf(objectId);
		return line == null || to[2] != fromPlane
			|| RouteGeometry.follows(to[0] - fromX, to[1] - fromY, line[0], line[1]);
	}

	/** {@code objectId=x,y,count|x,y,count;...} */
	String serialiseLines()
	{
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, Map<String, Integer>> e : lines.entrySet())
		{
			if (sb.length() > 0)
			{
				sb.append(';');
			}
			sb.append(e.getKey()).append('=');
			boolean first = true;
			for (Map.Entry<String, Integer> direction : e.getValue().entrySet())
			{
				sb.append(first ? "" : "|").append(direction.getKey()).append(',')
					.append(direction.getValue());
				first = false;
			}
		}
		return sb.toString();
	}

	void deserialiseLines(String saved)
	{
		lines.clear();
		if (saved == null || saved.isEmpty())
		{
			return;
		}
		for (String entry : saved.split(";"))
		{
			try
			{
				int split = entry.indexOf('=');
				Map<String, Integer> seen = new LinkedHashMap<>();
				for (String direction : entry.substring(split + 1).split("\\|"))
				{
					String[] parts = direction.split(",");
					seen.put(parts[0] + "," + parts[1], Integer.parseInt(parts[2]));
				}
				lines.put(Integer.parseInt(entry.substring(0, split)), seen);
			}
			catch (RuntimeException e)
			{
				log.debug("Skipping unreadable line: {}", entry);
			}
		}
	}

	String serialiseCurves()
	{
		// One entry per recording, oldest first, so an object with several repeats its id. Files
		// written when only one was kept read back unchanged.
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, List<MotionCurve>> e : curves.entrySet())
		{
			for (MotionCurve curve : e.getValue())
			{
				if (sb.length() > 0)
				{
					sb.append(';');
				}
				sb.append(e.getKey()).append('=').append(curve.serialise());
			}
		}
		return sb.toString();
	}

	void deserialiseCurves(String saved)
	{
		curves.clear();
		chosenCurves.clear();
		if (saved == null || saved.isEmpty())
		{
			return;
		}
		for (String entry : saved.split(";"))
		{
			int split = entry.indexOf('=');
			if (split <= 0)
			{
				continue;
			}
			try
			{
				MotionCurve curve = MotionCurve.parse(entry.substring(split + 1));
				if (curve != null)
				{
					addCurve(Integer.parseInt(entry.substring(0, split)), curve);
				}
			}
			catch (RuntimeException e)
			{
				log.debug("Skipping unreadable curve: {}", entry.substring(0, split));
			}
		}
		log.debug("Restored recorded motions for {} obstacles", curves.size());
	}

	private void addCurve(int objectId, MotionCurve curve)
	{
		List<MotionCurve> kept = curves.computeIfAbsent(objectId, k -> new ArrayList<>());
		// A recording that knows which way the player faced supersedes every one that does not,
		// or the older ones outvote it and the golem keeps facing forwards down the rockslide.
		if (curve.hasFacing())
		{
			kept.removeIf(old -> !old.hasFacing());
		}
		// Likewise keyframes: an older recording without them plays the clip at its authored
		// speed and hops before the jump.
		if (curve.hasKeyframes())
		{
			kept.removeIf(old -> !old.hasKeyframes());
		}
		kept.add(curve);
		while (kept.size() > CURVES_KEPT)
		{
			kept.remove(0);
		}
		chosenCurves.put(objectId, consensus(kept));
	}

	/**
	 * The recording the others most agree with: a medoid, not an average, since averaging two
	 * recordings of a door produces a glide neither contains. Compared on length, reach, lateral
	 * drift and number of movements; with two, the more ordinary wins.
	 */
	static MotionCurve consensus(List<MotionCurve> kept)
	{
		MotionCurve best = null;
		double bestScore = Double.MAX_VALUE;
		for (int i = kept.size() - 1; i >= 0; i--)
		{
			MotionCurve candidate = kept.get(i);
			double score = kept.size() < 3 ? oddness(candidate) : 0;
			for (MotionCurve other : kept)
			{
				if (other != candidate)
				{
					score += difference(candidate, other);
				}
			}
			if (score < bestScore - 1e-6)
			{
				best = candidate;
				bestScore = score;
			}
		}
		return best;
	}

	private static double difference(MotionCurve a, MotionCurve b)
	{
		return Math.abs(a.cycles() - b.cycles()) / (double) Math.max(1, Math.max(a.cycles(), b.cycles()))
			+ Math.abs(a.reach() - b.reach()) / (double) Golem.TILE
			+ Math.abs(a.maxLateral() - b.maxLateral()) / (double) Golem.TILE
			+ Math.abs(a.bursts() - b.bursts());
	}

	private static double oddness(MotionCurve curve)
	{
		return curve.maxLateral() / (double) Golem.TILE + Math.max(0, curve.bursts() - 1);
	}

	/** The middle value of one column of {@link #timings}. */
	private static int median(List<int[]> rows, int column)
	{
		int[] values = new int[rows.size()];
		for (int i = 0; i < values.length; i++)
		{
			values[i] = rows.get(i)[column];
		}
		Arrays.sort(values);
		return values[values.length / 2];
	}

	String serialiseRoutes()
	{
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, List<int[]>> e : routes.entrySet())
		{
			for (int[] to : e.getValue())
			{
				if (sb.length() > 0)
				{
					sb.append(';');
				}
				sb.append(e.getKey()).append('>').append(to[0]).append(',').append(to[1])
					.append(',').append(to[2]).append(',').append(to[3]);
				// Only where there is one, so a profile without instances saves as it always did.
				if (to[4] != 0)
				{
					sb.append(',').append(to[4]);
				}
			}
		}
		return sb.toString();
	}

	void deserialiseRoutes(String saved)
	{
		routes.clear();
		if (saved == null || saved.isEmpty())
		{
			return;
		}
		for (String entry : saved.split(";"))
		{
			try
			{
				String[] halves = entry.split(">");
				String[] to = halves[1].split(",");
				// The key is read back as numbers whenever routes are listed, outside any try, so
				// one bad key saved here would stop the plugin starting.
				String[] from = halves[0].split(",");
				if (from.length != 4)
				{
					throw new IllegalArgumentException("route key " + halves[0]);
				}
				for (String part : from)
				{
					Integer.parseInt(part);
				}
				routes.computeIfAbsent(halves[0], k -> new ArrayList<>())
					.add(new int[]{Integer.parseInt(to[0]), Integer.parseInt(to[1]),
						Integer.parseInt(to[2]), Integer.parseInt(to[3]),
						to.length > 4 ? Integer.parseInt(to[4]) : 0});
			}
			catch (RuntimeException e)
			{
				log.debug("Skipping unreadable route: {}", entry);
			}
		}
	}

	private void noteConfirmed(ObstacleSighting sighting)
	{
		List<int[]> places = confirmed.computeIfAbsent(sighting.objectId, k -> new ArrayList<>());
		if (isConfirmedAt(sighting.objectId, sighting.fromX, sighting.fromY,
			sighting.fromPlane, 1, 1))
		{
			return;
		}
		places.add(new int[]{sighting.fromX, sighting.fromY, sighting.fromPlane});
	}

	// ------------------------------------------------------------- what to play

	/**
	 * True if the player has been watched using this obstacle, whatever the outcome. Tells "seen,
	 * and it plays nothing" from "never seen", which an empty clip set cannot do on its own.
	 */
	boolean isLearned(int objectId)
	{
		Learned known = learned.get(objectId);
		return known != null && known.unlocked();
	}

	/**
	 * The clips a golem should play for this transport, best source first: what the player was
	 * seen doing beats what was shipped, which beats the archetype's guess.
	 */
	int[] clipsFor(GolemTransport transport)
	{
		// Only once it has cleared the bar everything else clears: a single sighting can be a
		// player attacked between clicking a ladder and reaching it.
		Learned known = learned.get(transport.getObjectId());
		if (known != null && known.unlocked())
		{
			// Including when it is empty: that is a measured "plays nothing".
			return known.clips;
		}
		if (MeasuredShortcuts.animationFor(transport.getObjectId()) != -1
			|| shippedAnimationKnown(transport))
		{
			return transport.animations();
		}

		// Not known, so the golem goes through in its ordinary pose rather than performing
		// something invented, as it already does for a door: plain, not wrong.
		return NOTHING;
	}

	private static final int[] NOTHING = new int[0];

	/** The recorded motion for this obstacle, or null if nobody has been watched doing it. */
	MotionCurve curveFor(GolemTransport transport)
	{
		return chosenCurves.get(transport.getObjectId());
	}

	/** Cycles a golem should stand still before moving, or 0 if unknown. */
	int moveDelayFor(GolemTransport transport)
	{
		Learned known = learned.get(transport.getObjectId());
		return known != null && known.unlocked() ? known.moveDelay : 0;
	}

	/** Cycles the movement itself should take, or 0 if unknown. */
	int moveSpanFor(GolemTransport transport)
	{
		Learned known = learned.get(transport.getObjectId());
		return known != null && known.unlocked() ? known.moveSpan : 0;
	}


	/**
	 * How long the traversal should take, or 0 to fall back to the clip and the table. A learned
	 * duration runs from the first clip to the player standing still, which for a single clip
	 * includes the pause afterwards and stretched a 38-cycle hop over 60, so a single clip is left
	 * at its own length and only a motion built in parts takes it.
	 */
	int ticksFor(GolemTransport transport)
	{
		Learned known = learned.get(transport.getObjectId());
		if (known != null && known.unlocked() && known.ticks > 0 && known.clips.length != 1)
		{
			return known.ticks;
		}
		return MeasuredShortcuts.ticksFor(transport.getObjectId());
	}

	// ---------------------------------------------------------------- learning

	/**
	 * Records one sighting, and reports whether it has just unlocked the obstacle. A sighting that
	 * disagrees with what was learned resets the count rather than averaging: averaging two
	 * animation ids produces a third that is not an animation.
	 */
	boolean record(ObstacleSighting sighting)
	{
		// An empty clip set is not a failed observation but the observation that this obstacle
		// animates nobody, as a staircase and fifteen hundred other rows do.
		noteConfirmed(sighting);
		noteLine(sighting);

		// Only a route lying along the obstacle is learned - along this traversal's movement and the
		// line the obstacle is known to move players - since a route off the line is performed off
		// it, as with the stile crossed on a slant. The animation is still learned.
		if (followsLine(sighting))
		{
			noteRoute(sighting);
			if (sighting.curve != null && !sighting.curve.isEmpty())
			{
				addCurve(sighting.objectId, sighting.curve);
			}
		}
		else
		{
			log.debug("Refused route off the line of {}: {}", sighting.objectId, sighting);
		}

		// One crossing is not one use: a sighting covers click to arrival, so three stepping stones
		// came back as 741,741,741 over six ticks while the table stores one row per hop. A run of
		// the same clip collapses to that motion and its share of the time.
		int[] clips = collapseRepeats(sighting.clips);
		int ticks = clips.length == sighting.clips.length || clips.length == 0
			? sighting.ticks
			: Math.max(1, Math.round(sighting.ticks / (float) sighting.clips.length));

		Learned existing = learned.get(sighting.objectId);
		if (existing == null || !sameClips(existing.clips, clips))
		{
			List<int[]> fresh = new ArrayList<>();
			fresh.add(new int[]{ticks, sighting.moveDelay, sighting.moveSpan});
			timings.put(sighting.objectId, fresh);
			learned.put(sighting.objectId,
				new Learned(clips, ticks, 1, sighting.moveDelay, sighting.moveSpan));
			return false;
		}

		boolean wasUnlocked = existing.unlocked();
		existing.sightings++;

		// The middle of the recent sightings, not the latest: one unusual traversal otherwise
		// rewrites what every golem does, a two-tick door being stored as three. What is saved
		// seeds the history across a restart.
		List<int[]> history = timings.computeIfAbsent(sighting.objectId, k ->
		{
			List<int[]> seeded = new ArrayList<>();
			seeded.add(new int[]{existing.ticks, existing.moveDelay, existing.moveSpan});
			return seeded;
		});
		history.add(new int[]{ticks, sighting.moveDelay, sighting.moveSpan});
		while (history.size() > CURVES_KEPT)
		{
			history.remove(0);
		}

		Learned updated = new Learned(existing.clips, median(history, 0), existing.sightings,
			median(history, 1), median(history, 2));
		learned.put(sighting.objectId, updated);

		return !wasUnlocked && updated.unlocked();
	}

	/**
	 * Reduces a run of one repeated clip to a single instance. Only when every clip is the same:
	 * a mixed set is a motion with parts, and losing them leaves a golem playing a mount and
	 * standing still.
	 */
	private static int[] collapseRepeats(int[] clips)
	{
		if (clips.length < 2)
		{
			return clips;
		}
		for (int clip : clips)
		{
			if (clip != clips[0])
			{
				return clips;
			}
		}
		return new int[]{clips[0]};
	}

	private static boolean sameClips(int[] a, int[] b)
	{
		if (a.length != b.length)
		{
			return false;
		}
		for (int i = 0; i < a.length; i++)
		{
			if (a[i] != b[i])
			{
				return false;
			}
		}
		return true;
	}

	/** How many obstacles the player has taught the plugin so far. */
	int unlockedCount()
	{
		int n = 0;
		for (Learned l : learned.values())
		{
			if (l.unlocked())
			{
				n++;
			}
		}
		return n;
	}

	// -------------------------------------------------------------- persistence

	/**
	 * Everything learned, as one line: {@code objectId:clip|clip|clip:ticks:sightings},
	 * semicolon separated. Compact because it lives in the RuneLite config, which is not a
	 * database.
	 */
	String serialise()
	{
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, Learned> e : learned.entrySet())
		{
			Learned l = e.getValue();
			if (sb.length() > 0)
			{
				sb.append(';');
			}
			sb.append(e.getKey()).append(':');
			for (int i = 0; i < l.clips.length; i++)
			{
				sb.append(i == 0 ? "" : "|").append(l.clips[i]);
			}
			sb.append(':').append(l.ticks).append(':').append(l.sightings)
				.append(':').append(l.moveDelay).append(':').append(l.moveSpan);
		}
		return sb.toString();
	}

	/**
	 * The obstacles the player has personally used, as one line: {@code objectId,x,y,plane}
	 * groups separated by semicolons. Purely cosmetic - losing it costs some green outlines.
	 */
	String serialiseConfirmed()
	{
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, List<int[]>> e : confirmed.entrySet())
		{
			for (int[] at : e.getValue())
			{
				if (sb.length() > 0)
				{
					sb.append(';');
				}
				sb.append(e.getKey()).append(',').append(at[0]).append(',')
					.append(at[1]).append(',').append(at[2]);
			}
		}
		return sb.toString();
	}

	void deserialiseConfirmed(String saved)
	{
		confirmed.clear();
		if (saved == null || saved.isEmpty())
		{
			return;
		}

		for (String entry : saved.split(";"))
		{
			try
			{
				String[] parts = entry.split(",");
				if (parts.length != 4)
				{
					continue;
				}
				confirmed.computeIfAbsent(Integer.parseInt(parts[0]), k -> new ArrayList<>())
					.add(new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
						Integer.parseInt(parts[3])});
			}
			catch (RuntimeException e)
			{
				log.debug("Skipping unreadable confirmed obstacle: {}", entry);
			}
		}
	}

	/** Restores what was learned in previous sessions. Bad rows are skipped, not fatal. */
	void deserialise(String saved)
	{
		learned.clear();
		if (saved == null || saved.isEmpty())
		{
			return;
		}

		for (String entry : saved.split(";"))
		{
			try
			{
				String[] parts = entry.split(":");
				if (parts.length < 4)
				{
					continue;
				}

				// An empty clip field is a learned "plays nothing", not a broken row: parsing it as a
				// number throws, and every silent obstacle taught was forgotten at the next login.
				int[] clips;
				if (parts[1].isEmpty())
				{
					clips = new int[0];
				}
				else
				{
					String[] clipText = parts[1].split("\\|");
					clips = new int[clipText.length];
					for (int i = 0; i < clips.length; i++)
					{
						clips[i] = Integer.parseInt(clipText[i]);
					}
				}

				// Repaired on the way in as well as out: entries written before the repeat collapse
				// existed hold a whole crossing where they should hold one hop.
				int ticks = Integer.parseInt(parts[2]);
				int[] collapsed = collapseRepeats(clips);
				if (collapsed.length != clips.length && clips.length > 0)
				{
					ticks = Math.max(1, Math.round(ticks / (float) clips.length));
				}

				// Rows written before the movement window was recorded have four fields; they load
				// with zeroes and are filled the next time anybody uses the obstacle.
				int delay = parts.length > 4 ? Integer.parseInt(parts[4]) : 0;
				int span = parts.length > 5 ? Integer.parseInt(parts[5]) : 0;
				learned.put(Integer.parseInt(parts[0]),
					new Learned(collapsed, ticks, Integer.parseInt(parts[3]), delay, span));
			}
			catch (RuntimeException e)
			{
				// One malformed entry must not cost everything else the player has taught.
				log.debug("Skipping unreadable learned obstacle: {}", entry);
			}
		}

		log.debug("Restored {} learned obstacles, {} unlocked", learned.size(), unlockedCount());
	}
}
