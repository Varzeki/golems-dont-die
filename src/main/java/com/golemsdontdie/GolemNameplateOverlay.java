package com.golemsdontdie;

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Draws a named golem's name above its head, the way NPC highlighting names an NPC.
 *
 * <p>Only golems actually being drawn, so inside an instance the name follows where the golem
 * is drawn rather than where it is simulated. The name rises with the golem mid-hop.
 */
class GolemNameplateOverlay extends Overlay
{
	/** Space between the top of the golem and the bottom of its name, in height units. */
	private static final int NAME_GAP = 40;

	private final Client client;
	private final GolemsDontDiePlugin plugin;
	private final GolemsDontDieConfig config;

	/** What a golem is called: its own name, or the one the setting gives it. */
	private final GolemNames names;

	@Inject
	GolemNameplateOverlay(Client client, GolemsDontDiePlugin plugin, GolemsDontDieConfig config,
		GolemNames names)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.names = names;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showNameplates())
		{
			return null;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return null;
		}

		// Read once for the frame, not through the config proxy for every named golem.
		java.awt.Color colour = config.nameplateColour();
		for (Golem golem : plugin.drawnGolems())
		{
			String name = names.of(golem);
			FakeGolem drawn = golem.getRenderer();
			if (name == null || name.trim().isEmpty() || drawn == null
				|| !golem.isAboard() && golem.getDrawPlane() != wv.getPlane())
			{
				continue;
			}

			// Where it is drawn, which for a golem aboard the player's ship is on the ship's deck.
			LocalPoint drawnAt = golem.drawnPoint(client);
			if (drawnAt == null)
			{
				continue;
			}

			Point at = Perspective.getCanvasTextLocation(client, graphics, drawnAt, name,
				drawn.getModelHeight() + NAME_GAP + golem.jumpArc() + golem.deckLift());
			if (at != null)
			{
				OverlayUtil.renderTextLocation(graphics, at, name, colour);
			}
		}

		pointAtFound(graphics, wv);
		return null;
	}

	/** How far above a golem's head the arrow floats, and how far it bobs. */
	private static final int ARROW_GAP = 80;
	private static final int ARROW_BOB = 6;

	/** How wide and tall the arrow is drawn, in pixels. The game's own is about this size. */
	private static final int ARROW_WIDE = 30;
	private static final int ARROW_TALL = 26;

	/**
	 * Draws an arrow over the golem being looked for.
	 *
	 * <p>Drawn here rather than left to the game's hint arrow, which points at a tile and moves
	 * once a tick: over a walking golem that reads as an arrow trailing along behind it. This one
	 * is drawn at wherever the golem is this frame, which is where the golem looks.
	 */
	private void pointAtFound(Graphics2D graphics, net.runelite.api.WorldView wv)
	{
		Golem golem = plugin.getFinding();
		FakeGolem drawn = golem == null ? null : golem.getRenderer();
		if (drawn == null || !golem.isAboard() && golem.getDrawPlane() != wv.getPlane())
		{
			return;
		}
		LocalPoint drawnAt = golem.drawnPoint(client);
		if (drawnAt == null)
		{
			return;
		}

		int bob = (int) (Math.sin(System.currentTimeMillis() / 220.0) * ARROW_BOB);
		Point at = Perspective.localToCanvas(client, drawnAt,
			golem.drawnLevel(), drawn.getModelHeight() + ARROW_GAP + golem.jumpArc() + golem.deckLift());
		if (at == null)
		{
			return;
		}

		int x = at.getX();
		int y = at.getY() + bob;
		java.awt.Polygon arrow = new java.awt.Polygon(
			new int[]{x - ARROW_WIDE / 2, x + ARROW_WIDE / 2, x},
			new int[]{y - ARROW_TALL, y - ARROW_TALL, y}, 3);
		graphics.setColor(java.awt.Color.BLACK);
		graphics.setStroke(new java.awt.BasicStroke(4f));
		graphics.drawPolygon(arrow);
		graphics.setColor(ARROW_COLOUR);
		graphics.fillPolygon(arrow);
	}

	/** The game's own hint arrow yellow. */
	private static final java.awt.Color ARROW_COLOUR = new java.awt.Color(0xFFE700);
}
