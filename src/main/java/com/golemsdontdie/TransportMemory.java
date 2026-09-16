package com.golemsdontdie;

import lombok.Getter;
import lombok.Setter;

/**
 * What one golem has used recently, so it does not pace back and forth through a door.
 *
 * <p>Per golem, not global — two golems meeting at the same ladder should both be able to
 * take it. That costs a handful of longs each, which at a thousand golems is still nothing
 * next to the models they share.
 *
 * <p>Transport cooldowns are not saved. They exist to stop a golem visibly oscillating over
 * the next minute or two; after a logout there is no oscillation left to interrupt. They are
 * measured in game ticks, so they advance with the simulation.
 *
 * <p>Shore leave is the exception on both counts: it is saved with the golem and measured in
 * real time, because a golem restored at a port it has just landed at should not sail straight
 * back out.
 *
 * <h2>Why endpoints and not row numbers</h2>
 *
 * <p>This used to remember which rows had been used, by index, and bar each row's
 * resolved reverse. Every part of that could come apart without anything looking wrong:
 *
 * <ul>
 *   <li>Two rows can go the same way. The rockslide had a shipped row and a learned route
 *       with identical ends under different object ids; the reverse lookup found one, the
 *       cooldown barred it, and the golem walked straight back through the other. Half of
 *       all uses of that rockslide were a golem immediately undoing the last one.</li>
 *   <li>Learned rows are renumbered whenever what was learned changes.</li>
 * </ul>
 *
 * <p>What a golem must not do is go back the way it came, and that is a statement about
 * tiles. Keyed on the endpoints, a duplicate row, a renumbered row and a row filed under the
 * wrong object all bar each other, because they are the same journey.
 */
final class TransportMemory
{
	/**
	 * How many recent journeys to remember. Small on purpose: this stops a golem shuffling
	 * in a doorway, not a golem revisiting a ladder half an hour later.
	 */
	private static final int HISTORY = 8;

	/** Ticks a used transport, and its reverse, stay barred. Roughly two minutes. */
	static final int COOLDOWN_TICKS = 200;

	/**
	 * Least time ashore before a golem may sail again: three minutes.
	 *
	 * <p>Long enough to walk off the dock and look round a small island, or get well away from
	 * the quayside on the mainland, before it thinks about boats again. Saved with the golem, so
	 * a restart does not let one straight back on.
	 */
	private static final long SHORE_LEAVE_MILLIS = 3 * 60_000L;

	/** Up to this much more, at random, so a boatload of golems landing together does not all leave together. */
	private static final long SHORE_LEAVE_SPREAD_MILLIS = 2 * 60_000L;

	private static final long MILLIS_PER_TICK = 600;

	/**
	 * The clock shore leave is measured on.
	 *
	 * <p>Real time rather than game ticks, because it is saved with the golem and has to mean
	 * the same thing next session: the tick count is the client's, and starts again. The
	 * roaming simulation replaces it with its own simulated time.
	 */
	static java.util.function.LongSupplier clock = System::currentTimeMillis;

	/** Ring of recently travelled endpoint pairs, and when each expires. */
	private final long[] recent = new long[HISTORY];
	private final int[] expiry = new int[HISTORY];
	private int next;

	/**
	 * The port this golem last sailed from, or -1.
	 *
	 * <p>State rather than a timer, and deliberately one field. A golem may not sail
	 * straight back where it came from, but from its next port onward home is reachable
	 * again — so a golem tours rather than commuting, and there is nothing to tune.
	 */
	@Getter
	@Setter
	private int blockedPort = -1;

	/** When this golem may next sail, on {@link #clock}; 0 if it may now. Saved with the golem. */
	@Getter
	@Setter
	private long shoreLeaveUntil;

	/**
	 * Records a use, barring both this journey and the one that undoes it.
	 *
	 * <p>Barring the reverse is the point. Barring only what was just used leaves a golem
	 * free to take the matching row straight back, which is the same oscillation seen from
	 * the other side.
	 */
	void used(GolemTransport transport, int tick)
	{
		used(transport, tick, COOLDOWN_TICKS);
	}

	/**
	 * As {@link #used(GolemTransport, int)}, barred for a given number of ticks.
	 *
	 * <p>Shorter where the far side is small: two minutes is a sensible wait before walking
	 * back through a door into a town, and a very long one to spend in a sheep pen.
	 */
	void used(GolemTransport transport, int tick, int cooldownTicks)
	{
		remember(transport.endpointKey(), tick, cooldownTicks);
		remember(transport.reverseKey(), tick, cooldownTicks);
	}

	private void remember(long key, int tick, int cooldownTicks)
	{
		recent[next] = key;
		expiry[next] = tick + cooldownTicks;
		next = (next + 1) % HISTORY;
	}

	boolean onCooldown(GolemTransport transport, int tick)
	{
		long key = transport.endpointKey();
		for (int i = 0; i < HISTORY; i++)
		{
			// An empty slot has expiry 0, which has always passed.
			if (recent[i] == key && expiry[i] > tick)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Bars sea travel for a while after the golem steps off a boat.
	 *
	 * <p>Takes the tick the golem <em>arrives</em>, not the tick it sets out, because a
	 * crossing is planned up front and the whole voyage happens between the two. Shore
	 * leave then runs from the landing, which is what stops a golem reaching a port and
	 * immediately leaving it again — otherwise no golem is ever seen anywhere but at sea.
	 */
	void beginShoreLeaveOnArrival(int arrivalTick, int nowTick, java.util.Random random)
	{
		long arrival = clock.getAsLong() + Math.max(0, arrivalTick - nowTick) * MILLIS_PER_TICK;
		long spread = random == null ? 0 : (long) (random.nextDouble() * SHORE_LEAVE_SPREAD_MILLIS);
		shoreLeaveUntil = arrival + SHORE_LEAVE_MILLIS + spread;
	}

	boolean onShoreLeave()
	{
		return clock.getAsLong() < shoreLeaveUntil;
	}
}
