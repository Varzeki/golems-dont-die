package com.golemsdontdie;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** What golems may use, and what watching a player teaches them. */
public class ObstacleKnowledgeTest
{
	private static final int DOOR = 28457;

	private static GolemTransport doorRow(int fromX, int fromY, int toX, int toY)
	{
		GolemTransport row = new GolemTransport(fromX, fromY, 0, toX, toY, 0, 1,
			GolemTransport.ARCHETYPE_DOOR, DOOR, new int[0], new int[0], new int[0], new int[0]);
		row.setLearnFirst(true);
		return row;
	}

	private static ObstacleSighting walked(int fromX, int fromY, int toX, int toY)
	{
		return new ObstacleSighting(DOOR, "Door", "Walk-through", new int[0], 1, fromX, fromY, 0, toX, toY, 0,
			false, 0, 0, null, toX - fromX, toY - fromY);
	}

	/**
	 * A row learned first opens once a player has been seen going its own way, and not because the
	 * same object was used somewhere else: two doors of one kind are two different guesses.
	 */
	@Test
	public void aLearnedRowOpensOnlyWhereItWasSeen()
	{
		ObstacleKnowledge knowledge = new ObstacleKnowledge();
		GolemTransport library = doorRow(1622, 3808, 1623, 3808);
		GolemTransport elsewhere = doorRow(1700, 3900, 1701, 3900);
		assertFalse(knowledge.isUnlocked(library));

		for (int i = 0; i < 3; i++)
		{
			knowledge.record(walked(1622, 3808, 1623, 3808));
		}
		assertTrue("seen walked through, still shut", knowledge.isUnlocked(library));
		assertFalse("a door of the same kind elsewhere opened too", knowledge.isUnlocked(elsewhere));
	}

	/** A door is pushed through the golem's own way, whatever the player was seen doing. */
	@Test
	public void aDoorKeepsItsOwnClip()
	{
		ObstacleKnowledge knowledge = new ObstacleKnowledge();
		GolemTransport library = doorRow(1622, 3808, 1623, 3808);
		for (int i = 0; i < 3; i++)
		{
			knowledge.record(walked(1622, 3808, 1623, 3808));
		}
		int[] clips = knowledge.clipsFor(library);
		assertTrue(clips.length == 1 && clips[0] == GolemContent.ANIM_DOOR_THROUGH);
	}
}
