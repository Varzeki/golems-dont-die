import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Proposes the player animation for every kind of shortcut, from Jagex's own names.
 *
 * <p>Which clip an obstacle plays is decided server-side and is in no file — measuring it
 * by hand works and does not scale past a few dozen. This is the way round that.
 *
 * <p>The insight is that <b>both sides of the join are named</b>. RuneLite ships Jagex's
 * debug names for animations, and the transport tables carry each obstacle's menu text. So
 * an obstacle called "Cross Basalt stepping stone" can be matched against an animation
 * called {@code HUMAN_SPOT_JUMP} by the words they share, and the proposal checked by eye.
 *
 * <p>That the naming can be trusted is not an assumption — it is tested. Four shortcuts
 * were measured in game first, and {@code selfTest} re-derives those four from their menu
 * text alone on every run. A scoring change that breaks one of them is visible immediately
 * rather than after it has shipped four thousand wrong clips.
 *
 * <p>This turns "measure two hundred obstacles" into "review two hundred proposals", which
 * is a different size of job. It does not turn guesses into facts: every proposal is still
 * a proposal, and the row counts say which ones are worth an in-game minute.
 *
 * <pre>
 * javac -cp runelite-api.jar -d out dev-tools/ProposeAnimations.java
 * java  -cp "runelite-api.jar;out" ProposeAnimations &lt;transports-dir&gt; [extra-dir]
 * </pre>
 */
public class ProposeAnimations
{
	/**
	 * Words that say nothing about the motion.
	 *
	 * <p>Every obstacle is a "shortcut" and most are some kind of "rock", so those words
	 * match everything and rank nothing.
	 */
	private static final Set<String> NOISE = new HashSet<>(Arrays.asList(
		"the", "a", "of", "to", "and", "into", "through", "over", "up", "down",
		"shortcut", "obstacle", "col", "ff", "ffffff", "large", "small", "old", "broken"));

	/**
	 * The only animations a player plays while traversing something.
	 *
	 * <p>This is the change that made the proposals usable. Scoring every one of the
	 * fourteen thousand names and nudging the likely ones upwards let nonsense win on
	 * sheer word overlap: "Climb Rocks" proposed {@code ROCKSLUG_READY} and "Climb-up
	 * Stairs" proposed {@code BRAIN_STAIRS_FIX}. Neither is a player, and no amount of
	 * weighting fixes that, because both genuinely do share the obstacle's noun.
	 *
	 * <p>Jagex's own naming already draws the line. Clips the player performs are
	 * {@code HUMAN_}; the Wilderness set is {@code WILD_}; the agility network names
	 * itself. Everything outside that is scenery, an NPC or a cutscene — and a candidate
	 * pool is a far stronger statement than a bonus.
	 *
	 * <p>{@code DOCK_GANGPLANK01} falls outside it, correctly: the plank animates, not the
	 * person walking up it. See {@code GolemContent}.
	 */
	private static final String[] PLAYER_PREFIXES = {
		"HUMAN_", "WILD_", "AGILITY", "AGILTY", "SHORTCUT",
	};

	/**
	 * Animation names that are never a player traversing something.
	 *
	 * <p>Applied inside the pool, not instead of it: {@code HUMAN_} and {@code WILD_} hold
	 * plenty of combat and emote clips, and {@code WILD_CAVE_CHAINMACE_CRUSH} was winning
	 * "Enter Cave entrance" on the strength of the word "cave".
	 */
	private static final String[] REJECT = {
		"DEATH", "ATTACK", "DEFEND", "SPELL", "CAST", "EMOTE", "CUTSCENE", "NPC_",
		"BOSS", "FIRE", "IMPACT", "HIT", "BLOCK", "MACE", "CRUSH", "SLASH", "STAB",
		"SWORD", "BOW_", "STAFF", "SHIELD", "PUNCH", "KICK", "DANCE", "SIT", "EAT",
		"DRINK", "CHOP", "MINE", "FISH", "SMITH", "COOK", "CRAFT", "PRAY", "BURY",
		"_BOW", "DART", "KNIFE", "AXE", "SPEAR", "WHIP", "CLAW", "HALBERD",
	};

