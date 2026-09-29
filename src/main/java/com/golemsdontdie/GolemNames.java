package com.golemsdontdie;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;

/**
 * What to call a golem the player has not named: one of the names of Gielinor, and a surname off
 * the rocks — Akrisae Flint, Doris Millstone, Veos Greystone — or, in the ordinal style, the order
 * it was crafted in, in Latin: Primus, Vicesimus Septimus, Bis Millesimus Quingentesimus.
 *
 * <p>No name is written down. It is worked out from the golem's own number, and the older golems'
 * where two would share one, or its craft number for an ordinal, so the same golem is always
 * called the same thing, no two unnamed golems answer to the same name, four hundred golems cost
 * nothing to name, and turning the setting off leaves nothing behind. A name the player types is a name; this is only what the
 * plugin calls a golem until then, and it gives way the moment one is typed.
 *
 * <p>Shown wherever a golem's name is: the list, its page, the map, its nameplate and its menu
 * entries. Turning auto naming off leaves only the names a player typed, and the rest go back to
 * being golems.
 */
@Slf4j
@Singleton
class GolemNames
{
	private static final String FILE = "/names.gz";

	/**
	 * The ordinals, one to a line: 1 to 999 each spelled out, then each thousand to fifty thousand,
	 * then "Last", for anything past the end of the list. Contributed by Hjaldr in issue 2, to ten
	 * thousand; the thousands past that follow the same pattern.
	 */
	private static final String ORDINALS = "/ordinals.txt";

	/** Place in a thousand, 1 to 999, spelled out; index 0 unused. */
	private final String[] ordinals = new String[1000];

	/** Each thousand, 1 to 50, as the words before the rest; index 0 unused. */
	private final String[] thousands = new String[51];

	/** For a golem numbered past the end of the list. */
	private String last;

	/**
	 * Surnames, all of them something a rock is or is made of. The first names do the work of
	 * telling golems apart; these do the work of making them golems.
	 */
	private static final String[] ROCKS = ("Pebble Boulder Granite Flint Slate Marble Gravel Quartz Basalt "
		+ "Cobble Shale Chalk Clay Grit Rubble Cairn Crag Scree Moss Lime Ochre Onyx Jasper Opal Amber "
		+ "Obsidian Pumice Sable Tuff Geode Mica Schist Gneiss Flagstone Kerb Cinder Ash Ember Soot Dolomite "
		+ "Lode Seam Quarry Millstone Keystone Cobblestone Bedrock Gritstone Whetstone Ironstone Limestone "
		+ "Sandstone Greystone Blackstone Brownstone Fieldstone Riverstone Rubblehead Stonewall Chippings "
		+ "Boulderfoot Flintlock Gravelly Marblewell Slatebottom Quarryman").split(" ");

	@Inject
	private GolemsDontDieConfig config;

	/** The people of Gielinor, harvested offline from the cache; null where one is struck out. */
	@Getter
	private String[] gielinor = new String[0];

	/**
	 * Each unnamed golem's name as of the last {@link #assign}, by id. Replaced whole rather than
	 * changed, as the panel reads it from the Swing thread while the client thread assigns.
	 */
	private volatile Map<Long, String> assigned = Collections.emptyMap();

	/**
	 * How many further names a golem tries before settling for a name it shares. A roster of fifty
	 * thousand fills well over half the names there are, and the last golem made still finds a free
	 * one within a few tries; this is only so a roster larger than the names can never hang.
	 */
	private static final int MOST_TRIES = 64;

