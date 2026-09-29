package com.golemsdontdie;

import net.runelite.api.*;

/**
 * A part of a golem boat's rig, the mast or the sail, drawn apart from the hull and following it.
 *
 * <p>Apart because each plays an animation of its own, the sail full and moving in the wind, and an
 * animation is a set of transforms about the model's own groups: played on a hull merged with it, it
 * would bend the hull. Placed each frame at its offset from the hull's middle, turned with the boat,
 * at the hull's height.
 */
class FakeRigPart extends RuneLiteObjectController
{
	private final Client client;
	private final FakeRaft boat;
	private final Model model;
	private final AnimationController animation;
	private final int across;
	private final int along;

	FakeRigPart(Client client, FakeRaft boat, Model model, int animation, int across, int along, int radius)
	{
		this.client = client;
		this.boat = boat;
		this.model = model;
		this.animation = new AnimationController(client, animation);
		this.across = across;
		this.along = along;
		setRadius(radius);
		setDrawFrontTilesFirst(true);
	}

	@Override
	public void tick(int ticksSinceLastFrame)
	{
		follow();
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

	/**
	 * Takes the boat's place, moved by the part's offset turned the way the boat is. The model is
	 * turned by the client as the hull is: x along x, z along y, both turned by the orientation.
	 */
	private void follow()
	{
		int orientation = boat.getBoatOrientation();
		double angle = orientation * Math.PI / 1024;
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		setX(boat.getX() + (int) Math.round(across * cos + along * sin));
		setY(boat.getY() + (int) Math.round(along * cos - across * sin));
		setZ(boat.getZ());
		setWorldView(boat.getWorldView());
		setLevel(boat.getLevel());
		setOrientation(orientation);
	}
}
