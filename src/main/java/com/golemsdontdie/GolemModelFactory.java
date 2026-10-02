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
	 * One built look per NPC, hat and chisel, shared by every golem that has it. Every golem is the
	 * same NPC, so building one each meant a cache load, merge, vertex clone and lighting pass per
	 * golem - seconds of stall as a few hundred arrive with the scene. Safe because
	 * {@link Client#applyTransformations} clones its source's vertices rather than writing to it.
	 */
	private final Map<Long, Look> cache = new HashMap<>();

	/**
	 * A golem's lit rest-pose model, and the chisel in its hand if it has one; held is null otherwise.
	 * The hat and chisel are what it was built with, which a look standing in for one that could not
	 * be built has not.
	 */
	static final class Look
	{
		final Model model;
		final HeldItem held;
		final GolemHat hat;
		final boolean chisel;

		Look(Model model, HeldItem held)
		{
			this(model, held, GolemHat.NONE, false);
		}

		Look(Model model, HeldItem held, GolemHat hat, boolean chisel)
		{
			this.model = model;
			this.held = held;
			this.hat = hat;
			this.chisel = chisel;
		}

		/** True if this is the look the golem has now: its hat, and its chisel if it has one. */
		boolean isOf(Golem golem)
		{
			return hat == golem.getHat() && chisel == golem.isChisel();
		}
	}

	/**
	 * A golem's look as it is now: its hat, and its chisel if it has one. If the hat or chisel cannot
	 * be loaded yet, the golem bare, until they can; null if even that is missing.
	 */
	Look lookFor(Golem golem)
	{
		Look look = lookFor(golem.getSnapshot(), golem.getHat(), golem.isChisel());
		return look != null || golem.getHat() == GolemHat.NONE && !golem.isChisel() ? look
			: lookFor(golem.getSnapshot(), GolemHat.NONE, false);
	}

	private Look lookFor(GolemSnapshot snapshot, GolemHat hat, boolean chisel)
	{
		long key = (long) snapshot.getNpcId() << 16 | hat.ordinal() << 1 | (chisel ? 1 : 0);
		Look cached = cache.get(key);
		if (cached != null)
		{
			return cached;
		}

		Look built = build(snapshot, hat, chisel);
		if (built != null)
		{
			cache.put(key, built);
		}
		return built;
	}

	/**
	 * The jeweller's chisel as a golem holds it. Its only model is its inventory icon, lying flat
	 * with its length on a slant; turned to point blade first ahead of the golem, the handle is put
	 * through the right fist, as a hammer is held. The slant, middle and grip are the icon's own,
	 * read from the cache: its long axis, its centre, and the middle of its handle along that axis.
	 */
	private static final float CHISEL_AXIS_X = 0.784f;
	private static final float CHISEL_AXIS_Z = 0.621f;
	private static final float CHISEL_CENTRE_X = -4.1f;
	private static final float CHISEL_CENTRE_Y = -4f;
	private static final float CHISEL_CENTRE_Z = -9f;
	private static final float CHISEL_GRIP = -13f;

	/**
	 * The top of the golem's head as it stands, read from the cache: the head's upward faces lean
	 * 9.4 degrees forward, where a player's are level, about a centre within a unit of where a hat's
	 * base already sits. So a hat is tipped forward that much about that centre, and sits flat on it.
	 */
	private static final float HEAD_TOP_Y = -188.6f;
	private static final float HEAD_TOP_Z = -5.9f;
	private static final float HEAD_TILT_SIN = 0.163f;
	private static final float HEAD_TILT_COS = 0.987f;

	/** The golem's right fist as it stands: its middle, and three of its corners to carry the chisel by. */
	private static final float[] FIST = {-30f, -87f, 0f};
	private static final float[][] FIST_CORNERS = {{-35f, -88f, -5f}, {-25f, -88f, -5f}, {-35f, -88f, 6f}};

	/**
	 * Animation definitions, shared the same way the models are: there are exactly two across the
	 * whole population, and an {@link Animation} is immutable frame data - mutable playback state
	 * lives in each golem's own controller - so one copy of each serves every golem.
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
	 * loops, -1 runs once. It cannot be guessed from the name - {@code human_climbing} (737) does
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
		// cycles made every clip four or five times shorter - a hop came out at 8 cycles against
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

	/** Builds the look a snapshot, hat and chisel describe; null if the cache lacks a part. */
	private Look build(GolemSnapshot snapshot, GolemHat hat, boolean chisel)
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
			int golemVertices = merged.getVerticesCount();

			// Hat and chisel after the golem's own recolour and scale: neither is the golem's to repaint.
			List<ModelData> worn = new ArrayList<>();
			worn.add(merged);
			// Either missing is not built without: built, it would be kept that way for good.
			if (hat.getModel() >= 0)
			{
				ModelData data = client.loadModelData(hat.getModel());
				if (data == null)
				{
					return null;
				}
				data = onHead(data.cloneColors().cloneVertices(), hat.getSink());
				short[] hatFrom = hat.getRecolourFrom();
				short[] hatTo = hat.getRecolourTo();
				for (int i = 0; hatFrom != null && i < Math.min(hatFrom.length, hatTo.length); i++)
				{
					data.recolor(hatFrom[i], hatTo[i]);
				}
				worn.add(data);
			}
			int chiselVertices = 0;
			if (chisel)
			{
				ModelData data = client.loadModelData(GolemContent.CHISEL_MODEL);
				if (data == null)
				{
					return null;
				}
				worn.add(inFist(data.cloneVertices()));
				chiselVertices = data.getVerticesCount();
			}
			if (worn.size() > 1)
			{
				merged = client.mergeModels(worn.toArray(new ModelData[0]), worn.size());
				if (merged == null)
				{
					return null;
				}
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
			HeldItem held = chiselVertices == 0 ? null : HeldItem.in(model, chiselVertices, golemVertices, FIST_CORNERS);
			if (chiselVertices > 0 && held == null)
			{
				// The hand is not where it was measured: a chisel it cannot carry would hang in the air
				// where the hand was, so the golem goes without.
				return build(snapshot, hat, false);
			}
			return new Look(model, held, hat, chisel);
		}
		catch (RuntimeException e)
		{
			log.debug("Could not build golem model for npc {}", snapshot.getNpcId(), e);
			return null;
		}
	}

	/** Tips a worn model forward onto the golem's head, and lowers it this far along it: see HEAD_TOP_Y. */
	private static ModelData onHead(ModelData worn, float sink)
	{
		float[] y = worn.getVerticesY();
		float[] z = worn.getVerticesZ();
		for (int i = 0; i < worn.getVerticesCount(); i++)
		{
			float dy = y[i] - HEAD_TOP_Y;
			float dz = z[i] - HEAD_TOP_Z;
			// Up is -y and forward is -z: turning up towards forward.
			y[i] = HEAD_TOP_Y + dy * HEAD_TILT_COS - dz * HEAD_TILT_SIN + sink * HEAD_TILT_COS;
			z[i] = HEAD_TOP_Z + dy * HEAD_TILT_SIN + dz * HEAD_TILT_COS + sink * HEAD_TILT_SIN;
		}
		return worn;
	}

	/** Puts the chisel's icon model in the golem's right fist, as it stands: see CHISEL_AXIS_X. */
	private static ModelData inFist(ModelData chisel)
	{
		float[] x = chisel.getVerticesX();
		float[] y = chisel.getVerticesY();
		float[] z = chisel.getVerticesZ();
		for (int i = 0; i < chisel.getVerticesCount(); i++)
		{
			float dx = x[i] - CHISEL_CENTRE_X;
			float dz = z[i] - CHISEL_CENTRE_Z;
			// Along its length, from handle to blade; and across it.
			float along = dx * CHISEL_AXIS_X + dz * CHISEL_AXIS_Z;
			float across = -dx * CHISEL_AXIS_Z + dz * CHISEL_AXIS_X;
			// Blade forward, which for a model is -z; the grip on the fist.
			x[i] = FIST[0] + across;
			y[i] = FIST[1] + y[i] - CHISEL_CENTRE_Y;
			z[i] = FIST[2] - (along - CHISEL_GRIP);
		}
		return chisel;
	}
}
