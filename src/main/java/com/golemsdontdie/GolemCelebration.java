package com.golemsdontdie;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;

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

	/** A game message, which might be a collection log entry. */
	void chatMessage(String message, int tick)
	{
		if (message != null && config.danceOnCollectionLog()
			&& message.toLowerCase().contains(COLLECTION_LOG))
		{
			log.debug("Golems celebrating a collection log entry");
			begin(tick);
		}
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

	/** Forgotten on login and on a world hop: the tick counter starts again on the other side. */
	void reset()
	{
		until = 0;
		golems = -1;
		java.util.Arrays.fill(levels, 0);
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
