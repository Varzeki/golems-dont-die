package com.golemsdontdie;

import java.util.*;
import java.util.regex.*;
import javax.inject.*;
import javax.inject.Inject;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.gameval.*;
import net.runelite.client.util.*;

/**
 * What the golems think is worth celebrating, and for how long.
 *
 * <p>Kept apart from the plugin because it is a set of small judgements rather than plumbing: a
 * level that went up is not a level first heard about at login, a collection log line is a
 * particular sentence, and a golem count that changed is only worth dancing about when it went up.
 * The plugin hands this the events and asks it, once a frame, whether the golems are dancing.
 *
 * <p>Client thread throughout.
 */
@Slf4j
@Singleton
class GolemCelebration
{
	/** How long the golems dance for, in game ticks. About ten seconds. */
	private static final int DANCE_TICKS = 17;

	/** The line the game prints when something is added to the collection log. */
	private static final String COLLECTION_LOG = "new item added to your collection log";

	/*
	 * The rest of the game's lines worth dancing about, worded as the game words them. Taken from
	 * RuneLite's own Screenshot and Chat Commands plugins, which recognise the same moments.
	 */

	/** A clue scroll finished: "You have completed 12 medium Treasure Trails." */
	private static final String CLUE_DONE = "You have completed";
	private static final String CLUE_TRAILS = "Treasure";

	/** A combat achievement, in chat: "Congratulations, you've completed an easy combat task: …". */
	private static final Pattern COMBAT_TASK =
		Pattern.compile("Congratulations, you've completed an? \\w+ combat task");

	/** Every way the game tells a player a pet has found them, in their backpack or not. */
	private static final String[] PET = {
		"You have a funny feeling like you're being followed",
		"You feel something weird sneaking into your backpack",
		"You have a funny feeling like you would have been followed",
	};

	/** Any timed kill or lap that beat the last: every such line ends the same way. */
	private static final String PERSONAL_BEST = "(new personal best)";

	/** The game's pop-up titles for the same moments, for a player who has the chat lines off. */
	private static final String POPUP_COLLECTION_LOG = "Collection log";
	private static final String POPUP_COMBAT_TASK = "Combat Task Completed!";

	/**
	 * Each achievement diary tier's own "complete" flag, as the game names them. Watched rather
	 * than read out of chat: the flag is exact, and the wording of the chat line is not settled.
	 */
	static final int[] DIARY_TIERS = {
		VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE,
		VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE,
		VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE,
		VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_DIARY_ELITE_COMPLETE,
		VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE,
		VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_DIARY_ELITE_COMPLETE,
		VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE,
		VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE,
		VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE,
		VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE,
		VarbitID.ATJUN_EASY_DONE, VarbitID.ATJUN_MED_DONE,
		VarbitID.ATJUN_HARD_DONE, VarbitID.KARAMJA_DIARY_ELITE_COMPLETE,
		VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE,
		VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_DIARY_ELITE_COMPLETE,
		VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE,
		VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE,
		VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE,
		VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE,
		VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE,
		VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_DIARY_ELITE_COMPLETE,
		VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE,
		VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_DIARY_ELITE_COMPLETE,
		VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE,
		VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE,
	};

	/**
	 * Diary reads to take as the starting point before any is compared. A tier never finished
	 * sends nothing at login, so its first change is the real one and cannot be told from the
	 * login's own by watching changes; the flags are read instead, and the first couple of reads
	 * after login are only where the player already stood.
	 */
	private static final int DIARY_SETTLING_READS = 2;

	/** The diary flags as last read, in the order of {@link #DIARY_TIERS}; null before the first. */
	private int[] diaries;
	private int diaryReads;

	@Inject
	private GolemsDontDieConfig config;

	/** The tick the dancing ends at; 0 when nothing is being celebrated. */
	private int until;

	/**
	 * The last level seen in each skill, or 0 for one not seen yet.
	 *
	 * <p>Every skill reports its level at login, and again on every experience drop. Only a level
	 * that has been seen before and has gone up is a level-up; the first sighting is the golems
	 * being told what the player already was.
	 */
	private final int[] levels = new int[Skill.values().length];

	/** The last golem count read from the varbit, or -1 before the first. */
	private int golems = -1;

