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
import net.runelite.api.coords.WorldPoint;
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
 * <p>The client dots every NPC whose composition asks for one, and the golem's does; a
 * {@link net.runelite.api.RuneLiteObject} is not an NPC, so the copies showed as bare minimap
 * beside real golems. The dot is the game's own sprite, not an approximation: the real one is a
 * shaded circle, and two subtly different yellow dots draw the eye to the fakes. Drawing is
 * clipped to the minimap widget so a golem at the edge slides under the frame.
 */
class GolemMinimapOverlay extends Overlay
{
	/**
	 * Candidate minimap widgets, one per interface layout. Only one exists at a time, and which
	 * depends on settings this plugin does not read, so all four are tried.
	 */
	private static final int[] MINIMAP_WIDGETS = {
		InterfaceID.Toplevel.MINIMAP,
		InterfaceID.ToplevelOsrsStretch.MINIMAP,
		InterfaceID.ToplevelPreEoc.MINIMAP,
		InterfaceID.ToplevelOsm.MINIMAP,
	};

	/** How far the minimap reaches, in tiles, zoomed all the way out. Beyond this, skip the projection. */
	private static final int MINIMAP_RANGE_TILES = 40;

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
		List<Golem> golems = plugin.drawnGolems();
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
			// Lazily: the sprite cache may not be ready at start-up, and null means
			// trying again next frame.
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

		// Beyond the minimap's reach no projection can yield a dot, and a tile-distance test is
		// two subtractions against a matrix transform — this runs every frame with hundreds of
		// golems. Measured in the main world, aboard a boat too; see PlayerPosition.
		WorldPoint me = PlayerPosition.of(client);
		if (me == null)
		{
			return null;
		}
		int playerX = me.getX();
		int playerY = me.getY();

		Shape original = graphics.getClip();
		graphics.setClip(minimap);
		try
		{
			for (Golem golem : golems)
			{
				// Only golems in the scene: one that has wandered out is off the minimap
				// anyway, and its local coordinates would be meaningless. Where it is drawn, not
				// where it is simulated: inside an instance the two are rooms apart.
				if (golem.getRenderer() == null || golem.getDrawPlane() != wv.getPlane())
				{
					continue;
				}

				if (Math.abs(golem.getDrawFineX() / Golem.TILE - playerX) > MINIMAP_RANGE_TILES
					|| Math.abs(golem.getDrawFineY() / Golem.TILE - playerY) > MINIMAP_RANGE_TILES)
				{
					continue;
				}

				int localX = golem.getDrawFineX() - wv.getBaseX() * Golem.TILE;
				int localY = golem.getDrawFineY() - wv.getBaseY() * Golem.TILE;
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
	 * The on-screen rectangle of whichever minimap widget is live.
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
