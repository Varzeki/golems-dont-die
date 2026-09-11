package com.golemsdontdie;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;

/**
 * Rebuilds a golem's model from the cache, unposed.
 *
 * <p>The tempting shortcut is to take {@link net.runelite.api.Renderable#getModel()}
 * off the live NPC, the way the Player Owned Island plugin copies the player. That
 * works for a copy that only has to live as long as the thing it is copying, and
 * only if the original keeps supplying fresh frames — the model handed back is
 * already posed for the current animation frame, and the client owns the buffer it
 * sits in.
 *
 * <p>Neither holds here. The copy has to outlive the golem by an unbounded amount
 * and animate on its own, so it needs the <i>rest</i> pose the animation frames are
 * defined against. Posing a posed model compounds the two transforms and produces a
 * golem folded through itself.
 *
 * <p>So the model is assembled the way the client would: load each model ID from the
 * composition, apply the composition's colour swaps, merge, scale, and light. What
 * comes back is a rest-pose {@link Model} that
 * {@link Client#applyTransformations(Model, net.runelite.api.Animation, int, net.runelite.api.Animation, int)}
 * will accept for as long as the plugin cares to keep it.
 */
@Slf4j
@Singleton
class GolemModelFactory
{
	/**
	 * The client's base lighting for actors. An NPC's own ambient and contrast are
	 * added to these — a detail that is easy to miss and plainly visible when missed,
	 * since a copy lit with the bare defaults sits next to the real thing looking
	 * flatter and the wrong brightness.
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
	 * One built model per NPC ID, shared by every golem wearing it.
	 *
	 * <p>Every golem on the island is the same NPC with the same models, recolours and
	 * scale, so building one per golem was building the same thing hundreds of times —
	 * a cache load, a merge, a vertex clone and a lighting pass each. With a few
	 * hundred golems arriving at once as the scene loads, that is seconds of stall for
	 * no benefit.
	 *
	 * <p>Sharing is safe because nothing downstream writes to the base model:
	 * {@link Client#applyTransformations} clones the vertices out of its source and
	 * leaves it untouched, and the posed result it returns is what gets drawn.
	 */
	private final Map<Integer, Model> cache = new HashMap<>();

	/**
	 * The rest-pose model for a snapshot, built once and thereafter shared.
	 *
	 * @return the lit model, or null if the cache would not give up the parts
	 */
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
	 * Animation definitions, shared the same way the models are.
	 *
	 * <p>Golems switch between walking and standing constantly, and each switch used to
	 * re-fetch the definition from the client. There are exactly two of them across the
	 * whole population, and an {@link Animation} is immutable frame data — the mutable
	 * playback state lives in each golem's own controller — so one copy of each serves
	 * every golem on the island.
	 */
	private final Map<Integer, Animation> animations = new HashMap<>();

	/** The animation for an ID, loaded once. Null for -1, or if the cache lacks it. */
	Animation animationFor(int animationId)
	{
		if (animationId == -1)
		{
			return null;
		}
		// computeIfAbsent is avoided: loadAnimation can return null, and that would
		// mean re-asking for a missing animation on every switch.
		if (animations.containsKey(animationId))
		{
			return animations.get(animationId);
		}
		Animation loaded = client.loadAnimation(animationId);
		animations.put(animationId, loaded);
		return loaded;
	}

	/**
	 * How long a clip runs for, in client cycles, or 0 if it is not known.
	 *
	 * <p>Asked of the client rather than measured offline and shipped. The length is the
	 * sum of the animation's own frame lengths, so this is the game's answer to how long
	 * the action takes — and it stays right if Jagex ever retimes one, where a harvested
	 * constant would quietly drift.
	 *
	 * <p>Used to pace a golem through a shortcut. Guessing that instead is what had golems
	 * scaling a cliff in two thirds of a second.
	 */
	int animationCycles(int animationId)
	{
		Animation animation = animationFor(animationId);
		if (animation == null)
		{
			return 0;
		}

		int duration = animation.getDuration();
		if (duration > 0)
		{
			return duration;
		}

		// Some clips report no duration; their frame lengths still add up to one.
		int[] frames = animation.getFrameLengths();
		if (frames == null)
		{
			return 0;
		}
		int total = 0;
		for (int length : frames)
		{
			total += length;
		}
		return total;
	}

	/**
	 * Drops the shared models and animations. Called when the plugin stops, so a
	 * disable/enable cycle does not keep handing out resources built against a client
	 * that has moved on.
	 */
	void clear()
	{
		cache.clear();
		animations.clear();
	}

	/**
	 * Builds the rest-pose model described by a snapshot.
	 *
	 * @return the lit model, or null if the cache would not give up the parts
	 */
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
					// A single missing part would leave a golem with no head. Better to
					// fail the whole build and let the real death play out.
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

			// Recolour and resize both write into the vertex and colour arrays, which
			// are shared with the cache's copy until cloned. Skipping this repaints
			// every other model in the game that happens to share the source.
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
