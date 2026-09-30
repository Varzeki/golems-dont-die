package com.golemsdontdie;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** What golems may use, and what watching a player teaches them. */
public class ObstacleKnowledgeTest
{
	private static final int DOOR = 28457;
	private static final int PIPE = 23140;
	private static final int[] SQUEEZE = {749, 748};

	private static GolemTransport row(int archetype, int object, int fromX, int fromY, int toX, int toY)
	{
		GolemTransport row = new GolemTransport(fromX, fromY, 0, toX, toY, 0, 1,
			archetype, object, new int[0], new int[0], new int[0], new int[0]);
		row.setLearnFirst(true);
		return row;
	}

	private static ObstacleSighting seen(int object, int[] clips, int ticks, int fromX, int fromY, int toX, int toY)
	{
		return new ObstacleSighting(object, "Obstacle", "Squeeze-through", clips, ticks, fromX, fromY, 0, toX, toY, 0,
			false, 0, 0, null, toX - fromX, toY - fromY);
	}

	/**
	 * A row learned first opens once a player has been seen going its own way, and not because the
	 * same object was used somewhere else: two pipes of one kind are two different guesses.
	 */
	@Test
	public void aLearnedRowOpensOnlyWhereItWasSeen()
	{
		ObstacleKnowledge knowledge = new ObstacleKnowledge();
		GolemTransport here = row(GolemTransport.ARCHETYPE_SQUEEZE, PIPE, 1622, 3808, 1626, 3808);
		GolemTransport elsewhere = row(GolemTransport.ARCHETYPE_SQUEEZE, PIPE, 1700, 3900, 1704, 3900);
		assertFalse(knowledge.isUnlocked(here));

		for (int i = 0; i < 3; i++)
		{
			knowledge.record(seen(PIPE, SQUEEZE, 4, 1622, 3808, 1626, 3808));
		}
		assertTrue("seen squeezed through, still shut", knowledge.isUnlocked(here));
		assertFalse("a pipe of the same kind elsewhere opened too", knowledge.isUnlocked(elsewhere));
	}

	/**
	 * A door is the golems' own: usable without being seen, learned first or not, and pushed through
	 * whatever a player was seen doing with it.
	 */
	@Test
	public void aDoorIsKnownWithoutLearning()
	{
		ObstacleKnowledge knowledge = new ObstacleKnowledge();
		GolemTransport library = row(GolemTransport.ARCHETYPE_DOOR, DOOR, 1622, 3808, 1623, 3808);
		assertTrue("a door waited to be learned", knowledge.isUnlocked(library));
		for (int i = 0; i < 3; i++)
		{
			knowledge.record(seen(DOOR, new int[]{832}, 2, 1622, 3808, 1623, 3808));
		}
		assertArrayEquals(new int[]{GolemContent.ANIM_DOOR_THROUGH}, knowledge.clipsFor(library));
		assertEquals("a player's timing reached a door", 0, knowledge.ticksFor(library));
	}

	/** Once unlocked, a sighting unlike the others is the odd one out, and locks nothing again. */
	@Test
	public void oneOddSightingLocksNothing()
	{
		ObstacleKnowledge knowledge = new ObstacleKnowledge();
		GolemTransport here = row(GolemTransport.ARCHETYPE_SQUEEZE, PIPE, 1622, 3808, 1626, 3808);
		for (int i = 0; i < 2; i++)
		{
			knowledge.record(seen(PIPE, SQUEEZE, 4, 1622, 3808, 1626, 3808));
		}
		assertTrue(knowledge.isUnlocked(here));
		// A player hit between the click and the pipe: something else played.
		knowledge.record(seen(PIPE, new int[]{424}, 1, 1622, 3808, 1626, 3808));
		assertTrue("one bad sighting locked it again", knowledge.isUnlocked(here));
		assertArrayEquals(SQUEEZE, knowledge.clipsFor(here));
	}

	/** Well learned is set in stone: later sightings change nothing about how it is done. */
	@Test
	public void aWellLearnedObstacleIsSetInStone()
	{
		ObstacleKnowledge knowledge = new ObstacleKnowledge();
		GolemTransport here = row(GolemTransport.ARCHETYPE_SQUEEZE, PIPE, 1622, 3808, 1626, 3808);
		for (int i = 0; i < ObstacleKnowledge.SIGHTINGS_TO_SETTLE; i++)
		{
			knowledge.record(seen(PIPE, SQUEEZE, 4, 1622, 3808, 1626, 3808));
		}
		assertEquals(4, knowledge.ticksFor(here));
		for (int i = 0; i < 5; i++)
		{
			knowledge.record(seen(PIPE, SQUEEZE, 12, 1622, 3808, 1626, 3808));
		}
		assertEquals("a settled obstacle's timing moved", 4, knowledge.ticksFor(here));
	}
}