	/**
	 * What the game calls a thing, versus what the animator called it.
	 *
	 * <p>The two vocabularies do not fully overlap, and the gaps are in the largest
	 * groups. Seven hundred rows of staircase matched nothing at all until "staircase"
	 * was allowed to reach {@code HUMAN_REACHFORLADDER} — a staircase is climbed with the
	 * ladder clip, which is obvious in game and invisible to a string comparison.
	 *
	 * <p>Each entry is a menu word followed by the animation words it licenses. Scored
	 * below a real word match, so a direct hit always wins where one exists.
	 */
	private static final String[][] SYNONYMS = {
		{"staircase", "ladder", "stair", "climb"},
		{"stairs", "ladder", "stair", "climb"},
		{"stairway", "ladder", "stair", "climb"},
		{"steps", "ladder", "stair", "climb"},
		{"trapdoor", "ladder", "trapdoor", "climb"},
		{"rocks", "climbing", "rockclimb", "scramble", "boulder"},
		{"rock", "climbing", "rockclimb", "scramble", "boulder"},
		{"rockslide", "climbing", "scramble"},
		{"boulder", "climbing", "scramble"},
		{"cliff", "climbing", "scramble"},
		{"cave", "crawl", "cave"},
		{"entrance", "crawl", "enter"},
		{"tunnel", "crawl", "tunnel", "squeeze"},
		{"hole", "crawl", "climbdown", "ladder"},
		{"passageway", "crawl", "squeeze"},
		{"crevice", "squeeze", "sidestep"},
		{"crevasse", "squeeze", "sidestep"},
		{"crack", "squeeze", "sidestep"},
		{"pipe", "crawl", "squeeze", "pipe"},
		{"gap", "jump", "leap", "spot"},
		{"chasm", "jump", "leap"},
		{"stone", "steppingstone", "spot", "jump"},
		{"stepping", "steppingstone", "spot", "jump"},
		{"stones", "steppingstone", "spot", "jump"},
		{"log", "balance", "log"},
		{"plank", "balance", "gangplank"},
		{"rope", "rope", "tightrope", "balance", "swing"},
		{"tightrope", "tightrope", "balance"},
		{"net", "climbing", "net"},
		{"vine", "climbing", "swing", "vine"},
		{"branch", "climbing", "swing", "branch"},
		{"tree", "climbing", "tree"},
		{"wall", "jump", "vault", "climbover"},
		{"fence", "jump", "vault", "stile", "climbover"},
		{"railing", "jump", "vault", "climbover"},
		{"hedge", "jump", "vault", "climbover"},
		{"ditch", "ditch", "jump"},
		{"stile", "stile", "climbover"},
		{"gangplank", "gangplank", "walk"},
		{"barrier", "jump", "vault", "climbover"},
		{"river", "swim", "wade", "jump"},
		{"stream", "swim", "wade", "jump"},
		{"water", "swim", "wade", "dive"},
		{"bridge", "balance", "walk"},
		{"pillar", "jump", "leap", "spot"},
		{"ledge", "balance", "sidestep", "shimmy"},
		{"beam", "balance", "tightrope"},
	};

	/**
	 * Suffixes that mark a clip as one part of a longer motion.
	 *
	 * <p>OSRS builds a traversal in three: take hold, haul, step off. Those are three
	 * animation ids sharing a stem, and asking which single id "is" the rock climb was the
	 * wrong question — the answer is all three. The stem is the unit worth proposing, and
	 * it is also the unit {@code GolemTransport.animations()} already consumes.
	 *
	 * <p>Only one suffix is stripped, which keeps direction variants apart:
	 * {@code HUMAN_CLIMBING_DOWN_LOOP} loses its {@code _LOOP} and becomes part of
	 * {@code HUMAN_CLIMBING_DOWN}, the family for going down, rather than collapsing all
	 * the way into the family for going up.
	 */
	private static final String[] MEMBER_SUFFIXES = {
		"_WALKMERGE", "_PRIORITY", "_READY", "_START", "_MERGE",
		"_LOOP", "_END", "_FALL", "_FAIL",
	};

