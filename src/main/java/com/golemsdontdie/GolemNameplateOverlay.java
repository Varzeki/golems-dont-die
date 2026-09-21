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

	@Inject
	GolemNameplateOverlay(Client client, GolemsDontDiePlugin plugin, GolemsDontDieConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
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
			String name = golem.getNickname();
			FakeGolem drawn = golem.getRenderer();
			if (name == null || name.trim().isEmpty() || drawn == null || golem.getDrawPlane() != wv.getPlane())
			{
				continue;
			}

			int localX = golem.getDrawFineX() - wv.getBaseX() * Golem.TILE;
			int localY = golem.getDrawFineY() - wv.getBaseY() * Golem.TILE;
			if (!Golem.isInScene(wv, localX, localY))
			{
				continue;
			}

			Point at = Perspective.getCanvasTextLocation(client, graphics, new LocalPoint(localX, localY, wv), name,
				drawn.getModelHeight() + NAME_GAP + golem.jumpArc() + golem.deckLift());
			if (at != null)
			{
				OverlayUtil.renderTextLocation(graphics, at, name, colour);
			}
		}
		return null;
	}
}
