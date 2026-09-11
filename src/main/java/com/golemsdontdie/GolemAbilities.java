package com.golemsdontdie;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;

/**
 * What a golem is allowed to do — the player's account, minus an inventory.
 *
 * <p>A golem made by a player who has done Plague City can use the Ardougne shortcuts
 * that quest opens, because the world the golem walks is the world the player unlocked.
 * It is the player's island, the player's game, and a golem finding a door locked that
 * the player walks through freely would read as a bug rather than as a rule.
 *
 * <p>Three deliberate departures from simply mirroring the player:
 *
 * <ul>
 *   <li><b>Real levels, never boosted.</b> A golem cannot drink a potion. A shortcut that
 *       worked only while the player happened to be boosted would strand golems on the
 *       far side of it an hour later, with nothing to explain why.</li>
 *   <li><b>No inventory, but a fixed purse.</b> Coins up to 500,000 and a dramen staff,
 *       granted flatly and never tracked. The most expensive single fare in the game is
 *       10,000, so the check always passes; it exists to say "a golem may take a ferry
 *       but may not chop a canoe" without modelling money.</li>
 *   <li><b>No timers.</b> Conditions of the countdown form are never satisfied.</li>
 * </ul>
 *
 * <p>Evaluated against live client state each time rather than cached. The reads are
 * varbit and skill lookups — cheap — and caching them would mean a stale answer every
 * time the player levels up or flips a lever, which §12 of the plan calls out as the
 * thing most likely to strand a golem mid-journey.
 */
@Slf4j
@Singleton
class GolemAbilities
{
	@Inject
	private Client client;

	private static final Skill[] SKILLS = Skill.values();
	private static final Quest[] QUESTS = Quest.values();

	/**
	 * True if a golem may use this transport right now.
	 *
	 * <p>Ordered cheapest test first. Most of the network is unconditional, so the common
	 * case returns on the first line.
	 */
	boolean canUse(GolemTransport transport)
	{
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
			if (ordinal < 0 || ordinal >= QUESTS.length)
			{
				continue;
			}
			if (QUESTS[ordinal].getState(client) != QuestState.FINISHED)
			{
				return false;
			}
		}
		return true;
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

			if (op == TransportNetwork.OP_AT)
			{
				// A wall-clock countdown — a home teleport cooling down, say. There is no
				// sense in which a golem is waiting for one, so it never passes.
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
