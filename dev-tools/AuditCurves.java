import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds what is wrong with learned obstacles without anybody watching a golem.
 *
 * <p>Until now every fault in this area was found by a person standing next to a golem and
 * describing what looked wrong. That works for six obstacles on one island. It cannot work
 * for eight thousand, and it is the only way the plugin has ever been checked.
 *
 * <p>So this reads the three things that together describe an obstacle completely, and
 * checks each against the others:
 *
 * <ul>
 *   <li><b>What was learned</b> - the RuneLite profile: stored curves, routes, and the
 *       summary animation, ticks, delay and span.</li>
 *   <li><b>What actually happened</b> - the obstacle journal: every sighting, and the raw
 *       20ms positions and animation events underneath each one. This is ground truth; the
 *       stored data is derived from it and can be compared against it.</li>
 *   <li><b>What the golems then did</b> - the same journal's golem rows: which transport
 *       each one took, whether it performed a recording, where it landed, and whether it
 *       immediately went back.</li>
 *   <li><b>What was shipped</b> - the transport tables the plugin loads, which a learned
 *       route or recording can contradict.</li>
 * </ul>
 *
 * <p>Every check is a fault that has reached the client, reversed into a question the data
 * can answer. Each is tagged with where the fault lives, because the fix is different:
 *
 * <ul>
 *   <li>{@code recording} - the capture or conditioning of a curve is wrong</li>
 *   <li>{@code learning}  - a good observation was stored badly or overwritten</li>
 *   <li>{@code data}      - the shipped tables disagree with what the game does</li>
 *   <li>{@code replay}    - golems perform correct data incorrectly</li>
 *   <li>{@code coverage}  - something exists that can never be replayed</li>
 * </ul>
 *
 * <pre>
 * javac -d out dev-tools/AuditCurves.java
 * java  -cp out AuditCurves [profile.properties] [journal.tsv]
 * </pre>
 */
public class AuditCurves
{
	private static final String DEFAULT_PROFILE =
		"C:/Users/varzeki/.runelite/profiles2/default-0.properties";
	private static final String RUNELITE_DIR = "C:/Users/varzeki/.runelite";
	private static final String[] TRANSPORT_DIRS = {
		"R:/RunelitePluginDevelopment/References/shortest-path/src/main/resources/transports",
		"R:/RunelitePluginDevelopment/MyPlugins/Golems Dont Die/dev-tools/transports",
	};

	/** 128ths of a tile, matching the plugin and the client's local coordinates. */
	private static final int TILE = 128;

	/** Samples are stored every other cycle. Must match {@code MotionCurve}. */
	private static final int SAMPLE_EVERY = 2;

	/** A step larger than this is a real discontinuity rather than sampling noise. */
	private static final int NOISE_STEP = TILE * 3 / 4;

	/**
	 * Stillness long enough to separate two steps of a chained obstacle, in stored samples.
	 *
	 * <p>Six samples is twelve cycles. The quantised source stalls for one or two cycles
	 * mid-movement - which is sampling, not stopping - while the pause between two basalt
	 * hops measured over sixty.
	 */
	private static final int HOLD_SAMPLES = 6;
	private static final int HOLD_CYCLES = 12;

	/** A golem taking the same obstacle again within this long has not had a cooldown. */
	private static final int PINGPONG_CYCLES = 300;

	private static final String RECORDING = "recording";
	private static final String LEARNING = "learning";
	private static final String DATA = "data";
	private static final String REPLAY = "replay";
	private static final String COVERAGE = "coverage";

	private final Map<Integer, List<String>> faults = new TreeMap<>();
	private final Map<String, Integer> byCategory = new TreeMap<>();
	private final Map<Integer, List<String>> measured = new TreeMap<>();
	private final Map<Integer, Integer> golemTakes = new TreeMap<>();

	/** Findings about golems in general rather than any one obstacle. */
	private final List<String> world = new ArrayList<>();

	/** Golems this close to an obstacle's way in had every chance to use it. */
	private static final int NEARBY_TILES = 8;

	/**
	 * The dev client's log.
	 *
	 * <p>The profile is only written now and then, so while the client is running it can be
	 * minutes behind. The log records every value as it is set — the stile had been learned
	 * for several minutes before the profile knew anything about it.
	 */
	private static final String CLIENT_LOG =
		"C:/Users/varzeki/AppData/Local/Temp/runelite-plugin-stdout.txt";

	/** Rescues the client log reported, by the tile the golem was lifted from. */
	private final Map<String, Integer> logRescues = new HashMap<>();

	/** Facing relative to the direction of travel, in eighths, per object. */
	private final Map<Integer, int[]> playerFacing = new HashMap<>();
	private final Map<Integer, int[]> golemFacing = new HashMap<>();

	/** The plugin's own walkability test, when the plugin classes are on the classpath. */
	private java.lang.reflect.Method probeWalkable;

	/** The land fill the roam planner picks destinations from, which is a stricter test. */
	private java.lang.reflect.Method probeLand;

	/** A golem's own step test, for judging whether a reported step was against its map. */
	private java.lang.reflect.Method probeCanStep;

	/** The mesh's ocean bit: the one sea, which the client never blocks. */
	private java.lang.reflect.Method probeOcean;

	private boolean ocean(int x, int y, int plane)
	{
		if (probeOcean == null)
		{
			return false;
		}
		try
		{
			return (Boolean) probeOcean.invoke(null, x, y, plane);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException("ocean probe failed at " + x + "," + y + "," + plane, e);
		}
	}

	/** Movement faster than this per cycle is part of a jump rather than a walk. */
	private static final int FAST_STEP = TILE / 8;

	public static void main(String[] args) throws IOException
	{
		new AuditCurves().run(args);
	}

	private void run(String[] args) throws IOException
	{
		String profilePath = args.length > 0 ? args[0] : DEFAULT_PROFILE;
		File journalFile = args.length > 1 ? new File(args[1]) : newestJournal();

		Map<String, String> config = readProfile(profilePath);
		int fromLog = overlayClientLog(config, profilePath);
		loadProbe(config.get("golemsdontdie.islandMap"));
		Map<Integer, Curve> curves = readCurves(config.get("golemsdontdie.learnedCurves"));
		Map<Integer, List<int[]>> shipped = readShipped();
		Map<Integer, List<int[]>> routes = ignoreLikePlugin(readRoutes(config.get("golemsdontdie.learnedRoutes")),
			readLines(config.get("golemsdontdie.learnedLines")), shipped);
		Map<Integer, Learned> learned = readLearned(config.get("golemsdontdie.learnedObstacles"));
		Journal journal = journalFile == null ? new Journal() : Journal.read(journalFile);
		prepareCurrency(journal);

		System.out.println("profile    : " + profilePath);
		System.out.println("journal    : " + (journalFile == null ? "(none)" : journalFile));
		System.out.println("learned    : " + curves.size() + " curves, " + routes.size()
			+ " routed objects, " + learned.size() + " animations");
		System.out.println("journal    : " + journal.sightings.size() + " sightings, "
			+ journal.golemActs.size() + " golem decisions, " + journal.golemFrames.size()
			+ " golems with drawn frames");
		System.out.println("client log : " + (fromLog > 0 ? fromLog + " values newer than the profile"
			: "nothing newer than the profile") + ", " + logRescues.size() + " rescue sites");
		System.out.println("walkability: " + (probeWalkable != null ? "plugin mesh loaded"
			: "unavailable - put the plugin classes and dev-tools/probe on the classpath"));
		System.out.println();

		TreeSet<Integer> objects = new TreeSet<>();
		objects.addAll(curves.keySet());
		objects.addAll(routes.keySet());
		objects.addAll(learned.keySet());
		for (Sighting s : journal.sightings)
		{
			objects.add(s.object);
		}

		for (int object : objects)
		{
			Curve curve = curves.get(object);
			List<int[]> objectRoutes = routes.getOrDefault(object, new ArrayList<>());
			List<int[]> objectShipped = shipped.getOrDefault(object, new ArrayList<>());
			List<Sighting> sightings = journal.sightingsFor(object);

			if (curve != null)
			{
				checkCurve(object, curve);
				checkCurveAgainstRows(object, curve, objectShipped);
				checkCurveAgainstSightings(object, curve, sightings);
				checkCurveAgainstRaw(object, curve, sightings, journal);
				checkTeleportReplay(object, curve, sightings, journal);
			}
			checkPlayerFacing(object, sightings, journal);
			checkRoutesAgainstShipped(object, objectRoutes, objectShipped, shipped);
			checkSightingConsistency(object, sightings);
			checkLearnedAgainstSightings(object, learned.get(object), sightings, curve);
			checkCoverage(object, curve, objectRoutes, sightings);
		}

		checkGolemReplay(journal, curves, routes, shipped);
		checkNeverTaken(objects, journal, routes);
		checkDuplicateEndpoints(routes, shipped);
		checkUnexplainedJumps(journal);
		checkFacing(objects);
		checkDrawnTraversals(objects, journal);
		checkRouteAccess(objects, routes, shipped, journal);
		checkRouteLine(objects, routes, journal);
		checkWorldEvents(journal);
		checkOneWay(objects, routes, journal);
		checkCrowding(journal);
		checkGolemGround(journal, routes, shipped);

		report(objects, curves, routes, shipped, journal);
	}

	// ======================================================= the recording itself

	private void checkCurve(int object, Curve curve)
	{
		// A silent obstacle's recording is only the pause before the player vanishes, and
		// can legitimately be a cycle or two.
		if (curve.cycles < 4 && curve.animationStarts() > 0)
		{
			add(object, RECORDING, "only " + curve.cycles + " cycles - a traversal with no animation");
		}
		if (curve.cycles > 400)
		{
			add(object, RECORDING, curve.cycles + " cycles - a clip holding its last frame");
		}
		if (Math.abs(curve.forward[0]) > TILE / 4)
		{
			add(object, RECORDING, "starts " + curve.forward[0] + " units along - the trim cut "
				+ "into the traversal, so a golem lurches forward on its first cycle");
		}

		int[] steps = curve.steps();
		int backwards = 0;
		int jumps = 0;
		for (int step : steps)
		{
			if (step < -8)
			{
				backwards++;
			}
			if (Math.abs(step) > NOISE_STEP)
			{
				jumps++;
			}
		}
		if (backwards > 0)
		{
			add(object, RECORDING, backwards + " backward steps - the golem stutters or reverses");
		}
		if (jumps > 1)
		{
			add(object, RECORDING, jumps + " large jumps - a teleport is one; several means "
				+ "the samples mix two frames of reference");
		}

		// Stutter is a zero step *between* two moving steps: the quantised source stalling
		// for a sample while still in motion. A long run of zeros is a hold, which is real.
		// Counting plateaus of any length, as this used to, called the pause between two
		// stepping-stone hops a smoothing failure.
		int stutters = 0;
		for (int i = 1; i < steps.length - 1; i++)
		{
			if (steps[i] == 0 && steps[i - 1] != 0 && steps[i + 1] != 0)
			{
				stutters++;
			}
		}
		if (stutters > 3)
		{
			add(object, RECORDING, stutters + " mid-motion stalls - quantised samples not "
				+ "smoothed, so the golem jumps rather than glides");
		}

		if (curve.maxLateral() > Math.max(TILE / 2, Math.abs(curve.reach()) / 2))
		{
			add(object, RECORDING, "sideways drift " + curve.maxLateral() + " against "
				+ curve.reach() + " forward - recorded along the wrong axis, usually because "
				+ "the player did something else mid-traversal");
		}

		int bursts = curve.bursts();
		int anims = curve.animationStarts();
		if (bursts > 1 || anims > 1)
		{
			add(object, RECORDING, "one recording holds " + Math.max(bursts, anims)
				+ " separate steps (" + bursts + " movements, " + anims + " animations) - a "
				+ "chained crossing captured under a single click, which a per-step transport "
				+ "cannot replay");
		}
	}

	// ============================================= the recording against the tables

	private void checkCurveAgainstRows(int object, Curve curve, List<int[]> objectShipped)
	{
		if (objectShipped.isEmpty())
		{
			return;
		}

		double longestRow = 0;
		for (int[] row : objectShipped)
		{
			if (row[2] == row[5])
			{
				longestRow = Math.max(longestRow, tiles(row));
			}
		}
		double reach = Math.abs(curve.reach()) / (double) TILE;

		if (longestRow > 0 && reach > longestRow + 0.5)
		{
			add(object, REPLAY, String.format("recording covers %.1f tiles but every shipped row "
				+ "for this object is at most %.1f - each row replays the whole thing compressed, "
				+ "so one gap gets every step's animation", reach, longestRow));
		}
	}

	private void checkRoutesAgainstShipped(int object, List<int[]> objectRoutes,
		List<int[]> objectShipped, Map<Integer, List<int[]>> allShipped)
	{
		for (int[] route : objectRoutes)
		{
			if (route[6] < 2)
			{
				continue;
			}

			// A learned route that passes over one of this object's own shipped origins has
			// been learned across a chain and skips the stop in the middle.
			List<String> skipped = new ArrayList<>();
			for (int[] row : objectShipped)
			{
				if (row[2] == route[2] && strictlyBetween(row[0], row[1], route))
				{
					String at = row[0] + "," + row[1];
					if (!skipped.contains(at))
					{
						skipped.add(at);
					}
				}
			}
			if (!skipped.isEmpty())
			{
				add(object, LEARNING, "learned route " + point(route, 0) + " -> "
					+ point(route, 3) + " passes over shipped stop" + (skipped.size() > 1 ? "s " : " ")
					+ skipped + " - a golem taking it clears the chain in one go");
			}

			// The shipped table has this object going the other way to how it is used.
			boolean shippedForward = false;
			boolean shippedReverse = false;
			for (int[] row : objectShipped)
			{
				if (near(row, 0, route, 0) && near(row, 3, route, 3))
				{
					shippedForward = true;
				}
				if (near(row, 0, route, 3) && near(row, 3, route, 0))
				{
					shippedReverse = true;
				}
			}
			if (shippedReverse && !shippedForward)
			{
				Integer owner = null;
				for (Map.Entry<Integer, List<int[]>> other : allShipped.entrySet())
				{
					if (other.getKey() == object)
					{
						continue;
					}
					for (int[] row : other.getValue())
					{
						if (near(row, 0, route, 0) && near(row, 3, route, 3))
						{
							owner = other.getKey();
						}
					}
				}
				add(object, DATA, "every observed use goes " + point(route, 0) + " -> "
					+ point(route, 3) + " but the shipped row for this object goes the other "
					+ "way" + (owner == null ? "" : "; the row for this direction is filed under "
					+ owner + " - the two ids are swapped in the table"));
			}
		}
	}

	// ======================================= the recording against its own sightings

	private void checkCurveAgainstSightings(int object, Curve curve, List<Sighting> sightings)
	{
		List<Integer> lengths = new ArrayList<>();
		Sighting stored = null;
		for (Sighting s : sightings)
		{
			if (s.curveCycles > 0)
			{
				lengths.add(s.curveCycles);
				stored = s;
			}
		}
		if (lengths.size() < 3)
		{
			return;
		}

		int median = median(lengths);
		if (Math.abs(curve.cycles - median) > Math.max(6, median / 4))
		{
			add(object, LEARNING, "stored recording is " + curve.cycles + " cycles where its "
				+ lengths.size() + " sightings have a median of " + median + " - every sighting "
				+ "overwrites the last, so one unrepresentative traversal replaced good ones");
		}

		if (stored != null && stored.rawAnimStarts > 0 && curve.animationStarts() != stored.rawAnimStarts)
		{
			add(object, RECORDING, "raw journal shows " + stored.rawAnimStarts + " animation "
				+ "starts for the stored traversal but the recording kept "
				+ curve.animationStarts());
		}
	}

