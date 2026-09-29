package com.golemsdontdie;

import java.util.*;
import java.util.function.*;
import lombok.*;
import static com.golemsdontdie.RouteGeometry.span;

/**
 * What one golem has used recently, so it does not pace back and forth through a door.
 *
 * <p>Per golem, not global - two golems meeting at the same ladder should both be able to take
 * it. Transport cooldowns are not saved: they stop a golem visibly oscillating over the next
 * minute or two, and after a logout there is none left to interrupt. Shore leave is the exception
 * on both counts, saved and in real time, because a golem restored at a port it has just landed
 * at should not sail straight back out.
 *
 * <p>Endpoints, not row numbers: two rows can go the same way - the rockslide had a shipped row
 * and a learned route with identical ends under different object ids - and learned rows are
 * renumbered whenever what was learned changes. Going back the way it came is about tiles.
 */
final class TransportMemory
{
	/** How many recent journeys to remember. Small: this stops shuffling in a doorway, no more. */
	private static final int HISTORY = 8;

	/** Ticks a used transport, and its reverse, stay barred. Roughly two minutes. */
	static final int COOLDOWN_TICKS = 200;

	/** Least time ashore before a golem may sail again: three minutes, saved with the golem. */
	private static final long SHORE_LEAVE_MILLIS = 3 * 60_000L;

	/** Up to this much more, at random, so a boatload of golems landing together does not all leave together. */
	private static final long SHORE_LEAVE_SPREAD_MILLIS = 2 * 60_000L;

	private static final long MILLIS_PER_TICK = 600;

	/** The clock shore leave is measured on: real time, because it is saved with the golem. */
	static LongSupplier clock = System::currentTimeMillis;

	/** Ring of recently travelled endpoint pairs, and when each expires. */
	private final long[] recent = new long[HISTORY];
	private final int[] expiry = new int[HISTORY];
	private int next;

	/**
	 * Ring of recently used obstacles: {objectId, fromX, fromY, toX, toY, plane}, with expiry.
	 * Endpoints alone were not enough: over a broken cart one way was 2879,2950 to 2876,2952 and
	 * back was 2876,2952 to 2880,2952, so the way back was never barred.
	 */
	private final int[][] usedObstacles = new int[HISTORY][];
	private final int[] usedObstacleExpiry = new int[HISTORY];
	private int nextObstacle;

	/** How near a used obstacle's ends another use of the same object has to start to count as it. */
	private static final int SAME_OBSTACLE_TILES = 2;

	/**
	 * The longest move, in tiles, that counts as crossing one obstacle, and so barred by object and
	 * place. A glider or fairy ring is one object id with a dozen destinations from one spot, and
	 * barring the object stranded golems at a glider platform a thousand times.
	 */
	private static final int OBSTACLE_REACH = 8;

	private static boolean isCrossing(int fromX, int fromY, int toX, int toY)
	{
		return span(toX - fromX, toY - fromY) <= OBSTACLE_REACH;
	}

	/** The port this golem last sailed from, or -1: reachable again from the next, so it tours. */
	@Getter
	@Setter
	private int blockedPort = -1;

	/**
	 * The port this golem has decided to sail to and is waiting at a dock for, or -1. The distance
	 * field for a port not sailed to lately is built in the background, and deciding afresh each
	 * time meant golems walked to the end of the dock and away again.
	 */
	@Getter
	private int pendingPort = -1;

	/** The dock the golem is waiting at for {@link #pendingPort}, or -1. */
	@Getter
	private int pendingFrom = -1;

	/** The last tick it waits there. */
	@Getter
	private int pendingUntil;

	/** How long a golem waits at a dock for a crossing being worked out: about twenty seconds. */
	static final int PENDING_WAIT_TICKS = 32;

	/** Waits at this dock for a crossing to that port, from now if it was not already. */
	void holdPending(int fromDock, int port, int tick)
	{
		if (pendingPort != port || pendingFrom != fromDock)
		{
			pendingPort = port;
			pendingFrom = fromDock;
			pendingUntil = tick + PENDING_WAIT_TICKS;
		}
	}

	void clearPending()
	{
		pendingPort = -1;
		pendingFrom = -1;
	}

	/** When this golem may next sail, on {@link #clock}; 0 if it may now. Saved with the golem. */
	@Getter
	@Setter
	private long shoreLeaveUntil;

