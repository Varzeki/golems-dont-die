package com.golemsdontdie;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The name a golem answers to when nobody has named it. */
public class GolemNamesTest
{
	private static final WorldPoint PLINTH = new WorldPoint(2596, 2256, 0);

	private GolemNames names;

	@Before
	public void setUp()
	{
		names = new GolemNames();
		names.load();
	}

	private static Golem golem(long seed, String nickname)
	{
		GolemSnapshot snapshot = new GolemSnapshot(1234, "Golem", new int[0], new short[0], new short[0],
			128, 128, 1, -1, -1, -1, PLINTH, 0);
		Golem golem = Golem.onTile(snapshot, PLINTH, seed, PLINTH);
		golem.setNickname(nickname);
		return golem;
	}

	private void setAutoName(boolean on) throws Exception
	{
		setConfig(on, GolemsDontDieConfig.NameStyle.DEFAULT);
	}

	private void setConfig(boolean on, GolemsDontDieConfig.NameStyle style) throws Exception
	{
		Field field = GolemNames.class.getDeclaredField("config");
		field.setAccessible(true);
		field.set(names, new GolemsDontDieConfig()
		{
			@Override
			public boolean autoName()
			{
				return on;
			}

			@Override
			public NameStyle nameStyle()
			{
				return style;
			}
		});
	}

	@Test
	public void theHarvestedNamesAreThere()
	{
		assertTrue("names.gz should hold the people of Gielinor", names.getGielinor().length > 500);
	}

	/** A name from Gielinor and a surname off the rocks: two words, both of them names. */
	@Test
	public void everyGolemIsCalledSomething()
	{
		for (long id = -200; id < 200; id++)
		{
			String name = names.nameFor(id);
			assertNotNull("golem " + id + " went unnamed", name);
			assertTrue("'" + name + "' is not a first name and a surname",
				name.matches("[A-Z][A-Za-z]+ [A-Z][a-z]+"));
		}
	}

	@Test
	public void aGolemIsAlwaysCalledTheSameThing()
	{
		for (long id = 0; id < 100; id++)
		{
			assertEquals(names.nameFor(id), names.nameFor(id));
		}
	}

	/** A roster of a thousand should be a thousand names, not sixty Dorises. */
	@Test
	public void thereAreEnoughNamesToGoRound()
	{
		Set<String> seen = new HashSet<>();
		for (long id = 0; id < 1000; id++)
		{
			seen.add(names.nameFor(id * 7919));
		}
		assertTrue("only " + seen.size() + " names for a thousand golems", seen.size() > 980);
	}

	/** A golem the player has named keeps that name, whatever the setting says. */
	@Test
	public void aNamedGolemIsLeftAlone() throws Exception
	{
		setAutoName(true);
		assertNotNull("an unnamed golem is given one", names.suggested(golem(7, null)));
		assertNull("a named golem keeps its name", names.suggested(golem(7, "Kevin")));
		assertNotNull("a name cleared back to nothing is not a name", names.suggested(golem(7, "")));
	}

	/** Off, nobody is called anything, and the golems are golems again. */
	@Test
	public void offMeansOff() throws Exception
	{
		setAutoName(false);
		assertNull(names.suggested(golem(7, null)));

		setAutoName(true);
		String named = names.suggested(golem(7, null));
		setAutoName(false);
		assertNull("turning it off takes the name away", names.suggested(golem(7, null)));
		setAutoName(true);
		assertEquals("and turning it on gives the same one back", named, names.suggested(golem(7, null)));
	}

	/** The examples from issue 2, and the ends of the list. */
	@Test
	public void ordinalsReadAsTheIssueAskedForThem()
	{
		assertEquals("Primus", names.ordinal(1));
		assertEquals("Vicesimus Septimus", names.ordinal(27));
		assertEquals("Undeseptuagesimus", names.ordinal(69));
		assertEquals("Octogesimus Primus", names.ordinal(81));
		assertEquals("Sescentesimus Tricesimus Quintus", names.ordinal(635));
		assertEquals("Octingentesimus Quinquagesimus Sextus", names.ordinal(856));
		assertEquals("Nongentesimus Octavus", names.ordinal(908));
		assertEquals("Millesimus", names.ordinal(1000));
		assertEquals("Bis Millesimus Quingentesimus Nonagesimus Sextus", names.ordinal(2596));
		assertEquals("Septies Millesimus Trecentesimus Duodenonagesimus", names.ordinal(7388));
		assertEquals("Vicies Septies Millesimus", names.ordinal(27000));
		assertEquals("Quinquagies Millesimus", names.ordinal(50000));
		assertEquals("Quinquagies Millesimus Nongentesimus Undecentesimus", names.ordinal(50999));
		assertEquals("Ultimus", names.ordinal(51000));
		assertNull("a golem not yet numbered has no ordinal", names.ordinal(0));
	}

	/**
	 * Eights and nines count down from the next ten, 98 and 99 from the hundred, and the hundreds
	 * and thousands in front of them are added unchanged.
	 */
	@Test
	public void ninetyEightCountsDownLikeEighteen()
	{
		assertEquals("Duodevicesimus", names.ordinal(18));
		assertEquals("Duodecentesimus", names.ordinal(98));
		assertEquals("Undecentesimus", names.ordinal(99));
		assertEquals("Centesimus Duodecentesimus", names.ordinal(198));
		assertEquals("Millesimus Duodecentesimus", names.ordinal(1098));
		assertEquals("Bis Millesimus Quingentesimus Undecentesimus", names.ordinal(2599));
	}

	/** The teens of thousands are each one word, from Undecies to Undevicies. */
	@Test
	public void theTeensOfThousandsAreOneWordEach()
	{
		String[] teens = {"Undecies", "Duodecies", "Terdecies", "Quaterdecies", "Quinquiesdecies",
			"Sexiesdecies", "Septiesdecies", "Duodevicies", "Undevicies"};
		for (int i = 0; i < teens.length; i++)
		{
			assertEquals(teens[i] + " Millesimus", names.ordinal((11 + i) * 1000));
		}
	}

	/** Every golem to fifty thousand has an ordinal of its own. */
	@Test
	public void everyOrdinalToFiftyThousandIsDifferent()
	{
		Set<String> seen = new HashSet<>();
		for (int n = 1; n <= 50_000; n++)
		{
			String name = names.ordinal(n);
			assertNotNull("no ordinal for " + n, name);
			assertTrue("the ordinal for " + n + " was taken already: " + name, seen.add(name));
		}
	}

	/** In the ordinal style an unnamed golem is called by its craft number; a named one keeps its name. */
	@Test
	public void theOrdinalStyleCountsCrafts() throws Exception
	{
		setConfig(true, GolemsDontDieConfig.NameStyle.ORDINAL);
		Golem golem = golem(7, null);
		golem.setCraftNumber(3);
		assertEquals("Tertius", names.suggested(golem));
		assertNull(names.suggested(golem(7, "Kevin")));

		setConfig(true, GolemsDontDieConfig.NameStyle.DEFAULT);
		assertEquals(names.nameFor(golem.getId()), names.suggested(golem));
	}
}
