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
 * What to call a golem the player has not named.
 *
 * <p>Nothing is written down. A name is worked out from the golem's own number, so the same golem
 * is always called the same thing, four hundred golems cost nothing to name, and changing the pack
 * renames all of them at once. A name the player types is a name; this is what the plugin calls a
 * golem until then.
 *
 * <p>Shown in the plugin's own places — the list and a golem's page — and nowhere the game draws.
 * A nameplate over a golem's head still means somebody named it, and the right-click menu still
 * says "Golem", because a copy that introduces itself is a copy that gives itself away.
 */
@Slf4j
@Singleton
class GolemNames
{
	/** Which set of names to draw from. The setting; see GolemsDontDieConfig.namePack. */
	enum Pack
	{
		OFF("Unnamed"),
		PLAIN("Plain"),
		STONE("Stone"),
		GIELINOR("Gielinor");

		private final String label;

		Pack(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	private static final String FILE = "/names.gz";

	/**
	 * Ordinary first names, for golems that are somebody's mate rather than a rock formation.
	 * Written rather than harvested: the game has no list of plain names, and a plugin that calls
	 * a golem Dave should be sure it means Dave.
	 */
	private static final String[] PLAIN = ("Aaron Abby Adam Adele Ahmed Aisha Alan Alba Alex Alice Amara Amir "
		+ "Amy Ana Andre Andrea Angus Anita Anna Anthony Arjun Arthur Asha Astrid Ayaan Barbara Basil Beatrice "
		+ "Ben Bernard Beth Bianca Bill Bob Bobby Bonnie Boris Brenda Brian Bruce Bruno Callum Carl Carla Carlos "
		+ "Carmen Carol Cathy Cecil Chen Chloe Chris Cindy Claire Clara Clive Colin Conor Craig Daisy Dale Dan "
		+ "Dana Daniel Dave David Dawn Dean Debbie Denis Derek Diana Dmitri Dolores Don Donna Doris Dorothy "
		+ "Dougal Duncan Ed Edith Edward Eileen Elena Eli Elsie Emeka Emily Enzo Eric Erika Esther Ethan Eva "
		+ "Evelyn Farah Fatima Felix Fernando Fiona Frank Freda Gail Gary Gemma George Gerald Gina Giulia Glenn "
		+ "Gloria Gordon Grace Graham Greg Gwen Hana Hank Hannah Harold Harriet Harry Hassan Hazel Heather "
		+ "Hector Helen Henry Hilda Hugo Ian Ida Ingrid Irene Isaac Ivan Ivy Jack Jackie Jacob Jade James Jamie "
		+ "Jan Jane Janet Jasmine Jason Javier Jean Jeff Jenny Jess Jim Joan Joanne Joe John Jonas Jordan Jose "
		+ "Joseph Joy Juan Judith Julia Julie June Karen Karl Kate Kathy Keith Kelly Ken Kevin Khalid Kim Kiran "
		+ "Kirsty Lars Laura Lee Leila Len Leo Leon Lewis Li Lila Linda Lisa Liu Liz Lloyd Logan Lola Lorna "
		+ "Louis Lucy Luis Luka Lydia Maeve Maggie Malik Marcel Margaret Maria Marie Mario Mark Martha Martin "
		+ "Mary Mateo Maureen Max Maya Megan Mei Melanie Michael Mick Mika Miles Millie Mohammed Molly Monica "
		+ "Morag Murray Nadia Nancy Naomi Natalie Neil Nelson Nia Nigel Nina Noah Nora Norman Oliver Olga Omar "
		+ "Oscar Owen Paolo Pat Patrick Paul Paula Pearl Pedro Peggy Penny Percy Pete Philip Phoebe Priya Rachel "
		+ "Rafael Raj Ralph Ramona Ray Rebecca Reg Rhys Rita Robert Robin Roger Rosa Rose Roy Ruby Rudy Russell "
		+ "Ruth Ryan Sadie Sally Sam Samir Sandra Sara Scott Sean Selma Shane Sheila Sid Simon Sofia Sonia "
		+ "Stanley Stella Steve Stuart Sue Susan Sven Sylvia Tariq Ted Terry Tessa Theo Thomas Tina Toby Tom "
		+ "Tracy Trevor Ulrich Una Ursula Valerie Vera Victor Violet Vincent Walter Wanda Wayne Wendy Wesley "
		+ "William Willow Wilma Xavier Yara Yasmin Yuki Yusuf Zara Zoe").split(" ");

	/** The front of a stone golem's name. All of them are something a rock is or is made of. */
	private static final String[] STONE_FIRST = ("Pebble Boulder Granite Flint Slate Marble Gravel Quartz Basalt "
		+ "Cobble Shale Chalk Sandy Clay Grit Rubble Cairn Crag Scree Moss Lime Ochre Onyx Jasper Opal Amber "
		+ "Obsidian Pumice Sable Tuff Geode Grindle Mica Schist Gneiss Flagstone Kerb Cinder Ash Ember Soot "
		+ "Dolomite Lode Seam Quarry Rubblehead Millstone Keystone Cobblestone Bedrock Gritstone Whetstone "
		+ "Ironstone Limestone Sandstone Greystone Blackstone Brownstone Fieldstone Riverstone").split(" ");

	/** And the back of it. A few are left off, so some golems are simply called Flint. */
	private static final String[] STONE_LAST = ("sworth ton ina wick bert ella us ia o y ington bottom brow "
		+ "foot heart head hew ric ard well ford stone shaw by ham dale more rick etta ilda ow ley man son wyn "
		+ "abelle isha ony arnia").split(" ");

	/** How often a stone golem gets the short form: Flint rather than Flintwick. */
	private static final int SHORT_IN = 6;

	/**
	 * How often a plain golem goes by one name. The rest get a surname off the rocks, which is
	 * what keeps four hundred golems from being sixty Daves: three hundred first names among five
	 * hundred golems is half of them sharing.
	 */
	private static final int ONE_NAME_IN = 3;

	@Inject
	private GolemsDontDieConfig config;

	/** The people of Gielinor, harvested from the cache; see dev-tools/BuildNames.java. */
	@Getter
	private String[] gielinor = new String[0];

	/** Reads the harvested names. Called once at start-up, beside the other data files. */
	void load()
	{
		try (InputStream in = GolemNames.class.getResourceAsStream(FILE))
		{
			if (in == null)
			{
				log.warn("No names on the classpath; the Gielinor name pack will be empty");
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
	}

	/**
	 * What to call this golem, or null if the player has named it or wants no names at all.
	 *
	 * <p>Cheap enough to ask every time a row is drawn: one multiply and a couple of array reads.
	 */
	String suggested(Golem golem)
	{
		if (golem == null || golem.getNickname() != null && !golem.getNickname().isEmpty())
		{
			return null;
		}
		Pack pack = config == null ? Pack.OFF : config.namePack();
		return pack == null ? null : nameFor(pack, golem.getId());
	}

	/** The name a pack gives a golem's own number; null for the pack that gives none. */
	String nameFor(Pack pack, long id)
	{
		// Its own generator, salted per pack, so switching packs is a fresh name for everyone and
		// asking a golem its name never disturbs where it walks. See GolemTrait.of.
		Random random = new Random(id * 0x9E3779B97F4A7C15L ^ (pack.ordinal() + 1L) * 0x5DEECE66DL);
		switch (pack)
		{
			case PLAIN:
				String plain = PLAIN[random.nextInt(PLAIN.length)];
				return random.nextInt(ONE_NAME_IN) == 0 ? plain
					: plain + " " + STONE_FIRST[random.nextInt(STONE_FIRST.length)];
			case STONE:
				String first = STONE_FIRST[random.nextInt(STONE_FIRST.length)];
				if (random.nextInt(SHORT_IN) == 0)
				{
					return first;
				}
				return join(first, STONE_LAST[random.nextInt(STONE_LAST.length)]);
			case GIELINOR:
				return gielinor.length == 0 ? null : gielinor[random.nextInt(gielinor.length)];
			default:
				return null;
		}
	}

	/**
	 * Puts the two halves of a stone name together, dropping a letter where they would collide:
	 * Pebble and -ina is Pebblina rather than Pebbleina, Tuff and -foot is Tuffoot rather than
	 * Tufffoot, and Flint and -ton is Flinton.
	 */
	static String join(String first, String last)
	{
		char seam = first.charAt(first.length() - 1);
		char next = last.charAt(0);
		boolean collides = seam == next
			|| "aeiouy".indexOf(seam) >= 0 && "aeiouy".indexOf(next) >= 0;
		return (collides ? first.substring(0, first.length() - 1) : first) + last;
	}
}
