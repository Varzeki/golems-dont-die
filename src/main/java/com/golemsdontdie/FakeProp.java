package com.golemsdontdie;

import lombok.*;
import net.runelite.api.*;

/**
 * A one-shot animated object played beside a golem that is using something.
 *
 * <p>Some transport animations belong to the scenery, not the person: {@code DOCK_GANGPLANK01}
 * is on framemap 2503 and the fairy ring's spin is the ring's own, so neither can play on the
 * golem's human rig. A copy of the object can, which is what this is.
 *
 * <p>Fire-and-forget and never reused; keeping props alive would mean tracking scenery state
 * across a scene reload for under a second of animation. The real object is untouched; the
 * copy is drawn over it, visible only to this player.
 */
class FakeProp extends RuneLiteObjectController
{
	private final Client client;
	private final Model model;
	private final AnimationController animation;

	/** World position in 128ths of a tile, as for golems. */
	private final int fineX;
	private final int fineY;
	private final int plane;
	private final int height;

	/** Cycles left before the prop is dropped. */
	private int remaining;

	/**
	 * Which way the prop faces, asked each frame: the golem's way for one the game plays on an actor,
	 * such as the air guitar's notes, which turned with the golem where the game plays them and
	 * stood facing south here, behind or beside the golem by turns.
	 */
	@lombok.Setter
	private java.util.function.IntSupplier facing = () -> 0;

	@Getter
	private final int animationId;

	FakeProp(Client client, Model model, Animation clip, int animationId,
		int fineX, int fineY, int plane, int height, int cycles)
	{
		this.client = client;
		this.model = model;
		this.animationId = animationId;
		this.fineX = fineX;
		this.fineY = fineY;
		this.plane = plane;
		this.height = height;
		this.remaining = cycles;

		this.animation = new AnimationController(client, -1);
		this.animation.setAnimation(clip);

		syncTransform();
	}

	@Override
	public void tick(int ticksSinceLastFrame)
	{
		remaining -= ticksSinceLastFrame;
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

	/** True once the clip has run out and the prop should be unregistered. */
	boolean isFinished()
	{
		return remaining <= 0;
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
		setLevel(plane);
		setZ(height);
		setOrientation(facing.getAsInt());
	}
}