	/**
	 * The stored recording against the raw journal rows it was made from.
	 *
	 * <p>A recording is conditioned before it is stored: trimmed to where the traversal
	 * starts, projected onto an axis, smoothed. Each of those has already cut away something
	 * real at least once - the wind-up before a hop, the start of a door, a tile of reach -
	 * and each time it was found by someone watching. The journal keeps the unconditioned
	 * positions, so the two can simply be compared.
	 */
	private void checkCurveAgainstRaw(int object, Curve curve, List<Sighting> sightings,
		Journal journal)
	{
		Sighting source = null;
		for (Sighting s : sightings)
		{
			if (s.curveCycles == curve.cycles)
			{
				source = s;
			}
		}
		if (source == null || source.windowStart < 0 || source.from[2] != source.to[2])
		{
			return;
		}

		int animStart = animStartFor(journal, source)[0];

		// Lookback rows are written out of order, when the traversal is confirmed, so the
		// track is rebuilt by cycle rather than read in file order.
		TreeMap<Integer, int[]> track = new TreeMap<>();
		for (int[] p : journal.positions)
		{
			if (p[0] >= source.windowStart && p[0] <= source.cycle)
			{
				keepFine(track, p);
			}
		}
		if (animStart < 0 || track.isEmpty() || track.floorEntry(animStart) == null)
		{
			return;
		}

		// A teleport of more than a few tiles in the raw positions is a scene change or a
		// cave mouth. Local coordinates are rebased across one, so a raw distance measured
		// over it means nothing, and the recording holds position there on purpose.
		int[] previous = null;
		for (int[] p : track.values())
		{
			if (previous != null && (Math.abs(p[1] - previous[1]) > 3 * TILE
				|| Math.abs(p[2] - previous[2]) > 3 * TILE))
			{
				return;
			}
			previous = p;
		}

		int[] rest = track.floorEntry(animStart).getValue();
		int[] end = endOfAnimation(journal, source, animStart, track);
		double ax = source.to[0] - source.from[0];
		double ay = source.to[1] - source.from[1];
		double length = Math.hypot(ax, ay);
		if (length == 0)
		{
			return;
		}
		int rawReach = (int) Math.round(((end[1] - rest[1]) * ax + (end[2] - rest[2]) * ay) / length);

		// Only the animated part is compared. A recording now carries walk steps on and off
		// an obstacle where its route's ends are outside it, which the player never made.
		int animated = animatedReach(curve);
		if (Math.abs(rawReach - animated) > TILE / 4)
		{
			add(object, RECORDING, "recording travels " + animated + " units while animating but "
				+ "the journal shows the player travelled " + rawReach + " for the same traversal");
		}

		int rawMove = -1;
		for (Map.Entry<Integer, int[]> e : track.tailMap(animStart, true).entrySet())
		{
			int[] p = e.getValue();
			if (Math.abs(p[1] - rest[1]) + Math.abs(p[2] - rest[2]) > 8)
			{
				rawMove = e.getKey();
				break;
			}
		}
		int curveAnim = -1;
		for (int[] change : curve.animations)
		{
			if (change.length > 1 && change[1] != -1)
			{
				curveAnim = change[0];
				break;
			}
		}
		int curveMove = -1;
		int fromSample = curveAnim < 0 ? 0 : Math.min(curveAnim / SAMPLE_EVERY, curve.forward.length - 1);
		for (int i = fromSample; i < curve.forward.length; i++)
		{
			if (Math.abs(curve.forward[i] - curve.forward[fromSample])
				+ Math.abs(curve.lateral[i] - curve.lateral[fromSample]) > 8)
			{
				curveMove = i * SAMPLE_EVERY;
				break;
			}
		}
		if (rawMove < 0 || curveAnim < 0 || curveMove < 0)
		{
			return;
		}

		int rawHold = rawMove - animStart;
		int curveHold = curveMove - curveAnim;
		evidence(object, "journal hold " + rawHold + "c reach " + rawReach
			+ " / recording hold " + curveHold + "c reach " + animated);
		if (Math.abs(rawHold - curveHold) > HOLD_CYCLES / 2)
		{
			add(object, RECORDING, "player held still " + rawHold + " cycles into the animation "
				+ "before moving; the recording holds " + curveHold + " - the "
				+ (curveHold < rawHold ? "wind-up was cut" : "movement was delayed"));
		}
	}

	/** Where the player was drawn when the sighting's animation ended, or at the last row. */
	private static int[] endOfAnimation(Journal journal, Sighting s, int animStart,
		TreeMap<Integer, int[]> track)
	{
		for (int[] a : journal.anims)
		{
			if (a[0] > animStart && a[0] <= s.cycle && a[1] == -1)
			{
				Map.Entry<Integer, int[]> at = track.floorEntry(a[0]);
				return at != null ? at.getValue() : track.lastEntry().getValue();
			}
		}
		return track.lastEntry().getValue();
	}

	/** Forward distance a recording covers from its first clip starting to that clip ending. */
	private static int animatedReach(Curve curve)
	{
		int start = -1;
		int end = curve.cycles;
		for (int[] change : curve.animations)
		{
			if (change.length < 2)
			{
				continue;
			}
			if (start < 0 && change[1] != -1)
			{
				start = change[0];
			}
			else if (start >= 0 && change[1] == -1)
			{
				end = change[0];
				break;
			}
		}
		if (start < 0)
		{
			return curve.reach();
		}
		int a = Math.min(start / SAMPLE_EVERY, curve.forward.length - 1);
		int b = Math.min(end / SAMPLE_EVERY, curve.forward.length - 1);
		return curve.forward[b] - curve.forward[a];
	}

	private void checkSightingConsistency(int object, List<Sighting> sightings)
	{
		// The usual shape of a use of this object, as |dx|,|dy|,plane-change, with any
		// sighting well away from it being something other than the obstacle.
		Map<String, Integer> shapes = new HashMap<>();
		for (Sighting s : sightings)
		{
			shapes.merge(s.shapeKey(), 1, Integer::sum);
		}
		String usual = null;
		int best = 0;
		for (Map.Entry<String, Integer> e : shapes.entrySet())
		{
			if (e.getValue() > best)
			{
				best = e.getValue();
				usual = e.getKey();
			}
		}
		if (usual == null || best < 2)
		{
			return;
		}

		int[] expected = Sighting.parseShape(usual);
		Sighting last = sightings.isEmpty() ? null : sightings.get(sightings.size() - 1);
		for (Sighting s : sightings)
		{
			int[] shape = s.shape();
			if (Math.abs(shape[0] - expected[0]) > 1 || Math.abs(shape[1] - expected[1]) > 1
				|| shape[2] != expected[2])
			{
				add(object, LEARNING, "sighting " + s.fromText() + " -> " + s.toText() + " moved ("
					+ shape[0] + "," + shape[1] + ") where this obstacle normally moves ("
					+ expected[0] + "," + expected[1] + ") - the player did something else during "
					+ "the traversal" + (s == last ? ", and it is the most recent, so it is what "
					+ "was stored" : ""));
			}
		}
	}

	private void checkLearnedAgainstSightings(int object, Learned learned, List<Sighting> sightings,
		Curve curve)
	{
		if (learned == null)
		{
			return;
		}

		if (learned.span <= 1 && curve != null && Math.abs(curve.reach()) > TILE)
		{
			add(object, RECORDING, "learned span is " + learned.span + " cycle for a recording that "
				+ "travels " + curve.reach() + " units - longest-unbroken-run cannot be measured on "
				+ "a quantised source, which stalls every other cycle");
		}

		List<Integer> ticks = new ArrayList<>();
		List<Integer> delays = new ArrayList<>();
		for (Sighting s : sightings)
		{
			if (!s.silent && s.clips.equals(learned.clips))
			{
				ticks.add(s.ticks);
				delays.add(s.delay);
			}
		}
		if (ticks.size() >= 3)
		{
			int medianTicks = median(ticks);
			if (Math.abs(learned.ticks - medianTicks) >= 1 && learned.ticks != medianTicks)
			{
				add(object, LEARNING, "learned ticks " + learned.ticks + " but sightings median "
					+ medianTicks + " - the latest sighting wins, so one outlier rewrites it");
			}
			int medianDelay = median(delays);
			if (Math.abs(learned.delay - medianDelay) > Math.max(3, medianDelay / 4))
			{
				add(object, LEARNING, "learned delay " + learned.delay + " but sightings median "
					+ medianDelay);
			}
		}
	}

	private void checkCoverage(int object, Curve curve, List<int[]> objectRoutes,
		List<Sighting> sightings)
	{
		if (curve != null || (objectRoutes.isEmpty() && sightings.isEmpty()))
		{
			return;
		}

		boolean allSilent = !sightings.isEmpty();
		for (Sighting s : sightings)
		{
			allSilent &= s.silent;
		}
		if (allSilent)
		{
			add(object, COVERAGE, sightings.size() + " sightings, all silent - silent traversals "
				+ "record no curve, so this obstacle can never be performed from a recording "
				+ "and always falls back to the parametric path");
		}
		else
		{
			add(object, COVERAGE, "has " + sightings.size() + " sightings and "
				+ objectRoutes.size() + " routes but no stored recording");
		}
	}

	// ====================================================== what the golems then did

	private void checkGolemReplay(Journal journal, Map<Integer, Curve> curves,
		Map<Integer, List<int[]>> routes, Map<Integer, List<int[]>> shipped)
	{
		Map<String, List<Act>> actsByGolem = new HashMap<>();
		for (Act act : journal.golemActs)
		{
			actsByGolem.computeIfAbsent(act.golem, k -> new ArrayList<>()).add(act);
		}

		// A recording only exists from the first sighting that made one. A parametric take
		// before that is correct behaviour, not a fallback.
		Map<Integer, Integer> recordedFrom = new HashMap<>();
		for (Sighting s : journal.sightings)
		{
			if (s.curveCycles > 0)
			{
				recordedFrom.putIfAbsent(s.object, s.cycle);
			}
		}

		Map<Integer, Integer> takes = golemTakes;
		Map<Integer, Integer> returns = new TreeMap<>();
		Map<Integer, Integer> missed = new TreeMap<>();
		Map<Integer, Integer> wrongLength = new TreeMap<>();
		Map<Integer, Integer> parametric = new TreeMap<>();
		Map<Integer, Integer> stretched = new TreeMap<>();
		Map<Integer, Integer> arrivals = new TreeMap<>();
		Map<Integer, List<Integer>> strandedTicks = new TreeMap<>();
		Map<Integer, Map<String, Integer>> strandedEndings = new TreeMap<>();
		Map<Integer, String> landing = new TreeMap<>();

		for (Map.Entry<String, List<Act>> entry : actsByGolem.entrySet())
		{
			List<State> states = journal.states.getOrDefault(entry.getKey(), new ArrayList<>());
			List<Act> decisions = entry.getValue();

			int[] pendingDest = null;
			int pendingObject = -1;
			int[] lastOrigin = null;
			int[] lastDest = null;
			int lastCycle = Integer.MIN_VALUE;

			for (Act act : entry.getValue())
			{
				if (act.object >= 0 && act.dest != null)
				{
					pendingObject = act.object;
					pendingDest = act.dest;
					continue;
				}
				if (pendingObject < 0 || (act.performing <= 0 && !act.parametric))
				{
					continue;
				}

				// The last logged state can be a tick stale - still walking up to the
				// obstacle - so the origin is the known route into this destination that
				// lies nearest the golem, not whatever tile it was last seen on.
				int[] origin;
				if (act.at != null)
				{
					origin = new int[]{act.at[0], act.at[1], pendingDest[2]};
				}
				else
				{
					State near = firstStateAfter(states, act.cycle - 1, true);
					if (near == null)
					{
						near = lastStateBefore(states, act.cycle);
					}
					origin = originOf(pendingObject, pendingDest, near, routes, shipped);
				}

				takes.merge(pendingObject, 1, Integer::sum);
				routeTakes.merge(pendingObject + ":" + pendingDest[0] + "," + pendingDest[1] + ","
					+ pendingDest[2], 1, Integer::sum);

				// Going straight back the way it came is the thing a cooldown exists to stop.
				// Moving on along a chain of the same object is not.
				// Matched on tiles rather than ids, because the two directions of one crossing
				// are often two objects: the rockslide is 62265 going up and 62267 coming down.
				if (act.cycle - lastCycle < PINGPONG_CYCLES && lastOrigin != null && lastDest != null
					&& origin != null && sameTile(pendingDest, lastOrigin) && sameTile(origin, lastDest))
				{
					returns.merge(pendingObject, 1, Integer::sum);
				}
				lastOrigin = origin;
				lastDest = pendingDest;
				lastCycle = act.cycle;

				boolean hadRecording = recordedFrom.containsKey(pendingObject)
					? act.cycle > recordedFrom.get(pendingObject)
					: curves.containsKey(pendingObject);
				if (act.parametric && hadRecording)
				{
					parametric.merge(pendingObject, 1, Integer::sum);
				}

				if (act.performing > 0)
				{
					State during = firstStateAfter(states, act.cycle, true);
					State after = firstStateAfter(states, act.cycle, false);

					// Only states close enough to belong to this performance. A golem is logged
					// only while it is on the player's floor, so one that climbs a ladder drops
					// out of the journal until it comes back down — and the next state seen,
					// minutes later on the floor it started on, was being read as its arrival.
					// That was 246 of 261 "missed" ladder climbs.
					if (during != null && during.cycle > act.cycle + act.performing)
					{
						during = null;
					}
					if (after != null && after.cycle > act.cycle + act.performing + 60)
					{
						after = null;
					}

					if (after != null && !sameTile(after.tile, pendingDest))
					{
						missed.merge(pendingObject, 1, Integer::sum);
					}
					if (during != null && during.transitTotal > 0
						&& Math.abs(during.transitTotal - act.performing) > 2)
					{
						wrongLength.merge(pendingObject, 1, Integer::sum);
					}

					// Compared against the recording in force at the time, which is not
					// necessarily the one stored now.
					double recorded = recordedTiles(journal, curves, pendingObject, act);
					if (origin != null && recorded >= 0.5 && origin[2] == pendingDest[2])
					{
						double needed = Math.hypot(pendingDest[0] - origin[0], pendingDest[1] - origin[1]);
						double scale = needed / recorded;
						if (scale < 0.6 || scale > 1.6)
						{
							stretched.merge(pendingObject, 1, Integer::sum);
						}
					}

					// Which way the golem faced while performing, against the way it went.
					if (origin != null && origin[2] == pendingDest[2] && !sameTile(origin, pendingDest)
						&& Math.hypot(pendingDest[0] - origin[0], pendingDest[1] - origin[1]) <= 8
						&& current(pendingObject, act.cycle))
					{
						int travel = heading(pendingDest[0] - origin[0], pendingDest[1] - origin[1]);
						int[] bins = golemFacing.computeIfAbsent(pendingObject, k -> new int[8]);
						for (State s : states)
						{
							if (s.performing && s.orient >= 0 && s.cycle > act.cycle
								&& s.cycle <= act.cycle + act.performing + 5)
							{
								bins[bin(s.orient - travel)]++;
							}
						}
					}
				}

				// Whether the golem could get on with its life where it came down.
				Act next = nextDecision(decisions, act.cycle);
				int[] stood = standingAfter(states, act.cycle, pendingDest, next == null ? -1 : next.cycle);
				if (stood != null)
				{
					arrivals.merge(pendingObject, 1, Integer::sum);
					landing.put(pendingObject, pendingDest[0] + "," + pendingDest[1] + "," + pendingDest[2]);
					boolean rescued = next != null && next.rescue && next.cycle - act.cycle < 3000;
					if (stood[0] >= 2 || rescued)
					{
						strandedTicks.computeIfAbsent(pendingObject, k -> new ArrayList<>()).add(stood[1]);
						String ending = rescued ? "rescued"
							: stood[2] == END_WALKED ? "eventually walked off"
							: stood[2] == END_DECISION ? "took a transport"
							: stood[2] == END_JUMP ? "vanished without a logged decision"
							: "still standing when the log ended";
						strandedEndings.computeIfAbsent(pendingObject, k -> new TreeMap<>())
							.merge(ending, 1, Integer::sum);
					}
				}

				pendingObject = -1;
				pendingDest = null;
			}
		}

		for (Map.Entry<Integer, Integer> e : takes.entrySet())
		{
			int object = e.getKey();
			int total = e.getValue();
			evidence(object, "golems " + total + " takes, " + returns.getOrDefault(object, 0)
				+ " straight back, " + missed.getOrDefault(object, 0) + " missed, "
				+ stretched.getOrDefault(object, 0) + " stretched");

			int back = returns.getOrDefault(object, 0);
			if (back > 0 && back * 10 >= total)
			{
				add(object, REPLAY, back + " of " + total + " uses were a golem going straight back "
					+ "through it within " + PINGPONG_CYCLES + " cycles - the return trip is not "
					+ "on cooldown");
			}
			int miss = missed.getOrDefault(object, 0);
			if (miss > 0)
			{
				add(object, REPLAY, miss + " performances ended somewhere other than the route's "
					+ "destination - the arrival snaps");
			}
			int length = wrongLength.getOrDefault(object, 0);
			if (length > 0)
			{
				add(object, REPLAY, length + " performances ran a different length to the recording");
			}
			int param = parametric.getOrDefault(object, 0);
			if (param > 0)
			{
				add(object, REPLAY, param + " uses fell back to the parametric path after a "
					+ "recording existed");
			}
			int stretch = stretched.getOrDefault(object, 0);
			if (stretch > 0)
			{
				add(object, REPLAY, stretch + " performances stretched a recording by more than "
					+ "60% onto a route of a different length - the motion no longer resembles "
					+ "the original");
			}

			int landed = arrivals.getOrDefault(object, 0);
			List<Integer> stuck = strandedTicks.getOrDefault(object, new ArrayList<>());
			int lifted = logRescues.getOrDefault(landing.getOrDefault(object, ""), 0);
			if (landed > 0)
			{
				evidence(object, "golems " + landed + " arrivals seen at " + landing.get(object) + ", "
					+ stuck.size() + " could not move on" + (lifted > 0
					? ", client log shows " + lifted + " rescues from there" : ""));
			}
			if (!stuck.isEmpty() && stuck.size() * 5 >= landed)
			{
				add(object, REPLAY, stuck.size() + " of " + landed + " golems that came down at "
					+ landing.get(object) + " could not move off it - they stood re-planning for "
					+ median(stuck) + " ticks (median) and then " + strandedEndings.get(object)
					+ (lifted > 0 ? "; the client log shows " + lifted + " rescues from that tile" : "")
					+ groundAt(landing.get(object)));
			}
			else if (lifted > 0)
			{
				add(object, REPLAY, "the client log shows " + lifted + " golems rescued from "
					+ landing.get(object) + ", where this obstacle puts them down - they arrive "
					+ "somewhere they cannot leave");
			}
		}
	}

