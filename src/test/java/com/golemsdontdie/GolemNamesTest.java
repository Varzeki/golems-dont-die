package com.golemsdontdie;

import java.util.HashSet;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The names a golem answers to when nobody has named it. */
public class GolemNamesTest
{
	/** The same letter three times over, which a join can make and a name never has. */
	private static final java.util.regex.Pattern TRIPLE =
		java.util.regex.Pattern.compile("([A-Za-z])\\1\\1");

	private GolemNames names;

	@Before
	public void setUp()
	{
		names = new GolemNames();
		names.load();
	}

	@Test
	public void theHarvestedNamesAreThere()
	{
		assertTrue("names.gz should hold the people of Gielinor", names.getGielinor().length > 500);
	}

	@Test
	public void everyPackNamesEveryGolem()
	{
		for (GolemNames.Pack pack : GolemNames.Pack.values())
		{
			for (long id = -50; id < 50; id++)
			{
				String name = names.nameFor(pack, id);
				if (pack == GolemNames.Pack.OFF)
				{
					assertNull("nothing is named when the pack is off", name);
					continue;
				}
				assertNotNull(pack + " named golem " + id, name);
				assertTrue(pack + " gave an empty name", name.length() > 1);
				assertTrue(pack + " gave '" + name + "'", name.matches("[A-Z][A-Za-z]+( [A-Z][a-z]+)?"));
			}
		}
	}

	@Test
	public void aGolemIsAlwaysCalledTheSameThing()
	{
		for (long id = 0; id < 100; id++)
		{
			assertEquals(names.nameFor(GolemNames.Pack.STONE, id), names.nameFor(GolemNames.Pack.STONE, id));
		}
	}

	/** A roster of a few hundred should not be half Daves. */
	@Test
	public void aPackHasEnoughNamesToGoRound()
	{
		for (GolemNames.Pack pack : new GolemNames.Pack[]{
			GolemNames.Pack.PLAIN, GolemNames.Pack.STONE, GolemNames.Pack.GIELINOR})
		{
			Set<String> seen = new HashSet<>();
			for (long id = 0; id < 500; id++)
			{
				seen.add(names.nameFor(pack, id * 7919));
			}
			assertTrue(pack + " gave only " + seen.size() + " names to 500 golems", seen.size() > 300);
		}
	}

	/** Switching packs is a fresh name for everyone, not the same name in a different list. */
	@Test
	public void thePacksDisagree()
	{
		int same = 0;
		for (long id = 0; id < 200; id++)
		{
			if (names.nameFor(GolemNames.Pack.PLAIN, id).equals(names.nameFor(GolemNames.Pack.GIELINOR, id)))
			{
				same++;
			}
		}
		assertTrue("the packs should not agree on much", same < 5);
	}

	/** Where the two halves of a stone name meet, a vowel gives way to the next. */
	@Test
	public void theHalvesOfAStoneNameJoinUp()
	{
		assertEquals("Pebblina", GolemNames.join("Pebble", "ina"));
		assertEquals("Pebblesworth", GolemNames.join("Pebble", "sworth"));
		assertEquals("Sandy", GolemNames.join("Sandy", "y"));
		assertEquals("Ashy", GolemNames.join("Ash", "y"));
		assertEquals("Granitella", GolemNames.join("Granite", "ella"));
		assertEquals("Flintwick", GolemNames.join("Flint", "wick"));
		assertEquals("Tuffoot", GolemNames.join("Tuff", "foot"));
		assertEquals("Flinton", GolemNames.join("Flint", "ton"));
		assertEquals("Mossworth", GolemNames.join("Moss", "sworth"));
	}

	/** And nothing comes out with a letter three times over, which is a join gone wrong. */
	@Test
	public void noStoneNameStutters()
	{
		for (long id = 0; id < 5000; id++)
		{
			String name = names.nameFor(GolemNames.Pack.STONE, id);
			assertTrue(name + " has a letter three times over", !TRIPLE.matcher(name).find());
		}
	}
}
