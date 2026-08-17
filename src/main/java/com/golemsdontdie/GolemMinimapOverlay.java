package com.golemsdontdie;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws the copies onto the minimap, the way a real golem is drawn.
 *
 * <p>The client puts a dot on the minimap for every NPC whose composition asks for
 * one, and the golem's does. A {@link net.runelite.api.RuneLiteObject} is not an NPC,
 * so it gets nothing — which left the real golems showing as yellow dots and the
 * copies beside them showing as bare minimap.
 *
 * <p>The dot is the game's own sprite, not an approximation of it. A hand-drawn square
 * is close enough to notice and not close enough to pass: the real dot is a shaded
 * circle, and two subtly different kinds of yellow dot on one minimap draw the eye
 * straight to the fake ones.
 *
 * <p>Drawing is clipped to the minimap widget so a golem at the edge slides under the
 * frame as a real one does, instead of being painted over it.
 */
class GolemMinimapOverlay extends Overlay
{
	/**
	 * Candidate minimap widgets, one per interface layout — fixed, the two resizable
	 * modes, and the stretched variant. Only one exists at a time; which one depends on
	 * settings this plugin has no business reading, so all four are tried.
	 */
	private static final int[] MINIMAP_WIDGETS = {
		InterfaceID.Toplevel.MINIMAP,
		InterfaceID.ToplevelOsrsStretch.MINIMAP,
		InterfaceID.ToplevelPreEoc.MINIMAP,
		InterfaceID.ToplevelOsm.MINIMAP,
	};

	/**
	 * How far the minimap reaches, in tiles, with a margin. Anything beyond this is
	 * rejected before the projection is attempted.
	 */
	private static final int MINIMAP_RANGE_TILES = 20;

	private final Client client;
	private final GolemsDontDiePlugin plugin;
	private final SpriteManager spriteManager;

	/** The game's yellow NPC dot, fetched once the sprite cache can supply it. */
	private BufferedImage dot;

	@Inject
	GolemMinimapOverlay(Client client, GolemsDontDiePlugin plugin, SpriteManager spriteManager)
	{
		this.client = client;
		this.plugin = plugin;
		this.spriteManager = spriteManager;
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(PRIORITY_LOW);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		List<Golem> golems = plugin.activeGolems();
		if (golems.isEmpty())
		{
			return null;
		}

		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return null;
		}

		if (dot == null)
		{
			// Asked for lazily: the sprite cache is not necessarily ready when the
			// plugin starts, and a null here simply means trying again next frame.
			dot = spriteManager.getSprite(SpriteID.MapdotsInterface.YELLOW_NPC, 0);
			if (dot == null)
			{
				return null;
			}
		}

		Rectangle minimap = minimapBounds();
		if (minimap == null)
		{
			return null;
		}

		// The minimap only reaches a short way, so a golem further out than that cannot
		// produce a dot however the projection is done. Rejecting on tile distance
		// first is two subtractions against a matrix transform, which matters when the
		// island holds hundreds of golems and this runs every frame.
		Player me = client.getLocalPlayer();
		if (me == null || me.getWorldLocation() == null)
		{
			return null;
		}
		int playerX = me.getWorldLocation().getX();
		int playerY = me.getWorldLocation().getY();

		Shape original = graphics.getClip();
		graphics.setClip(minimap);
		try
		{
			for (Golem golem : golems)
			{
				// Only golems actually being drawn in the scene. One that has wandered
				// out of it is off the minimap anyway, and its local coordinates would
				// be meaningless.
				if (golem.getRenderer() == null || golem.getPlane() != wv.getPlane())
				{
					continue;
				}

				if (Math.abs(golem.getFineX() / Golem.TILE - playerX) > MINIMAP_RANGE_TILES
					|| Math.abs(golem.getFineY() / Golem.TILE - playerY) > MINIMAP_RANGE_TILES)
				{
					continue;
				}

				int localX = golem.getFineX() - wv.getBaseX() * Golem.TILE;
				int localY = golem.getFineY() - wv.getBaseY() * Golem.TILE;
				if (!Golem.isInScene(wv, localX, localY))
				{
					continue;
				}

				Point at = Perspective.localToMinimap(client, new LocalPoint(localX, localY, wv));
				if (at == null)
				{
					continue;
				}

				// Centred on the point, matching how the client places its own dots.
				graphics.drawImage(dot,
					at.getX() - dot.getWidth() / 2,
					at.getY() - dot.getHeight() / 2,
					null);
			}
		}
		finally
		{
			graphics.setClip(original);
		}

		return null;
	}

	/**
	 * The on-screen rectangle of whichever minimap widget is currently live.
	 *
	 * @return the bounds, or null if no minimap is showing
	 */
	private Rectangle minimapBounds()
	{
		for (int id : MINIMAP_WIDGETS)
		{
			Widget widget = client.getWidget(id);
			if (widget != null && !widget.isHidden())
			{
				return widget.getBounds();
			}
		}
		return null;
	}
}
