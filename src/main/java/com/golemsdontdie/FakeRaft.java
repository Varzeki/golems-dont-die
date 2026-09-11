package com.golemsdontdie;

import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.RuneLiteObjectController;
import net.runelite.api.WorldView;

/**
 * Draws the raft a golem crosses the ocean on.
 *
 * <p>A drawn prop, under the plugin's complete control. No game physics apply to it and
 * nothing about it is real: {@code WorldEntity} — the thing the game's own boats are — is
 * read-only to a plugin, so a plugin boat is a model at a position, exactly as a fake
 * golem is.
 *
 * <p>Three things differ from {@link FakeGolem}, and each is the fix for something that
 * would otherwise look obviously wrong:
 *
 * <ul>
 *   <li><b>A fixed water height.</b> {@code Perspective.getTileHeight} samples the terrain
 *       under a tile, and the terrain under the sea is seabed. Asking it for a height out
 *       on the ocean returns nonsense, so the raft sits at a constant instead.</li>
 *   <li><b>A slower turn.</b> A boat that pivots at a golem's turn rate reads as a sliding
 *       crate. It comes round gradually, and its heading leads its motion.</li>
 *   <li><b>Its own animation.</b> The helm clips are on framemap 2486 — the boat's rig,
 *       not the human one — which is precisely why the golem cannot play them and the
 *       raft can.</li>
 * </ul>
 */
class FakeRaft extends RuneLiteObjectController
{
	/**
	 * Height the raft is drawn at, in the client's units.
	 *
	 * <p>Sea level is flat and the terrain beneath it is not, so this is a constant rather
	 * than anything sampled. Zero is the plane's own ground datum, which out on open water
	 * is the water surface.
	 */
	private static final int WATER_HEIGHT = 0;

	/** Orientation units per cycle. A third of a golem's, so the raft comes about slowly. */
	private static final int TURN_PER_CYCLE = 8;

	private final Client client;
	private final Model model;
	private final AnimationController animation;

	/** Where the raft is, in the same 128ths-of-a-tile world units the golems use. */
	private int fineX;
	private int fineY;

	private int orientation;
	private int targetOrientation;

	FakeRaft(Client client, Model model, GolemModelFactory shared, int fineX, int fineY,
		int orientation)
	{
		this.client = client;
		this.model = model;
		this.fineX = fineX;
		this.fineY = fineY;
		this.orientation = orientation;
		this.targetOrientation = orientation;

		this.animation = new AnimationController(client, -1);
		this.animation.setAnimation(shared.animationFor(GolemContent.ANIM_RAFT_HELM_LOOP));

		// A raft covers several tiles, so the ground under its footprint has to be drawn
		// before it or the hull z-fights with the water at its edges.
		setRadius(3 * 64);
		setDrawFrontTilesFirst(true);
		syncTransform();
	}

	/** Moves the raft to a world position, easing the heading toward where it is going. */
	void steer(int worldFineX, int worldFineY, int heading)
	{
		this.fineX = worldFineX;
		this.fineY = worldFineY;
		this.targetOrientation = heading & 2047;
	}

	@Override
	public void tick(int ticksSinceLastFrame)
	{
		turn(ticksSinceLastFrame);
		syncTransform();
		animation.tick(ticksSinceLastFrame);
	}

	@Override
	public Model getModel()
	{
		if (animation.getAnimation() == null)
		{
			return model;
		}
		Model posed = animation.animate(model);
		return posed == null ? model : posed;
	}

	/** Where a passenger should stand, so the golem rides the deck rather than the sea. */
	int getDeckX()
	{
		return fineX;
	}

	int getDeckY()
	{
		return fineY;
	}

	int getHeading()
	{
		return orientation;
	}

	/** Eases the heading round the short way, at a boat's rate rather than a golem's. */
	private void turn(int cycles)
	{
		if (orientation == targetOrientation || cycles <= 0)
		{
			return;
		}
		int delta = ((targetOrientation - orientation + 1024) & 2047) - 1024;
		int step = Math.min(Math.abs(delta), TURN_PER_CYCLE * cycles);
		orientation = (orientation + Integer.signum(delta) * step) & 2047;
	}

	private void syncTransform()
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}

		setX(fineX - wv.getBaseX() * Golem.TILE);
		setY(fineY - wv.getBaseY() * Golem.TILE);
		setWorldView(wv.getId());
		setLevel(0);
		setOrientation(orientation);
		setZ(WATER_HEIGHT);
	}
}