	/** Where each member sits in the motion, or -1 for one that should never be played. */
	private static int rank(String name)
	{
		if (name.endsWith("_FALL") || name.endsWith("_FAIL"))
		{
			// Falling off is a failure state. A golem does not fail.
			return -1;
		}
		if (name.endsWith("_READY") || name.endsWith("_START"))
		{
			return 0;
		}
		if (name.endsWith("_MERGE") || name.endsWith("_END") || name.endsWith("_WALKMERGE"))
		{
			return 2;
		}
		// The bare stem and the loop are both the middle: the part that carries the motion
		// and repeats for as long as the obstacle is long.
		return 1;
	}

	/**
	 * The obstacles somebody has actually stood in front of and timed.
	 *
	 * <p>The test is that the measured id appears <i>in the proposed family</i>, not that
	 * it is the one name the scorer happens to rank first. A proposal of
	 * {@code HUMAN_CLIMBING} that expands to {738, 4435, 12338} has found the rock climb
	 * even though 4435 is not the name the words matched.
	 */
	private static final String[][] MEASURED = {
		{"Cross Basalt stepping stone", "741"},
		{"Cross Slippery basalt stepping stone", "741"},
		{"Climb Rocks", "4435"},
		{"Enter Cave", "2796"},
	};

	/**
	 * What the shipped archetype already plays for each obstacle, for comparison.
	 *
	 * <p>A list of proposals on its own is not worth much — it is a second opinion with
	 * nothing to be a second opinion <i>about</i>. Held against the archetype the plugin
	 * already ships, the same list says something useful: where the two independently
	 * agree the clip is twice-sourced, and where they differ one of them is wrong and the
	 * obstacle is worth a minute in game.
	 *
	 * <p>Keyed by {@code BuildTransports.classify}, whose ordering is reproduced in
	 * {@link #classify}. Ids are the sets {@code GolemTransport.animations()} builds; the
	 * jump row holds all four lengths because which one applies is a distance decision
	 * made per row, not per obstacle name.
	 */
	private static final int[][] ARCHETYPE_CLIPS = {
		{},                        // none
		{},                        // door — the door animates, not the golem
		{828, 833, 13991},         // ladder, both directions
		{738, 4435, 12338},        // climb
		{6132},                    // ditch
		{741, 769, 1604, 807},     // jump, all four lengths
		{14452},                   // gangplank — the golem keeps walking
		{2583},                    // climb-over
		{747, 746, 748},           // squeeze
		{762, 7134},               // balance
		{14235},                   // stile
		{762, 4772},               // tightrope
	};

	private static final String[] ARCHETYPE_NAMES = {
		"none", "door", "ladder", "climb", "ditch", "jump",
		"gangplank", "climb-over", "squeeze", "balance", "stile", "tightrope",
	};

	/**
	 * The archetype this menu text is given when the transport table is built.
	 *
	 * <p>A copy of {@code BuildTransports.classify}, ordering included. Duplicated rather
	 * than shared because the two are standalone tools compiled separately, and because
	 * the whole point of this comparison is that the two answers are arrived at
	 * independently — importing one into the other would quietly make them agree.
	 */
	private static int classify(String menu)
	{
		String m = menu.toLowerCase(Locale.ROOT);
		if (m.isEmpty())
		{
			return 0;
		}
		if (m.contains("door") || m.contains("gate") || m.contains("open "))
		{
			return 1;
		}
		if (m.contains("squeeze") || m.contains("pipe") || m.contains("railing")
			|| m.contains("cart tunnel") || m.contains("crevice") || m.contains("crevasse"))
		{
			return 8;
		}
		if (m.contains("stile"))
		{
			return 10;
		}
		if (m.contains("obstacle net") || m.contains("broken wall")
			|| m.contains("climb-over") || m.contains("climb over"))
		{
			return 7;
		}
		if (m.contains("wall") && !m.contains("under")
			&& (m.contains("climb") || m.contains("jump")))
		{
			return 7;
		}
		if (m.contains("tightrope"))
		{
			return 11;
		}
		if (m.contains("log balance") || m.contains("rope bridge") || m.contains("balance"))
		{
			return 9;
		}
		if (m.contains("ditch"))
		{
			return 4;
		}
		if (m.contains("gangplank") || m.contains("board ") || m.contains("shipplank"))
		{
			return 6;
		}
		if (m.contains("jump") || m.contains("stepping stone") || m.contains("gap")
			|| m.contains("pillar") || m.contains("leap") || m.contains("floorboard"))
		{
			return 5;
		}
		if (m.contains("ladder") || m.contains("staircase") || m.contains("stairs")
			|| m.contains("trapdoor") || m.contains("rope"))
		{
			return 2;
		}
		if (m.contains("climb") || m.contains("rocks") || m.contains("scramble"))
		{
			return 3;
		}
		return 0;
	}

