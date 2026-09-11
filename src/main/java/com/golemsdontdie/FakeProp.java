package com.golemsdontdie;

import lombok.Getter;
import net.runelite.api.Animation;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.RuneLiteObjectController;
import net.runelite.api.WorldView;

/**
 * A one-shot animated object played beside a golem that is using something.
 *
 * <p>Some of the game's transport animations belong to the scenery rather than the person:
 * {@code DOCK_GANGPLANK01} is on framemap 2503 and the fairy ring's spin is the ring's own,
 * so neither can be played on the golem's human rig at all. They can be played on a copy of
 * the object itself, which is what this is — the plank lowering, the ring turning, drawn for
 * as long as the golem is interacting and then dropped.
 *
 * <p>Deliberately fire-and-forget. It owns no intention and is never reused: a prop is
 * created when a golem starts using something, plays once, and is unregistered when
 * {@link #isFinished()} says the clip has run out. Holding them open would mean tracking
 * scenery state across a scene reload for something that lasts under a second.
 *
 * <p>Nothing is sent anywhere and the real object is untouched — the copy is drawn on top
 * of wherever the real one stands, visible only to the player running the plugin.
 */
class FakeProp extends RuneLiteObjectController
{
	private final Client client;
	private final Model model;
	private final AnimationController animation;

	/** World position in 128ths of a tile, matching the golems. */
	private final int fineX;
	private final int fineY;
	private final int plane;
	private final int height;

	/** Cycles left before this prop should be dropped. */
	private int remaining;

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

		setRadius(80);
		setDrawFrontTilesFirst(true);
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

	/** True once the clip has run its course and the prop should be unregistered. */
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
	}
}
