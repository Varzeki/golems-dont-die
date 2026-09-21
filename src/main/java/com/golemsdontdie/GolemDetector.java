package com.golemsdontdie;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Singleton;
import net.runelite.api.NPC;

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
	void forget(NPC npc)
	{
		tracked.remove(npc.getIndex());
		replaced.remove(npc.getIndex());
		leftPlinth.remove(npc.getIndex());
	}

	/** Drops everything; on logout and plugin stop. */
	void reset()
	{
		tracked.clear();
		replaced.clear();
		leftPlinth.clear();
	}
}
