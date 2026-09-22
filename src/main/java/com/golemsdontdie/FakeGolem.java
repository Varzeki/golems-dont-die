package com.golemsdontdie;

import net.runelite.api.Animation;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.RuneLiteObjectController;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

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

	/** Source of the shared model and animation definitions. */
	private final GolemModelFactory shared;

	/**
	 * Drives the walk or idle loop. One controller, not the client's active-plus-pose pair:
	 * a wandering golem only ever plays one thing at a time.
	 */
	private final AnimationController animation;

	/** Which animation ID the controller currently holds, to avoid resetting it every frame. */
	private int loadedAnimationId = Integer.MIN_VALUE;

	/**
	 * Where each drawn frame of a traversal is reported, or null. Here rather than in the
	 * simulation because this is what is on screen: the position after the renderer copied it.
	 */
	private java.util.function.Consumer<String> trace;

	/**
	 * Whether anyone is recording. A golem mid-obstacle otherwise built a dozen strings every
	 * frame for nothing to read.
	 */
	private java.util.function.BooleanSupplier tracing = () -> true;

	/** How tall the golem is drawn, in the client's height units, for putting things above its head. */
	int getModelHeight()
	{
		return baseModel.getModelHeight();
	}

	/** Whether the last traced frame was mid-traversal. */
	private boolean wasTraversing;

	FakeGolem(Client client, Golem golem, Model baseModel, GolemModelFactory shared)
	{
		this.client = client;
		this.golem = golem;
		this.baseModel = baseModel;
		this.shared = shared;
		this.animation = new AnimationController(client, -1);
		this.animation.setOnFinished(this::poseFinished);

		// The default radius suits a single-tile object; a bigger golem needs the tiles under
		// its footprint drawn first or it z-fights with the ground.
		int size = Math.max(1, golem.getSnapshot().getSize());
		setRadius(size * 64 - 4);
		setDrawFrontTilesFirst(true);

		syncTransform();
		applyPose();
	}

	void setTrace(java.util.function.Consumer<String> trace, java.util.function.BooleanSupplier tracing)
	{
		this.trace = trace;
		this.tracing = tracing;
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
		// advance them — that was the drift.
		if (golem.getMotionFrame() < 0)
		{
			animation.tick(ticksSinceLastFrame);
		}

		// One frame past the end too: a door teleports on the frame its transition ends, so
		// stopping there logged its last position halfway across.
		boolean traversing = golem.inTransition();
		boolean landed = wasTraversing && !traversing;
		wasTraversing = traversing;
		if (trace != null && (traversing || landed) && tracing.getAsBoolean())
		{
			Animation playing = animation.getAnimation();
			trace.accept("fine=" + golem.getFineX() + "," + golem.getFineY()
				+ " drawn=" + getX() + "," + getY()
				+ " orient=" + golem.getOrientation()
				+ " anim=" + loadedAnimationId
				+ " keyframe=" + (playing == null ? -1 : animation.getFrame())
				+ " keyframes=" + (playing == null ? -1 : playing.getNumFrames())
				+ " motionFrame=" + golem.getMotionFrame());
		}
	}

	/** The client cycle this golem was last asked for a model, which is when it was drawn. */
	private int lastDrawnCycle = -1;

	int getLastDrawnCycle()
	{
		return lastDrawnCycle;
	}

	@Override
	public Model getModel()
	{
		// Only asked for when the client actually draws the object, so a golem registered but
		// not drawn shows in the journal.
		lastDrawnCycle = client.getGameCycle();
		if (animation.getAnimation() == null)
		{
			return baseModel;
		}
		Model posed = animation.animate(baseModel);
		return posed == null ? baseModel : posed;
	}

	/**
	 * The shape the mouse has to be inside for this golem to be hovered. A
	 * {@link RuneLiteObjectController} is drawn but not clickable, the client building its menu
	 * from real entities, so the hit test is done by hand.
	 *
	 * <p>Tested against the <b>rest pose</b>, so the box ignores a swinging arm:
	 * {@link #getModel()} meant a skeletal transform per golem per tick on top of the
	 * projection, which stalled the client whenever the cursor sat over the scene, and was
	 * unsound, that model being a shared buffer the API forbids holding across another
	 * transformation.
	 *
	 * @return the clickbox, or null if it cannot be computed this frame
	 */
	java.awt.Shape clickbox()
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null || baseModel == null)
		{
			return null;
		}
		try
		{
			return Perspective.getClickbox(client, wv, baseModel, getOrientation(), getX(), getY(), getZ());
		}
		catch (RuntimeException e)
		{
			// Off-screen or degenerate geometry; not being hoverable for a frame is
			// not worth propagating.
			return null;
		}
	}

	/** Copies the simulation's position and heading onto the drawn object. */
	private void syncTransform()
	{
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
		setOrientation(golem.getOrientation());

		// Ground height is re-read as the golem moves: Wyrmscraig is not flat, and a golem
		// holding its spawn height would sink into a rise.
		if (Golem.isInScene(wv, localX, localY))
		{
			// Terrain height plus whatever the golem is doing above it — mid-jump an arc, so
			// a hop leaves the ground instead of sliding at ankle height.
			setZ(Perspective.getTileHeight(client, new LocalPoint(localX, localY, wv), golem.getDrawPlane())
				- golem.jumpArc() - golem.deckLift());
		}
	}

	/**
	 * The next dance move, when one finishes.
	 *
	 * <p>Set here rather than left to {@link #applyPose()}, which only acts when the wanted
	 * animation changes: the next move may be the one that just finished, and the golem would
	 * stop on its last frame.
	 *
	 * <p>Only when what finished was the move itself. A golem dancing when it starts to crumble
	 * is playing its death animation, and that ending is not a cue to dance again.
	 */
	private void poseFinished(AnimationController controller)
	{
		GolemDance move = golem.getDanceMove();
		if (!golem.isDancing() || move == null || loadedAnimationId != move.getAnimationId())
		{
			controller.loop();
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
		// seven during the jump, where the authored clip takes forty — golems finished hopping
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