	/** Reads the harvested names. Called once at start-up, beside the other data files. */
	void load()
	{
		try (InputStream in = GolemNames.class.getResourceAsStream(FILE))
		{
			if (in == null)
			{
				log.warn("No names on the classpath; golems will go unnamed");
				return;
			}
			List<String> names = new ArrayList<>();
			try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8)))
			{
				for (String line = reader.readLine(); line != null; line = reader.readLine())
				{
					String name = line.trim();
					if (!name.isEmpty())
					{
						// A line struck out with # is a job or a creature rather than a name. It keeps
						// its place, empty, so the names after it do not move: taken out, every golem
						// the plugin had named would have come back called something else.
						names.add(name.startsWith("#") ? null : name);
					}
				}
			}
			gielinor = names.toArray(new String[0]);
			log.debug("Loaded {} names", gielinor.length);
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Names unreadable", e);
		}
		loadOrdinals();
	}

	private void loadOrdinals()
	{
		try (InputStream in = GolemNames.class.getResourceAsStream(ORDINALS))
		{
			if (in == null)
			{
				log.warn("No ordinals on the classpath; ordinal names are off");
				return;
			}
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
			{
				for (String line = reader.readLine(); line != null; line = reader.readLine())
				{
					int split = line.indexOf(';');
					if (line.startsWith("#") || split < 0)
					{
						continue;
					}
					String key = line.substring(0, split).trim();
					String name = line.substring(split + 1).trim();
					if (key.equals("Last"))
					{
						last = name;
						continue;
					}
					int n = Integer.parseInt(key);
					if (n < 1000)
					{
						ordinals[n] = name;
					}
					else if (n % 1000 == 0 && n / 1000 < thousands.length)
					{
						thousands[n / 1000] = name;
					}
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Ordinals unreadable", e);
		}
	}

	/**
	 * What to call this golem: the name the player gave it, the one the setting gives it, or null
	 * if it has neither. What a nameplate says, and what the right-click menu calls it.
	 */
	String of(Golem golem)
	{
		if (golem == null)
		{
			return null;
		}
		String nickname = golem.getNickname();
		return nickname != null && !nickname.isEmpty() ? nickname : suggested(golem);
	}

	/**
	 * What to call this golem, or null if the player has named it or the setting is off.
	 *
	 * <p>Cheap enough to ask every time a row is drawn: one map lookup, the work being done in
	 * {@link #assign} when the roster changes.
	 */
	String suggested(Golem golem)
	{
		if (golem == null || golem.getNickname() != null && !golem.getNickname().isEmpty()
			|| config != null && !config.autoName())
		{
			return null;
		}
		if (config != null && config.nameStyle() == GolemsDontDieConfig.NameStyle.ORDINAL)
		{
			return ordinal(golem.getCraftNumber());
		}
		// A golem made since the last assignment has not been checked against the others yet, and
		// goes by its own name until it is.
		String name = assigned.get(golem.getId());
		return name != null ? name : nameFor(golem.getId());
	}

	/**
	 * Settles who answers to what, so no two unnamed golems in the roster share a name. Called on
	 * the client thread whenever the roster changes or a golem is named, never per frame.
	 *
	 * <p>Oldest first, by craft number, each golem takes the first name its own number gives it that
	 * nobody older has taken, so a name two golems would share stays with the older and the newer
	 * moves on to its next. Nothing is written down: the same roster always comes out the same, and
	 * a golem made later never changes the name of one made before it. A golem the player has named
	 * holds no name here, and one that went without because of it takes it back.
	 *
	 * @return whether any golem already assigned a name now has a different one
	 */
	boolean assign(List<Golem> roster)
	{
		if (gielinor.length == 0)
		{
			return false;
		}
		List<Golem> unnamed = new ArrayList<>(roster.size());
		for (Golem golem : roster)
		{
			if (golem.getNickname() == null || golem.getNickname().isEmpty())
			{
				unnamed.add(golem);
			}
		}
		// Not yet numbered counts as newest: it is a golem restored or made a moment ago.
		unnamed.sort(Comparator.comparingInt((Golem golem) ->
				golem.getCraftNumber() > 0 ? golem.getCraftNumber() : Integer.MAX_VALUE)
			.thenComparingLong(Golem::getId));

		Map<Long, String> before = assigned;
		Map<Long, String> after = new HashMap<>(unnamed.size() * 2);
		Set<String> taken = new HashSet<>(unnamed.size() * 2);
		boolean changed = false;
		for (Golem golem : unnamed)
		{
			Random random = generator(golem.getId());
			String name = draw(random);
			for (int tries = 0; taken.contains(name) && tries < MOST_TRIES; tries++)
			{
				name = draw(random);
			}
			taken.add(name);
			after.put(golem.getId(), name);
			String was = before.get(golem.getId());
			changed |= was != null && !was.equals(name);
		}
		assigned = after;
		return changed;
	}

	/**
	 * The Latin ordinal of a craft number: 27 is Vicesimus Septimus, 2596 Bis Millesimus
	 * Quingentesimus Nonagesimus Sextus. Past the thousands the list has, the last. Null for a golem
	 * not numbered yet, which is one not yet restored.
	 */
	String ordinal(int n)
	{
		if (n <= 0)
		{
			return null;
		}
		if (n < 1000)
		{
			return ordinals[n];
		}
		int thousand = n / 1000;
		int rest = n % 1000;
		if (thousand >= thousands.length || thousands[thousand] == null)
		{
			return last;
		}
		return rest == 0 ? thousands[thousand] : thousands[thousand] + " " + ordinals[rest];
	}

	/**
	 * The name a golem's own number gives it, or null if there are no names to give. The first of
	 * the names that number gives, and the one it goes by unless an older golem has it: see assign.
	 */
	String nameFor(long id)
	{
		return gielinor.length == 0 ? null : draw(generator(id));
	}

	/**
	 * Its own generator, so asking a golem its name never disturbs where it walks or what it is
	 * like: those come out of generators of their own. See GolemTrait.of.
	 */
	private static Random generator(long id)
	{
		return new Random(id * 0x9E3779B97F4A7C15L ^ 0x27D4EB2F165667C5L);
	}

	/** The next name out of a golem's generator, drawing again past a struck-out one. */
	private String draw(Random random)
	{
		String first = gielinor[random.nextInt(gielinor.length)];
		while (first == null)
		{
			first = gielinor[random.nextInt(gielinor.length)];
		}
		return first + " " + ROCKS[random.nextInt(ROCKS.length)];
	}
}
