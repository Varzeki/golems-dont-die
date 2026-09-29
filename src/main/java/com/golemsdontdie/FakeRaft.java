package com.golemsdontdie;

import net.runelite.api.*;
import net.runelite.api.coords.*;

/**
 * Draws the boat a golem crosses the ocean in.
 *
 * <p>{@code WorldEntity} — what the game's own boats are — is read-only to a plugin, so this
 * is a drawn model at a position, as a fake golem is. It turns at a third of a golem's rate,
 * because a boat pivoting at a golem's rate reads as a sliding crate, and its heading leads
 * its motion.
 */
class FakeRaft extends RuneLiteObjectController
{
	/** Orientation units per cycle; a third of a golem's. */
	private static final int TURN_PER_CYCLE = 8;

	private final Client client;
	private final Model model;
	private final AnimationController animation;

	/** Where the boat is, in 128ths of a tile, as for golems. */
	private int fineX;
	private int fineY;

	private int orientation;
	private int targetOrientation;

	/**
	 * The ground height the boat sits at: the golem's, not the ground under the boat's own middle.
	 *
	 * <p>The raft's middle is a tile ahead of the golem and at sea the ground under water is
	 * seabed, so a raft on its own tile sank out of sight where that drops away, leaving the
	 * golem on nothing at the helm; one whose middle lay outside the scene got no height at all.
	 */
	private int groundZ = Integer.MIN_VALUE;

	FakeRaft(Client client, Model model, GolemModelFactory shared, int fineX, int fineY,
		int orientation, int radius)
	{
		this.client = client;
		this.model = model;
		this.fineX = fineX;
		this.fineY = fineY;
		this.orientation = orientation;
		this.targetOrientation = orientation;

		// The raft's models carry no rig, so it plays nothing; the golem at the helm does.
		this.animation = new AnimationController(client, -1);

		// A boat covers several tiles; ground under its footprint must draw first or the hull
		// z-fights with the water. How many tiles is the boat's own business — see
		// GolemBoat.drawRadius.
		setRadius(radius);
		setDrawFrontTilesFirst(true);
		syncTransform();
	}

	/** Moves the boat to a world position, easing the heading round. */
	void steer(int worldFineX, int worldFineY, int heading)
	{
		this.fineX = worldFineX;
		this.fineY = worldFineY;
		this.targetOrientation = heading & 2047;
	}

	/** As {@link #steer(int, int, int)}, at this ground height. */
	void steer(int worldFineX, int worldFineY, int heading, int groundZ)
	{
		steer(worldFineX, worldFineY, heading);
		this.groundZ = groundZ;
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

	/** Eases the heading round the short way, at a boat's rate. */
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
		if (groundZ != Integer.MIN_VALUE)
		{
			setZ(groundZ);
		}
		else if (Golem.isInScene(wv, localX, localY))
		{
			setZ(Perspective.getTileHeight(client, new LocalPoint(localX, localY, wv), 0));
		}
	}
}