	private static final int END_LOG = 0;
	private static final int END_WALKED = 1;
	private static final int END_DECISION = 2;
	private static final int END_JUMP = 3;

	/** The golem's next decision or rescue after this moment, or null. */
	private static Act nextDecision(List<Act> decisions, int after)
	{
		for (Act act : decisions)
		{
			if (act.cycle > after + 1 && (act.object >= 0 || act.rescue))
			{
				return act;
			}
		}
		return null;
	}

	/**
	 * How a golem got on after arriving: {times it gave up planning and waited again, ticks
	 * spent standing, how it ended}, or null if it was never seen standing at the landing.
	 *
	 * <p>Restarting a dwell without walking is a planner that found nowhere to go. Once is
	 * a golem pausing. Twice in a row on the same tile is a golem that cannot leave it.
	 */
	private static int[] standingAfter(List<State> states, int from, int[] dest, int until)
	{
		int redwells = 0;
		int first = -1;
		int last = -1;
		int previousDwell = -1;
		int end = END_LOG;
		State previous = null;
		for (State s : states)
		{
			if (s.cycle <= from || s.performing)
			{
				continue;
			}
			if (until > 0 && s.cycle >= until)
			{
				end = END_DECISION;
				break;
			}
			if (!sameTile(s.tile, dest))
			{
				if (first < 0)
				{
					return null;
				}
				end = previous != null && Math.abs(s.tile[0] - previous.tile[0])
					+ Math.abs(s.tile[1] - previous.tile[1]) > 3 || s.tile[2] != previous.tile[2]
					? END_JUMP : END_WALKED;
				break;
			}
			if (s.walking)
			{
				end = END_WALKED;
				if (first < 0)
				{
					first = s.cycle;
					last = s.cycle;
				}
				break;
			}
			if (first < 0)
			{
				first = s.cycle;
			}
			if (previousDwell >= 0 && s.dwell > previousDwell)
			{
				redwells++;
			}
			previousDwell = s.dwell;
			last = s.cycle;
			previous = s;
		}
		if (first < 0)
		{
			return null;
		}
		return new int[]{redwells, (last - first) / 30, end};
	}

	// ============================================================ how it was drawn

	/**
	 * Which way the player faces while using an obstacle, relative to where they go.
	 *
	 * <p>Not always forwards. Climbing down the rockslide the player faces the rock and
	 * moves away from it, which is a quarter of what makes it read as a climb; a golem
	 * facing its direction of travel looks like it is abseiling off the cliff outwards.
	 */
	private void checkPlayerFacing(int object, List<Sighting> sightings, Journal journal)
	{
		for (Sighting s : sightings)
		{
			if (s.silent || s.windowStart < 0 || s.from[2] != s.to[2])
			{
				continue;
			}
			double distance = Math.hypot(s.to[0] - s.from[0], s.to[1] - s.from[1]);
			if (distance == 0 || distance > 8)
			{
				continue;
			}
			int travel = heading(s.to[0] - s.from[0], s.to[1] - s.from[1]);
			int[] bins = playerFacing.computeIfAbsent(object, k -> new int[8]);
			for (int[] p : journal.positions)
			{
				if (p[0] >= s.windowStart && p[0] <= s.cycle && p[3] >= 0 && p[4] != -1)
				{
					bins[bin(p[3] - travel)]++;
				}
			}
		}
	}

	private void checkFacing(TreeSet<Integer> objects)
	{
		for (int object : objects)
		{
			int[] player = playerFacing.get(object);
			int[] golem = golemFacing.get(object);
			if (player == null || golem == null || sum(player) < 3 || sum(golem) < 3)
			{
				continue;
			}
			int p = mode(player);
			int g = mode(golem);
			evidence(object, "facing while traversing: player " + describeFacing(p)
				+ ", golems " + describeFacing(g));
			int apart = Math.abs(p - g);
			apart = Math.min(apart, 8 - apart);
			if (apart >= 2)
			{
				add(object, REPLAY, "golems face " + describeFacing(g) + " while performing it, "
					+ "but the player faces " + describeFacing(p) + " - golems always face their "
					+ "direction of travel, and this obstacle is not done that way");
			}
		}
	}

	/**
	 * Teleports in a recording that replay turns into slides.
	 *
	 * <p>A recording keeps a sample every other cycle and replay interpolates between them.
	 * That is right for movement and wrong for a teleport: a door moves the player a whole
	 * tile in one cycle, and interpolation puts the golem halfway across on the cycle in
	 * between — a slide so quick it is nearly, but not quite, a teleport.
	 */
	private void checkTeleportReplay(int object, Curve curve, List<Sighting> sightings,
		Journal journal)
	{
		int biggest = 0;
		for (int i = 1; i < curve.forward.length; i++)
		{
			int step = Math.abs(curve.forward[i] - curve.forward[i - 1])
				+ Math.abs(curve.lateral[i] - curve.lateral[i - 1]);
			if (step > NOISE_STEP)
			{
				biggest = Math.max(biggest, step);
			}
		}
		if (biggest == 0)
		{
			return;
		}

		Sighting source = null;
		for (Sighting s : sightings)
		{
			if (s.curveCycles == curve.cycles)
			{
				source = s;
			}
		}
		int playerCycles = source == null ? -1 : fastestCover(positionsIn(journal, source), NOISE_STEP);
		// Replay holds position across a jump this size rather than interpolating it, so the
		// recording alone no longer predicts a slide. Whether one is drawn is judged from the
		// golems' drawn frames, in checkDrawnTraversals.
		evidence(object, "teleport of " + biggest + " units: player crossed it in "
			+ (playerCycles < 0 ? "?" : String.valueOf(playerCycles)) + " cycle(s); replay holds "
			+ "position across it");
	}

	/**
	 * What golems actually drew, frame by frame, against what the player did.
	 *
	 * <p>The animation is compared cycle by cycle from the moment it starts: which keyframe
	 * the player is on 10, 20, 40 cycles in, and which keyframe golems are on at the same
	 * point. That one comparison covers every way the two can disagree — a clip that stalls,
	 * one that finishes early, one that lags — without deciding in advance which to look
	 * for. An earlier version asked only "did the animation finish before the golem moved",
	 * which is exactly what a door is supposed to do, and missed a hop whose clip never left
	 * its first keyframe.
	 *
	 * <p>Where the movement falls inside the animation is reported alongside: the player
	 * crosses a stepping stone during keyframes 2 to 7, and a golem that crosses during
	 * keyframe 0 is visibly not jumping.
	 */
	private void checkDrawnTraversals(TreeSet<Integer> objects, Journal journal)
	{
		Map<Integer, Map<Integer, List<Integer>>> playerKeys = new HashMap<>();
		Map<Integer, List<int[]>> playerMoves = new HashMap<>();
		Map<Integer, Integer> playerCover = new HashMap<>();
		Map<Integer, Integer> playerAnim = new HashMap<>();
		Map<Integer, Integer> keyframeCount = new HashMap<>();

		for (Sighting s : journal.sightings)
		{
			if (s.silent || s.windowStart < 0)
			{
				continue;
			}
			TreeMap<Integer, int[]> track = positionsIn(journal, s);
			int cover = fastestCover(track, NOISE_STEP);
			if (cover > 0)
			{
				playerCover.merge(s.object, cover, Math::min);
			}

			int[] started = animStartFor(journal, s);
			int animStart = started[0];
			int anim = started[1];
			if (animStart < 0)
			{
				continue;
			}
			playerAnim.put(s.object, anim);
			java.util.SortedMap<Integer, int[]> during = track.tailMap(animStart, true);
			Map<Integer, List<Integer>> table = playerKeys.computeIfAbsent(s.object, k -> new TreeMap<>());
			for (int[] p : during.values())
			{
				if (p[4] == anim && p[5] >= 0)
				{
					table.computeIfAbsent(p[0] - animStart, k -> new ArrayList<>()).add(p[5]);
					keyframeCount.merge(s.object, p[5] + 1, Math::max);
				}
			}
			int[] moved = movementKeyframes(during, anim);
			if (moved != null)
			{
				playerMoves.computeIfAbsent(s.object, k -> new ArrayList<>()).add(moved);
			}
		}

		if (journal.golemFrames.isEmpty())
		{
			world.add("[coverage] no drawn golem frames in this journal, so animation timing and "
				+ "drawn teleports were not checked - they need a journal from a build that "
				+ "writes GOLEMFRAME rows, with golem logging on");
			byCategory.merge(COVERAGE, 1, Integer::sum);
			return;
		}

		Map<Integer, Map<Integer, List<Integer>>> golemKeys = new HashMap<>();
		Map<Integer, List<int[]>> golemMoves = new HashMap<>();
		Map<Integer, List<Integer>> golemCover = new HashMap<>();
		Map<Integer, Integer> performances = new HashMap<>();
		Map<Integer, Integer> between = new HashMap<>();

		Map<String, List<Act>> actsByGolem = new HashMap<>();
		for (Act act : journal.golemActs)
		{
			actsByGolem.computeIfAbsent(act.golem, k -> new ArrayList<>()).add(act);
		}
		for (Map.Entry<String, List<Act>> entry : actsByGolem.entrySet())
		{
			List<int[]> frames = journal.golemFrames.get(entry.getKey());
			if (frames == null)
			{
				continue;
			}
			int pending = -1;
			int[] pendingDest = null;
			for (Act act : entry.getValue())
			{
				if (act.object >= 0)
				{
					pending = act.object;
					pendingDest = act.dest;
					continue;
				}
				if (pending < 0 || act.performing <= 0)
				{
					continue;
				}
				TreeMap<Integer, int[]> drawn = new TreeMap<>();
				for (int[] f : frames)
				{
					if (f[0] >= act.cycle && f[0] <= act.cycle + act.performing + 2)
					{
						drawn.put(f[0], f);
					}
				}
				int object = pending;
				pending = -1;
				if (drawn.size() < 3 || !current(object, act.cycle))
				{
					continue;
				}
				performances.merge(object, 1, Integer::sum);

				// Drawn somewhere between where it started and where it lands. Normal for a
				// glide; for an obstacle the player crosses in a single cycle it is the slide.
				//
				// Measured this way rather than by how long the crossing took, because a
				// golem's frames are logged only while it is mid-traversal and a door lands on
				// the very frame that ends it — so the logged frames stop halfway across and a
				// crossing time can never be read off them.
				if (pendingDest != null)
				{
					int[] start = drawn.firstEntry().getValue();
					int destX = pendingDest[0] * TILE + TILE / 2;
					int destY = pendingDest[1] * TILE + TILE / 2;
					for (int[] f : drawn.values())
					{
						if (Math.abs(f[1] - start[1]) + Math.abs(f[2] - start[2]) > TILE / 8
							&& Math.abs(f[1] - destX) + Math.abs(f[2] - destY) > TILE / 8)
						{
							between.merge(object, 1, Integer::sum);
							break;
						}
					}
				}
				pendingDest = null;

				int cover = fastestCover(drawn, NOISE_STEP);
				if (cover > 0)
				{
					golemCover.computeIfAbsent(object, k -> new ArrayList<>()).add(cover);
				}

				Integer want = playerAnim.get(object);
				int clip = -1;
				int animStart = -1;
				for (Map.Entry<Integer, int[]> e : drawn.entrySet())
				{
					int[] f = e.getValue();
					if (f[4] >= 0 && f[5] >= 0 && (want == null || f[4] == want))
					{
						clip = f[4];
						animStart = e.getKey();
						break;
					}
				}
				if (animStart < 0)
				{
					continue;
				}

				java.util.SortedMap<Integer, int[]> during = drawn.tailMap(animStart, true);
				TreeMap<Integer, Integer> keys = new TreeMap<>();
				for (Map.Entry<Integer, int[]> e : during.entrySet())
				{
					int[] f = e.getValue();
					keys.put(e.getKey() - animStart, f[4] == clip ? f[5] : -1);
				}
				Map<Integer, List<Integer>> table = golemKeys.computeIfAbsent(object, k -> new TreeMap<>());
				for (int e = 0; e <= keys.lastKey(); e++)
				{
					table.computeIfAbsent(e, k -> new ArrayList<>()).add(keys.floorEntry(e).getValue());
				}
				int[] moved = movementKeyframes(during, clip);
				if (moved != null)
				{
					golemMoves.computeIfAbsent(object, k -> new ArrayList<>()).add(moved);
				}
			}
		}

		for (int object : objects)
		{
			int total = performances.getOrDefault(object, 0);
			if (total == 0)
			{
				continue;
			}

			List<Integer> gc = golemCover.get(object);
			Integer pc = playerCover.get(object);
			if (pc != null && gc != null && !gc.isEmpty())
			{
				evidence(object, "drawn crossing of a " + NOISE_STEP + "-unit jump: player " + pc
					+ " cycle(s), golems " + median(gc) + " (median of " + gc.size() + ", fastest "
					+ java.util.Collections.min(gc) + ")");
				// No finding from this alone. Drawn frames are logged per rendered frame, often
				// two cycles apart, so a golem that teleports between two frames still reads as
				// taking two cycles. Whether it slid is judged by whether it was ever drawn
				// part-way across, below.
			}

			if (pc != null && pc <= 1)
			{
				int mid = between.getOrDefault(object, 0);
				evidence(object, "drawn: " + mid + " of " + total + " performances showed the golem "
					+ "part-way across a jump the player makes in one cycle");
				if (mid > 0 && mid * 5 >= total)
				{
					add(object, REPLAY, "the player crosses this in a single cycle, but " + mid + " of "
						+ total + " golems were drawn part-way across - on screen it is a very quick "
						+ "slide, not a teleport");
				}
			}

			Map<Integer, List<Integer>> pk = playerKeys.get(object);
			Map<Integer, List<Integer>> gk = golemKeys.get(object);
			if (pk == null || gk == null)
			{
				evidence(object, "drawn: " + total + " performances; no animation to compare");
				continue;
			}

			int count = keyframeCount.getOrDefault(object, 0);
			int compared = 0;
			int worstAt = -1;
			int worstGap = 0;
			int worstPlayer = 0;
			int worstGolem = 0;
			int playerLow = Integer.MAX_VALUE;
			int playerHigh = -1;
			int golemLow = Integer.MAX_VALUE;
			int golemHigh = -1;
			for (Map.Entry<Integer, List<Integer>> e : pk.entrySet())
			{
				List<Integer> g = gk.get(e.getKey());
				if (g == null)
				{
					continue;
				}
				compared++;
				int p = median(e.getValue());
				int gv = median(g);
				playerLow = Math.min(playerLow, p);
				playerHigh = Math.max(playerHigh, p);
				if (gv >= 0)
				{
					golemLow = Math.min(golemLow, gv);
					golemHigh = Math.max(golemHigh, gv);
				}
				// A golem whose clip has already ended is past the last keyframe.
				int gap = Math.abs((gv < 0 ? count : gv) - p);
				if (gap > worstGap)
				{
					worstGap = gap;
					worstAt = e.getKey();
					worstPlayer = p;
					worstGolem = gv;
				}
			}

			String moves = describeMoves(playerMoves.get(object), golemMoves.get(object));
			evidence(object, "drawn: " + total + " performances; keyframes compared over " + compared
				+ " cycles, widest gap " + worstGap + (worstAt < 0 ? "" : " at " + worstAt
				+ " cycles in (player " + worstPlayer + ", golems " + describeKey(worstGolem) + ")")
				+ "; " + moves);

			if (compared >= 5 && worstGap >= 2)
			{
				String how;
				if (golemHigh >= 0 && golemHigh - golemLow <= 1 && playerHigh - playerLow >= 3)
				{
					how = "golems' animation stalls on keyframe " + golemLow + " while the player's runs "
						+ "from " + playerLow + " to " + playerHigh;
				}
				else if (worstGolem < 0 || worstGolem > worstPlayer)
				{
					how = "golems' animation runs ahead of the player's";
				}
				else
				{
					how = "golems' animation lags behind the player's";
				}
				add(object, REPLAY, how + " - " + worstAt + " cycles into it the player is on keyframe "
					+ worstPlayer + " of " + count + " and golems on " + describeKey(worstGolem)
					+ ". " + moves);
			}
		}
	}

