package com.golemsdontdie;

import java.awt.*;
import java.util.function.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;

/**
 * Draws a {@link Golem} while it is inside the loaded scene.
 *
 * <p>Deliberately thin: it decides nothing about where the golem goes, only reading the
 * simulation's position each frame, and a golem that leaves the scene loses its renderer but
 * keeps walking. The pose is applied here rather than copied from a real NPC, which would need
 * an original still alive and posed every frame; the base model is the cache's rest pose, driven
 * by an {@link AnimationController} of our own.
 */
class FakeGolem extends RuneLiteObjectController
{
	/** 128ths of a tile per tile, for scene-to-local conversion. */
	private static final int TILE = Golem.TILE;

	private final Client client;
	private final Golem golem;
	private final Model baseModel;

	/** The look it was built with, so a hat changed is seen; and the chisel it carries, or null. */
	@lombok.Getter
	private final GolemModelFactory.Look look;
	private final HeldItem held;

	/** Source of the shared model and animation definitions. */
	private final GolemModelFactory shared;

	/**
	 * Drives the walk or idle loop. One controller, not the client's active-plus-pose pair:
	 * a wandering golem only ever plays one thing at a time.
	 */
	private final AnimationController animation;

	/** Which animation ID the controller currently holds, to avoid resetting it every frame. */
	private int loadedAnimationId = Integer.MIN_VALUE;

	/** How tall the golem is drawn, in the client's height units, for putting things above its head. */
	int getModelHeight()
	{
		return baseModel.getModelHeight();
	}

	FakeGolem(Client client, Golem golem, GolemModelFactory.Look look, GolemModelFactory shared)
	{
		this.client = client;
		this.golem = golem;
		this.baseModel = look.model;
		this.look = look;
		this.held = look.held;
		this.shared = shared;
		this.animation = new AnimationController(client, -1);
		this.animation.setOnFinished(this::poseFinished);

		syncTransform();
		applyPose();
	}

	/**
	 * Called by the client once per frame while registered. Only the animation is advanced here;
	 * position is advanced centrally for every golem, including those off screen, or one would
	 * freeze on leaving the scene.
	 */
	@Override
	public void tick(int ticksSinceLastFrame)
	{
		syncTransform();
		applyPose();

		// A recording drives its own keyframes, so the client's clock must not also
		// advance them - that was the drift.
		if (golem.getMotionFrame() < 0)
		{
			animation.tick(ticksSinceLastFrame);
		}
	}

	@Override
	public Model getModel()
	{
		Model drawn = baseModel;
		if (animation.getAnimation() != null)
		{
			Model posed = animation.animate(baseModel);
			drawn = posed == null ? baseModel : posed;
			if (held != null && posed != null)
			{
				held.follow(posed);
			}
		}
		measure(drawn);
		return drawn;
	}

	/**
	 * The pose last drawn, as far out from the golem's middle as it reaches and from its lowest point
	 * to its highest, in model units. Read as the client draws it: the box these make holds the pose
	 * whichever way the golem faces, so the mouse outside it is not over the golem.
	 */
	private int reach = -1;
	private int lowest;
	private int highest;

	/** The rest pose's measurement, which never changes, so it is taken once. */
	private boolean restMeasured;

	private void measure(Model model)
	{
		if (model == baseModel && restMeasured)
		{
			return;
		}
		float[] xs = model.getVerticesX();
		float[] ys = model.getVerticesY();
		float[] zs = model.getVerticesZ();
		float far = 0;
		float low = 0;
		float high = 0;
		for (int i = 0; i < model.getVerticesCount(); i++)
		{
			far = Math.max(far, xs[i] * xs[i] + zs[i] * zs[i]);
			low = Math.min(low, ys[i]);
			high = Math.max(high, ys[i]);
		}
		reach = (int) Math.ceil(Math.sqrt(far));
		lowest = (int) Math.floor(low);
		highest = (int) Math.ceil(high);
		restMeasured = model == baseModel;
	}

	/**
	 * Whether the mouse is over this golem, tested as the game tests a model it draws: inside the
	 * bounds, then inside the faces drawn this frame, by RuneLite's own reproduction of the game's
	 * picking (Perspective.getClickbox). A {@link RuneLiteObjectController} is drawn but not picked,
	 * the client building its menu from real entities, so it is done here.
	 *
	 * <p>The pose is only struck again for a golem whose bounds hold the mouse: striking every golem
	 * near the cursor each tick stalled the client. The model it returns is a shared buffer, used
	 * here at once and not kept.
	 */
	boolean isUnder(int mouseX, int mouseY)
	{
		// The world it is drawn in: aboard the player's ship, the ship's own.
		WorldView wv = golem.isAboard() ? client.getWorldView(golem.getAboardView()) : client.getTopLevelWorldView();
		if (wv == null || reach < 0)
		{
			return false;
		}
		float[] across = BOX_ACROSS;
		float[] along = BOX_ALONG;
		float[] up = BOX_UP;
		int[] screenX = BOX_X;
		int[] screenY = BOX_Y;
		for (int i = 0; i < 8; i++)
		{
			across[i] = (i & 1) == 0 ? -reach : reach;
			along[i] = (i & 2) == 0 ? -reach : reach;
			up[i] = i < 4 ? lowest : highest;
		}
		Perspective.modelToCanvas(client, wv, 8, getX(), getY(), getZ(), 0, across, along, up, screenX, screenY);
		int left = Integer.MAX_VALUE;
		int right = Integer.MIN_VALUE;
		int top = Integer.MAX_VALUE;
		int bottom = Integer.MIN_VALUE;
		boolean whole = true;
		for (int i = 0; i < 8; i++)
		{
			whole &= screenX[i] != Integer.MIN_VALUE && screenY[i] != Integer.MIN_VALUE;
			left = Math.min(left, screenX[i]);
			right = Math.max(right, screenX[i]);
			top = Math.min(top, screenY[i]);
			bottom = Math.max(bottom, screenY[i]);
		}
		// The game lets a face out by this many pixels each way; a corner behind the camera bounds
		// nothing, so the faces are asked.
		if (whole && (mouseX < left - FACE_MARGIN || mouseX > right + FACE_MARGIN
			|| mouseY < top - FACE_MARGIN || mouseY > bottom + FACE_MARGIN))
		{
			return false;
		}
		try
		{
			Shape box = Perspective.getClickbox(client, wv, getModel(), getOrientation(), getX(), getY(), getZ());
			return box != null && box.contains(mouseX, mouseY);
		}
		catch (RuntimeException e)
		{
			// Degenerate geometry; not being hoverable for a frame is not worth propagating.
			return false;
		}
	}

