package com.golemsdontdie;

import java.util.*;
import javax.inject.*;
import lombok.extern.slf4j.*;
import net.runelite.api.coords.*;

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
	private RoamPlanner planner;

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

		/**
		 * The port chosen for the crew, or null, kept while its crossing is worked out: chosen afresh
		 * every tick, a crew asked for a new sea field each time and sailed only if one happened to
		 * be built already. Chosen again whenever someone joins, for them too.
		 */
		SailingDocks.Dock port;

		/** The last tick the crew waits on that port's crossing, as a lone golem waits on its own. */
		int portUntil;

		/** When each golem in view next moves about the quay while it waits. */
		final Map<Golem, Integer> nextMove = new IdentityHashMap<>();

		/** Golems that have just come to wait, to be waved at. */
		final Map<Golem, Newcomer> newcomers = new IdentityHashMap<>();

		Muster(SailingDocks.Dock dock, int since)
		{
			this.dock = dock;
			this.since = since;
		}

		/** Signs a golem onto the wait, as a newcomer to be waved at. */
		void join(Golem golem, int tick)
		{
			if (!waiting.contains(golem))
			{
				waiting.add(golem);
				newcomers.put(golem, new Newcomer(tick));
				port = null;
			}
		}
	}

	/** A golem that has just come to wait at a quay: when it came, and who waved first. */
	private static final class Newcomer
	{
		final int came;
		Golem wavedBy;
		int wavedOn;

		Newcomer(int came)
		{
			this.came = came;
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
		Set<Golem> alive = Collections.newSetFromMap(new IdentityHashMap<>());
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
		if (let != null && tick >= let && tick - let < MUSTER_TICKS)
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
		muster.join(golem, tick);
		// Put back on the quayside rather than left where it stands. Crossings begin between game
		// ticks and this runs on one, so by now the golem is a tile or two out on the water, and
		// standing it there would leave it on the sea until the watchdog fetched it back.
		golem.waitAshore(tick, MUSTER_TICKS, placeFor(golem, muster));
		// The crossing it gave up booked shore leave for its whole length; without this the golem
		// could not sail again for as long as the voyage it is not taking would have lasted.
		golem.getTransportMemory().clearShoreLeave();
		sailing.put(golem, false);
	}

	/**
	 * A golem about to cast off alone, asked first: kept at the quayside to wait for company
	 * instead, unless it has only just waited here and nobody came. Asked before it sails rather
	 * than caught after, when a golem in view was already in its raft and was pulled out of it.
	 * {@link #hold} still catches a crossing begun some way this does not see.
	 *
	 * @return true if the golem is waiting now, its own crossing given up
	 */
	boolean offer(Golem golem, RoamContext context)
	{
		int tick = context.getTick();
		Integer let = released.get(golem);
		if (golem.isAboard() || let != null && tick >= let && tick - let < MUSTER_TICKS)
		{
			return false;
		}
		SailingDocks.Dock dock = voyage == null ? null : voyage.dockAt(golem.currentTile(), QUAYSIDE);
		if (dock == null || dock.getShore() == null)
		{
			return false;
		}
		Muster muster = mustering.computeIfAbsent(dock.getRowId(), id -> new Muster(dock, tick));
		muster.join(golem, tick);
		golem.waitAshore(tick, MUSTER_TICKS, placeFor(golem, muster), context);
		// Planning the crossing booked shore leave for its whole length.
		golem.getTransportMemory().clearShoreLeave();
		sailing.put(golem, false);
		return true;
	}

	/**
	 * Where on the quayside a golem waits: a tile of its own, near the dock's. The whole crew stood
	 * on the one quayside tile, one inside another.
	 */
	private WorldPoint placeFor(Golem golem, Muster muster)
	{
		Set<Long> taken = new HashSet<>();
		for (Golem other : muster.waiting)
		{
			if (other != golem)
			{
				WorldPoint there = other.currentTile();
				taken.add(RoamContext.tileKey(there.getX(), there.getY(), there.getPlane()));
			}
		}
		return planner.freeTileNear(muster.dock.getShore(), taken);
	}

	/** Makes up the crews that are ready, and lets go of the ones nobody joined. */
	private void sail(int tick, RoamContext context)
	{
		for (Iterator<Map.Entry<Integer, Muster>> it = mustering.entrySet().iterator(); it.hasNext(); )
		{
			Muster muster = it.next().getValue();
			// Anyone who wandered off, died or was carried away is no longer waiting.
			muster.waiting.removeIf(golem -> golem.isDying() || golem.isAboard()
				|| golem.currentTile().distanceTo2D(muster.dock.getShore()) > QUAYSIDE);

			if (muster.waiting.size() >= GolemCrew.LEAST && cast(muster, tick, context))
			{
				it.remove();
				continue;
			}
			// However many are waiting: a crew no crossing can be planned for waited the same as one
			// nobody joined, and then went its separate ways. Left to wait until one could be, it
			// never could, and they stood at the quay for good.
			if (tick - muster.since >= MUSTER_TICKS || tick < muster.since)
			{
				release(muster.waiting, tick);
				it.remove();
				continue;
			}
			mill(muster, tick, context);
		}
	}

	/** Ticks between one move about the quay and a waiting golem's next: three to seven seconds. */
	private static final int MOVE_MIN_TICKS = 5;
	private static final int MOVE_SPREAD_TICKS = 7;

	/** The chance a move is a look out at the water rather than a few steps. */
	private static final float LOOK_CHANCE = 0.4f;

	/** How long after a golem comes to wait the others wave at it, and it may wave back. */
	private static final int WELCOME_TICKS = 5;
	private static final int WAVE_BACK_TICKS = 12;

	/** The chance a golem waiting waves at a newcomer on a given tick, so they do not all at once. */
	private static final float WELCOME_CHANCE = 0.5f;

	/** How long a wave takes, and how long before a golem at a quay waves at another again. */
	private static final int WAVE_TICKS = 4;
	private static final int WAVE_COOLDOWN_TICKS = 100;

	/**
	 * Golems waiting at a quay in view of the player, kept from standing frozen for as long as the
	 * muster lasts, facing wherever their last step pointed: now and then each wanders a tile or two
	 * about its place, or turns to look out at the water, each on its own clock so they do not move
	 * as one. Whoever is waiting waves at a golem coming to join them, and it waves back once it is
	 * in its place. Nothing is given to a golem still walking: a wave would stop it where it was.
	 */
	private void mill(Muster muster, int tick, RoamContext context)
	{
		muster.newcomers.values().removeIf(newcomer -> tick - newcomer.came > WAVE_BACK_TICKS || tick < newcomer.came);
		for (Golem golem : muster.waiting)
		{
			if (!golem.isIdleOnQuay(tick) || welcome(muster, golem, tick))
			{
				continue;
			}
			Integer due = muster.nextMove.get(golem);
			if (due != null && tick < due && due - tick <= MOVE_MIN_TICKS + MOVE_SPREAD_TICKS)
			{
				continue;
			}
			muster.nextMove.put(golem, tick + MOVE_MIN_TICKS + random.nextInt(MOVE_SPREAD_TICKS));
			if (due == null)
			{
				// Only just settled: it stands a moment first.
				continue;
			}
			WorldPoint at = golem.currentTile();
			WorldPoint water = muster.dock.getBerth();
			if (random.nextFloat() < LOOK_CHANCE)
			{
				golem.faceToward(water.getX() - at.getX(), water.getY() - at.getY());
				continue;
			}
			WorldPoint spot = golem.waitingSpot();
			WorldPoint to = spot == null ? null : planner.quayTileNear(spot, taken(muster, golem), random);
			// Never so far it no longer counts as waiting here.
			if (to != null && to.distanceTo2D(muster.dock.getShore()) <= QUAYSIDE)
			{
				golem.millTo(to, context);
			}
		}
	}

	/**
	 * Waves at a newcomer, or back at whoever waved first. Golems standing still only, and not again
	 * for a while, so a busy quay is not all waving.
	 *
	 * @return true if the golem waved
	 */
	private boolean welcome(Muster muster, Golem golem, int tick)
	{
		if (!golem.mayWaveAtGolem(tick, WAVE_COOLDOWN_TICKS))
		{
			return false;
		}
		Golem other = null;
		Newcomer self = muster.newcomers.get(golem);
		// In its place now, and somebody waved on its way in: it waves back, the once. Not the tick
		// they waved, which looks like two golems who happened to wave at the same moment.
		if (self != null && self.wavedBy != null && tick > self.wavedOn)
		{
			other = self.wavedBy;
			muster.newcomers.remove(golem);
		}
		else
		{
			// A chance a tick rather than all at once, so the quay's welcome comes a tick or two apart.
			for (Map.Entry<Golem, Newcomer> entry : muster.newcomers.entrySet())
			{
				Newcomer newcomer = entry.getValue();
				if (entry.getKey() != golem && tick - newcomer.came <= WELCOME_TICKS
					&& entry.getKey().getRenderer() != null && random.nextFloat() < WELCOME_CHANCE)
				{
					other = entry.getKey();
					if (newcomer.wavedBy == null)
					{
						newcomer.wavedBy = golem;
						newcomer.wavedOn = tick;
					}
					break;
				}
			}
		}
		if (other == null || !muster.waiting.contains(other))
		{
			return false;
		}
		WorldPoint at = golem.currentTile();
		WorldPoint there = other.currentTile();
		golem.waveAtGolem(tick + WAVE_TICKS, there.getX() - at.getX(), there.getY() - at.getY(), tick);
		return true;
	}

	/** The tiles golems waiting here stand on, and the others are walking to, for one to keep off. */
	private static Set<Long> taken(Muster muster, Golem golem)
	{
		Set<Long> taken = new HashSet<>();
		for (Golem other : muster.waiting)
		{
			WorldPoint tile = other.currentTile();
			taken.add(RoamContext.tileKey(tile.getX(), tile.getY(), tile.getPlane()));
			if (other != golem)
			{
				tile = other.goalTile();
				taken.add(RoamContext.tileKey(tile.getX(), tile.getY(), tile.getPlane()));
			}
		}
		return taken;
	}

	/**
	 * Lets golems waiting at a quay go, each to sail alone or not as it would have without company,
	 * and not to be held again straight away.
	 */
	private void release(List<Golem> golems, int tick)
	{
		for (Golem golem : golems)
		{
			released.put(golem, tick);
			golem.endWait();
		}
	}

	/**
	 * Picks the crew's port, plans the one crossing, and signs everyone on; or, if there is no port
	 * all of them would go to, lets them go.
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

		if (muster.port == null)
		{
			muster.port = voyage.crewPort(muster.dock, memories, random, context);
			muster.portUntil = tick + TransportMemory.PENDING_WAIT_TICKS;
		}
		// Nowhere all of them would go, and waiting longer changes nothing about that; nor does
		// waiting any longer on a crossing still being worked out. They go their separate ways now
		// rather than stand out the muster.
		if (muster.port == null || tick > muster.portUntil)
		{
			release(muster.waiting, tick);
			return true;
		}
		WorldPoint at = crew.get(0).currentTile();
		Itinerary crossing = voyage.crossTo(muster.dock, muster.port, at, tick, random, context);
		if (crossing == null)
		{
			// Waiting on its sea field: the port is kept. No crossing to be had there at all: another.
			if (!voyage.wasNotReady())
			{
				muster.port = null;
			}
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
		List<Golem> left = new ArrayList<>(muster.waiting);
		left.removeIf(golem -> crews.get(golem) == made);
		release(left, tick);
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
		// The crossing ends every one of them on the same tile; each steps off onto one of its own.
		WorldPoint at = golem.currentTile();
		WorldPoint own = planner.freeTileNear(at, crew.getAshore());
		if (!own.equals(at))
		{
			golem.relocate(own);
		}
		crew.getAshore().add(RoamContext.tileKey(own.getX(), own.getY(), own.getPlane()));
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

	/**
	 * Lets every crew still making up go, on a log out or a world hop: the golems waiting are put
	 * back to planning for themselves rather than kept standing on a wait that was timed by the
	 * session just left.
	 */
	void releaseMusters()
	{
		for (Muster muster : mustering.values())
		{
			for (Golem golem : muster.waiting)
			{
				golem.stopWaiting();
			}
		}
		mustering.clear();
		released.clear();
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
