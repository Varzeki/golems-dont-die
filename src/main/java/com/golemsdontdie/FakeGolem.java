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
 * <p>This is deliberately thin: it owns no intentions and decides nothing about
 * where the golem goes. It reads the simulation's position each frame and poses the
 * model to match. A golem that leaves the scene loses its renderer and keeps walking;
 * one that comes back gets a new one.
 *
 * <p>The pose is applied here rather than borrowed from a real NPC. Copying a posed
 * model — the trick the Player Owned Island plugin uses for the player — needs an
 * original that is still alive and still being posed every frame. There isn't one:
 * the golem this stands in for is dead. So the base model is the cache's rest pose
 * and the animation is driven from an {@link AnimationController} of our own, which
 * is also what lets a golem keep walking indefinitely rather than for as long as
 * something else animates it.
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
	 * Drives the walk or idle loop. A single controller rather than the client's
	 * active-plus-pose pair, because a wandering golem only ever plays one thing at
	 * a time — there is no attack or emote to layer over the gait.
	 */
	private final AnimationController animation;

	/** Which animation ID the controller currently holds, to avoid resetting it every frame. */
	private int loadedAnimationId = Integer.MIN_VALUE;

	/**
	 * Where each drawn frame of a traversal is reported, or null.
	 *
	 * <p>Here rather than in the simulation because this is what is actually on screen:
	 * the position after the renderer has copied it, and the keyframe the controller is
	 * really on, which is not necessarily the one the simulation asked for.
	 */
	private java.util.function.Consumer<String> trace;

	/** Whether the last traced frame was mid-traversal. */
	private boolean wasTraversing;

	FakeGolem(Client client, Golem golem, Model baseModel, GolemModelFactory shared)
	{
		this.client = client;
		this.golem = golem;
		this.baseModel = baseModel;
		this.shared = shared;
		this.animation = new AnimationController(client, -1);

		// A single-tile object is drawn correctly by the default radius; a bigger
		// golem needs the tiles under its footprint drawn first or it will z-fight
		// with the ground at its edges.
		int size = Math.max(1, golem.getSnapshot().getSize());
		setRadius(size * 64 - 4);
		setDrawFrontTilesFirst(true);

		syncTransform();
		applyPose();
	}

	void setTrace(java.util.function.Consumer<String> trace)
	{
		this.trace = trace;
	}

	/**
	 * Called by the client once per frame while registered.
	 *
	 * <p>Only the animation is advanced here. Position is advanced centrally by the
	 * plugin, for every golem including the ones off screen — if movement happened
	 * here, a golem would freeze the moment it left the scene and never walk back in.
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

		// One frame past the end as well, so the landing is on record. A door teleports on
		// the frame its transition ends, and stopping the trace there left every door's
		// last logged position halfway across.
		boolean traversing = golem.inTransition();
		boolean landed = wasTraversing && !traversing;
		wasTraversing = traversing;
		if (trace != null && (traversing || landed))
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
		// Only asked for when the client actually draws the object. Recorded so that a golem
		// registered but not drawn — culled, or one too many on a crowded tile — shows in the
		// journal rather than only as flicker on screen.
		lastDrawnCycle = client.getGameCycle();
		if (animation.getAnimation() == null)
		{
			return baseModel;
		}
		Model posed = animation.animate(baseModel);
		return posed == null ? baseModel : posed;
	}

	/**
	 * The shape the mouse has to be inside for this golem to be hovered.
	 *
	 * <p>A {@link RuneLiteObjectController} is drawn but not clickable — the client
	 * builds its menu from real entities, and a client-side object is not one. So the
	 * hit test has to be done by hand.
	 *
	 * <p>Tested against the <b>rest pose</b>, not the posed model. Using
	 * {@link #getModel()} here meant a full skeletal transform per golem per tick on
	 * top of projecting the geometry, which is what made the client stall whenever the
	 * cursor sat over the scene. It was also unsound: that model is a shared buffer the
	 * client reuses, and the API forbids holding it across another transformation —
	 * which is exactly what computing a clickbox from it does.
	 *
	 * <p>The cost of using the rest pose is that the box does not follow a swinging arm.
	 * For deciding whether the cursor is over a golem, that is not a cost worth paying
	 * a transform for.
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
			// Off-screen or degenerate geometry. Not being hoverable for a frame is
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

		// Ground height has to be re-read as the golem moves, not just when it is
		// placed: Wyrmscraig is not flat, and a golem holding its spawn height would
		// sink into a rise and float off a dip.
		if (Golem.isInScene(wv, localX, localY))
		{
			// Terrain height, plus whatever the golem is doing above it. Mid-jump that is
			// an arc, so a stepping-stone hop leaves the ground instead of sliding across
			// the water at ankle height.
			setZ(Perspective.getTileHeight(client, new LocalPoint(localX, localY, wv), golem.getDrawPlane())
				- golem.jumpArc());
		}
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
		//
		// Earlier versions advanced the clip by the number of cycles the recording had
		// run, at the clip's authored speed. That is not how the player's clip runs: on a
		// basalt stone the player holds the first keyframe for about twenty-five cycles
		// and then goes through the other seven during the jump, where the authored clip
		// is done in forty. So golems finished hopping before they had left the ground.
		// The recording now carries which keyframe the player was on at every sample, and
		// that is simply what is drawn.
		//
		// Never past the last keyframe: a one-shot that completes is nulled by the
		// controller, and the next frame would load it again and play it twice.
		int frame = golem.getMotionFrame();
		Animation loaded = animation.getAnimation();
		if (frame >= 0 && loaded != null && loaded.getNumFrames() > 0)
		{
			animation.setFrame(Math.min(frame, loaded.getNumFrames() - 1));
		}
	}
}