	/** The bounds' corners and where they are drawn, shared: every golem is tested on the client thread. */
	private static final float[] BOX_ACROSS = new float[8];
	private static final float[] BOX_ALONG = new float[8];
	private static final float[] BOX_UP = new float[8];
	private static final int[] BOX_X = new int[8];
	private static final int[] BOX_Y = new int[8];

	/** Pixels the game lets each face out by when picking. See Perspective.getClickbox. */
	private static final int FACE_MARGIN = 5;

	/** Copies the simulation's position and heading onto the drawn object. */
	private void syncTransform()
	{
		if (golem.isAboard())
		{
			syncAboard();
			return;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}

		// Drawn where the golem appears to the player, which inside an instance is not where
		// it is simulated. See Golem.setDrawOffset.
		int localX = golem.getDrawFineX() - wv.getBaseX() * TILE;
		int localY = golem.getDrawFineY() - wv.getBaseY() * TILE;

		setX(localX);
		setY(localY);
		setWorldView(wv.getId());
		setLevel(golem.getDrawPlane());
		// A golem in a crew looks out over the side it stands on; everyone else faces where it is
		// going. See Golem.drawOrientation.
		setOrientation(golem.drawOrientation());

		// Ground height is re-read as the golem moves: Wyrmscraig is not flat, and a golem
		// holding its spawn height would sink into a rise.
		if (Golem.isInScene(wv, localX, localY))
		{
			// Terrain height plus whatever the golem is doing above it - mid-jump an arc, so
			// a hop leaves the ground instead of sliding at ankle height.
			// And at sea, riding the swell with the boat under it, as golems on the player's ship
			// ride that ship.
			int swell = golem.isAfloat() || golem.isCrewed() ? FakeRaft.bob(client.getGameCycle(), golem.getBoatSeed()) : 0;
			setZ(Perspective.getTileHeight(client, new LocalPoint(localX, localY, wv), golem.getDrawPlane())
				- golem.jumpArc() - golem.deckLift() + swell);
		}
	}

	/**
	 * Aboard the player's ship: on the deck, in the ship's own world, which the client moves and
	 * turns with the boat. Height is the deck's, which that world knows.
	 */
	private void syncAboard()
	{
		LocalPoint deck = golem.drawnPoint(client);
		if (deck == null)
		{
			return;
		}
		setX(deck.getX());
		setY(deck.getY());
		setWorldView(deck.getWorldView());
		setLevel(golem.drawnLevel());
		setOrientation(golem.drawOrientation());
		setZ(Perspective.getTileHeight(client, deck, golem.drawnLevel()));
	}

	/**
	 * The next dance move, when one finishes.
	 *
	 * <p>Set here rather than left to {@link #applyPose()}, which only acts when the wanted
	 * animation changes: the next move may be the one that just finished, and the golem would
	 * stop on its last frame.
	 *
	 * <p>Only when what finished was the move itself. A golem dancing when it starts to crumble
	 * is playing its death animation, and that ending is not a cue to dance again. When the dance
	 * is over, the move's end is where the golem stops dancing: see Golem.danceMoveEnded.
	 */
	private void poseFinished(AnimationController controller)
	{
		GolemDance move = golem.getDanceMove();
		if (move == null || loadedAnimationId != move.getAnimationId())
		{
			controller.loop();
			return;
		}

		// The move has ended. The dance being over, the golem is free to go; until now it held
		// still to finish what it had started.
		if (!golem.isDancing())
		{
			golem.danceMoveEnded();
			loadedAnimationId = golem.currentPoseAnimation();
			controller.setAnimation(shared.animationFor(loadedAnimationId));
			return;
		}

		golem.nextDanceMove();
		loadedAnimationId = golem.currentPoseAnimation();
		controller.setAnimation(shared.animationFor(loadedAnimationId));
	}

	/** Switches between the walk and idle loops as the simulation changes its mind. */
	private void applyPose()
	{
		int wanted = golem.currentPoseAnimation();
		if (wanted != loadedAnimationId || animation.getAnimation() == null)
		{
			loadedAnimationId = wanted;
			animation.setAnimation(shared.animationFor(wanted));
		}

		// While performing a recording, the keyframe is the player's own, set directly.
		// Advancing the clip by elapsed cycles at its authored speed is wrong: on a basalt
		// stone the player holds keyframe one for about twenty-five cycles then runs the other
		// seven during the jump, where the authored clip takes forty - golems finished hopping
		// before leaving the ground.
		//
		// Never past the last keyframe: the controller nulls a completed one-shot, and the
		// next frame would load and play it again.
		int frame = golem.getMotionFrame();
		Animation loaded = animation.getAnimation();
		if (frame >= 0 && loaded != null && loaded.getNumFrames() > 0)
		{
			animation.setFrame(Math.min(frame, loaded.getNumFrames() - 1));
		}
	}
}
