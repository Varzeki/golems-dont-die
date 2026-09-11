package com.golemsdontdie;

import lombok.Getter;
import lombok.Setter;

/**
 * What one golem has used recently, so it does not pace back and forth through a door.
 *
 * <p>Per golem, not global — two golems meeting at the same ladder should both be able to
 * take it. That costs a handful of ints each, which at a thousand golems is still nothing
 * next to the models they share.
 *
 * <p>Nothing here is saved. A cooldown exists to stop a golem visibly oscillating over
 * the next minute or two; after a logout there is no oscillation left to interrupt, and
 * persisting it would mean widening the save format for state with no consequence.
 *
 * <p>Time is measured in game ticks rather than wall clock so that it advances with the
 * simulation and not with a golem the player is nowhere near.
 */
final class TransportMemory
{
	/**
	 * How many recent transports to remember. Small on purpose: this stops a golem
	 * shuffling in a doorway, not a golem revisiting a ladder half an hour later.
	 */
	private static final int HISTORY = 8;

	/** Ticks a used transport, and its reverse, stay barred. Roughly two minutes. */
	private static final int COOLDOWN_TICKS = 200;

	/**
	 * Ticks ashore before a golem may sail again — about six minutes.
	 *
	 * <p>Long enough that a golem lands, walks inland and is plausibly somewhere else by
	 * the time it thinks about boats again; short enough that it does not spend an hour
	 * pacing one port. Nothing depends on the exact value.
	 */
	private static final int SHORE_LEAVE_TICKS = 600;

	/** Ring of recently used transport indexes, and when each expires. */
	private final int[] recent = new int[HISTORY];
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

	private int shoreLeaveUntil;

	TransportMemory()
	{
		for (int i = 0; i < HISTORY; i++)
		{
			recent[i] = -1;
		}
	}

	/**
	 * Records a use, barring both this transport and the one that undoes it.
	 *
	 * <p>Barring the reverse is the point. Barring only what was just used leaves a golem
	 * free to take the matching row straight back, which is the same oscillation seen from
	 * the other side.
	 */
	void used(int index, GolemTransport transport, int tick)
	{
		remember(index, tick);
		if (transport != null && transport.getReverse() >= 0)
		{
			remember(transport.getReverse(), tick);
		}
	}

	private void remember(int index, int tick)
	{
		recent[next] = index;
		expiry[next] = tick + COOLDOWN_TICKS;
		next = (next + 1) % HISTORY;
	}

	boolean onCooldown(int index, int tick)
	{
		for (int i = 0; i < HISTORY; i++)
		{
			if (recent[i] == index && expiry[i] > tick)
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
	void beginShoreLeaveOnArrival(int arrivalTick)
	{
		shoreLeaveUntil = arrivalTick + SHORE_LEAVE_TICKS;
	}

	boolean onShoreLeave(int tick)
	{
		return tick < shoreLeaveUntil;
	}
}