	/** A level went up. */
	void statChanged(Skill skill, int level, int tick)
	{
		int at = skill.ordinal();
		if (at < 0 || at >= levels.length)
		{
			return;
		}
		int was = levels[at];
		levels[at] = level;
		if (was > 0 && level > was && config.danceOnLevelUp())
		{
			log.debug("Golems celebrating {} level {}", skill, level);
			begin(tick);
		}
	}

	/** A game message, which might be any of the things worth dancing about. */
	void chatMessage(String message, int tick)
	{
		if (message == null)
		{
			return;
		}
		String plain = Text.removeTags(message);
		if (config.danceOnCollectionLog() && plain.toLowerCase().contains(COLLECTION_LOG))
		{
			celebrate("a collection log entry", tick);
		}
		else if (config.danceOnClue() && plain.contains(CLUE_DONE) && plain.contains(CLUE_TRAILS))
		{
			celebrate("a clue", tick);
		}
		else if (config.danceOnCombatTask() && COMBAT_TASK.matcher(plain).find())
		{
			celebrate("a combat task", tick);
		}
		else if (config.danceOnPersonalBest() && plain.contains(PERSONAL_BEST))
		{
			celebrate("a personal best", tick);
		}
		else if (config.danceOnPet())
		{
			for (String pet : PET)
			{
				if (plain.contains(pet))
				{
					celebrate("a pet", tick);
					return;
				}
			}
		}
	}

	/**
	 * One of the game's pop-up notifications, by its title. The same moments as the chat lines,
	 * for a player who turned those off: the pop-up comes either way.
	 */
	void popup(String title, int tick)
	{
		if (title == null)
		{
			return;
		}
		if (config.danceOnCollectionLog() && title.equalsIgnoreCase(POPUP_COLLECTION_LOG))
		{
			celebrate("a collection log entry", tick);
		}
		else if (config.danceOnCombatTask() && title.equalsIgnoreCase(POPUP_COMBAT_TASK))
		{
			celebrate("a combat task", tick);
		}
	}

	/** The quest reward scroll opened: a quest or a miniquest is done. */
	void questCompleted(int tick)
	{
		if (config.danceOnQuest())
		{
			celebrate("a quest", tick);
		}
	}

	/**
	 * The diary flags as they stand, in the order of {@link #DIARY_TIERS}. Any tier that has gone
	 * from not done to done since the last read is a diary finished.
	 */
	void diaries(int[] done, int tick)
	{
		if (diaries == null || diaries.length != done.length || diaryReads < DIARY_SETTLING_READS)
		{
			diaries = done.clone();
			diaryReads++;
			return;
		}
		boolean finished = false;
		for (int i = 0; i < done.length; i++)
		{
			finished |= diaries[i] == 0 && done[i] > 0;
		}
		diaries = done.clone();
		if (finished && config.danceOnDiary())
		{
			celebrate("an achievement diary", tick);
		}
	}

	private void celebrate(String what, int tick)
	{
		log.debug("Golems celebrating {}", what);
		begin(tick);
	}

	/**
	 * The number of golems ever crafted, from the varbit rather than the chat line: the count is
	 * what the game itself keeps, and it is right whether or not the player has the message
	 * filtered, is standing in a busy chat, or crafted the golem while the plugin was off.
	 */
	void golemCount(int count, int tick)
	{
		int was = golems;
		golems = count;
		if (was >= 0 && count > was && config.danceOnGolemCrafted())
		{
			log.debug("Golems celebrating golem {}", count);
			begin(tick);
		}
	}

	/** Whether the golems are dancing at this tick. */
	boolean isDancing(int tick)
	{
		return until > 0 && tick < until;
	}

	/** How long the golems have been at it, in ticks, so the fireworks can open loudly. */
	int startedAt(int tick)
	{
		return until <= 0 ? Integer.MAX_VALUE : tick - (until - DANCE_TICKS);
	}

	/** Forgotten on login and on a world hop: the tick counter starts again on the other side. */
	void reset()
	{
		until = 0;
		golems = -1;
		Arrays.fill(levels, 0);
		diaries = null;
		diaryReads = 0;
	}

	/**
	 * Starts the golems dancing, or keeps them at it: two levels at once is one celebration, not
	 * a dance cut short by the second.
	 */
	private void begin(int tick)
	{
		until = Math.max(until, tick + DANCE_TICKS);
	}
}