	/** Records a use, barring both this journey and the one that undoes it, which is the same. */
	void used(GolemTransport transport, int tick)
	{
		used(transport, tick, COOLDOWN_TICKS);
	}

	/**
	 * What the golem this belongs to is like, as a mask of {@link GolemTrait}.
	 *
	 * <p>Kept here because this is the one piece of a golem the planner is handed: it plans for a
	 * golem it cannot see, and a golem's habits belong to that planning as much as its cooldowns do.
	 */
	@Getter
	@Setter
	private int traits;

	boolean is(GolemTrait trait)
	{
		return trait.in(traits);
	}

	/**
	 * How much this golem likes the look of a shortcut, as a multiplier on the chance of taking it.
	 *
	 * <p>A cautious golem will not risk a jump or a stone; a sure-footed one goes out of its way for
	 * one. A climber prefers anything leading up and a spelunker anything leading down, which is what
	 * sends one to the rooftops and the other into the caves.
	 */
	float tasteFor(GolemTransport transport)
	{
		if (traits == 0)
		{
			return 1f;
		}
		boolean risky = transport.getArchetype() == GolemTransport.ARCHETYPE_JUMP
			|| transport.getArchetype() == GolemTransport.ARCHETYPE_BALANCE
			|| transport.getArchetype() == GolemTransport.ARCHETYPE_TIGHTROPE;
		if (risky && is(GolemTrait.CAUTIOUS))
		{
			return 0f;
		}

		float taste = is(GolemTrait.SURE_FOOTED) ? 1.6f : 1f;
		int rise = transport.getFromPlane() - transport.getToPlane();
		// Down is off the surface map, into a cave or somewhere below it; up is back onto it.
		boolean down = rise > 0
			|| WorldLayout.isSurface(transport.getFromY()) && !WorldLayout.isSurface(transport.getToY());
		boolean up = rise < 0
			|| !WorldLayout.isSurface(transport.getFromY()) && WorldLayout.isSurface(transport.getToY());
		if (down && is(GolemTrait.SPELUNKER) || up && is(GolemTrait.CLIMBER))
		{
			taste *= 2.5f;
		}
		if (up && is(GolemTrait.SPELUNKER) || down && is(GolemTrait.CLIMBER))
		{
			taste *= 0.4f;
		}
		return taste;
	}


	/**
	 * How long a golem walks after using a shortcut before it looks for another. Three minutes.
	 *
	 * <p>Not a cooldown on the shortcut but on the habit: Meiyerditch gave a golem a shortcut in
	 * reach on every decision, and a year of roaming left a third of all golems there. Stepping off
	 * somewhere it cannot walk is exempt.
	 */
	static final int TRANSPORT_REST_TICKS = 300;

	private int restUntil = Integer.MIN_VALUE;

	/** True if the golem has used a shortcut lately and is walking for a while. */
	boolean restingFromTransports(int tick)
	{
		if (is(GolemTrait.RESTLESS))
		{
			// Off again almost as soon as it lands. In longs: before its first shortcut the rest is
			// Integer.MIN_VALUE, and taking half a rest off that wrapped round to the largest int,
			// so a restless golem rested until it had used a shortcut, which it then seldom did.
			return tick < (long) restUntil - TRANSPORT_REST_TICKS / 2;
		}
		return tick < restUntil;
	}

	/**
	 * As {@link #used(GolemTransport, int)}, barred for a given number of ticks. Shorter where the
	 * far side is small: two minutes is sensible before a town, very long in a sheep pen.
	 */
	void used(GolemTransport transport, int tick, int cooldownTicks)
	{
		restUntil = tick + TRANSPORT_REST_TICKS;
		remember(transport.endpointKey(), tick, cooldownTicks);
		remember(transport.reverseKey(), tick, cooldownTicks);
		journeys[nextJourney] = new int[]{transport.getFromX(), transport.getFromY(), transport.getFromPlane(),
			transport.getToX(), transport.getToY(), transport.getToPlane()};
		journeyExpiry[nextJourney] = tick + cooldownTicks;
		nextJourney = (nextJourney + 1) % HISTORY;
		if (transport.getFromPlane() == transport.getToPlane()
			&& isCrossing(transport.getFromX(), transport.getFromY(), transport.getToX(), transport.getToY()))
		{
			usedObstacles[nextObstacle] = new int[]{transport.getObjectId(), transport.getFromX(), transport.getFromY(),
				transport.getToX(), transport.getToY(), transport.getFromPlane()};
			usedObstacleExpiry[nextObstacle] = tick + cooldownTicks;
			nextObstacle = (nextObstacle + 1) % HISTORY;
		}
	}

