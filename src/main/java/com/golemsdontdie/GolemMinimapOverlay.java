package com.golemsdontdie;

import java.awt.*;
import java.awt.image.*;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.widgets.*;
import net.runelite.client.game.*;
import net.runelite.client.ui.overlay.*;

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

	/**
	 * How far the minimap reaches, in tiles, zoomed all the way out. Beyond this, skip the
	 * projection; within it, the projection says whether the dot is on the minimap at this zoom.
	 */
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
		// two subtractions against a matrix transform - this runs every frame with hundreds of
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
				// where it is simulated: inside an instance the two are rooms apart, and a crew's
				// golems stand about the deck rather than all on the helm.
				if (golem.getRenderer() == null || golem.getDrawPlane() != wv.getPlane())
				{
					continue;
				}
				int fineX = golem.getDrawFineX();
				int fineY = golem.getDrawFineY();

				if (Math.abs(fineX / Golem.TILE - playerX) > MINIMAP_RANGE_TILES
					|| Math.abs(fineY / Golem.TILE - playerY) > MINIMAP_RANGE_TILES)
				{
					continue;
				}

				int localX = fineX - wv.getBaseX() * Golem.TILE;
				int localY = fineY - wv.getBaseY() * Golem.TILE;
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
