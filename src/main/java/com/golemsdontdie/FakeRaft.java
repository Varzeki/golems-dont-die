package com.golemsdontdie;

import java.util.*;
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

	/** The mast and the sail, drawn apart so each can play its own animation; see FakeRigPart. */
	private final List<FakeRigPart> parts = new ArrayList<>();

	/** How far the boat rises and falls on the swell, in height units, and how long a swell takes. */
	private static final int BOB_HEIGHT = 4;
	private static final int BOB_CYCLES = 160;

	/** Where on the swell this boat is, so a fleet does not rise and fall as one. */
	private final int bobPhase;

	/** The radius the parts share with the hull; see GolemBoat.drawRadius. */
	private final int radius;

	FakeRaft(Client client, Model model, GolemModelFactory shared, int fineX, int fineY,
		int orientation, int radius)
	{
		this.client = client;
		this.model = model;
		this.fineX = fineX;
		this.fineY = fineY;
		this.orientation = orientation;
		this.targetOrientation = orientation;
		this.radius = radius;
		this.bobPhase = Math.floorMod(fineX * 31 + fineY * 17, BOB_CYCLES);

		// The raft's models carry no rig, so it plays nothing; the golem at the helm does.
		this.animation = new AnimationController(client, -1);

		// A boat covers several tiles; ground under its footprint must draw first or the hull
		// z-fights with the water. How many tiles is the boat's own business — see
		// GolemBoat.drawRadius.
		setRadius(radius);
		setDrawFrontTilesFirst(true);
		syncTransform();
	}

	/**
	 * Adds a part of the rig: a model drawn at an offset from the middle of the hull, across then
	 * along, turning with the boat and playing an animation of its own.
	 */
	void addPart(Model model, int animation, int acrossOffset, int alongOffset)
	{
		if (model != null)
		{
			parts.add(new FakeRigPart(client, this, model, animation, acrossOffset, alongOffset, radius));
		}
	}

	/** Puts the boat and its rig in the scene. */
	void attach()
	{
		client.registerRuneLiteObject(this);
		for (FakeRigPart part : parts)
		{
			client.registerRuneLiteObject(part);
		}
	}

	/** Takes the boat and its rig out of the scene. */
	void detach()
	{
		client.removeRuneLiteObject(this);
		for (FakeRigPart part : parts)
		{
			client.removeRuneLiteObject(part);
		}
	}

	int getBoatOrientation()
	{
		return orientation;
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
		// A little up and down on the swell, as the game's own boats sit on the water.
		int bob = (int) Math.round(Math.sin((client.getGameCycle() + bobPhase) * 2 * Math.PI / BOB_CYCLES) * BOB_HEIGHT);
		if (groundZ != Integer.MIN_VALUE)
		{
			setZ(groundZ + bob);
		}
		else if (Golem.isInScene(wv, localX, localY))
		{
			setZ(Perspective.getTileHeight(client, new LocalPoint(localX, localY, wv), 0) + bob);
		}
	}
}
