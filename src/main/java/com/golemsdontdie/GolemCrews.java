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
			// Aboard the player's ship, it is the player's crew and not one of these.
			if (golem.isAboard())
			{
				continue;
			}
			boolean atSea = golem.isSailing(tick);
			Boolean was = sailing.put(golem, atSea);
			if (atSea && !Boolean.TRUE.equals(was) && !golem.isCrewed())
			{
				hold(golem, tick);
			}
			// Ashore and still signed on: landed this tick, or carried off its boat by a rescue
			// and never noticed. Either way it is done sailing.
			if (!atSea && (Boolean.TRUE.equals(was) || crews.containsKey(golem)))
			{
				land(golem);
			}
			else if (atSea && golem.isCrewed())
			{
				steer(golem);
			}
		}
		sail(tick, context);
		if (sailing.size() > golems.size())
		{
			forget(golems);
		}
	}

	/**
	 * Drops everything remembered about golems that are no longer on the roster. Golems do not
	 * die, but reviving one builds it afresh, and the old object would be held here for ever.
	 */
	private void forget(List<Golem> golems)
	{
		java.util.Set<Golem> alive = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		alive.addAll(golems);
		sailing.keySet().retainAll(alive);
		released.keySet().retainAll(alive);
		crews.keySet().retainAll(alive);
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
		if (dock == null || dock.getShore() == null)
		{
			return;
		}

		Muster muster = mustering.computeIfAbsent(dock.getRowId(), id -> new Muster(dock, tick));
		// Already on the list and sailing again: its wait ran out, it planned afresh and cast off
		// while the crew was still making up. Held again rather than left under way on a list it
		// could be signed off, which would have carried it back to the dock it had left.
		if (!muster.waiting.contains(golem))
		{
			muster.waiting.add(golem);
		}
		// Put back on the quayside rather than left where it stands. Crossings begin between game
		// ticks and this runs on one, so by now the golem is a tile or two out on the water, and
		// standing it there would leave it on the sea until the watchdog fetched it back.
		golem.waitAshore(tick, MUSTER_TICKS, dock.getShore());
		// The crossing it gave up booked shore leave for its whole length; without this the golem
		// could not sail again for as long as the voyage it is not taking would have lasted.
		golem.getTransportMemory().clearShoreLeave();
		sailing.put(golem, false);
	}

	/** Makes up the crews that are ready, and lets go of the ones nobody joined. */
	private void sail(int tick, RoamContext context)
	{
		for (java.util.Iterator<Map.Entry<Integer, Muster>> it = mustering.entrySet().iterator(); it.hasNext(); )
		{
			Muster muster = it.next().getValue();
			// Anyone who wandered off, died or was carried away is no longer waiting.
			muster.waiting.removeIf(golem -> golem.isDying() || golem.isAboard()
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
		// More waiting than the boat has berths: the rest are let go rather than left standing at
		// a quayside whose crew has sailed.
		for (Golem left : muster.waiting)
		{
			if (crews.get(left) != made)
			{
				released.put(left, tick);
			}
		}
		log.debug("{} golems crewed a {} from {}", made.size(), boat, muster.dock.getName());
		return true;
	}

	/**
	 * Keeps a crew facing as one.
	 *
	 * <p>A berth is measured from the boat and turned to wherever the boat is pointing, and the
	 * only heading a golem has is its own. Left to themselves they each turn from whatever way
	 * they happened to face at the dock, at their own rate, so for the first seconds of a crossing
	 * the crew would be strewn around the boat rather than standing on it. The helm's heading is
	 * every member's; which way each one looks from there is {@link Golem#drawOrientation}.
	 */
	private void steer(Golem golem)
	{
		GolemCrew crew = crews.get(golem);
		Golem helm = crew == null ? null : crew.helm();
		if (helm != null && helm != golem)
		{
			golem.faceAs(helm.getOrientation());
		}
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
