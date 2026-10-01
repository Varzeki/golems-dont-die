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
	/**
	 * The smallest crew that is worth a boat: two, as a skiff takes. At three, a pair waiting together
	 * waited out the muster and walked off, and every crew sailed at three, the moment the third came.
	 */
	static final int LEAST = 2;

	@Getter
	private final GolemBoat boat;

	/** The crossing every member is on, and the tick it ends. */
	@Getter
	private final Itinerary crossing;

	private final List<Golem> members = new ArrayList<>();

	/** The tiles members have already stepped off onto, so each lands on one of its own. */
	@Getter
	private final Set<Long> ashore = new HashSet<>();

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
		golem.board(boat.getDeck()[berth][0], boat.getDeck()[berth][1], boat.getHelmPose());
		// One boat, one swell: everyone aboard rides it as the helm's boat does.
		golem.setBoatSeed(members.get(0).getId());
		return true;
	}

	/**
	 * The golem at the helm, which is the one the boat itself is drawn under: the first of the crew
	 * still standing. Fixed as the first, a helm crumbling mid-crossing took the boat with it, and
	 * the rest finished the crossing on open water.
	 */
	Golem helm()
	{
		for (Golem member : members)
		{
			if (!member.isDying())
			{
				return member;
			}
		}
		return null;
	}

	boolean isHelm(Golem golem)
	{
		return golem != null && helm() == golem;
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
		ashore.clear();
	}
}