	/**
	 * The keyframes a traversal's movement starts and ends on, {start, end}, or null if it
	 * does not move. -1 means the animation had already ended.
	 *
	 * <p>Movement means travelling at least 32 units in four cycles. That skips the few
	 * units a cycle a player drifts while settling onto an obstacle, which is not the
	 * obstacle moving them.
	 */
	private static int[] movementKeyframes(java.util.SortedMap<Integer, int[]> rows, int clip)
	{
		if (!(rows instanceof java.util.NavigableMap) || rows.isEmpty())
		{
			return null;
		}
		java.util.NavigableMap<Integer, int[]> map = (java.util.NavigableMap<Integer, int[]>) rows;
		Integer first = null;
		Integer last = null;
		for (Map.Entry<Integer, int[]> e : map.entrySet())
		{
			Map.Entry<Integer, int[]> ahead = map.floorEntry(e.getKey() + 4);
			int[] a = e.getValue();
			int[] b = ahead.getValue();
			if (Math.abs(b[1] - a[1]) + Math.abs(b[2] - a[2]) >= 32)
			{
				if (first == null)
				{
					first = e.getKey();
				}
				last = ahead.getKey();
			}
		}
		if (first == null)
		{
			return null;
		}
		int[] start = map.get(first);
		int[] end = map.get(last);
		return new int[]{start[4] == clip ? start[5] : -1, end[4] == clip ? end[5] : -1};
	}

	private static String describeMoves(List<int[]> player, List<int[]> golems)
	{
		return "the player moves during keyframes " + span(player) + ", golems during " + span(golems);
	}

	private static String span(List<int[]> moves)
	{
		if (moves == null || moves.isEmpty())
		{
			return "(no movement seen)";
		}
		List<Integer> starts = new ArrayList<>();
		List<Integer> ends = new ArrayList<>();
		for (int[] m : moves)
		{
			starts.add(m[0]);
			ends.add(m[1]);
		}
		return describeKey(median(starts)) + " to " + describeKey(median(ends));
	}

	private static String describeKey(int keyframe)
	{
		return keyframe < 0 ? "after the animation ended" : String.valueOf(keyframe);
	}

	/**
	 * Whether a golem can ever get onto a route, and get off where it lands.
	 *
	 * <p>Asked of the plugin's own mesh rather than inferred from behaviour, so it is known
	 * before a single golem has walked past. Obstacles routinely start and end on tiles the
	 * collision map blocks — the stile moves the player onto itself before it climbs them
	 * over — and a route whose start nothing can reach is one no golem will ever take.
	 */
	private void checkRouteAccess(TreeSet<Integer> objects, Map<Integer, List<int[]>> routes,
		Map<Integer, List<int[]>> shipped, Journal journal)
	{
		if (probeWalkable == null)
		{
			return;
		}

		java.util.Set<String> origins = new java.util.HashSet<>();
		List<int[]> edges = new ArrayList<>();
		List<List<int[]>> all = new ArrayList<>(routes.values());
		all.addAll(shipped.values());
		for (List<int[]> rows : all)
		{
			for (int[] row : rows)
			{
				// A learned route seen once is not offered to golems, so it is no way onward.
				if (row.length > 6 && row[6] < 2)
				{
					continue;
				}
				origins.add(row[0] + "," + row[1] + "," + row[2]);
				edges.add(row);
			}
		}

		// Somewhere a golem can come to stand: walkable ground, or where a transport it can
		// already reach puts it down. Grown to a fixed point, because "another route lands
		// there" is not enough on its own — the stile's two tiles are each other's only way
		// in, so each vouched for the other and neither could be reached from anywhere.
		java.util.Set<String> reachable = new java.util.HashSet<>();
		java.util.Map<String, Boolean> ground = new HashMap<>();
		boolean grew = true;
		while (grew)
		{
			grew = false;
			for (int[] row : edges)
			{
				String start = row[0] + "," + row[1] + "," + row[2];
				boolean from = reachable.contains(start) || ground.computeIfAbsent(start,
					k -> walkable(row[0], row[1], row[2]));
				if (from)
				{
					reachable.add(start);
					if (reachable.add(row[3] + "," + row[4] + "," + row[5]))
					{
						grew = true;
					}
				}
			}
		}

		for (int object : objects)
		{
			for (int[] route : routes.getOrDefault(object, new ArrayList<>()))
			{
				String start = route[0] + "," + route[1] + "," + route[2];
				String end = route[3] + "," + route[4] + "," + route[5];

				if (!walkable(route[0], route[1], route[2]) && !reachable.contains(start))
				{
					int[] approach = approachTo(journal, object, route);
					add(object, REPLAY, "learned route starts on " + start + ", which golems cannot "
						+ "walk onto, and no transport from anywhere they can reach arrives there - so "
						+ "no golem can ever take it"
						+ (approach == null ? "" : " - the player was moved onto it from "
						+ approach[0] + "," + approach[1] + "," + approach[2] + " by the obstacle itself"
						+ (walkable(approach[0], approach[1], approach[2])
						? ", a walkable tile, which is the real way in" : "")));
				}

				if (!walkable(route[3], route[4], route[5]))
				{
					if (!origins.contains(end))
					{
						add(object, REPLAY, "learned route lands on " + end + ", which golems cannot "
							+ "walk on and no transport leaves from - a dead end");
					}
					else
					{
						evidence(object, "lands on " + end + ", which is not walkable; the only way "
							+ "off is the transport that starts there");
					}
				}
			}
		}
	}

	/**
	 * Learned routes that do not run along the line the obstacle moves the player.
	 *
	 * <p>An obstacle moves the player along a fixed line — the stile from one of its tiles to
	 * the other, straight across. A route whose ends are off that line is performed along the
	 * line between its ends, which is a slant: golems cross the stile diagonally and come
	 * down a tile beside it. The line is read from the raw journal, the player's tile just
	 * before the animation and where they were when it ended, so it does not depend on how
	 * the route's ends were chosen.
	 */
	private void checkRouteLine(TreeSet<Integer> objects, Map<Integer, List<int[]>> routes,
		Journal journal)
	{
		for (int object : objects)
		{
			Map<String, Integer> lines = new HashMap<>();
			for (Sighting s : journal.sightingsFor(object))
			{
				if (s.silent || s.windowStart < 0)
				{
					continue;
				}
				int[] started = animStartFor(journal, s);
				if (started[0] < 0)
				{
					continue;
				}
				int[] before = null;
				for (int[] t : journal.ticks)
				{
					if (t[0] >= started[0])
					{
						break;
					}
					before = t;
				}
				int[] landing = null;
				for (int[] a : journal.anims)
				{
					if (a[0] > started[0] && a[0] <= s.cycle && a[1] == -1 && a.length > 4)
					{
						landing = new int[]{a[0], a[2], a[3], a[4]};
						break;
					}
				}
				if (before == null || landing == null || before[3] != landing[3])
				{
					continue;
				}
				int mx = landing[1] - before[1];
				int my = landing[2] - before[2];
				if ((mx == 0 && my == 0) || Math.abs(mx) > 8 || Math.abs(my) > 8)
				{
					continue;
				}
				lines.merge(mx + "," + my, 1, Integer::sum);
			}
			if (lines.isEmpty())
			{
				continue;
			}
			String line = null;
			for (Map.Entry<String, Integer> e : lines.entrySet())
			{
				if (line == null || e.getValue() > lines.get(line))
				{
					line = e.getKey();
				}
			}
			String[] parts = line.split(",");
			int mx = Integer.parseInt(parts[0]);
			int my = Integer.parseInt(parts[1]);
			evidence(object, "the obstacle moves the player along (" + line + ")");

			for (int[] route : routes.getOrDefault(object, new ArrayList<>()))
			{
				int rx = route[3] - route[0];
				int ry = route[4] - route[1];
				if (route[2] != route[5] || (rx == 0 && ry == 0) || Math.abs(rx) > 8 || Math.abs(ry) > 8)
				{
					continue;
				}
				// Either way along the line: a stile is crossed in both directions.
				double cos = Math.abs(rx * mx + ry * my) / (Math.hypot(rx, ry) * Math.hypot(mx, my));
				double degrees = Math.toDegrees(Math.acos(Math.min(1, cos)));
				if (degrees > 10)
				{
					add(object, LEARNING, String.format("learned route %s -> %s runs %.0f degrees off "
						+ "the line the obstacle moves the player along (%s) - golems taking it cross "
						+ "on a slant and come down beside the obstacle", point(route, 0),
						point(route, 3), degrees, line));
				}
			}
		}
	}

	/** Golem uses per route, "object:x,y,plane" of the landing, counted during replay. */
	private final Map<String, Integer> routeTakes = new HashMap<>();

	/**
	 * Routes golems go through and never come back through.
	 *
	 * <p>A one-way obstacle fills whatever is behind it. The stile's pen held three hundred
	 * golems by the time anyone noticed, because the way out had been learned ending on the
	 * stile itself and was never usable — and nothing about that looks wrong from either
	 * side, only from the count.
	 */
	private void checkOneWay(TreeSet<Integer> objects, Map<Integer, List<int[]>> routes, Journal journal)
	{
		for (int object : objects)
		{
			for (int[] route : routes.getOrDefault(object, new ArrayList<>()))
			{
				if (route[6] < 2)
				{
					continue;
				}
				int through = routeTakes.getOrDefault(object + ":" + route[3] + "," + route[4] + "," + route[5], 0);
				if (through < 3)
				{
					continue;
				}

				int[] back = null;
				int backObject = -1;
				for (Map.Entry<Integer, List<int[]>> other : routes.entrySet())
				{
					for (int[] r : other.getValue())
					{
						if (r[6] >= 2 && r[2] == route[5] && r[5] == route[2]
							&& Math.abs(r[0] - route[3]) <= 1 && Math.abs(r[1] - route[4]) <= 1
							&& Math.abs(r[3] - route[0]) <= 1 && Math.abs(r[4] - route[1]) <= 1)
						{
							back = r;
							backObject = other.getKey();
						}
					}
				}
				if (back == null)
				{
					add(object, REPLAY, "golems went through " + point(route, 0) + " -> " + point(route, 3)
						+ " " + through + " times and no usable way back is known - golems that go "
						+ "through stay there");
					continue;
				}

				int returned = routeTakes.getOrDefault(backObject + ":" + back[3] + "," + back[4] + "," + back[5], 0);
				if (returned > 0)
				{
					continue;
				}
				TreeSet<String> waiting = new TreeSet<>();
				for (Map.Entry<String, List<State>> entry : journal.states.entrySet())
				{
					for (State s : entry.getValue())
					{
						if (s.tile[2] == back[2] && Math.abs(s.tile[0] - back[0]) <= 2
							&& Math.abs(s.tile[1] - back[1]) <= 2)
						{
							waiting.add(entry.getKey());
							break;
						}
					}
				}
				String landing = back[3] + "," + back[4] + "," + back[5];
				String why = probeWalkable == null ? "" : walkable(back[3], back[4], back[5]) ? ""
					: "; the way back sets golems down on " + landing + ", which they cannot walk on"
					+ (hasUsableRouteFrom(routes, back[3], back[4], back[5]) ? ""
					: " and no usable route leaves, so it is refused");
				add(object, REPLAY, "golems went through " + point(route, 0) + " -> " + point(route, 3) + " "
					+ through + " times and back through " + point(back, 0) + " -> " + point(back, 3)
					+ " never, though " + waiting.size() + " golems came within two tiles of the way "
					+ "back - they pile up on the far side" + why);
			}
		}
	}

