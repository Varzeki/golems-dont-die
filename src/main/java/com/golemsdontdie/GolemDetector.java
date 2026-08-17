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
 * <p>Both answers are hardcoded from {@link GolemContent}, harvested once out of the
 * game cache rather than discovered at runtime. That is worth doing rather than
 * clever: a name match would sweep up the sixty-odd unrelated golems in the game, and
 * learning the death animation from the first golem to die costs one visible crumble
 * every time the roster is empty. Knowing the IDs up front means the very first swap
 * is as seamless as the hundredth.
 *
 * <p>Both remain overridable, because a content update can move them and a config
 * field is faster than a rebuild.
 */
@Singleton
class GolemDetector
{
	/** Live golems being watched, by NPC index, with their most recent snapshot. */
	private final Map<Integer, GolemSnapshot> tracked = new HashMap<>();

	/** Golems already replaced by a copy, so a despawn does not replace them twice. */
	private final Set<Integer> replaced = new HashSet<>();

	/**
	 * Golems seen playing the plinth animation. The copy takes over on the tick that
	 * animation ends, which is the one moment a golem is reliably standing still.
	 */
	private final Set<Integer> leftPlinth = new HashSet<>();

	// ---- identity ----

	/** True if this NPC is a crafted golem. */
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

	/** Records or refreshes what a live golem looks like. */
	void track(NPC npc)
	{
		GolemSnapshot snapshot = GolemSnapshot.of(npc);
		if (snapshot != null)
		{
			tracked.put(npc.getIndex(), snapshot);
		}
	}

	/** The last snapshot taken of a golem, or null if it was never tracked. */
	GolemSnapshot snapshotOf(NPC npc)
	{
		return tracked.get(npc.getIndex());
	}

	/** Records that a golem has played the animation for stepping off its plinth. */
	void noteLeftPlinth(NPC npc)
	{
		leftPlinth.add(npc.getIndex());
	}

	/** True if this golem has stepped off its plinth and is now standing free. */
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

	/** Drops everything. Called on logout and when the plugin stops. */
	void reset()
	{
		tracked.clear();
		replaced.clear();
		leftPlinth.clear();
	}
}