	/**
	 * Recent journeys by their ends, {fromX, fromY, fromPlane, toX, toY, toPlane}, to recognise a way
	 * back that is not the exact reverse: down into a cave and back up are often two objects whose
	 * ends are a tile apart, and golems went down and up every few seconds.
	 */
	private final int[][] journeys = new int[HISTORY][];
	private final int[] journeyExpiry = new int[HISTORY];
	private int nextJourney;

	/** True if this transport undoes a recent journey: starts where it ended and ends where it started. */
	private boolean undoesRecentJourney(GolemTransport transport, int tick)
	{
		for (int i = 0; i < HISTORY; i++)
		{
			int[] j = journeys[i];
			if (j != null && journeyExpiry[i] > tick
				&& transport.getFromPlane() == j[5] && transport.getToPlane() == j[2]
				&& near(transport.getFromX(), transport.getFromY(), j[3], j[4])
				&& near(transport.getToX(), transport.getToY(), j[0], j[1]))
			{
				return true;
			}
		}
		return false;
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
		// A way back that is not the exact reverse. Short hops on one floor go to the rule below.
		if ((transport.getFromPlane() != transport.getToPlane()
			|| !isCrossing(transport.getFromX(), transport.getFromY(), transport.getToX(), transport.getToY()))
			&& undoesRecentJourney(transport, tick))
		{
			return true;
		}

		// The same obstacle, at or beside either end of a recent use: over the cart and back again.
		for (int i = 0; i < HISTORY; i++)
		{
			int[] used = usedObstacles[i];
			if (used == null || usedObstacleExpiry[i] <= tick || used[0] != transport.getObjectId()
				|| used[5] != transport.getFromPlane() || transport.getToPlane() != transport.getFromPlane()
				|| !isCrossing(transport.getFromX(), transport.getFromY(), transport.getToX(), transport.getToY()))
			{
				continue;
			}
			if (near(transport.getFromX(), transport.getFromY(), used[1], used[2])
				|| near(transport.getFromX(), transport.getFromY(), used[3], used[4]))
			{
				// Not the next stone of a crossing: a hop carrying on the same way is the same obstacle.
				int lastX = used[3] - used[1];
				int lastY = used[4] - used[2];
				int nextX = transport.getToX() - transport.getFromX();
				int nextY = transport.getToY() - transport.getFromY();
				if (lastX * nextX + lastY * nextY > 0)
				{
					continue;
				}
				return true;
			}
		}
		return false;
	}

	private static boolean near(int x, int y, int otherX, int otherY)
	{
		return span(x - otherX, y - otherY) <= SAME_OBSTACLE_TILES;
	}

	/**
	 * Bars sea travel for a while after the golem steps off a boat. Takes the tick the golem
	 * <em>arrives</em>, not the tick it sets out: a crossing is planned up front, so shore leave
	 * would otherwise be spent at sea.
	 */
	void beginShoreLeaveOnArrival(int arrivalTick, int nowTick, Random random)
	{
		long arrival = clock.getAsLong() + Math.max(0, arrivalTick - nowTick) * MILLIS_PER_TICK;
		long spread = random == null ? 0 : (long) (random.nextDouble() * SHORE_LEAVE_SPREAD_MILLIS);
		shoreLeaveUntil = arrival + SHORE_LEAVE_MILLIS + spread;
	}

	/**
	 * Takes back the shore leave a crossing booked. Shore leave is set when a voyage is planned
	 * rather than when it ends, so a golem whose crossing is given up - held at the quayside to
	 * wait for a crew - would sit out the leave for a voyage it never made, and by the time that
	 * ran out the crew would be long gone.
	 */
	void clearShoreLeave()
	{
		shoreLeaveUntil = 0;
	}

	boolean onShoreLeave()
	{
		return clock.getAsLong() < shoreLeaveUntil;
	}
}