	private static boolean hasUsableRouteFrom(Map<Integer, List<int[]>> routes, int x, int y, int plane)
	{
		for (List<int[]> rows : routes.values())
		{
			for (int[] r : rows)
			{
				if (r[6] >= 2 && r[0] == x && r[1] == y && r[2] == plane)
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Golems piled onto one tile, and whether the client drew them.
	 *
	 * <p>The game draws only a few objects per tile. Rather than trust a number for that, the
	 * journal records when each golem was last drawn, and golems on crowded tiles are compared
	 * against golems standing alone — the alone ones are hidden only by the camera, so any
	 * difference is the crowding.
	 */
	private void checkCrowding(Journal journal)
	{
		Map<Integer, Map<String, int[]>> byCycle = new HashMap<>();
		boolean drawnInfo = false;
		for (List<State> states : journal.states.values())
		{
			for (State s : states)
			{
				int[] cell = byCycle.computeIfAbsent(s.cycle, k -> new HashMap<>())
					.computeIfAbsent(s.tile[0] + "," + s.tile[1] + "," + s.tile[2], k -> new int[3]);
				cell[0]++;
				if (s.drawnAgo >= 0)
				{
					drawnInfo = true;
					cell[1] += s.drawnAgo > 5 ? 1 : 0;
				}
				cell[2] += s.undrawn ? 1 : 0;
			}
		}

		int crowded = 0;
		int deepest = 0;
		String deepestAt = null;
		long crowdedGolems = 0;
		long crowdedMissed = 0;
		long crowdedHeld = 0;
		long alone = 0;
		long aloneMissed = 0;
		for (Map<String, int[]> tiles : byCycle.values())
		{
			for (Map.Entry<String, int[]> e : tiles.entrySet())
			{
				int[] cell = e.getValue();
				if (cell[0] > deepest)
				{
					deepest = cell[0];
					deepestAt = e.getKey();
				}
				if (cell[0] >= 5)
				{
					crowded++;
					crowdedGolems += cell[0];
					crowdedMissed += cell[1];
					crowdedHeld += cell[2];
				}
				else if (cell[0] == 1)
				{
					alone++;
					aloneMissed += cell[1];
				}
			}
		}
		if (deepest < 5)
		{
			return;
		}
		String drawn = !drawnInfo ? "; this journal has no drawn-frame data to show whether they were hidden"
			: String.format("; on those tiles %.0f%% of golems went undrawn by the client and %.0f%% were held "
			+ "back from drawing, against %.0f%% undrawn standing alone", 100.0 * crowdedMissed / crowdedGolems,
			100.0 * crowdedHeld / crowdedGolems, alone == 0 ? 0 : 100.0 * aloneMissed / alone);
		world.add("[replay] golems stood five or more deep on one tile " + crowded + " times (deepest "
			+ deepest + " at " + deepestAt + ")" + drawn);
		byCategory.merge(REPLAY, 1, Integer::sum);
	}

	private static final int[] ISLAND_REGIONS = {10018, 10019, 10020, 10274, 10275, 10276, 10530, 10531, 10532};
	private static final int PLINTH_X = 2596;
	private static final int PLINTH_Y = 2256;

	/**
	 * Golems seen where they cannot be: on ground they cannot walk, or on walkable ground
	 * nothing connects to where golems start.
	 *
	 * <p>The first is a golem on the water. The second is a golem in a sealed room — the
	 * static copy of a boss room, which exists in the world but has no way in on foot. Both
	 * are invisible to every other check, because a golem standing still does nothing wrong
	 * frame to frame; what is wrong is where it is. Reachability is worked out from the
	 * plinth over the golems' own step test and every transport golems may take.
	 */
	private void checkGolemGround(Journal journal, Map<Integer, List<int[]>> routes,
		Map<Integer, List<int[]>> shipped)
	{
		if (probeWalkable == null || probeCanStep == null || journal.states.isEmpty())
		{
			return;
		}

		java.util.Set<Integer> island = new java.util.HashSet<>();
		for (int r : ISLAND_REGIONS)
		{
			island.add(r);
		}
		java.util.Set<Integer> allowed = new java.util.HashSet<>(island);
		Map<Long, List<Long>> edges = new HashMap<>();
		List<int[]> rows = new ArrayList<>();
		for (List<int[]> list : routes.values())
		{
			for (int[] r : list)
			{
				if (r[6] >= 2)
				{
					rows.add(r);
				}
			}
		}
		for (List<int[]> list : shipped.values())
		{
			for (int[] r : list)
			{
				if (island.contains(regionOf(r[0], r[1])))
				{
					rows.add(r);
				}
			}
		}
		java.util.Set<Long> origins = new java.util.HashSet<>();
		for (int[] r : rows)
		{
			int rx = r[3] >> 6;
			int ry = r[4] >> 6;
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					allowed.add((rx + dx) << 8 | (ry + dy));
				}
			}
			origins.add(tileKey(r[0], r[1], r[2]));
			edges.computeIfAbsent(tileKey(r[0], r[1], r[2]), k -> new ArrayList<>()).add(tileKey(r[3], r[4], r[5]));
		}

		java.util.Set<Long> reachable = new java.util.HashSet<>();
		java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
		long start = tileKey(PLINTH_X, PLINTH_Y, 0);
		reachable.add(start);
		queue.add(start);
		try
		{
			while (!queue.isEmpty() && reachable.size() < 600_000)
			{
				long at = queue.poll();
				int x = (int) (at >> 20 & 0xFFFFF);
				int y = (int) (at & 0xFFFFF);
				int plane = (int) (at >> 40);
				for (int dx = -1; dx <= 1; dx++)
				{
					for (int dy = -1; dy <= 1; dy++)
					{
						if ((dx == 0 && dy == 0) || !allowed.contains(regionOf(x + dx, y + dy)))
						{
							continue;
						}
						long next = tileKey(x + dx, y + dy, plane);
						if (!reachable.contains(next) && (Boolean) probeCanStep.invoke(null, x, y, plane, dx, dy))
						{
							reachable.add(next);
							queue.add(next);
						}
					}
				}
				for (long next : edges.getOrDefault(at, new ArrayList<>()))
				{
					if (reachable.add(next))
					{
						queue.add(next);
					}
				}
			}
		}
		catch (ReflectiveOperationException e)
		{
			return;
		}

		Map<String, java.util.Set<String>> unwalkable = new TreeMap<>();
		Map<String, Integer> onRoute = new HashMap<>();
		Map<String, java.util.Set<String>> sealed = new TreeMap<>();
		for (Map.Entry<String, List<State>> entry : journal.states.entrySet())
		{
			for (State s : entry.getValue())
			{
				if (s.performing || s.climbing)
				{
					continue;
				}
				long key = tileKey(s.tile[0], s.tile[1], s.tile[2]);
				String tile = s.tile[0] + "," + s.tile[1] + "," + s.tile[2];
				if (!walkable(s.tile[0], s.tile[1], s.tile[2]))
				{
					if (!origins.contains(key))
					{
						unwalkable.computeIfAbsent(tile, k -> new java.util.TreeSet<>()).add(entry.getKey());
						if (s.itinerary)
						{
							onRoute.merge(tile, 1, Integer::sum);
						}
					}
				}
				else if (allowed.contains(regionOf(s.tile[0], s.tile[1])) && !reachable.contains(key))
				{
					String chunk = (s.tile[0] >> 3 << 3) + "," + (s.tile[1] >> 3 << 3) + "," + s.tile[2];
					sealed.computeIfAbsent(chunk, k -> new java.util.TreeSet<>()).add(entry.getKey());
				}
			}
		}

		evidence(-1, "reachable from the plinth on foot or by transport: " + reachable.size() + " tiles");
		// Judged by the live game, where the journal has it. This is the reading to trust.
		if (!journal.blockedStanding.isEmpty())
		{
			java.util.Set<String> golems = new java.util.TreeSet<>();
			for (java.util.Set<String> seen : journal.blockedStanding.values())
			{
				golems.addAll(seen);
			}
			world.add("[replay] by the live game's collision, " + golems.size() + " golems stood on "
				+ journal.blockedStanding.size() + " tiles nothing can stand on - water or scenery - outside "
				+ "any traversal; " + journal.blockedOnRoute.size() + " of them while following a route. Most: "
				+ topSets(journal.blockedStanding));
			byCategory.merge(REPLAY, 1, Integer::sum);
		}
		if (!unwalkable.isEmpty())
		{
			// The open sea is named apart: the client never blocks it, so a golem out there is a
			// map that trusted the client, not scenery the saved map got wrong.
			int sea = 0;
			for (String tile : unwalkable.keySet())
			{
				String[] at = tile.split(",");
				if (ocean(Integer.parseInt(at[0]), Integer.parseInt(at[1]), Integer.parseInt(at[2])))
				{
					sea++;
				}
			}
			world.add("[replay] by the saved map, which can itself be wrong, golems stood on ground they "
				+ "cannot walk, outside any traversal, on "
				+ unwalkable.size() + " tiles - " + sea + " of them on the open sea, the rest water or scenery. "
				+ "Most golems: " + topSets(unwalkable) + describeRouted(onRoute));
			byCategory.merge(REPLAY, 1, Integer::sum);
		}
		// No finding for sealed rooms. Reachability from the plinth depends on the saved map
		// being complete, and a map that has not yet been walked over leaves whole open fields
		// unconnected — it reported a field golems roam freely as a sealed room. A real sealed
		// room needs the live game to judge; until then this is only counted.
		if (!sealed.isEmpty())
		{
			evidence(-1, "areas not connected to the plinth by the saved map: " + sealed.size());
		}
	}

	private static String topSets(Map<String, java.util.Set<String>> byTile)
	{
		List<Map.Entry<String, java.util.Set<String>>> sorted = new ArrayList<>(byTile.entrySet());
		sorted.sort((a, b) -> b.getValue().size() - a.getValue().size());
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < Math.min(5, sorted.size()); i++)
		{
			out.append(i == 0 ? "" : "; ").append(sorted.get(i).getKey()).append(" (")
				.append(sorted.get(i).getValue().size()).append(" golems)");
		}
		return out.toString();
	}

	private static String describeRouted(Map<String, Integer> onRoute)
	{
		int total = 0;
		for (int n : onRoute.values())
		{
			total += n;
		}
		return total == 0 ? "" : "; " + total + " of those sightings were golems following a route";
	}

	private static int regionOf(int x, int y)
	{
		return (x >> 6) << 8 | (y >> 6);
	}

	private static long tileKey(int x, int y, int plane)
	{
		return ((long) plane << 40) | ((long) x << 20) | y;
	}

	/** Findings from what golems did anywhere, rather than on any one obstacle. */
	private void checkWorldEvents(Journal journal)
	{
		int crossed = 0;
		for (int n : journal.blockedEdges.values())
		{
			crossed += n;
		}
		if (crossed > 0)
		{
			// For each of the worst, whether the golems' own map thought the step was open.
			// Open means the map is out of date with the game — a door shut since it was read.
			// Blocked means the golem stepped against its own map, which is a movement bug.
			List<Map.Entry<String, Integer>> worst = new ArrayList<>(journal.blockedEdges.entrySet());
			worst.sort((a, b) -> b.getValue() - a.getValue());
			StringBuilder detail = new StringBuilder();
			for (int i = 0; i < Math.min(5, worst.size()); i++)
			{
				String edge = worst.get(i).getKey();
				detail.append(i == 0 ? "" : "; ").append(edge).append(" x").append(worst.get(i).getValue());
				String map = golemMapAllows(edge);
				if (map != null)
				{
					detail.append(" (golem map: ").append(map).append(")");
				}
				String flags = journal.edgeFlags.get(edge);
				if (flags != null)
				{
					detail.append(" [flags ").append(flags).append("]");
				}
			}
			world.add("[replay] golems stepped across " + crossed + " edges the live game had "
				+ "blocked - a shut door or a wall - most often " + detail);
			byCategory.merge(REPLAY, 1, Integer::sum);
		}
		// One finding for all of them, worst first. A dead end repeated hundreds of times at
		// one tile is a golem ping-ponging between two landings, and it is the count that
		// shows it; eighteen separate lines hid that.
		int deadEnds = 0;
		for (int n : journal.deadEnds.values())
		{
			deadEnds += n;
		}
		if (deadEnds > 0)
		{
			List<Map.Entry<String, Integer>> sorted = new ArrayList<>(journal.deadEnds.entrySet());
			sorted.sort((a, b) -> b.getValue() - a.getValue());
			StringBuilder worst = new StringBuilder();
			for (int i = 0; i < Math.min(3, sorted.size()); i++)
			{
				worst.append(i == 0 ? "" : "; ").append(sorted.get(i).getValue()).append(" at ")
					.append(sorted.get(i).getKey()).append(" (").append(groundAt(sorted.get(i).getKey())
					.replace(". The plugin's mesh says ", "").replace(sorted.get(i).getKey() + " ", ""))
					.append(")");
			}
			world.add("[replay] " + deadEnds + " times golems found nowhere to walk and went back the "
				+ "way they came, at " + sorted.size() + " tiles; hundreds at one tile is a golem "
				+ "ping-ponging between two landings. Worst: " + worst);
			byCategory.merge(REPLAY, 1, Integer::sum);
		}
	}

	/** "open" or "blocked" for an edge written "x,y,p to x,y,p", by the golems' own map; null if unknown. */
	private String golemMapAllows(String edge)
	{
		if (probeCanStep == null)
		{
			return null;
		}
		try
		{
			String[] ends = edge.split(" to ");
			String[] a = ends[0].split(",");
			String[] b = ends[1].split(",");
			int x = Integer.parseInt(a[0]);
			int y = Integer.parseInt(a[1]);
			int plane = Integer.parseInt(a[2]);
			boolean open = (Boolean) probeCanStep.invoke(null, x, y, plane,
				Integer.parseInt(b[0]) - x, Integer.parseInt(b[1]) - y);
			return open ? "open" : "blocked";
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			return null;
		}
	}

