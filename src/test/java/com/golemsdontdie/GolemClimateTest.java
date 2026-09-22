package com.golemsdontdie;

import java.lang.reflect.Field;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The climate lists are curated by hand against the shipped place names, so the tests that matter
 * are that every name in them still names a place, and that a golem with a taste for one ends up
 * preferring it.
 */
public class GolemClimateTest
{
	private static final int WEISS_X = 2878;
	private static final int WEISS_Y = 3940;
	private static final int AL_KHARID_X = 3293;
	private static final int AL_KHARID_Y = 3184;
	private static final int VARROCK_X = 3213;
	private static final int VARROCK_Y = 3428;
	private static final int WYRMSCRAIG_X = GolemContent.PLINTH_X;
	private static final int WYRMSCRAIG_Y = GolemContent.PLINTH_Y;

	private PlaceNames places;
	private GolemClimate climate;

	@Before
	public void setUp()
	{
		places = new PlaceNames();
		places.load();
		climate = new GolemClimate();
		climate.learn(places);
	}

	/** Every curated name still names somewhere: the lists are only as good as the data under them. */
	@Test
	public void everyCuratedNameIsAPlace() throws Exception
	{
		for (String list : new String[]{"COLD", "HOT"})
		{
			Field field = GolemClimate.class.getDeclaredField(list);
			field.setAccessible(true);
			for (String name : (String[]) field.get(null))
			{
				assertFalse(list + " has no place called " + name,
					places.regionsNamed(new String[]{name}).isEmpty());
			}
		}
	}

	@Test
	public void coldPlacesAreColdAndHotPlacesHot()
	{
		assertTrue("Weiss is cold", climate.isCold(WEISS_X, WEISS_Y));
		assertFalse("Weiss is not hot", climate.isHot(WEISS_X, WEISS_Y));
		assertTrue("Al Kharid is hot", climate.isHot(AL_KHARID_X, AL_KHARID_Y));
		assertFalse("Al Kharid is not cold", climate.isCold(AL_KHARID_X, AL_KHARID_Y));
		assertFalse("Varrock is neither", climate.isCold(VARROCK_X, VARROCK_Y) || climate.isHot(VARROCK_X, VARROCK_Y));
	}

	/** A golem with no taste in places is untouched by any of it. */
	@Test
	public void aGolemWithoutTastesIsUnmoved()
	{
		TransportMemory plain = new TransportMemory();
		plain.setTraits(GolemTrait.FRIENDLY.mask());
		assertFalse(climate.cares(plain));
		assertEquals(1f, climate.liking(plain, WEISS_X, WEISS_Y), 0.0001f);
		assertEquals(1f, climate.desire(plain, WEISS_X, WEISS_Y, AL_KHARID_X, AL_KHARID_Y), 0.0001f);
		assertEquals(1f, climate.desire(null, WEISS_X, WEISS_Y, AL_KHARID_X, AL_KHARID_Y), 0.0001f);
	}

	@Test
	public void aColdGolemPrefersTheSnow()
	{
		TransportMemory cold = new TransportMemory();
		cold.setTraits(GolemTrait.LIKES_THE_COLD.mask());
		assertTrue(climate.cares(cold));
		assertEquals(1f, climate.liking(cold, WEISS_X, WEISS_Y), 0.0001f);
		assertTrue("the snow beats the desert",
			climate.liking(cold, WEISS_X, WEISS_Y) > climate.liking(cold, AL_KHARID_X, AL_KHARID_Y));
		// Under White Wolf Mountain, against Varrock: the slope is gentle, but it points.
		assertTrue("nearer the snow is better",
			climate.liking(cold, 2900, 3550) > climate.liking(cold, VARROCK_X, VARROCK_Y));

		// Leaving the snow is a discount on whatever offered; arriving at it never is.
		assertTrue(climate.desire(cold, WEISS_X, WEISS_Y, VARROCK_X, VARROCK_Y) < 0.7f);
		assertEquals(1f, climate.desire(cold, VARROCK_X, VARROCK_Y, WEISS_X, WEISS_Y), 0.0001f);
		assertEquals(1f, climate.desire(cold, AL_KHARID_X, AL_KHARID_Y, VARROCK_X, VARROCK_Y), 0.0001f);
	}

	@Test
	public void aHotGolemPrefersTheDesert()
	{
		TransportMemory hot = new TransportMemory();
		hot.setTraits(GolemTrait.LIKES_THE_HEAT.mask());
		assertEquals(1f, climate.liking(hot, AL_KHARID_X, AL_KHARID_Y), 0.0001f);
		assertTrue(climate.liking(hot, AL_KHARID_X, AL_KHARID_Y) > climate.liking(hot, WEISS_X, WEISS_Y));
		assertTrue(climate.desire(hot, AL_KHARID_X, AL_KHARID_Y, WEISS_X, WEISS_Y) < 0.7f);
	}

	/** Home is the island, and the island's cave is part of it: a dungeon lies under its own ground. */
	@Test
	public void aHomesickGolemCountsTheCaveAsHome()
	{
		TransportMemory homesick = new TransportMemory();
		homesick.setTraits(GolemTrait.HOMESICK.mask());
		assertEquals(1f, climate.liking(homesick, WYRMSCRAIG_X, WYRMSCRAIG_Y), 0.0001f);
		assertEquals(1f, climate.liking(homesick, WYRMSCRAIG_X, WYRMSCRAIG_Y + 6400), 0.0001f);
		assertTrue(climate.liking(homesick, WYRMSCRAIG_X, WYRMSCRAIG_Y) > climate.liking(homesick, VARROCK_X, VARROCK_Y));
		assertTrue(climate.desire(homesick, WYRMSCRAIG_X, WYRMSCRAIG_Y, VARROCK_X, VARROCK_Y) < 0.5f);
		assertEquals("and coming back is never a discount", 1f,
			climate.desire(homesick, VARROCK_X, VARROCK_Y, WYRMSCRAIG_X, WYRMSCRAIG_Y), 0.0001f);
	}
}
