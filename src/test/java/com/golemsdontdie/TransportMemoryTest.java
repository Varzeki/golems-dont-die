package com.golemsdontdie;

import org.junit.Test;
import static org.junit.Assert.assertFalse;

/** A golem's habit with shortcuts, before it has used any. */
public class TransportMemoryTest
{
	/** Neither a plain golem nor a restless one is resting from a shortcut it never took. */
	@Test
	public void nobodyRestsBeforeTheirFirstShortcut()
	{
		TransportMemory plain = new TransportMemory();
		assertFalse(plain.restingFromTransports(0));
		assertFalse(plain.restingFromTransports(5000));

		TransportMemory restless = new TransportMemory();
		restless.setTraits(GolemTrait.RESTLESS.mask());
		assertFalse("a restless golem rested until it had used a shortcut", restless.restingFromTransports(0));
		assertFalse(restless.restingFromTransports(5000));
	}
}
