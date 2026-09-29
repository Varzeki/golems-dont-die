package com.golemsdontdie;

import java.util.*;
import lombok.*;

/**
 * Golems crossing together on one boat.
 *
 * <p>They share a single crossing rather than each sailing its own, so they leave together, move as
 * one and land at the same moment; the berths they take keep them from standing in a heap, since by
 * the route's reckoning every one of them is at the same place. One is at the helm and the rest
 * stand along the deck.
 */
class GolemCrew
{
	/** The smallest crew that is worth a boat. Fewer than this and each golem takes its raft. */
	static final int LEAST = 3;

	@Getter
	private final GolemBoat boat;

	/** The crossing every member is on, and the tick it ends. */
	@Getter
	private final Itinerary crossing;

	private final List<Golem> members = new ArrayList<>();

	GolemCrew(GolemBoat boat, Itinerary crossing)
	{
		this.boat = boat;
		this.crossing = crossing;
	}

	/**
	 * Signs a golem on and gives it a berth, the helm first. Returns false if the boat is full.
	 */
	boolean sign(Golem golem)
	{
		int berth = members.size();
		if (berth >= boat.getBerths())
		{
			return false;
		}
		members.add(golem);
		golem.board(boat.getDeck()[berth][0], boat.getDeck()[berth][1]);
		return true;
	}

	/** The golem at the helm, which is the one the boat itself is drawn under. */
	Golem helm()
	{
		return members.isEmpty() ? null : members.get(0);
	}

	boolean isHelm(Golem golem)
	{
		return !members.isEmpty() && members.get(0) == golem;
	}

	int size()
	{
		return members.size();
	}

	/** Puts everyone ashore: berths given up, and the crew is done with. */
	void payOff()
	{
		for (Golem golem : members)
		{
			golem.disembark();
		}
		members.clear();
	}
}
