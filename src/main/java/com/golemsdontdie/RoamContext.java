package com.golemsdontdie;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/**
 * The world as one golem sees it while deciding what to do next.
 *
 * <p>{@link Golem#advance} needs the passability map, the pathfinder, the transport
 * network, what the player has unlocked, and the current tick. Passing five collaborators
 * through a per-frame call — once per golem, and there can be a thousand — is the kind of
 * signature that grows another argument every phase and is miserable to read by the third.
 *
 * <p>One instance, owned by the plugin and reused for every golem in the frame. It holds
 * no per-golem state: everything specific to a golem lives on the golem.
 */
@Getter
@RequiredArgsConstructor
final class RoamContext
{
	private final IslandMemory memory;
	private final GolemPathfinder pathfinder;
	private final TransportNetwork transports;
	private final GolemAbilities abilities;

	/**
	 * The route planner, so a golem in view can consider sailing.
	 *
	 * <p>Set after construction rather than injected, because the planner needs the same
	 * singletons this context carries and wiring it both ways in the constructor would be a
	 * cycle for no benefit.
	 */
	@Setter
	private RoamPlanner planner;

	/**
	 * The shared model and animation cache, so a golem can ask how long a clip runs for.
	 *
	 * <p>Set after construction for the same reason the planner is.
	 */
	@Setter
	private GolemModelFactory models;

	/**
	 * The client's tick counter, used for cooldowns.
	 *
	 * <p>Set once per frame rather than read per golem. Ticks rather than wall clock so
	 * that cooldowns advance with the game and pause with it.
	 */
	@Setter
	private int tick;

	/**
	 * Whether this golem may spend one of the frame's path searches.
	 *
	 * <p>Set per golem by the plugin as it works down the roster, so the budget is shared
	 * across all of them rather than each golem searching whenever it feels like it.
	 */
	@Setter
	private boolean mayPath;

	/**
	 * Whether an ocean search may still be run this frame.
	 *
	 * <p>Crossings are cached per pair of ports, so in a settled session almost every
	 * voyage is a map lookup. The exception is the first golem to sail a given route,
	 * which pays for an A* across a 2.4-million-tile sea — far too much to run twice in
	 * one frame, and enough to be worth rationing even once.
	 *
	 * <p>A golem refused a search simply does not sail this frame and considers it again
	 * on its next decision, which is indistinguishable from it having chosen to stay.
	 */
	@Setter
	private boolean maySearchSea;

	/** Set by the planner when it spends the frame's sea search. */
	void spendSeaSearch()
	{
		maySearchSea = false;
	}
}
