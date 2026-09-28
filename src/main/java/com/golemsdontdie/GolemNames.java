package com.golemsdontdie;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * What to call a golem the player has not named: one of the names of Gielinor, and a surname off
 * the rocks — Akrisae Flint, Doris Millstone, Veos Greystone — or, in the ordinal style, the order
 * it was crafted in, in Latin: Primus, Vicesimus Septimus, Bis Millesimus Quingentesimus.
 *
 * <p>No name is written down. It is worked out from the golem's own number, or its craft number
 * for an ordinal, so the same golem is always called the same thing, four hundred golems cost
 * nothing to name, and turning the setting off leaves nothing behind. A name the player types is a name; this is only what the
 * plugin calls a golem until then, and it gives way the moment one is typed.
 *
 * <p>Shown in the plugin's own places — the list and a golem's page — and nowhere the game draws.
 * A nameplate over a golem's head still means somebody named it, and the right-click menu still
 * says "Golem", because a copy that introduces itself is a copy that gives itself away.
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

	/** The people of Gielinor, harvested offline from the cache. */
	@Getter
	private String[] gielinor = new String[0];

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
						names.add(name);
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
	 * <p>Cheap enough to ask every time a row is drawn: one multiply and two array reads.
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
		return nameFor(golem.getId());
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

	/** The name a golem's own number gives it, or null if there are no names to give. */
	String nameFor(long id)
	{
		if (gielinor.length == 0)
		{
			return null;
		}
		// Its own generator, so asking a golem its name never disturbs where it walks or what it
		// is like: those come out of generators of their own. See GolemTrait.of.
		Random random = new Random(id * 0x9E3779B97F4A7C15L ^ 0x27D4EB2F165667C5L);
		return gielinor[random.nextInt(gielinor.length)] + " " + ROCKS[random.nextInt(ROCKS.length)];
	}
}
