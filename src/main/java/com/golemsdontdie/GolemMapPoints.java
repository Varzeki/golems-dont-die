package com.golemsdontdie;

import java.awt.image.BufferedImage;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import net.runelite.client.util.ImageUtil;

/**
 * Golems on the world map, each a face that says who it is when the mouse is over it.
 *
 * <p>Every golem can be shown at once — a thousand of them — so the work per golem has to be almost
 * nothing. Two things make that true. A point is moved rather than remade, and only when its golem
 * has changed tile; and the tooltip is worked out when it is asked for, which the map overlay only
 * does for the one point under the mouse. Nothing is drawn on the map but the faces.
 */
@Slf4j
@Singleton
class GolemMapPoints
{
	/**
	 * A guard against the absurd rather than a design limit: a player with tens of thousands of
	 * golems gets the first few thousand on the map.
	 */
	private static final int MOST_POINTS = 4000;

	@Inject
	private Client client;

	@Inject
	private WorldMapPointManager mapPoints;

	@Inject
	private Whereabouts whereabouts;

	private BufferedImage face;

	/** The point showing each golem, by golem. */
	private final Map<Golem, GolemPoint> points = new IdentityHashMap<>();

	/** The tick the roster was last looked at, for the tooltips to date themselves by. */
	private int tick;

	void startUp()
	{
		face = ImageUtil.loadImageResource(GolemsDontDiePlugin.class, "/golem-map-icon.png");
	}

	/** Takes every golem off the map. */
	void clear()
	{
		for (WorldMapPoint point : points.values())
		{
			mapPoints.remove(point);
		}
		points.clear();
	}

	/**
	 * Brings the map up to date with where the golems are.
	 *
	 * <p>Only while the map is open, and only golems it could draw: the overlay walks every point it
	 * holds on every frame, looking up a widget and measuring it for each one, so a point that is
	 * underground, or off the edge of what is on screen, costs the same as one you can see and shows
	 * nothing. Closed, the map holds no golems at all.
	 *
	 * @param named true to show only golems with names
	 */
	void refresh(List<Golem> golems, boolean named, int tick)
	{
		if (face == null)
		{
			return;
		}
		this.tick = tick;

		int[] window = visibleTiles();
		if (window == null)
		{
			clear();
			return;
		}

		java.util.Set<Golem> wanted = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		int shown = 0;
		for (Golem golem : golems)
		{
			if (shown >= MOST_POINTS || named && !isNamed(golem))
			{
				continue;
			}

			// A golem on a crossing is drawn at the port it is bound for: a raft mid-ocean is
			// nowhere anybody can look for it, and the map would show it adrift on open water.
			WorldPoint where = golem.isSailing(tick) ? golem.saveTile() : golem.currentTile();
			// The map draws the surface only, and only the part of it on screen.
			if (where.getY() >= UNDERGROUND || where.getX() < window[0] || where.getX() > window[2]
				|| where.getY() < window[1] || where.getY() > window[3])
			{
				continue;
			}
			shown++;
			wanted.add(golem);

			GolemPoint point = points.get(golem);
			if (point == null)
			{
				point = new GolemPoint(golem, where, face);
				points.put(golem, point);
				mapPoints.add(point);
			}
			else if (!where.equals(point.getWorldPoint()))
			{
				point.setWorldPoint(where);
			}
		}

		points.entrySet().removeIf(entry ->
		{
			if (wanted.contains(entry.getKey()))
			{
				return false;
			}
			mapPoints.remove(entry.getValue());
			return true;
		});
	}

	/** Coordinates this far north are underground, which the world map does not draw. */
	private static final int UNDERGROUND = 4160;

	/** Tiles kept beyond the edge of the map, so a golem walking into view is already there. */
	private static final int MARGIN = 16;

	/**
	 * The stretch of the world on screen, as {minX, minY, maxX, maxY}, or null if the map is closed.
	 */
	private int[] visibleTiles()
	{
		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		WorldMap worldMap = client.getWorldMap();
		if (map == null || map.isHidden() || worldMap == null)
		{
			return null;
		}
		float pixelsPerTile = worldMap.getWorldMapZoom();
		if (pixelsPerTile <= 0)
		{
			return null;
		}
		Point centre = worldMap.getWorldMapPosition();
		int halfWidth = (int) Math.ceil(map.getBounds().getWidth() / pixelsPerTile / 2) + MARGIN;
		int halfHeight = (int) Math.ceil(map.getBounds().getHeight() / pixelsPerTile / 2) + MARGIN;
		return new int[]{centre.getX() - halfWidth, centre.getY() - halfHeight,
			centre.getX() + halfWidth, centre.getY() + halfHeight};
	}

	private static boolean isNamed(Golem golem)
	{
		return golem.getNickname() != null && !golem.getNickname().isEmpty();
	}

	/** One golem's face on the map, which says who it is only when asked. */
	private final class GolemPoint extends WorldMapPoint
	{
		private final Golem golem;

		private GolemPoint(Golem golem, WorldPoint at, BufferedImage face)
		{
			super(at, face);
			this.golem = golem;
			setName(isNamed(golem) ? golem.getNickname() : "Golem");
			// The map offers "Focus on" for a point it may jump to, which is worth having on a
			// golem halfway across the world.
			setJumpOnClick(true);
		}

		/**
		 * Asked by the map overlay for the point under the mouse, and only that one, so the work of
		 * saying where a golem is happens once rather than a thousand times a frame.
		 */
		@Override
		public String getTooltip()
		{
			return getName() + " — " + whereabouts.of(golem, tick);
		}
	}
}
