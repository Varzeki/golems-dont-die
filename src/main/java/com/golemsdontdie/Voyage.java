package com.golemsdontdie;

import java.util.List;
import java.util.Random;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/**
 * Decides where a golem sails, and plans the crossing.
 *
 * <p>Two rules, both chosen because they need no tuning and cannot be subtly wrong.
 *
 * <p><b>Uniform dispersal.</b> The destination is drawn evenly from every port the golem
 * is allowed to reach, not weighted by distance. Weighting by distance looks sensible and
 * compounds badly: a golem near Catherby keeps drawing Catherby's neighbours, never gets
 * anywhere, and the population puddles around wherever it started. Golems leave to see the
 * world, so they see all of it evenly.
 *
 * <p><b>One blocked port.</b> A golem may not sail straight back to the port it just left.
 * That is a single field of state rather than a timer, so there is no cooldown to balance
 * and no clock to get wrong, and home becomes reachable again from the third hop — which
 * is a tour rather than a commute.
 *
 * <p>The accepted consequence is that Wyrmscraig's standing population thins over a long
 * session. That is the right trade: the island is where golems are <i>made</i>, so it
 * refills with every craft, and the revive button covers anyone who wants them back.
 */
@Slf4j
@Singleton
class Voyage
{
	/**
	 * Ticks a crossing takes per straightened leg tile.
	 *
	 * <p>Slower than walking. A raft should feel like a raft, and the crossing is
	 * replayed from a timestamp rather than stepped, so a long one costs nothing extra.
	 */
	private static final int TICKS_PER_SEA_TILE = 2;

	@Inject
	private SailingDocks docks;

	@Inject
	private SeaMesh sea;

	/**
	 * Plans a crossing from a dock, or returns null if the golem should stay ashore.
	 *
	 * @param from   the dock the golem is leaving
	 * @param memory the golem's own memory, consulted and updated for the blocked port
	 */
	Itinerary depart(SailingDocks.Dock from, TransportMemory memory, int tick, Random random,
		RoamContext context)
	{
		List<SailingDocks.Dock> open = docks.openDocks();
		if (open.size() < 2)
		{
			return null;
		}

		// Candidates: anywhere open, except here and except where we just came from.
		List<SailingDocks.Dock> candidates = new java.util.ArrayList<>(open);
		candidates.removeIf(d -> d.getRowId() == from.getRowId()
			|| d.getRowId() == memory.getBlockedPort());

		if (candidates.isEmpty())
		{
			// Deliberately not relaxed. If the only place left to go is the port we came
			// from, the golem stays and roams locally rather than bouncing between two
			// docks forever — which is exactly what relaxing the bar to break the
			// deadlock would produce.
			return null;
		}

		SailingDocks.Dock to = candidates.get(random.nextInt(candidates.size()));

		// A crossing that starts or ends off the open sea cannot be a route across the
		// ocean mesh, because the two ends are not on the same water. Wyrmscraig's cave
		// dock is the case in point: it sits on the underground map, and you leave it
		// through the mouth of the cave rather than by sailing there from anywhere.
		//
		// That is a passage, not a voyage, and it is expressed as one — two waypoints and
		// a duration. Nobody watches a golem cross water it could not be followed onto.
		if (!from.isOnOpenSea() || !to.isOnOpenSea())
		{
			memory.setBlockedPort(from.getRowId());
			log.debug("Golem taking the passage {} -> {}", from.getName(), to.getName());
			return passage(from, to, tick);
		}

		boolean searched = sea.isSearched(from.getMooring(), to.getMooring());

		if (!searched && (context == null || !context.isMaySearchSea()))
		{
			// This crossing has never been computed and the frame's one sea search is
			// already spent. The golem stays put and rolls again on its next decision,
			// which from outside is simply a golem that did not feel like leaving.
			return null;
		}

		List<int[]> route = sea.route(from.getMooring(), to.getMooring());
		if (!searched && context != null)
		{
			// Spend the budget on the attempt rather than on success. A crossing that
			// turned out to have no route cost just as much to discover as one that did.
			context.spendSeaSearch();
		}
		if (route == null)
		{
			return null;
		}

		memory.setBlockedPort(from.getRowId());
		log.debug("Golem sailing {} -> {} ({} legs)",
			from.getName(), to.getName(), route.size());

		// The crossing ends ashore, not at the mooring. Coming in off the water a golem
		// does not have to reach an exact tile — anywhere within sight of the dock will
		// do, and it steps off there — which is the reverse of boarding, where it has to
		// walk up to the quayside first.
		List<int[]> ashore = new java.util.ArrayList<>(route);
		ashore.add(new int[]{to.getShore().getX(), to.getShore().getY()});

		// Paced per tile of water crossed, not per waypoint. The route is straightened,
		// so its waypoints are corners rather than steps — counting them made a long
		// crossing take the same time as a short one with the same number of turns.
		return Itinerary.of(ashore, to.getShore().getPlane(), tick, TICKS_PER_SEA_TILE,
			0, true);
	}

	/**
	 * A crossing with no drawn route: the golem leaves one dock and arrives at the other.
	 *
	 * <p>Timed by straight-line distance so that a long passage still takes long enough to
	 * be believable, and floored so a short one is not instant.
	 */
	private Itinerary passage(SailingDocks.Dock from, SailingDocks.Dock to, int tick)
	{
		WorldPoint a = from.getMooring();
		WorldPoint b = to.getMooring();

		List<int[]> ends = new java.util.ArrayList<>();
		ends.add(new int[]{a.getX(), a.getY()});
		ends.add(new int[]{to.getShore().getX(), to.getShore().getY()});

		// Underground maps sit thousands of tiles from the surface they lie beneath, so
		// the raw separation between a cave dock and a sea one is meaningless as a
		// distance. Capped rather than used directly.
		int spread = Math.min(400,
			Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()));

		return Itinerary.of(ends, to.getShore().getPlane(), tick, TICKS_PER_SEA_TILE,
			Math.max(50, spread), true);
	}

	/**
	 * The dock a golem standing here could embark from, or null.
	 *
	 * <p>Generous about distance: the mooring is open water beside the dock, and the golem
	 * is on the land next to it, so they are never the same tile.
	 */
	SailingDocks.Dock dockAt(WorldPoint at, int radius)
	{
		for (SailingDocks.Dock dock : docks.getDocks())
		{
			// Measured to the quayside, not the buoy. The buoy is out on the water, so a
			// golem is never near it — matching on it meant golems boarded from wherever
			// they happened to be standing when the radius caught them.
			WorldPoint shore = dock.getShore();
			if (shore.getPlane() == at.getPlane()
				&& Math.abs(shore.getX() - at.getX()) <= radius
				&& Math.abs(shore.getY() - at.getY()) <= radius)
			{
				return dock;
			}
		}
		return null;
	}
}