	private static String top(Map<String, Integer> counts)
	{
		List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
		sorted.sort((a, b) -> b.getValue() - a.getValue());
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < Math.min(5, sorted.size()); i++)
		{
			out.append(i == 0 ? "" : "; ").append(sorted.get(i).getKey()).append(" x")
				.append(sorted.get(i).getValue());
		}
		return out.toString();
	}

	/** The last cycle each object was recorded, and whether golems performed it after that. */
	private final Map<Integer, Integer> latestRecording = new HashMap<>();
	private final java.util.Set<Integer> performedSince = new java.util.HashSet<>();

	private void prepareCurrency(Journal journal)
	{
		for (Sighting s : journal.sightings)
		{
			if (s.curveCycles > 0)
			{
				latestRecording.merge(s.object, s.cycle, Math::max);
			}
		}
		for (Act a : journal.golemActs)
		{
			if (a.object >= 0 && a.cycle > latestRecording.getOrDefault(a.object, Integer.MIN_VALUE))
			{
				performedSince.add(a.object);
			}
		}
	}

	/**
	 * Whether a golem performance reflects the current recording.
	 *
	 * <p>A fix that needs a fresh recording only shows after that recording is made, and
	 * counting every performance from the start of the session outvoted it: the rockslide
	 * was reported as facing the wrong way long after golems had started facing the rock.
	 * Where golems have performed since the latest recording, only those performances
	 * count; where they have not, every one does.
	 */
	private boolean current(int object, int cycle)
	{
		return !performedSince.contains(object)
			|| cycle > latestRecording.getOrDefault(object, Integer.MIN_VALUE);
	}

	/** The tile the player stood on just before being put on a route's start, if seen. */
	private static int[] approachTo(Journal journal, int object, int[] route)
	{
		Map<String, Integer> seen = new HashMap<>();
		for (Sighting s : journal.sightingsFor(object))
		{
			if (s.windowStart < 0 || s.from[0] != route[0] || s.from[1] != route[1]
				|| s.from[2] != route[2])
			{
				continue;
			}
			int[] before = null;
			for (int[] t : journal.ticks)
			{
				if (t[0] < s.windowStart || t[0] > s.cycle)
				{
					continue;
				}
				if (t[1] == route[0] && t[2] == route[1] && t[3] == route[2])
				{
					break;
				}
				before = t;
			}
			if (before != null)
			{
				seen.merge(before[1] + "," + before[2] + "," + before[3], 1, Integer::sum);
			}
		}
		String best = null;
		for (Map.Entry<String, Integer> e : seen.entrySet())
		{
			if (best == null || e.getValue() > seen.get(best))
			{
				best = e.getKey();
			}
		}
		if (best == null)
		{
			return null;
		}
		String[] p = best.split(",");
		return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
	}

	/**
	 * What the plugin's own mesh says about a tile, as a clause for a finding.
	 *
	 * <p>Two tests, because golems use two: the pathfinder walks on the island memory, and
	 * the roam planner only picks destinations on the land fill. A tile can pass the first
	 * and fail the second, which leaves a golem standing somewhere it may walk but can find
	 * nowhere to walk to.
	 */
	private String groundAt(String tile)
	{
		if (probeWalkable == null || tile == null)
		{
			return "";
		}
		String[] p = tile.split(",");
		int x = Integer.parseInt(p[0]);
		int y = Integer.parseInt(p[1]);
		int plane = Integer.parseInt(p[2]);
		boolean land;
		try
		{
			land = (Boolean) probeLand.invoke(null, x, y, plane);
		}
		catch (ReflectiveOperationException e)
		{
			land = true;
		}
		return ". The plugin's mesh says " + tile + " is " + (walkable(x, y, plane) ? "" : "not ")
			+ "walkable for the pathfinder and " + (land ? "is" : "is not") + " on the land fill "
			+ "the roam planner picks destinations from";
	}

	private boolean walkable(int x, int y, int plane)
	{
		try
		{
			return (Boolean) probeWalkable.invoke(null, x, y, plane);
		}
		catch (ReflectiveOperationException e)
		{
			// Never guess. A probe that fails silently answered "walkable" for every tile the
			// island memory had not harvested, and every finding built on it was wrong.
			throw new IllegalStateException("walkability probe failed at " + x + "," + y + "," + plane,
				e.getCause() != null ? e.getCause() : e);
		}
	}

	private void loadProbe(String islandMap)
	{
		try
		{
			Class<?> probe = Class.forName("com.golemsdontdie.MeshProbe");
			probe.getMethod("load", String.class).invoke(null, islandMap);
			probeWalkable = probe.getMethod("walkable", int.class, int.class, int.class);
			probeLand = probe.getMethod("landWalkable", int.class, int.class, int.class);
			probeOcean = probe.getMethod("ocean", int.class, int.class, int.class);
			probeCanStep = probe.getMethod("canStep", int.class, int.class, int.class, int.class, int.class);
		}
		catch (ReflectiveOperationException | LinkageError e)
		{
			probeWalkable = null;
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			System.out.println("walkability probe failed: " + cause);
		}
	}

	/**
	 * Takes config values from the client log where it is newer than the profile, and
	 * counts the rescues it reports.
	 *
	 * @return how many values were taken from the log
	 */
	private int overlayClientLog(Map<String, String> config, String profilePath) throws IOException
	{
		File log = new File(CLIENT_LOG);
		if (!log.isFile())
		{
			return 0;
		}
		boolean newer = log.lastModified() > new File(profilePath).lastModified();
		Pattern set = Pattern.compile("Setting configuration value for (golemsdontdie\\.[A-Za-z]+) to (.*)$");
		Pattern rescue = Pattern.compile("(?:Rescuing stuck golem \\d+ from|was on unwalkable ground at) "
			+ "WorldPoint\\(x=(\\d+), y=(\\d+), plane=(\\d+)\\)");
		Map<String, String> latest = new HashMap<>();
		try (BufferedReader reader = new BufferedReader(new FileReader(log)))
		{
			String line;
			while ((line = reader.readLine()) != null)
			{
				Matcher r = rescue.matcher(line);
				if (r.find())
				{
					logRescues.merge(r.group(1) + "," + r.group(2) + "," + r.group(3), 1, Integer::sum);
					continue;
				}
				if (newer && line.contains("Setting configuration value for golemsdontdie."))
				{
					Matcher m = set.matcher(line);
					if (m.find())
					{
						latest.put(m.group(1), m.group(2));
					}
				}
			}
		}
		config.putAll(latest);
		return latest.size();
	}

	// ================================================================== small maths

	/** The game's own orientation for a direction: 0 south, 512 west, 1024 north, 1536 east. */
	private static int heading(int dx, int dy)
	{
		return (int) (1024 + Math.round(Math.atan2(dx, dy) / Math.PI * 1024)) & 2047;
	}

	/** An orientation relative to travel, in eighths: 0 forwards, 4 backwards. */
	private static int bin(int relative)
	{
		return ((relative + 128) & 2047) / 256;
	}

	private static String describeFacing(int bin)
	{
		switch (bin)
		{
			case 0:
				return "the way they are going";
			case 4:
				return "backwards, toward where they started";
			case 2:
				return "sideways, to their right";
			case 6:
				return "sideways, to their left";
			default:
				return "half-turned (" + bin * 45 + " degrees)";
		}
	}

	private static int sum(int[] bins)
	{
		int total = 0;
		for (int b : bins)
		{
			total += b;
		}
		return total;
	}

	private static int mode(int[] bins)
	{
		int best = 0;
		for (int i = 1; i < bins.length; i++)
		{
			if (bins[i] > bins[best])
			{
				best = i;
			}
		}
		return best;
	}

	/**
	 * When this sighting's own animation began, {cycle, animation}, or {-1, -1}.
	 *
	 * <p>The first clip in the window that the sighting says it played. A window can hold
	 * another obstacle's clip first — climbing a ladder straight after climbing down it
	 * puts the descent's 827 ahead of the ascent's 828 — and taking any animation compared
	 * the player's climb up against the golems' climb down, which never lines up.
	 */
	private static int[] animStartFor(Journal journal, Sighting s)
	{
		int want = -1;
		if (!s.clips.isEmpty())
		{
			want = Integer.parseInt(s.clips.split(",")[0]);
		}
		for (int[] a : journal.anims)
		{
			if (a[0] >= s.windowStart && a[0] <= s.cycle && a[1] != -1 && (want < 0 || a[1] == want))
			{
				return new int[]{a[0], a[1]};
			}
		}
		return new int[]{-1, -1};
	}

	/** The player's positions for one sighting, by cycle. */
	private static TreeMap<Integer, int[]> positionsIn(Journal journal, Sighting s)
	{
		TreeMap<Integer, int[]> track = new TreeMap<>();
		if (s.windowStart < 0)
		{
			return track;
		}
		for (int[] p : journal.positions)
		{
			if (p[0] >= s.windowStart && p[0] <= s.cycle)
			{
				keepFine(track, p);
			}
		}
		return track;
	}

	/**
	 * Adds a position, never letting a lookback row replace a live one for the same cycle.
	 *
	 * <p>The lookback is flushed again whenever another animation starts, and a ladder
	 * starts one mid-climb — so its rows arrive after the live rows for the same cycles and
	 * carry no animation or keyframe. Taken in file order they wiped out the whole climb.
	 */
	private static void keepFine(TreeMap<Integer, int[]> track, int[] p)
	{
		track.merge(p[0], p, (held, incoming) -> incoming[3] < 0 && held[3] >= 0 ? held : incoming);
	}

	/**
	 * The fewest cycles in which the track covers at least this distance, or -1 if it never
	 * does. One for a teleport; several for anything that glides.
	 */
	private static int fastestCover(TreeMap<Integer, int[]> track, int distance)
	{
		List<Map.Entry<Integer, int[]>> points = new ArrayList<>(track.entrySet());
		int best = -1;
		for (int i = 0; i < points.size(); i++)
		{
			int[] a = points.get(i).getValue();
			for (int j = i + 1; j < points.size(); j++)
			{
				int span = points.get(j).getKey() - points.get(i).getKey();
				if (best > 0 && span >= best)
				{
					break;
				}
				int[] b = points.get(j).getValue();
				int moved = Math.abs(b[1] - a[1]) + Math.abs(b[2] - a[2]);
				// Past three tiles is a scene rebase or a cave mouth, not a crossing.
				if (moved >= distance && moved <= 3 * TILE)
				{
					best = span;
					break;
				}
			}
		}
		return best;
	}

	/**
	 * Learned routes golems walked right past and never used.
	 *
	 * <p>Green on the overlay says an obstacle is known. It says nothing about whether a golem
	 * can get to it — the stile's way in is a tile the planner refuses, and a staircase can
	 * lead somewhere the planner cannot see — so "known but never taken while golems stood
	 * next to it" is its own fault, and one nobody notices without standing there waiting.
	 */
	private void checkNeverTaken(TreeSet<Integer> objects, Journal journal,
		Map<Integer, List<int[]>> routes)
	{
		if (journal.states.isEmpty())
		{
			return;
		}
		for (int object : objects)
		{
			List<int[]> objectRoutes = routes.getOrDefault(object, new ArrayList<>());
			if (objectRoutes.isEmpty() || golemTakes.getOrDefault(object, 0) > 0)
			{
				continue;
			}

			// The first learned sighting, so golems wandering past before it was known
			// are not counted as having ignored it.
			int knownFrom = Integer.MIN_VALUE;
			for (Sighting s : journal.sightingsFor(object))
			{
				knownFrom = s.cycle;
				break;
			}

			TreeSet<String> golems = new TreeSet<>();
			int visits = 0;
			for (Map.Entry<String, List<State>> entry : journal.states.entrySet())
			{
				for (State s : entry.getValue())
				{
					if (s.cycle <= knownFrom)
					{
						continue;
					}
					for (int[] route : objectRoutes)
					{
						if (route[2] == s.tile[2] && Math.abs(route[0] - s.tile[0]) <= NEARBY_TILES
							&& Math.abs(route[1] - s.tile[1]) <= NEARBY_TILES)
						{
							golems.add(entry.getKey());
							visits++;
							break;
						}
					}
				}
			}
			evidence(object, "golems 0 takes, " + golems.size() + " golems within "
				+ NEARBY_TILES + " tiles of a way in for " + visits + " ticks");
			if (golems.size() >= 2)
			{
				add(object, REPLAY, "has " + objectRoutes.size() + " learned routes but no golem "
					+ "took it, though " + golems.size() + " golems came within " + NEARBY_TILES
					+ " tiles - it is known but not reachable, or not offered to the planner");
			}
		}
	}

	/**
	 * Learned routes that duplicate a shipped row end to end.
	 *
	 * <p>The network resolves each transport's reverse through a map keyed by endpoints, so
	 * two rows with the same endpoints leave only one of them findable. A golem coming
	 * through puts that one on cooldown, and the duplicate beside it is free — so it walks
	 * straight back. The rockslide has exactly this: the shipped table and the learned
	 * routes both carry 2550 to 2554, under different ids.
	 */
	private void checkDuplicateEndpoints(Map<Integer, List<int[]>> routes,
		Map<Integer, List<int[]>> shipped)
	{
		for (Map.Entry<Integer, List<int[]>> entry : routes.entrySet())
		{
			for (int[] route : entry.getValue())
			{
				for (Map.Entry<Integer, List<int[]>> other : shipped.entrySet())
				{
					for (int[] row : other.getValue())
					{
						boolean same = true;
						for (int k = 0; k < 6; k++)
						{
							same &= row[k] == route[k];
						}
						// The plugin now replaces a shipped row with the learned route that
						// duplicates it, so a duplicate is only a fault when the table files
						// the journey under a different object.
						if (same && !other.getKey().equals(entry.getKey()))
						{
							add(entry.getKey(), DATA, "learned route " + point(route, 0) + " -> "
								+ point(route, 3) + " is filed in the shipped table under "
								+ other.getKey() + " - the table has the wrong object for this journey");
						}
					}
				}
			}
		}
	}

	/**
	 * Golems that moved further than a step between two ticks without deciding anything.
	 *
	 * <p>Every way a golem is meant to get somewhere far is logged as a decision. A golem
	 * that turns up several tiles away without one was moved by something that is not
	 * logging — which is exactly how staircases went missing: taken, but invisible, so the
	 * journal said they never were.
	 */
	private void checkUnexplainedJumps(Journal journal)
	{
		Map<String, List<Act>> actsByGolem = new HashMap<>();
		for (Act act : journal.golemActs)
		{
			actsByGolem.computeIfAbsent(act.golem, k -> new ArrayList<>()).add(act);
		}

		Map<String, Integer> jumps = new HashMap<>();
		int total = 0;
		for (Map.Entry<String, List<State>> entry : journal.states.entrySet())
		{
			List<Act> acts = actsByGolem.getOrDefault(entry.getKey(), new ArrayList<>());
			State previous = null;
			for (State s : entry.getValue())
			{
				// Consecutive ticks only. A golem is not logged while it is out of range, so
				// two rows minutes apart show a walk as a jump.
				// Nor the landing at the end of a traversal, which is a jump by design.
				if (previous != null && !previous.performing && s.cycle - previous.cycle <= 35
					&& (previous.tile[2] != s.tile[2]
					|| Math.abs(previous.tile[0] - s.tile[0]) > 3
					|| Math.abs(previous.tile[1] - s.tile[1]) > 3))
				{
					boolean explained = false;
					for (Act act : acts)
					{
						if (act.cycle >= previous.cycle && act.cycle <= s.cycle)
						{
							explained = true;
							break;
						}
					}
					if (!explained)
					{
						jumps.merge(previous.tile[0] + "," + previous.tile[1] + "," + previous.tile[2]
							+ " -> " + s.tile[0] + "," + s.tile[1] + "," + s.tile[2], 1, Integer::sum);
						total++;
					}
				}
				previous = s;
			}
		}

		if (total == 0)
		{
			return;
		}
		List<Map.Entry<String, Integer>> worst = new ArrayList<>(jumps.entrySet());
		worst.sort((a, b) -> b.getValue() - a.getValue());
		StringBuilder top = new StringBuilder();
		for (int i = 0; i < Math.min(5, worst.size()); i++)
		{
			top.append(i == 0 ? "" : "; ").append(worst.get(i).getKey())
				.append(" x").append(worst.get(i).getValue());
		}
		world.add("[replay] " + total + " golem moves of more than three tiles, or between planes, "
			+ "with no decision logged for them - something moves golems without saying so. "
			+ "Most common: " + top);
		byCategory.merge(REPLAY, 1, Integer::sum);
	}

	/** Tiles covered by the recording a golem performed, or -1 if it cannot be told. */
	private static double recordedTiles(Journal journal, Map<Integer, Curve> curves, int object,
		Act act)
	{
		Sighting source = null;
		for (Sighting s : journal.sightings)
		{
			if (s.object == object && s.cycle < act.cycle && s.curveCycles == act.performing)
			{
				source = s;
			}
		}
		if (source != null)
		{
			return source.from[2] != source.to[2] ? -1
				: Math.hypot(source.to[0] - source.from[0], source.to[1] - source.from[1]);
		}
		Curve curve = curves.get(object);
		return curve != null && curve.cycles == act.performing
			? Math.abs(curve.reach()) / (double) TILE : -1;
	}

	private static int[] originOf(int object, int[] dest, State near,
		Map<Integer, List<int[]>> routes, Map<Integer, List<int[]>> shipped)
	{
		List<int[]> candidates = new ArrayList<>(routes.getOrDefault(object, new ArrayList<>()));
		candidates.addAll(shipped.getOrDefault(object, new ArrayList<>()));

		int[] best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int[] row : candidates)
		{
			if (row[3] != dest[0] || row[4] != dest[1] || row[5] != dest[2])
			{
				continue;
			}
			double distance = near == null ? 0 : Math.hypot(row[0] + 0.5 - near.fineX,
				row[1] + 0.5 - near.fineY);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = new int[]{row[0], row[1], row[2]};
			}
		}
		if (best == null && near != null)
		{
			best = near.tile;
		}
		return best;
	}

	private static boolean sameTile(int[] a, int[] b)
	{
		return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
	}

	private static State lastStateBefore(List<State> states, int cycle)
	{
		State found = null;
		for (State s : states)
		{
			if (s.cycle <= cycle)
			{
				found = s;
			}
		}
		return found;
	}

	private static State firstStateAfter(List<State> states, int cycle, boolean performing)
	{
		for (State s : states)
		{
			if (s.cycle > cycle && s.performing == performing)
			{
				return s;
			}
		}
		return null;
	}

	// ==================================================================== output

	private void report(TreeSet<Integer> objects, Map<Integer, Curve> curves,
		Map<Integer, List<int[]>> routes, Map<Integer, List<int[]>> shipped, Journal journal)
	{
		int clean = 0;
		for (int object : objects)
		{
			Curve curve = curves.get(object);
			List<Sighting> sightings = journal.sightingsFor(object);
			String name = sightings.isEmpty() ? "" : sightings.get(sightings.size() - 1).name;

			StringBuilder line = new StringBuilder();
			line.append(String.format("%-7d %-36s", object, name.length() > 36
				? name.substring(0, 36) : name));
			if (curve != null)
			{
				line.append(String.format(" curve %3dc %.1ft steps %d", curve.cycles,
					Math.abs(curve.reach()) / (double) TILE,
					Math.max(curve.bursts(), curve.animationStarts())));
			}
			else
			{
				line.append(" no curve               ");
			}
			line.append(String.format(" | shipped %d  routes %d  sightings %d",
				shipped.getOrDefault(object, new ArrayList<>()).size(),
				routes.getOrDefault(object, new ArrayList<>()).size(), sightings.size()));
			System.out.println(line);
			for (String fact : measured.getOrDefault(object, new ArrayList<>()))
			{
				System.out.println("          . " + fact);
			}

			List<String> found = faults.get(object);
			if (found == null)
			{
				clean++;
				continue;
			}
			for (String fault : found)
			{
				System.out.println("        " + fault);
			}
		}

		for (String finding : world)
		{
			System.out.println();
			System.out.println("golems  " + finding);
		}

		System.out.println();
		System.out.println(clean + " of " + objects.size() + " obstacles have no findings");
		if (!byCategory.isEmpty())
		{
			System.out.println();
			System.out.println("findings by where they live:");
			for (Map.Entry<String, Integer> e : byCategory.entrySet())
			{
				System.out.printf("  %-10s %d%n", e.getKey(), e.getValue());
			}
		}
	}

	/** Measurements behind the verdicts, printed so a clean result can be told from a skipped one. */
	private void evidence(int object, String text)
	{
		measured.computeIfAbsent(object, k -> new ArrayList<>()).add(text);
	}

	private void add(int object, String category, String text)
	{
		faults.computeIfAbsent(object, k -> new ArrayList<>()).add("[" + category + "] " + text);
		byCategory.merge(category, 1, Integer::sum);
	}

	// ================================================================== geometry

	private static double tiles(int[] row)
	{
		return Math.hypot(row[3] - row[0], row[4] - row[1]);
	}

	private static boolean near(int[] a, int at, int[] b, int bt)
	{
		return a[at + 2] == b[bt + 2] && Math.abs(a[at] - b[bt]) <= 1
			&& Math.abs(a[at + 1] - b[bt + 1]) <= 1;
	}

	/** True if (x,y) lies on the route's segment, away from both of its ends. */
	private static boolean strictlyBetween(int x, int y, int[] route)
	{
		double ax = route[0];
		double ay = route[1];
		double bx = route[3];
		double by = route[4];
		double length = Math.hypot(bx - ax, by - ay);
		if (length < 2)
		{
			return false;
		}
		double t = ((x - ax) * (bx - ax) + (y - ay) * (by - ay)) / (length * length);
		if (t <= 0.15 || t >= 0.85)
		{
			return false;
		}
		double px = ax + t * (bx - ax);
		double py = ay + t * (by - ay);
		return Math.hypot(px - x, py - y) <= 1.0;
	}

	private static String point(int[] a, int at)
	{
		return "(" + a[at] + "," + a[at + 1] + "," + a[at + 2] + ")";
	}

	private static int median(List<Integer> values)
	{
		List<Integer> sorted = new ArrayList<>(values);
		sorted.sort(null);
		return sorted.get(sorted.size() / 2);
	}

	// ================================================================= data types

	private static final class Curve
	{
		int cycles;
		int[] forward;
		int[] lateral;
		int[][] animations;

		int reach()
		{
			return forward.length == 0 ? 0 : forward[forward.length - 1];
		}

		int[] steps()
		{
			int[] out = new int[Math.max(0, forward.length - 1)];
			for (int i = 1; i < forward.length; i++)
			{
				out[i - 1] = forward[i] - forward[i - 1];
			}
			return out;
		}

		int maxLateral()
		{
			int most = 0;
			for (int value : lateral)
			{
				most = Math.max(most, Math.abs(value));
			}
			return most;
		}

		/** Separate movements, divided by holds long enough to be a pause between steps. */
		int bursts()
		{
			int count = 0;
			boolean moving = false;
			int still = HOLD_SAMPLES;
			for (int step : steps())
			{
				if (step != 0)
				{
					if (!moving && still >= HOLD_SAMPLES)
					{
						count++;
					}
					moving = true;
					still = 0;
				}
				else
				{
					still++;
					if (still >= HOLD_SAMPLES)
					{
						moving = false;
					}
				}
			}
			return count;
		}

		int animationStarts()
		{
			int count = 0;
			for (int[] change : animations)
			{
				if (change.length > 1 && change[1] != -1)
				{
					count++;
				}
			}
			return count;
		}
	}

	private static final class Learned
	{
		String clips;
		int ticks;
		int sightings;
		int delay;
		int span;
	}

	private static final class Sighting
	{
		int object;
		int cycle;
		int clickCycle = -1;

		/**
		 * Where this traversal's raw rows begin: the click, or the previous sighting if that
		 * was later. One click crosses several stones, so the click alone would hand every
		 * hop the rows of the hops before it.
		 */
		int windowStart = -1;
		String name = "";
		boolean silent;
		String clips = "";
		int ticks;
		int delay;
		int span;
		int curveCycles = -1;
		int[] from = new int[3];
		int[] to = new int[3];
		int rawAnimStarts;

		int[] shape()
		{
			return new int[]{Math.abs(to[0] - from[0]), Math.abs(to[1] - from[1]),
				to[2] != from[2] ? 1 : 0};
		}

		String shapeKey()
		{
			int[] s = shape();
			// Bucketed to the tile, so 34 and 35 across a staircase count as the same move.
			return s[0] + "," + s[1] + "," + s[2];
		}

		static int[] parseShape(String key)
		{
			String[] p = key.split(",");
			return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
		}

		String fromText()
		{
			return from[0] + "," + from[1] + "," + from[2];
		}

		String toText()
		{
			return to[0] + "," + to[1] + "," + to[2];
		}
	}

	private static final class Act
	{
		String golem;
		int cycle;
		int object = -1;
		int[] dest;
		int performing = -1;
		boolean parametric;
		int[] at;

		/** A rescue: the plugin lifted this golem out of somewhere it could not leave. */
		boolean rescue;
	}

	private static final class State
	{
		int cycle;
		int[] tile;
		double fineX;
		double fineY;
		boolean performing;
		boolean walking;
		int orient = -1;
		int dwell;

		/** Cycles since the client last drew this golem, -1 if not logged; undrawn if it has no renderer. */
		int drawnAgo = -1;
		boolean undrawn;
		boolean itinerary;
		boolean climbing;
		int transitTotal = -1;
	}

	// ==================================================================== journal

	private static final class Journal
	{
		final List<Sighting> sightings = new ArrayList<>();
		final List<Act> golemActs = new ArrayList<>();
		final Map<String, List<State>> states = new HashMap<>();

		/**
		 * The player's render position, {cycle, localX, localY, orientation, animation,
		 * keyframe}, from PRE and FINE rows. PRE rows carry only the position; the rest is -1.
		 */
		final List<int[]> positions = new ArrayList<>();

		/** The player's logical tile each tick, {cycle, x, y, plane}. */
		final List<int[]> ticks = new ArrayList<>();

		/** Steps golems took across edges the live game had blocked, "from to" to count. */
		final Map<String, Integer> blockedEdges = new TreeMap<>();

		/** The raw collision flags logged for each such edge, "from,to" in hex. */
		final Map<String, String> edgeFlags = new HashMap<>();

		/** Tiles golems stood on that the live game blocks, to the golems seen there. */
		final Map<String, java.util.Set<String>> blockedStanding = new TreeMap<>();

		/** Golems seen standing on a blocked tile while following a route. */
		final java.util.Set<String> blockedOnRoute = new java.util.TreeSet<>();

		/** Tiles golems gave up on and went back the way they came, to count. */
		final Map<String, Integer> deadEnds = new TreeMap<>();

		/** Drawn golem frames by golem, {cycle, fineX, fineY, orientation, anim, keyframe, keyframes}. */
		final Map<String, List<int[]>> golemFrames = new HashMap<>();

		/** The player's animation changes, {cycle, anim}. */
		final List<int[]> anims = new ArrayList<>();

		private static final Pattern SIGHTING = Pattern.compile(
			"^(silent )?(\\d+) \"([^\"]*)\" clips=([\\d,]*) ticks=(\\d+)"
			+ "(?: delay=(\\d+) span=(\\d+))?"
			+ "(?: \\[curve (\\d+)c \\d+ samples, \\d+ anim changes\\])?"
			+ " (\\d+),(\\d+),(\\d+) -> (\\d+),(\\d+),(\\d+)");
		private static final Pattern TRANSPORT = Pattern.compile(
			"^transport obj=(\\d+) to=(\\d+),(\\d+),(\\d+)");
		private static final Pattern PERFORMING = Pattern.compile("^performing curve (\\d+)c");
		private static final Pattern TILE_AT = Pattern.compile("tile=(\\d+),(\\d+),(\\d+)");
		private static final Pattern TRANSIT = Pattern.compile("transit=\\d+/(\\d+)");
		private static final Pattern CLICK_ID = Pattern.compile("^id=(\\d+) ");
		private static final Pattern LOCAL = Pattern.compile("localX=(-?\\d+) localY=(-?\\d+)");
		private static final Pattern FINE_AT = Pattern.compile("fine=(-?\\d+),(-?\\d+)");
		private static final Pattern AT = Pattern.compile(" at=(\\d+),(\\d+)");
		private static final Pattern ANIM_ID = Pattern.compile("^anim=(-?\\d+)");
		private static final Pattern FRAME = Pattern.compile(" frame=(-?\\d+)");
		private static final Pattern ORIENT = Pattern.compile("orient=(\\d+)");
		private static final Pattern DWELL = Pattern.compile("dwell=(\\d+)");
		private static final Pattern DRAWN_AGO = Pattern.compile("drawnAgo=(-?\\d+)");
		private static final Pattern DRAWN = Pattern.compile("fine=(-?\\d+),(-?\\d+) drawn=\\S+ "
			+ "orient=(\\d+) anim=(-?\\d+) keyframe=(-?\\d+) keyframes=(-?\\d+)");
		private static final Pattern RESCUE = Pattern.compile("^rescued \\w+ from (\\d+),(\\d+),(\\d+)");

		List<Sighting> sightingsFor(int object)
		{
			List<Sighting> out = new ArrayList<>();
			for (Sighting s : sightings)
			{
				if (s.object == object)
				{
					out.add(s);
				}
			}
			return out;
		}

		static Journal read(File file) throws IOException
		{
			Journal journal = new Journal();

			List<String[]> rows = new ArrayList<>();
			try (BufferedReader reader = new BufferedReader(new FileReader(file)))
			{
				String line;
				while ((line = reader.readLine()) != null)
				{
					if (line.startsWith("#"))
					{
						continue;
					}
					String[] p = line.split("\t", -1);
					if (p.length >= 10)
					{
						rows.add(p);
					}
				}
			}

			// The click that each sighting belongs to, for reading the raw events under it.
			Map<Integer, Integer> lastClickRow = new HashMap<>();
			Map<Integer, Integer> lastClickCycle = new HashMap<>();
			int lastSightingCycle = -1;

			// A traversal still in flight is completed by the player's next click, and that
			// click is written first — so the newest click is the one that ended it, not the
			// one that began it.
			Map<Integer, Integer> previousClickRow = new HashMap<>();
			Map<Integer, Integer> previousClickCycle = new HashMap<>();

			for (int i = 0; i < rows.size(); i++)
			{
				String[] p = rows.get(i);
				String type = p[2];
				String detail = p[9];
				int cycle = parse(p[0]);

				switch (type)
				{
					case "PRE":
					case "FINE":
					{
						Matcher m = LOCAL.matcher(detail);
						if (m.find())
						{
							boolean fine = "FINE".equals(type);
							Matcher fr = FRAME.matcher(detail);
							journal.positions.add(new int[]{cycle, Integer.parseInt(m.group(1)),
								Integer.parseInt(m.group(2)),
								fine ? parse(p[8]) : -1,
								fine ? parse(p[6]) : -1,
								fine && fr.find() ? Integer.parseInt(fr.group(1)) : -1});
						}
						break;
					}
					case "TICK":
					{
						journal.ticks.add(new int[]{cycle, parse(p[3]), parse(p[4]), parse(p[5])});
						break;
					}
					case "GOLEMFRAME":
					{
						int space = detail.indexOf(' ');
						Matcher m = DRAWN.matcher(detail);
						if (space > 0 && m.find())
						{
							journal.golemFrames.computeIfAbsent(detail.substring(0, space),
								k -> new ArrayList<>()).add(new int[]{cycle,
								Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
								Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
								Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6))});
						}
						break;
					}
					case "ANIM":
					{
						Matcher m = ANIM_ID.matcher(detail);
						if (m.find())
						{
							journal.anims.add(new int[]{cycle, Integer.parseInt(m.group(1)),
								parse(p[3]), parse(p[4]), parse(p[5])});
						}
						break;
					}
					case "CLICK":
					{
						Matcher m = CLICK_ID.matcher(detail);
						if (m.find())
						{
							int id = Integer.parseInt(m.group(1));
							if (lastClickRow.containsKey(id))
							{
								previousClickRow.put(id, lastClickRow.get(id));
								previousClickCycle.put(id, lastClickCycle.get(id));
							}
							lastClickRow.put(id, i);
							lastClickCycle.put(id, cycle);
						}
						break;
					}
					case "SIGHTING":
					{
						Matcher m = SIGHTING.matcher(detail);
						if (!m.find())
						{
							break;
						}
						Sighting s = new Sighting();
						s.cycle = cycle;
						s.silent = m.group(1) != null;
						s.object = Integer.parseInt(m.group(2));
						s.name = m.group(3);
						s.clips = m.group(4);
						s.ticks = Integer.parseInt(m.group(5));
						s.delay = m.group(6) == null ? 0 : Integer.parseInt(m.group(6));
						s.span = m.group(7) == null ? 0 : Integer.parseInt(m.group(7));
						s.curveCycles = m.group(8) == null ? -1 : Integer.parseInt(m.group(8));
						for (int k = 0; k < 3; k++)
						{
							s.from[k] = Integer.parseInt(m.group(9 + k));
							s.to[k] = Integer.parseInt(m.group(12 + k));
						}

						// Ground truth: how many animations really started between the
						// click and this sighting.
						Integer clickRow = lastClickRow.get(s.object);
						Integer clickCycle = lastClickCycle.get(s.object);
						if (clickCycle != null && clickCycle == cycle
							&& previousClickCycle.containsKey(s.object))
						{
							clickRow = previousClickRow.get(s.object);
							clickCycle = previousClickCycle.get(s.object);
						}
						if (clickRow != null && clickCycle != null)
						{
							s.clickCycle = clickCycle;
							s.windowStart = Math.max(clickCycle, lastSightingCycle);
							for (int r = clickRow; r < i; r++)
							{
								String[] q = rows.get(r);
								if ("ANIM".equals(q[2]) && !q[9].equals("anim=-1"))
								{
									int c = parse(q[0]);
									if (c >= s.windowStart && c <= cycle)
									{
										s.rawAnimStarts++;
									}
								}
							}
						}
						lastSightingCycle = cycle;
						journal.sightings.add(s);
						break;
					}
					case "GOLEM":
					{
						int space = detail.indexOf(' ');
						if (space < 0)
						{
							break;
						}
						String golem = detail.substring(0, space);
						String rest = detail.substring(space + 1);
						Matcher t = TILE_AT.matcher(rest);
						if (!t.find())
						{
							break;
						}
						State st = new State();
						st.cycle = cycle;
						st.tile = new int[]{Integer.parseInt(t.group(1)), Integer.parseInt(t.group(2)),
							Integer.parseInt(t.group(3))};
						st.performing = rest.contains(" performing");
						st.walking = rest.contains(" walking");
						Matcher o = ORIENT.matcher(rest);
						if (o.find())
						{
							st.orient = Integer.parseInt(o.group(1));
						}
						Matcher d = DWELL.matcher(rest);
						if (d.find())
						{
							st.dwell = Integer.parseInt(d.group(1));
						}
						Matcher da = DRAWN_AGO.matcher(rest);
						if (da.find())
						{
							st.drawnAgo = Integer.parseInt(da.group(1));
						}
						st.undrawn = rest.contains(" undrawn");
						st.itinerary = rest.contains(" itinerary");
						st.climbing = rest.contains(" climbing");
						Matcher f = FINE_AT.matcher(rest);
						if (f.find())
						{
							st.fineX = Double.parseDouble(f.group(1)) / TILE;
							st.fineY = Double.parseDouble(f.group(2)) / TILE;
						}
						Matcher tr = TRANSIT.matcher(rest);
						if (tr.find())
						{
							st.transitTotal = Integer.parseInt(tr.group(1));
						}
						journal.states.computeIfAbsent(golem, k -> new ArrayList<>()).add(st);
						break;
					}
					case "GOLEMACT":
					{
						int space = detail.indexOf(' ');
						if (space < 0)
						{
							break;
						}
						Act act = new Act();
						act.golem = detail.substring(0, space);
						act.cycle = cycle;
						String rest = detail.substring(space + 1);

						Matcher tm = TRANSPORT.matcher(rest);
						Matcher pm = PERFORMING.matcher(rest);
						if (tm.find())
						{
							act.object = Integer.parseInt(tm.group(1));
							act.dest = new int[]{Integer.parseInt(tm.group(2)),
								Integer.parseInt(tm.group(3)), Integer.parseInt(tm.group(4))};
						}
						else if (pm.find())
						{
							act.performing = Integer.parseInt(pm.group(1));
						}
						else if (rest.startsWith("crossed blocked edge from "))
						{
							String edge = rest.substring("crossed blocked edge from ".length());
							int flagsAt = edge.indexOf(" flags=");
							if (flagsAt > 0)
							{
								journal.edgeFlags.put(edge.substring(0, flagsAt), edge.substring(flagsAt + 7));
								edge = edge.substring(0, flagsAt);
							}
							journal.blockedEdges.merge(edge, 1, Integer::sum);
							break;
						}
						else if (rest.startsWith("standing on blocked tile "))
						{
							String tail = rest.substring("standing on blocked tile ".length());
							String tile = tail.split(" ")[0];
							journal.blockedStanding.computeIfAbsent(tile, k -> new java.util.TreeSet<>()).add(act.golem);
							if (tail.endsWith(" route"))
							{
								journal.blockedOnRoute.add(act.golem);
							}
							break;
						}
						else if (rest.startsWith("dead end"))
						{
							List<State> seen = journal.states.get(act.golem);
							State last = seen == null || seen.isEmpty() ? null : seen.get(seen.size() - 1);
							if (last != null)
							{
								journal.deadEnds.merge(last.tile[0] + "," + last.tile[1] + "," + last.tile[2],
									1, Integer::sum);
							}
							break;
						}
						else if (RESCUE.matcher(rest).find())
						{
							act.rescue = true;
						}
						else if (rest.startsWith("relocated"))
						{
							// Taken with nothing to perform: put down at the far end.
							act.parametric = true;
						}
						else if (rest.startsWith("clips="))
						{
							act.parametric = true;
							Matcher am = AT.matcher(rest);
							if (am.find())
							{
								act.at = new int[]{Integer.parseInt(am.group(1)),
									Integer.parseInt(am.group(2))};
							}
						}
						else
						{
							break;
						}
						journal.golemActs.add(act);
						break;
					}
					default:
						break;
				}
			}
			return journal;
		}

		private static int parse(String text)
		{
			try
			{
				return Integer.parseInt(text.trim());
			}
			catch (NumberFormatException e)
			{
				return 0;
			}
		}
	}

	private static File newestJournal()
	{
		File[] files = new File(RUNELITE_DIR).listFiles(
			(d, n) -> n.startsWith("golem-obstacles-") && n.endsWith(".tsv"));
		if (files == null || files.length == 0)
		{
			return null;
		}
		Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
		return files[0];
	}

	// ============================================================ profile & tables

	private static Map<Integer, Curve> readCurves(String raw)
	{
		Map<Integer, Curve> out = new LinkedHashMap<>();
		if (raw == null || raw.isEmpty())
		{
			return out;
		}
		for (String entry : raw.split(";"))
		{
			int split = entry.indexOf('=');
			if (split <= 0)
			{
				continue;
			}
			try
			{
				String[] parts = entry.substring(split + 1).split("\\|", -1);
				Curve curve = new Curve();
				curve.cycles = Integer.parseInt(parts[0]);
				curve.forward = ints(parts[1]);
				curve.lateral = ints(parts[2]);
				List<int[]> anims = new ArrayList<>();
				if (parts.length > 3 && !parts[3].isEmpty())
				{
					for (String change : parts[3].split(","))
					{
						String[] bits = change.split(":");
						int[] values = new int[bits.length];
						for (int k = 0; k < bits.length; k++)
						{
							values[k] = Integer.parseInt(bits[k]);
						}
						anims.add(values);
					}
				}
				curve.animations = anims.toArray(new int[0][]);
				if (curve.forward.length > 0)
				{
					out.put(Integer.parseInt(entry.substring(0, split)), curve);
				}
			}
			catch (RuntimeException e)
			{
				System.out.println("unreadable curve: " + entry.substring(0, Math.min(24, entry.length())));
			}
		}
		return out;
	}

	/** objectId to routes as {fromX, fromY, fromPlane, toX, toY, toPlane, sightings}. */
	private static Map<Integer, List<int[]>> readRoutes(String raw)
	{
		Map<Integer, List<int[]>> out = new LinkedHashMap<>();
		if (raw == null || raw.isEmpty())
		{
			return out;
		}
		for (String entry : raw.split(";"))
		{
			try
			{
				String[] halves = entry.split(">");
				String[] from = halves[0].split(",");
				String[] to = halves[1].split(",");
				out.computeIfAbsent(Integer.parseInt(from[0]), k -> new ArrayList<>())
					.add(new int[]{
						Integer.parseInt(from[1]), Integer.parseInt(from[2]), Integer.parseInt(from[3]),
						Integer.parseInt(to[0]), Integer.parseInt(to[1]), Integer.parseInt(to[2]),
						to.length > 3 ? Integer.parseInt(to[3]) : 1,
					});
			}
			catch (RuntimeException e)
			{
				// Not a curve fault.
			}
		}
		return out;
	}

	/** objectId to its most common line {x, y}, from learnedLines ("id=x,y,count|x,y,count;..."). */
	private static Map<Integer, int[]> readLines(String raw)
	{
		Map<Integer, int[]> out = new HashMap<>();
		if (raw == null || raw.isEmpty())
		{
			return out;
		}
		for (String entry : raw.split(";"))
		{
			try
			{
				int split = entry.indexOf('=');
				int best = -1;
				int[] line = null;
				for (String direction : entry.substring(split + 1).split("\\|"))
				{
					String[] p = direction.split(",");
					int count = Integer.parseInt(p[2]);
					if (count > best)
					{
						best = count;
						line = new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])};
					}
				}
				if (line != null)
				{
					out.put(Integer.parseInt(entry.substring(0, split)), line);
				}
			}
			catch (RuntimeException e)
			{
				// Not a curve fault.
			}
		}
		return out;
	}

	/**
	 * The saved routes the plugin will actually offer golems.
	 *
	 * <p>The plugin ignores some of what is saved rather than deleting it: routes in an
	 * instance's own coordinates, and routes off the line their obstacle moves players along.
	 * Reporting those as faults described data no golem will ever see, so they are left out
	 * here the same way, and only counted.
	 */
	private Map<Integer, List<int[]>> ignoreLikePlugin(Map<Integer, List<int[]>> routes, Map<Integer, int[]> lines,
		Map<Integer, List<int[]>> shipped)
	{
		java.lang.reflect.Method isObstacle = null;
		try
		{
			isObstacle = Class.forName("com.golemsdontdie.MeshProbe").getMethod("isObstacle", int.class,
				boolean.class, int.class, int.class, int.class, int.class, int.class, int.class);
		}
		catch (ReflectiveOperationException | LinkageError e)
		{
			// Without the plugin classes only the checks below that need no map are applied.
		}
		Map<Integer, List<int[]>> kept = new LinkedHashMap<>();
		for (Map.Entry<Integer, List<int[]>> e : routes.entrySet())
		{
			int[] line = lines.get(e.getKey());
			for (int[] r : e.getValue())
			{
				String why = null;
				if (r[0] >= 6400 || r[3] >= 6400)
				{
					why = "in an instance's coordinates";
				}
				else if (line != null && r[2] == r[5] && !followsLine(r[3] - r[0], r[4] - r[1], line[0], line[1]))
				{
					why = "off the obstacle's line";
				}
				else if (isObstacle != null && probeWalkable != null)
				{
					try
					{
						if (!(Boolean) isObstacle.invoke(null, e.getKey(), shipped.containsKey(e.getKey()),
							r[0], r[1], r[2], r[3], r[4], r[5]))
						{
							why = "not an obstacle - the player could have walked it";
						}
					}
					catch (ReflectiveOperationException ex)
					{
						// Leave it in rather than guess.
					}
				}
				if (why == null)
				{
					kept.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(r);
				}
				else
				{
					evidence(e.getKey(), "saved route " + point(r, 0) + " -> " + point(r, 3) + " is ignored by the plugin: " + why);
				}
			}
		}
		return kept;
	}

	/** The plugin's RouteGeometry.follows, repeated: within ten degrees of the line, either way. */
	private static boolean followsLine(int rx, int ry, int lx, int ly)
	{
		boolean local = (lx != 0 || ly != 0) && Math.abs(lx) <= 8 && Math.abs(ly) <= 8
			&& (rx != 0 || ry != 0) && Math.abs(rx) <= 8 && Math.abs(ry) <= 8;
		if (!local)
		{
			return true;
		}
		double cos = Math.abs(rx * lx + ry * ly) / (Math.hypot(rx, ry) * Math.hypot(lx, ly));
		return Math.toDegrees(Math.acos(Math.min(1, cos))) <= 10;
	}

	private static Map<Integer, Learned> readLearned(String raw)
	{
		Map<Integer, Learned> out = new LinkedHashMap<>();
		if (raw == null || raw.isEmpty())
		{
			return out;
		}
		for (String entry : raw.split(";"))
		{
			String[] p = entry.split(":", -1);
			if (p.length < 4)
			{
				continue;
			}
			try
			{
				Learned l = new Learned();
				l.clips = p[1].replace('|', ',');
				l.ticks = Integer.parseInt(p[2]);
				l.sightings = Integer.parseInt(p[3]);
				l.delay = p.length > 4 ? Integer.parseInt(p[4]) : 0;
				l.span = p.length > 5 ? Integer.parseInt(p[5]) : 0;
				out.put(Integer.parseInt(p[0]), l);
			}
			catch (RuntimeException e)
			{
				// Not a curve fault.
			}
		}
		return out;
	}

	/** Shipped rows by object id, as {fromX, fromY, fromPlane, toX, toY, toPlane}. */
	private static Map<Integer, List<int[]>> readShipped() throws IOException
	{
		Map<Integer, List<int[]>> out = new HashMap<>();
		for (String dir : TRANSPORT_DIRS)
		{
			File[] files = new File(dir).listFiles((d, n) -> n.endsWith(".tsv"));
			if (files == null)
			{
				continue;
			}
			for (File f : files)
			{
				try (BufferedReader reader = new BufferedReader(new FileReader(f)))
				{
					String line;
					while ((line = reader.readLine()) != null)
					{
						if (line.startsWith("#"))
						{
							continue;
						}
						String[] p = line.split("\t", -1);
						if (p.length < 3)
						{
							continue;
						}
						String[] from = p[0].trim().split("\\s+");
						String[] to = p[1].trim().split("\\s+");
						String[] menu = p[2].trim().split("\\s+");
						if (from.length < 3 || to.length < 3 || menu.length == 0)
						{
							continue;
						}
						try
						{
							int id = Integer.parseInt(menu[menu.length - 1]);
							out.computeIfAbsent(id, k -> new ArrayList<>()).add(new int[]{
								Integer.parseInt(from[0]), Integer.parseInt(from[1]),
								Integer.parseInt(from[2]), Integer.parseInt(to[0]),
								Integer.parseInt(to[1]), Integer.parseInt(to[2]),
							});
						}
						catch (NumberFormatException e)
						{
							// Rows without an object id are not obstacles.
						}
					}
				}
			}
		}
		return out;
	}

	private static int[] ints(String csv)
	{
		if (csv.isEmpty())
		{
			return new int[0];
		}
		String[] parts = csv.split(",");
		int[] out = new int[parts.length];
		for (int i = 0; i < parts.length; i++)
		{
			out[i] = Integer.parseInt(parts[i].trim());
		}
		return out;
	}

	/**
	 * Reads a RuneLite profile.
	 *
	 * <p>Properties escape their separators, so a stored value arrives with a backslash in
	 * front of every colon and equals sign. Those are stripped rather than interpreted,
	 * because nothing written by this plugin contains a meaningful one.
	 */
	private static Map<String, String> readProfile(String path) throws IOException
	{
		Map<String, String> out = new LinkedHashMap<>();
		try (BufferedReader reader = new BufferedReader(new FileReader(path)))
		{
			String line;
			while ((line = reader.readLine()) != null)
			{
				int split = line.indexOf('=');
				if (split <= 0 || line.startsWith("#"))
				{
					continue;
				}
				out.put(line.substring(0, split).trim(), line.substring(split + 1).replace("\\", ""));
			}
		}
		return out;
	}
}
