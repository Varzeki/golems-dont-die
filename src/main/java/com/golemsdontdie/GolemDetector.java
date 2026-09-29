package com.golemsdontdie;

import java.util.*;
import javax.inject.*;
import net.runelite.api.*;

/**
 * Decides what counts as a golem, and when one is dying.
 *
 * <p>Both are hardcoded in {@link GolemContent}, not discovered at runtime: a name match
 * would sweep up the sixty-odd unrelated golems in the game, and learning the death
 * animation from the first death costs a visible crumble whenever the roster is empty.
 * Both stay overridable, as a content update can move them.
 */
@Singleton
class GolemDetector
{
	/** Live golems watched, by NPC index, with their latest snapshot. */
	private final Map<Integer, GolemSnapshot> tracked = new HashMap<>();

	/** Golems already replaced by a copy, so a despawn does not replace them twice. */
	private final Set<Integer> replaced = new HashSet<>();

	/**
	 * Golems seen playing the plinth animation. The copy takes over as it ends, the one
	 * moment a golem is reliably standing still.
	 */
	private final Set<Integer> leftPlinth = new HashSet<>();

	/** Golems replaced and then seen crumbling: the real thing is on its way out, and done with. */
	private final Set<Integer> crumbling = new HashSet<>();

	/**
	 * Replaced golems that went out of view still standing, by NPC index, with the tick until which
	 * one coming back is the same golem. Forgotten on the way out, one that walked back into view
	 * before crumbling was taken over a second time: two golems where there had been one.
	 */
	private final Map<Integer, Integer> away = new HashMap<>();

	/** Longer than a crafted golem lives: past this, an index seen again is a different golem. */
	private static final int AWAY_TICKS = 100;

	// ---- identity ----

	boolean isGolem(NPC npc)
	{
		return npc != null && GolemContent.isGolem(npc.getId());
	}

	/** True if this animation is the crumble. */
	boolean isDeathAnimation(int animationId)
	{
		return animationId != -1 && animationId == GolemContent.GOLEM_DEATH_ANIMATION;
	}

	// ---- tracking ----

	void track(NPC npc)
	{
		GolemSnapshot snapshot = GolemSnapshot.of(npc);
		if (snapshot != null)
		{
			tracked.put(npc.getIndex(), snapshot);
		}
	}

	/** Last snapshot of a golem, or null if never tracked. */
	GolemSnapshot snapshotOf(NPC npc)
	{
		return tracked.get(npc.getIndex());
	}

	void noteLeftPlinth(NPC npc)
	{
		leftPlinth.add(npc.getIndex());
	}

	boolean hasLeftPlinth(NPC npc)
	{
		return leftPlinth.contains(npc.getIndex());
	}

	/** Marks a golem as already replaced, so its despawn is ignored. */
	void markReplaced(NPC npc)
	{
		replaced.add(npc.getIndex());
	}

	boolean wasReplaced(NPC npc)
	{
		return replaced.contains(npc.getIndex());
	}

	/** Drops all state for an NPC that has left the scene. */
	void forget(NPC npc, int tick)
	{
		int index = npc.getIndex();
		if (replaced.contains(index) && !crumbling.contains(index))
		{
			away.put(index, tick + AWAY_TICKS);
		}
		tracked.remove(index);
		replaced.remove(index);
		leftPlinth.remove(index);
		crumbling.remove(index);
	}

	/** Notes a replaced golem playing its crumble, so its despawn is taken as its end. */
	void noteCrumbling(NPC npc)
	{
		crumbling.add(npc.getIndex());
	}

	/**
	 * True if a golem just in view is one already replaced, back from out of view, and marks it
	 * replaced again. A golem newly crafted onto the same index is told apart by stepping off the
	 * plinth; see {@link #crafted}.
	 */
	boolean returned(NPC npc, int tick)
	{
		Integer until = away.remove(npc.getIndex());
		if (until == null || tick > until || tick < until - AWAY_TICKS)
		{
			return false;
		}
		replaced.add(npc.getIndex());
		return true;
	}

	/** A golem stepping off the plinth is new, whatever held its index before. */
	void crafted(NPC npc)
	{
		replaced.remove(npc.getIndex());
		away.remove(npc.getIndex());
	}

	/** Drops everything; on logout and plugin stop. */
	void reset()
	{
		tracked.clear();
		replaced.clear();
		leftPlinth.clear();
		crumbling.clear();
		away.clear();
	}
}
