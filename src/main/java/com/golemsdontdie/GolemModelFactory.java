package com.golemsdontdie;

import java.util.*;
import javax.inject.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;

/**
 * Rebuilds a golem's model from the cache, unposed.
 *
 * <p>{@link net.runelite.api.Renderable#getModel()} on the live NPC returns a model already
 * posed for the current frame, in a client-owned buffer. This copy outlives the golem and
 * animates on its own, so it needs the <i>rest</i> pose the animation frames are defined
 * against; posing a posed model compounds the two transforms and folds the golem through itself.
 */
@Slf4j
@Singleton
class GolemModelFactory
{
	/**
	 * The client's base lighting for actors; an NPC's own ambient and contrast add to these. Bare
	 * defaults look flatter and the wrong brightness beside the real thing.
	 */
	private static final int BASE_AMBIENT = 64;
	private static final int BASE_CONTRAST = 850;
	private static final int LIGHT_X = -30;
	private static final int LIGHT_Y = -50;
	private static final int LIGHT_Z = -30;

	/** Composition scales are in 128ths; 128 means "as authored". */
	private static final int UNSCALED = 128;

	@Inject
	private Client client;

	/**
	 * One built model per NPC ID, shared by every golem wearing it. Every golem is the same NPC,
	 * so building one each meant a cache load, merge, vertex clone and lighting pass per golem —
	 * seconds of stall as a few hundred arrive with the scene. Safe because
	 * {@link Client#applyTransformations} clones its source's vertices rather than writing to it.
	 */
	private final Map<Integer, Model> cache = new HashMap<>();

	/** The lit rest-pose model for a snapshot, built once and shared; null if a part is missing. */
	Model modelFor(GolemSnapshot snapshot)
	{
		Model cached = cache.get(snapshot.getNpcId());
		if (cached != null)
		{
			return cached;
		}

		Model built = build(snapshot);
		if (built != null)
		{
			cache.put(snapshot.getNpcId(), built);
		}
		return built;
	}

	/**
	 * Animation definitions, shared the same way the models are: there are exactly two across the
	 * whole population, and an {@link Animation} is immutable frame data — mutable playback state
	 * lives in each golem's own controller — so one copy of each serves every golem.
	 */
	private final Map<Integer, Animation> animations = new HashMap<>();

	/** The animation for an ID, loaded once. Null for -1, or if the cache lacks it. */
	Animation animationFor(int animationId)
	{
		if (animationId == -1)
		{
			return null;
		}
		// computeIfAbsent is avoided: loadAnimation can return null, which would mean
		// re-asking for a missing animation on every switch.
		if (animations.containsKey(animationId))
		{
			return animations.get(animationId);
		}
		Animation loaded = client.loadAnimation(animationId);
		animations.put(animationId, loaded);
		return loaded;
	}

	/**
	 * True if this animation is built to repeat.
	 *
	 * <p>{@code frameStep} is how many frames the client winds back at a clip's end: positive
	 * loops, -1 runs once. It cannot be guessed from the name — {@code human_climbing} (737) does
	 * not loop, {@code human_climbing_loop} (4435) does. Only a loop may be stretched to fill a
	 * duration; a stretched one-shot restarts partway and drifts.
	 */
	boolean loops(int animationId)
	{
		Animation animation = animationFor(animationId);
		return animation != null && animation.getFrameStep() > 0;
	}

	/**
	 * How long a clip runs for, in client cycles, or 0 if unknown: the sum of its frame lengths,
	 * asked of the client so it stays right if Jagex retimes one. Paces a golem through a
	 * shortcut; guessing had golems scaling a cliff in two thirds of a second.
	 */
	int animationCycles(int animationId)
	{
		Animation animation = animationFor(animationId);
		if (animation == null)
		{
			return 0;
		}

		// Frame lengths, summed, not getDuration(): that is a frame *count*, and reading it as
		// cycles made every clip four or five times shorter — a hop came out at 8 cycles against
		// its real 38. Frame lengths are in client cycles.
		int[] frames = animation.getFrameLengths();
		if (frames == null || frames.length == 0)
		{
			return 0;
		}

		int total = 0;
		for (int length : frames)
		{
			total += length;
		}
		return Math.min(total, MAX_CLIP_CYCLES);
	}

	/**
	 * The longest a clip is allowed to claim to be, in client cycles.
	 *
	 * <p>Some sequences end on a frame held absurdly long, meaning "stay like this until told
	 * otherwise": {@code agilityarena_handholds_middle} reports 20,056 cycles and
	 * {@code agilty_shortcut_enter_hole} 2,060, which taken at face value freeze a golem in the
	 * obstacle for minutes. Twenty ticks is longer than any real traversal.
	 */
	private static final int MAX_CLIP_CYCLES = 600;

	/**
	 * Drops the shared models and animations when the plugin stops, so a disable/enable cycle
	 * does not hand out resources built against a client that has moved on.
	 */
	void clear()
	{
		cache.clear();
		animations.clear();
	}

	/** Builds the rest-pose model a snapshot describes; null if the cache lacks a part. */
	private Model build(GolemSnapshot snapshot)
	{
		try
		{
			int[] ids = snapshot.getModelIds();
			ModelData[] parts = new ModelData[ids.length];
			int found = 0;

			for (int id : ids)
			{
				ModelData part = client.loadModelData(id);
				if (part == null)
				{
					// A missing part would leave a golem with no head; better to fail the
					// whole build and let the real death play out.
					log.debug("Golem model part {} not in cache for npc {}", id, snapshot.getNpcId());
					return null;
				}
				parts[found++] = part;
			}

			if (found == 0)
			{
				return null;
			}

			ModelData merged = client.mergeModels(parts, found);
			if (merged == null)
			{
				return null;
			}

			// Recolour and resize write into the vertex and colour arrays, shared with the
			// cache's copy until cloned. Skipping this repaints every other model in the
			// game that shares the source.
			merged.cloneColors().cloneVertices();

			short[] from = snapshot.getRecolourFrom();
			short[] to = snapshot.getRecolourTo();
			for (int i = 0; i < Math.min(from.length, to.length); i++)
			{
				merged.recolor(from[i], to[i]);
			}

			int width = snapshot.getWidthScale() <= 0 ? UNSCALED : snapshot.getWidthScale();
			int height = snapshot.getHeightScale() <= 0 ? UNSCALED : snapshot.getHeightScale();
			if (width != UNSCALED || height != UNSCALED)
			{
				// Y is the vertical axis, and it is the one the height scale drives.
				merged.scale(width, height, width);
			}

			Model model = merged.light(
				BASE_AMBIENT + GolemContent.GOLEM_AMBIENT,
				BASE_CONTRAST + GolemContent.GOLEM_CONTRAST,
				LIGHT_X, LIGHT_Y, LIGHT_Z);
			if (model == null)
			{
				return null;
			}
			model.calculateBoundsCylinder();
			return model;
		}
		catch (RuntimeException e)
		{
			log.debug("Could not build golem model for npc {}", snapshot.getNpcId(), e);
			return null;
		}
	}
}
