package com.golemsdontdie;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The shipped transport table, as the plugin reads it. */
public class TransportNetworkTest
{
	/**
	 * Rows the tables infer rather than calculate arrive locked, and stay locked until golems have
	 * seen one used; the calculated rows beside them arrive ready. The pillars in the Ruins of
	 * Mokhaiotl are a long jump nobody has measured, so they wait; the lift into the crypt is a known
	 * pair of objects.
	 */
	@Test
	public void inferredRowsWaitToBeSeen()
	{
		TransportNetwork network = new TransportNetwork();
		network.load();
		ObstacleKnowledge knowledge = new ObstacleKnowledge();
		GolemTransport pad = null;
		GolemTransport lift = null;
		for (GolemTransport t : network.all())
		{
			if (t.getObjectId() == 56609 && pad == null)
			{
				pad = t;
			}
			if (t.getObjectId() == 56650)
			{
				lift = t;
			}
		}
		assertTrue("no pillar in the table", pad != null);
		assertTrue("no lift in the table", lift != null);
		assertTrue(pad.isLearnFirst());
		assertFalse("an inferred jump was usable before anyone was seen on it", knowledge.isUnlocked(pad));
		assertFalse(lift.isLearnFirst());
		assertTrue(knowledge.isUnlocked(lift));
	}
}
