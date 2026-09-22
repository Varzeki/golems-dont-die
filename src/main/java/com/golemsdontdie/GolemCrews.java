package com.golemsdontdie;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/**
 * Golems that leave a dock together sail together.
 *
 * <p>A golem about to cast off alone is held at the quayside for a few seconds first, in case
 * anyone else is coming. Three or more and they take a boat instead of a raft each: the route is
 * chosen after the crew is, so it is one they all suit rather than whichever port the first of them
 * happened to pick, and the crossing itself is shared, so they leave, cross and land as one.
 *
 * <p>Plugin side rather than in the planner, which is handed a golem's memory and never the golem:
 * a crew is the one thing about sailing that cannot be worked out one golem at a time.
 */
@Slf4j
@Singleton
class GolemCrews
{
	/** How long a golem waits at the quayside for company before sailing alone, in ticks. */
	private static final int MUSTER_TICKS = 30;

	/** How near the dock a golem must still be to count as waiting at it. */
	private static final int QUAYSIDE = 4;

	@Inject
	private Voyage voyage;

	@Inject
	private SailingDocks docks;

	/** Crews being made up, by the dock they are waiting at. */
	private final Map<Integer, Muster> mustering = new HashMap<>();

	/** The crew each golem at sea belongs to, for drawing and for putting them ashore. */
	private final Map<Golem, GolemCrew> crews = new IdentityHashMap<>();

	/** Golems already known to be at sea, so a crossing is noticed the tick it begins. */
	private final Map<Golem, Boolean> sailing = new IdentityHashMap<>();

	/** Golems that have just waited and been let go, so they are not held twice over. */
	private final Map<Golem, Integer> released = new IdentityHashMap<>();

	private final Random random = new Random();

	/** A crew being made up at one dock. */
	private static final class Muster
	{
		final SailingDocks.Dock dock;
		final List<Golem> waiting = new ArrayList<>();
		final int since;

		Muster(SailingDocks.Dock dock, int since)
		{
			this.dock = dock;
			this.since = since;
		}
	}

	/**
	 * Looks over every golem once a tick: who has just cast off, who is still waiting, and who has
	 * landed.
	 */
	void update(List<Golem> golems, int tick, RoamContext context)
	{
		for (Golem golem : golems)
		{
			boolean atSea = golem.isSailing(tick);
			Boolean was = sailing.put(golem, atSea);
			if (atSea && !Boolean.TRUE.equals(was) && !golem.isCrewed())
			{
				hold(golem, tick);
			}
			if (!atSea && Boolean.TRUE.equals(was))
			{
				land(golem);
			}
		}
		sail(tick, context);
	}

	/**
	 * Takes a golem that has just begun a crossing and stands it back on the quayside, waiting.
	 * Its own crossing is dropped: the crew's is planned when the crew is made up, and a golem
	 * that waits alone plans a fresh one when it is let go.
	 */
	private void hold(Golem golem, int tick)
	{
		Integer let = released.get(golem);
		if (let != null && tick - let < MUSTER_TICKS)
		{
			// Waited once already and found nobody; it goes now.
			return;
		}

		WorldPoint at = golem.currentTile();
		SailingDocks.Dock dock = voyage == null ? null : voyage.dockAt(at, QUAYSIDE);
		if (dock == null)
		{
			return;
		}

		Muster muster = mustering.computeIfAbsent(dock.getRowId(), id -> new Muster(dock, tick));
		if (muster.waiting.contains(golem))
		{
			return;
		}
		muster.waiting.add(golem);
		golem.waitAshore(tick, MUSTER_TICKS);
		sailing.put(golem, false);
	}

	/** Makes up the crews that are ready, and lets go of the ones nobody joined. */
	private void sail(int tick, RoamContext context)
	{
		for (java.util.Iterator<Map.Entry<Integer, Muster>> it = mustering.entrySet().iterator(); it.hasNext(); )
		{
			Muster muster = it.next().getValue();
			// Anyone who wandered off, died or was carried away is no longer waiting.
			muster.waiting.removeIf(golem -> golem.isDying()
				|| golem.currentTile().distanceTo2D(muster.dock.getShore()) > QUAYSIDE);

			if (muster.waiting.size() >= GolemCrew.LEAST)
			{
				if (cast(muster, tick, context))
				{
					it.remove();
				}
				continue;
			}
			if (tick - muster.since >= MUSTER_TICKS)
			{
				for (Golem golem : muster.waiting)
				{
					released.put(golem, tick);
				}
				it.remove();
			}
		}
	}

	/**
	 * Picks the crew's port, plans the one crossing, and signs everyone on.
	 *
	 * @return false if the crossing could not be planned this tick, so the crew keeps waiting
	 */
	private boolean cast(Muster muster, int tick, RoamContext context)
	{
		List<Golem> crew = muster.waiting.size() <= GolemBoat.SLOOP.getBerths()
			? new ArrayList<>(muster.waiting)
			: new ArrayList<>(muster.waiting.subList(0, GolemBoat.SLOOP.getBerths()));

		List<TransportMemory> memories = new ArrayList<>(crew.size());
		for (Golem golem : crew)
		{
			memories.add(golem.getTransportMemory());
		}

		WorldPoint at = crew.get(0).currentTile();
		Itinerary crossing = voyage.crewCrossing(muster.dock, memories, at, tick, random, context);
		if (crossing == null)
		{
			return false;
		}

		GolemBoat boat = GolemBoat.forCrew(crew.size());
		GolemCrew made = new GolemCrew(boat, crossing);
		for (Golem golem : crew)
		{
			if (!made.sign(golem))
			{
				break;
			}
			golem.boardCrossing(crossing);
			golem.getTransportMemory().beginShoreLeaveOnArrival(tick + crossing.getDuration(), tick, random);
			crews.put(golem, made);
			sailing.put(golem, true);
		}
		log.debug("{} golems crewed a {} from {}", made.size(), boat, muster.dock.getName());
		return true;
	}

	/** Puts a golem ashore, and its crew with it once the last of them has landed. */
	private void land(Golem golem)
	{
		GolemCrew crew = crews.remove(golem);
		if (crew == null)
		{
			return;
		}
		golem.disembark();
		if (crews.values().stream().noneMatch(other -> other == crew))
		{
			crew.payOff();
		}
	}

	/** The crew this golem is sailing with, or null. */
	GolemCrew crewOf(Golem golem)
	{
		return crews.get(golem);
	}

	/** Forgets everything: the golems are being rebuilt. */
	void clear()
	{
		mustering.clear();
		crews.clear();
		sailing.clear();
		released.clear();
	}
}