	public static void main(String[] args) throws IOException
	{
		Map<String, Integer> animations = animationNames();
		if (animations.isEmpty())
		{
			System.err.println("No AnimationID on the classpath — add runelite-api.jar");
			System.exit(1);
		}

		Map<String, Integer> pool = pool(animations);
		Map<String, List<String>> families = families(pool);
		System.out.println("animation names available  : " + animations.size());
		System.out.println("player-traversal candidates: " + pool.size()
			+ " in " + families.size() + " motions");
		System.out.println();

		if (!selfTest(pool, families))
		{
			System.out.println();
			System.out.println("The scorer disagrees with something that was measured in game.");
			System.out.println("Fix that before trusting anything below it.");
		}
		System.out.println();

		Map<String, int[]> obstacles = readObstacles(args);
		System.out.println("distinct obstacle menu texts: " + obstacles.size());
		System.out.println();

		List<Map.Entry<String, int[]>> byRows = new ArrayList<>(obstacles.entrySet());
		byRows.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));

		System.out.println("rows  obstacle                             archetype   verdict  proposed motion");
		System.out.println("----  -----------------------------------  ----------  -------  ---------------");

		int confident = 0;
		int covered = 0;
		int total = 0;
		int agreed = 0;
		int differed = 0;
		int silent = 0;

		for (Map.Entry<String, int[]> entry : byRows)
		{
			List<Family> best = propose(entry.getKey(), pool, families);
			int rows = entry.getValue()[0];
			total += rows;

			int archetype = classify(entry.getKey());
			int[] shipped = ARCHETYPE_CLIPS[archetype];

			if (best.isEmpty())
			{
				System.out.println(pad(rows, 6) + pad(entry.getKey(), 37)
					+ pad(ARCHETYPE_NAMES[archetype], 12) + "-        -- nothing matched");
				continue;
			}

			covered += rows;
			Family top = best.get(0);
			boolean sure = top.score >= 4 && (best.size() == 1 || top.score > best.get(1).score);
			if (sure)
			{
				confident += rows;
			}

			// Three verdicts, and only one of them is a job.
			String verdict;
			if (shipped.length == 0)
			{
				// The archetype deliberately plays nothing — a door, or an unclassified
				// row. A proposal cannot contradict silence, only suggest breaking it.
				verdict = "silent";
				silent += rows;
			}
			else if (shares(shipped, top.clips))
			{
				verdict = "agrees";
				agreed += rows;
			}
			else
			{
				verdict = "DIFFERS";
				differed += rows;
			}

			StringBuilder sb = new StringBuilder();
			sb.append(pad(rows, 6)).append(pad(entry.getKey(), 37))
				.append(pad(ARCHETYPE_NAMES[archetype], 12)).append(pad(verdict, 9))
				.append(sure ? "  " : "? ").append(top.set());
			System.out.println(sb);
		}

		System.out.println();
		System.out.println("rows with a confident proposal : " + confident + " of " + total);
		System.out.println("rows with a proposal to review : " + (covered - confident));
		System.out.println("rows with nothing at all       : " + (total - covered));
		System.out.println();
		System.out.println("against the archetypes the plugin already ships:");
		System.out.println("  agrees  " + pad(agreed, 8) + "twice-sourced — the name and the archetype");
		System.out.println("          " + pad("", 8) + "were arrived at separately and match");
		System.out.println("  DIFFERS " + pad(differed, 8) + "one of the two is wrong. This is the list");
		System.out.println("          " + pad("", 8) + "worth spending in-game minutes on");
		System.out.println("  silent  " + pad(silent, 8) + "archetype plays nothing on purpose");
		System.out.println();
		System.out.println("A '?' means the top two families scored alike. Work down from the top:");
		System.out.println("the distribution is steep and the first forty lines are most of the");
		System.out.println("network, so a DIFFERS near the top is worth far more than one near the");
		System.out.println("bottom.");
	}

	// --------------------------------------------------------------------- testing

	/**
	 * Re-derives the measured obstacles from their menu text and reports disagreement.
	 *
	 * <p>These are the only ground truth there is, so they are spent on keeping the scorer
	 * honest rather than on the rows they describe — which are hardcoded in
	 * {@code MeasuredShortcuts} regardless and never consult this tool.
	 */
	private static boolean selfTest(Map<String, Integer> pool,
		Map<String, List<String>> families)
	{
		System.out.println("checking the scorer against what was measured in game");
		int exact = 0;
		int near = 0;

		for (String[] test : MEASURED)
		{
			List<Family> best = propose(test[0], pool, families);
			int want = Integer.parseInt(test[1]);

			int rank = -1;
			for (int i = 0; i < best.size(); i++)
			{
				if (best.get(i).contains(want))
				{
					rank = i;
					break;
				}
			}

			String got = best.isEmpty() ? "nothing" : best.get(0).set();
			if (rank == 0)
			{
				System.out.println("  ok    " + pad(test[0], 38) + got);
				exact++;
			}
			else if (rank > 0 && rank < 3)
			{
				// Near enough to review by eye, which is all this tool claims to produce.
				System.out.println("  near  " + pad(test[0], 38) + got
					+ "   (" + want + " is in candidate " + (rank + 1) + ")");
				near++;
			}
			else
			{
				System.out.println("  WRONG " + pad(test[0], 38) + got
					+ "   (wanted " + want + ", which is in no proposed family)");
			}
		}

		// Reported apart on purpose. "In the top three of thirteen hundred" is a good
		// result for something a human is going to read anyway, but it is not the same
		// claim as "the name alone got it right", and rolling the two together would
		// flatter this tool into sounding like it had replaced measuring.
		System.out.println("  of " + MEASURED.length + " measured obstacles: "
			+ exact + " proposed exactly, " + near + " inside the top three, "
			+ (MEASURED.length - exact - near) + " missed");
		return exact + near == MEASURED.length;
	}

	// ---------------------------------------------------------------- proposing

	/**
	 * Groups the candidate animations into the motions they are parts of.
	 *
	 * <p>Built once and shared. Every obstacle is scored against the same families, and
	 * there are fourteen hundred candidates.
	 */
	private static Map<String, List<String>> families(Map<String, Integer> pool)
	{
		Map<String, List<String>> out = new TreeMap<>();
		for (String name : pool.keySet())
		{
			out.computeIfAbsent(stem(name), k -> new ArrayList<>()).add(name);
		}
		return out;
	}

	/** The name with any part-of-a-motion suffix removed. */
	private static String stem(String name)
	{
		for (String suffix : MEMBER_SUFFIXES)
		{
			if (name.endsWith(suffix) && name.length() > suffix.length())
			{
				return name.substring(0, name.length() - suffix.length());
			}
		}
		return name;
	}

	/** The clips of one family, in the order they are played. */
	private static List<String> ordered(List<String> members)
	{
		// Where a family has both a bare stem and a loop, they are the same middle of the
		// same motion and playing both would run it twice. The loop is the one that
		// carries the travel, and it is the one the rock climb was measured playing.
		boolean hasLoop = false;
		for (String member : members)
		{
			hasLoop |= member.endsWith("_LOOP");
		}

		List<String> out = new ArrayList<>();
		for (int want = 0; want <= 2; want++)
		{
			for (String member : members)
			{
				if (rank(member) != want)
				{
					continue;
				}
				if (want == 1 && hasLoop && !member.endsWith("_LOOP"))
				{
					continue;
				}
				out.add(member);
			}
		}
		return out;
	}

	/** Families whose names share meaningful words with the obstacle, best first. */
	private static List<Family> propose(String obstacle, Map<String, Integer> pool,
		Map<String, List<String>> families)
	{
		Map<String, Integer> best = new java.util.HashMap<>();
		for (Scored scored : score(obstacle, pool))
		{
			// A family is as good as its best-matching member: "Climb Rocks" matches
			// HUMAN_CLIMBING on the stem and nothing on HUMAN_CLIMBING_LOOP, but the loop
			// is the clip that actually plays.
			best.merge(stem(scored.name), scored.score, Math::max);
		}

		List<Family> out = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : best.entrySet())
		{
			List<String> members = ordered(families.get(entry.getKey()));
			if (members.isEmpty())
			{
				// Nothing but failure states. Not a traversal.
				continue;
			}
			int[] clips = new int[members.size()];
			for (int i = 0; i < clips.length; i++)
			{
				clips[i] = pool.get(members.get(i));
			}
			out.add(new Family(entry.getKey(), members, clips, entry.getValue()));
		}

		out.sort(Comparator.comparingInt((Family f) -> -f.score).thenComparing(f -> f.name));
		return out;
	}

	/** Every candidate animation with the score its name earned against this obstacle. */
	private static List<Scored> score(String obstacle, Map<String, Integer> pool)
	{
		// The noun is the obstacle; the verb is almost noise.
		//
		// Scoring both alike produced nonsense: "Cross Gangplank" matched HUMAN_CROSSBOW
		// because "cross" is a substring of "crossbow", and every ladder in the game
		// proposed CLIMB_TRELLIS because they share the word "climb". What identifies an
		// animation is the thing being traversed — ladder, ditch, stile, stepping stone —
		// so a match on that is worth several times a match on the verb, and an animation
		// that matches only the verb is not a candidate at all.
		String[] split = obstacle.split("\\s+", 2);
		Set<String> verb = words(split[0]);
		Set<String> noun = words(split.length > 1 ? split[1] : "");
		Set<String> alias = synonyms(noun);

		List<Scored> out = new ArrayList<>();

		for (Map.Entry<String, Integer> animation : pool.entrySet())
		{
			String name = animation.getKey();
			String lower = name.toLowerCase(Locale.ROOT);
			Set<String> animWords = words(name);

			int nounScore = 0;
			for (String word : noun)
			{
				if (animWords.contains(word))
				{
					nounScore += 6;
				}
				else if (lower.contains(word))
				{
					// "steppingstonejump" contains "stepping" without splitting on it.
					nounScore += 4;
				}
			}

			for (String word : alias)
			{
				if (animWords.contains(word) || lower.contains(word))
				{
					nounScore += 3;
				}
			}

			if (nounScore == 0)
			{
				// Nothing to do with this obstacle, whatever verb it shares.
				continue;
			}

			int score = nounScore;
			for (String word : verb)
			{
				if (animWords.contains(word) || lower.contains(word))
				{
					score += 2;
				}
			}

			// A long name is a specific piece of content — a quest, a boss, a cutscene —
			// where a short one is the generic motion that most obstacles reuse.
			score -= animWords.size();

			out.add(new Scored(name, animation.getValue(), score));
		}

		out.sort(Comparator.comparingInt((Scored s) -> -s.score).thenComparing(s -> s.name));
		return out;
	}

	/** True if the two clip sets have any animation in common. */
	private static boolean shares(int[] a, int[] b)
	{
		for (int x : a)
		{
			for (int y : b)
			{
				if (x == y)
				{
					return true;
				}
			}
		}
		return false;
	}

	/** The animation vocabulary the obstacle's own words license. */
	private static Set<String> synonyms(Set<String> noun)
	{
		Set<String> out = new HashSet<>();
		for (String[] row : SYNONYMS)
		{
			if (noun.contains(row[0]))
			{
				out.addAll(Arrays.asList(row).subList(1, row.length));
			}
		}
		out.removeAll(noun);
		return out;
	}

	/** The animations a player could be performing, which is a small part of the table. */
	private static Map<String, Integer> pool(Map<String, Integer> all)
	{
		Map<String, Integer> out = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> e : all.entrySet())
		{
			String name = e.getKey();
			if (player(name) && !rejected(name))
			{
				out.put(name, e.getValue());
			}
		}
		return out;
	}

	private static boolean player(String name)
	{
		for (String prefix : PLAYER_PREFIXES)
		{
			if (name.startsWith(prefix) || name.contains("_" + prefix))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean rejected(String name)
	{
		for (String bad : REJECT)
		{
			if (name.contains(bad))
			{
				return true;
			}
		}
		return false;
	}

	private static Set<String> words(String text)
	{
		Set<String> out = new HashSet<>();
		for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
		{
			if (word.length() > 2 && !NOISE.contains(word))
			{
				out.add(word);
			}
		}
		return out;
	}

	// ----------------------------------------------------------------- reading

	/** Animation debug names from RuneLite's generated table. */
	private static Map<String, Integer> animationNames()
	{
		Map<String, Integer> out = new LinkedHashMap<>();
		try
		{
			Class<?> ids = Class.forName("net.runelite.api.gameval.AnimationID");
			for (Field f : ids.getDeclaredFields())
			{
				if (f.getType() == int.class && Modifier.isStatic(f.getModifiers()))
				{
					f.setAccessible(true);
					out.put(f.getName(), f.getInt(null));
				}
			}
		}
		catch (ReflectiveOperationException e)
		{
			// Reported by the caller as an empty map.
		}
		return out;
	}

	/** Distinct obstacle menu texts, with how many transport rows use each. */
	private static Map<String, int[]> readObstacles(String[] dirs) throws IOException
	{
		Map<String, int[]> out = new TreeMap<>();

		for (String dir : dirs)
		{
			File[] files = new File(dir).listFiles((d, n) -> n.endsWith(".tsv"));
			if (files == null)
			{
				continue;
			}
			Arrays.sort(files);

			for (File f : files)
			{
				try (BufferedReader r = new BufferedReader(new FileReader(f)))
				{
					String[] header = null;
					String line;
					while ((line = r.readLine()) != null)
					{
						if (line.trim().isEmpty())
						{
							continue;
						}
						if (line.startsWith("#"))
						{
							if (header == null)
							{
								header = line.substring(1).trim().split("\t");
							}
							continue;
						}
						if (header == null)
						{
							header = line.split("\t");
							continue;
						}

						String menu = col(header, line.split("\t", -1),
							"menuOption menuTarget objectID");
						if (menu.isEmpty())
						{
							continue;
						}

						// Drop the trailing object id; the words are what matter.
						String text = menu.replaceAll("\\s+\\d+$", "").trim();
						if (text.isEmpty() || text.toLowerCase(Locale.ROOT).startsWith("open "))
						{
							// Doors animate themselves; the player just walks through.
							continue;
						}
						out.computeIfAbsent(text, k -> new int[1])[0]++;
					}
				}
			}
		}
		return out;
	}

	private static String col(String[] header, String[] fields, String name)
	{
		for (int i = 0; i < header.length; i++)
		{
			if (header[i].trim().equalsIgnoreCase(name) && i < fields.length)
			{
				return fields[i].trim();
			}
		}
		return "";
	}

	private static String pad(Object value, int width)
	{
		StringBuilder sb = new StringBuilder(String.valueOf(value));
		while (sb.length() < width)
		{
			sb.append(' ');
		}
		return sb.toString();
	}

	private static final class Scored
	{
		final String name;
		final int id;
		final int score;

		Scored(String name, int id, int score)
		{
			this.name = name;
			this.id = id;
			this.score = score;
		}
	}

	/** One complete motion: the clips of a family, in the order they are played. */
	private static final class Family
	{
		final String name;
		final List<String> members;
		final int[] clips;
		final int score;

		Family(String name, List<String> members, int[] clips, int score)
		{
			this.name = name;
			this.members = members;
			this.clips = clips;
			this.score = score;
		}

		boolean contains(int id)
		{
			for (int clip : clips)
			{
				if (clip == id)
				{
					return true;
				}
			}
			return false;
		}

		/** The clip set as it would be written in {@code GolemContent}. */
		String set()
		{
			StringBuilder sb = new StringBuilder(name).append(" {");
			for (int i = 0; i < clips.length; i++)
			{
				sb.append(i == 0 ? "" : ", ").append(clips[i]);
			}
			return sb.append('}').toString();
		}
	}
}
