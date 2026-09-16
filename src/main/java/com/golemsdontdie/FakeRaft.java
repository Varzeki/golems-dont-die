package com.golemsdontdie;

import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.RuneLiteObjectController;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

/**
 * Draws the boat a golem crosses the ocean in.
 *
 * <p>A drawn prop, under the plugin's complete control. No game physics apply to it and
 * nothing about it is real: {@code WorldEntity} — the thing the game's own boats are — is
 * read-only to a plugin, so a plugin boat is a model at a position, exactly as a fake
 * golem is.
 *
 * <p>Two things differ from {@link FakeGolem}:
 *
 * <ul>
 *   <li><b>A slower turn.</b> A boat that pivots at a golem's turn rate reads as a sliding
 *       crate. It comes round gradually, and its heading leads its motion.</li>
 *   <li><b>Its own animation.</b> The boat's idle is on the boat's rig, which is precisely
 *       why the golem cannot play it and the boat can.</li>
 * </ul>
 *
 * <p>Its height is the ground under it, the same as the golem's, so the golem stands in the
 * boat rather than above or below it. This used to be a constant zero on the grounds that
 * the terrain under the sea is seabed — never checked in game, and it would have put the
 * golem and its boat at different heights whichever was true.
 */
class FakeRaft extends RuneLiteObjectController
{
	/** Orientation units per cycle. A third of a golem's, so the boat comes about slowly. */
	private static final int TURN_PER_CYCLE = 8;

	private final Client client;
	private final Model model;
	private final AnimationController animation;

	/** Where the boat is, in the same 128ths-of-a-tile world units the golems use. */
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
		this.animation.setAnimation(shared.animationFor(GolemContent.RAFT_ANIM));

		// A boat covers several tiles, so the ground under its footprint has to be drawn
		// before it or the hull z-fights with the water at its edges.
		setRadius(3 * 64);
		setDrawFrontTilesFirst(true);
		syncTransform();
	}

	/** Moves the boat to a world position, easing the heading toward where it is going. */
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

		int localX = fineX - wv.getBaseX() * Golem.TILE;
		int localY = fineY - wv.getBaseY() * Golem.TILE;
		setX(localX);
		setY(localY);
		setWorldView(wv.getId());
		setLevel(0);
		setOrientation(orientation);
		if (Golem.isInScene(wv, localX, localY))
		{
			setZ(Perspective.getTileHeight(client, new LocalPoint(localX, localY, wv), 0));
		}
	}
}
