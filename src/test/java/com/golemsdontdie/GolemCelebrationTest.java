package com.golemsdontdie;

import java.lang.reflect.Field;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the golems dance about. The awkward half is what they must <em>not</em> dance about: every
 * level arrives at login, and the golem count arrives with it.
 */
public class GolemCelebrationTest
{
	/**
	 * Settings with every celebration on, so the tests are about the events and not the config.
	 * Golems crafted is the one that ships off.
	 */
	private static class AllOn implements GolemsDontDieConfig
	{
		@Override
		public boolean danceOnGolemCrafted()
		{
			return true;
		}

		@Override
		public boolean danceOnClue()
		{
			return true;
		}
	}

	private GolemCelebration celebration;

	@Before
	public void setUp() throws Exception
	{
		celebration = new GolemCelebration();
		setConfig(new AllOn());
	}

	private void setConfig(GolemsDontDieConfig config) throws Exception
	{
		Field field = GolemCelebration.class.getDeclaredField("config");
		field.setAccessible(true);
		field.set(celebration, config);
	}

	@Test
	public void aLevelGoingUpStartsTheDancing()
	{
		celebration.statChanged(Skill.MINING, 41, 100);
		assertFalse("the first sighting is not a level-up", celebration.isDancing(100));

		celebration.statChanged(Skill.MINING, 42, 100);
		assertTrue(celebration.isDancing(100));
		assertTrue("and it lasts a few seconds", celebration.isDancing(110));
		assertFalse("but not for ever", celebration.isDancing(200));
	}

	@Test
	public void experienceWithoutALevelIsNotWorthDancingAbout()
	{
		celebration.statChanged(Skill.MINING, 41, 100);
		celebration.statChanged(Skill.MINING, 41, 101);
		celebration.statChanged(Skill.MINING, 41, 102);
		assertFalse(celebration.isDancing(102));
	}

	/** Logging in reports every level and the golem count; none of that is news. */
	@Test
	public void loggingInIsNotACelebration()
	{
		for (Skill skill : Skill.values())
		{
			celebration.statChanged(skill, 70, 1);
		}
		celebration.golemCount(452, 1);
		assertFalse(celebration.isDancing(1));
	}

	@Test
	public void aGolemCraftedStartsTheDancing()
	{
		celebration.golemCount(452, 100);
		assertFalse(celebration.isDancing(100));
		celebration.golemCount(453, 100);
		assertTrue(celebration.isDancing(100));
	}

	@Test
	public void aCollectionLogEntryStartsTheDancing()
	{
		celebration.chatMessage("You have been awarded a pet!", 100);
		assertFalse(celebration.isDancing(100));
		celebration.chatMessage("New item added to your collection log: Abyssal whip", 100);
		assertTrue(celebration.isDancing(100));
	}

	/** Two things at once is one celebration, and the second does not cut the first short. */
	@Test
	public void aSecondCelebrationKeepsThemDancing()
	{
		celebration.statChanged(Skill.MINING, 41, 100);
		celebration.statChanged(Skill.MINING, 42, 100);
		celebration.statChanged(Skill.FISHING, 50, 108);
		celebration.statChanged(Skill.FISHING, 51, 108);
		assertTrue("still going at the first one's end", celebration.isDancing(117));
		assertTrue(celebration.isDancing(124));
	}

	@Test
	public void aSettingTurnedOffKeepsThemStill() throws Exception
	{
		setConfig(new GolemsDontDieConfig()
		{
			@Override
			public boolean danceOnLevelUp()
			{
				return false;
			}
		});
		celebration.statChanged(Skill.MINING, 41, 100);
		celebration.statChanged(Skill.MINING, 42, 100);
		assertFalse(celebration.isDancing(100));
	}

	/** A world hop restarts the tick counter, so a deadline in the old one must not be kept. */
	@Test
	public void hoppingWorldsForgetsEverything()
	{
		celebration.statChanged(Skill.MINING, 41, 5000);
		celebration.statChanged(Skill.MINING, 42, 5000);
		assertTrue(celebration.isDancing(5000));

		celebration.reset();
		assertFalse(celebration.isDancing(1));
		celebration.statChanged(Skill.MINING, 42, 1);
		assertFalse("and the level it already had is news to nobody", celebration.isDancing(1));
	}

	/** The game's own lines for the other occasions, colour tags and all. */
	@Test
	public void theOtherOccasionsAreRecognised()
	{
		String[] lines = {
			"Congratulations, you've completed a hard combat task: <col=06600c>Whack-a-Mole</col>.",
			"You have a funny feeling like you're being followed.",
			"Fight duration: <col=ff0000>1:23.40</col> (new personal best)",
			"You have completed <col=ef1020>12</col> medium Treasure Trails.",
		};
		int tick = 100;
		for (String line : lines)
		{
			tick += 100;
			celebration.chatMessage(line, tick);
			assertTrue(line, celebration.isDancing(tick));
		}
	}

	/** And an ordinary line is not an occasion. */
	@Test
	public void anOrdinaryLineIsNot()
	{
		celebration.chatMessage("You manage to mine some copper.", 100);
		celebration.chatMessage("Your Zulrah kill count is: <col=ff0000>12</col>.", 100);
		assertFalse(celebration.isDancing(100));
	}

	/** The pop-ups come whether or not the chat lines are on. */
	@Test
	public void aPopUpIsEnough()
	{
		celebration.popup("Collection log", 100);
		assertTrue(celebration.isDancing(100));
		celebration.popup("Combat Task Completed!", 300);
		assertTrue(celebration.isDancing(300));
		celebration.popup("Something else", 500);
		assertFalse(celebration.isDancing(500));
	}

	@Test
	public void aQuestIsWorthDancingAbout()
	{
		celebration.questCompleted(100);
		assertTrue(celebration.isDancing(100));
	}

	/**
	 * A diary tier already finished at login is not finished again: the first reads are where the
	 * player already stood, and only a tier that goes from not done to done afterwards counts.
	 */
	@Test
	public void aDiaryCountsOnlyWhenItIsFinished()
	{
		int[] atLogin = new int[GolemCelebration.DIARY_TIERS.length];
		atLogin[0] = 1;
		celebration.diaries(atLogin, 10);
		celebration.diaries(atLogin, 15);
		celebration.diaries(atLogin, 20);
		assertFalse("already done at login", celebration.isDancing(20));

		int[] later = atLogin.clone();
		later[5] = 1;
		celebration.diaries(later, 25);
		assertTrue(celebration.isDancing(25));
	}

	/** A tier that is set during the settling reads is the login arriving, not a finish. */
	@Test
	public void aDiaryArrivingWithLoginIsNot()
	{
		int[] empty = new int[GolemCelebration.DIARY_TIERS.length];
		int[] synced = empty.clone();
		synced[3] = 1;
		celebration.diaries(empty, 10);
		celebration.diaries(synced, 15);
		celebration.diaries(synced, 20);
		assertFalse(celebration.isDancing(20));
	}
}
