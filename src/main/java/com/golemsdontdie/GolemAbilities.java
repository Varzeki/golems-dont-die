package com.golemsdontdie;

import java.util.*;
import javax.inject.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;

/**
 * What a golem is allowed to do - the player's account, minus an inventory.
 *
 * <p>A golem made by a player who has done Plague City can use the Ardougne shortcuts that
 * quest opens: a door locked to a golem that the player walks through freely would read as
 * a bug. Three deliberate departures:
 *
 * <ul>
 *   <li><b>Real levels, never boosted.</b> A golem cannot drink a potion, and a shortcut
 *       open only while the player was boosted would strand golems an hour later.</li>
 *   <li><b>No inventory, but a fixed purse.</b> Coins up to 500,000 and a dramen staff,
 *       never tracked. The dearest fare is 10,000, so the check always passes; it says a
 *       golem may take a ferry but not chop a canoe.</li>
 *   <li><b>No timers.</b> Conditions of the countdown form are never satisfied.</li>
 * </ul>
 *
 * <p>Read live each time: the lookups are cheap, and a cached answer would go stale
 * whenever the player levels up or flips a lever - §12 of the plan calls that the likeliest
 * way to strand a golem mid-journey.
 */
@Slf4j
@Singleton
class GolemAbilities
{
	@Inject
	private Client client;

	@Inject
	private ObstacleKnowledge knowledge;

	private static final Skill[] SKILLS = Skill.values();
	private static final Quest[] QUESTS = Quest.values();

	/**
	 * True if a golem may use this transport right now. Cheapest test first: most of the
	 * network is unconditional, so the common case returns early.
	 */
	boolean canUse(GolemTransport transport)
	{
		// Not whether the golem is permitted through, but whether the plugin knows what
		// using it looks like. The shipped animation for most obstacles is a guess from
		// menu text that measuring has overturned every time, so a golem routes around
		// anything unproven.
		if (!knowledge.isUnlocked(transport))
		{
			return false;
		}

		if (transport.isUnconditional())
		{
			return true;
		}
		return hasSkills(transport.skills())
			&& hasQuests(transport.quests())
			&& conditionsMet(transport.varbits(), true)
			&& conditionsMet(transport.varps(), false);
	}

	private boolean hasSkills(int[] requirements)
	{
		for (int i = 0; i + 1 < requirements.length; i += 2)
		{
			int level = requirements[i];
			int ordinal = requirements[i + 1];
			if (ordinal < 0 || ordinal >= SKILLS.length)
			{
				continue;
			}
			if (client.getRealSkillLevel(SKILLS[ordinal]) < level)
			{
				return false;
			}
		}
		return true;
	}

	private boolean hasQuests(int[] requirements)
	{
		for (int ordinal : requirements)
		{
			if (ordinal == TransportNetwork.QUEST_UNKNOWN)
			{
				// A quest the plugin cannot name cannot be checked, so it is not done.
				return false;
			}
			if (ordinal < 0 || ordinal >= QUESTS.length)
			{
				continue;
			}
			if (!questFinished(ordinal))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * How long a quest's state is trusted before it is asked again, as SailingDocks does.
	 * Asking runs a client script, once per candidate transport per tile a golem entered.
	 * A quest opening its shortcut a few minutes late goes unnoticed.
	 */
	private static final long QUEST_RECHECK_MILLIS = 5 * 60 * 1000L;

	/** Per quest ordinal: when it was last asked, and whether it was finished. */
	private final long[] questAskedAt = new long[QUESTS.length];
	private final boolean[] questDone = new boolean[QUESTS.length];

	private boolean questFinished(int ordinal)
	{
		long now = System.currentTimeMillis();
		if (questAskedAt[ordinal] == 0 || now - questAskedAt[ordinal] >= QUEST_RECHECK_MILLIS)
		{
			questDone[ordinal] = QUESTS[ordinal].getState(client) == QuestState.FINISHED;
			questAskedAt[ordinal] = now;
		}
		return questDone[ordinal];
	}

	/** Forgets every quest state, for a login that may be another account. */
	void forgetQuests()
	{
		Arrays.fill(questAskedAt, 0);
	}

	/**
	 * Evaluates varbit or varp conditions, stored as triples of (id, operator, value).
	 *
	 * @param varbit true to read varbits, false for varplayers
	 */
	private boolean conditionsMet(int[] conditions, boolean varbit)
	{
		for (int i = 0; i + 2 < conditions.length; i += 3)
		{
			int id = conditions[i];
			int op = conditions[i + 1];
			int wanted = conditions[i + 2];

			if (op == TransportNetwork.OP_UNKNOWN)
			{
				// A requirement that could not be read is not a requirement met.
				return false;
			}
			if (op == TransportNetwork.OP_AT)
			{
				// A wall-clock countdown, a home teleport cooling down say: a golem is
				// never waiting for one, so it never passes.
				return false;
			}

			int actual = varbit ? client.getVarbitValue(id) : client.getVarpValue(id);
			switch (op)
			{
				case TransportNetwork.OP_EQ:
					if (actual != wanted)
					{
						return false;
					}
					break;
				case TransportNetwork.OP_GT:
					if (actual <= wanted)
					{
						return false;
					}
					break;
				case TransportNetwork.OP_LT:
					if (actual >= wanted)
					{
						return false;
					}
					break;
				case TransportNetwork.OP_AND:
					if ((actual & wanted) == 0)
					{
						return false;
					}
					break;
				default:
					return false;
			}
		}
		return true;
	}
}
