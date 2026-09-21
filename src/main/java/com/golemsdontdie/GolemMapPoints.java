package com.golemsdontdie;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
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
 * Golems on the world map: all of them, as faces that say who is there when hovered.
 *
 * <p>A face per golem does not scale — a player who has crafted for months has ten thousand, and the
 * map overlay looks up a widget and measures it for every point it holds, on every frame the map is
 * open. So golems are gathered by where they would be drawn: the map is cut into cells the size of
 * one face at the current zoom, and each cell holding golems gets one point saying how many. Zoom in
 * and the cells shrink until each golem has a face of its own; zoom out and a crowd becomes one face
 * with a number. Either way the map holds about as many points as there are places to put them.
 *
 * <p>Only while the map is open, and only for the stretch on screen. Which map that is does not
 * matter: the coordinates of a golem underground fall inside the layer drawn when that layer is the
 * one being looked at, so golems appear on a dungeon map as readily as on the surface. Closed, the
 * map holds nothing and this costs one widget lookup a tick.
 */
@Slf4j
@Singleton
class GolemMapPoints
{
	/** Points held at most. The screen has nowhere to put more faces than this without overlap. */
	private static final int MOST_POINTS = 600;

	/** Tiles kept beyond the edge of the map, so a golem walking into view is already there. */
	private static final int MARGIN = 16;

	/** The face's size on screen, in pixels, which sets how far apart two faces must be. */
	private static final int FACE_PIXELS = 15;

	/** Names listed on a crowded face's tooltip before it counts instead. */
	private static final int NAMES_SHOWN = 3;

	@Inject
	private Client client;

	@Inject
	private WorldMapPointManager mapPoints;

	@Inject
	private Whereabouts whereabouts;

	private BufferedImage face;

	/** Cell key to its place in {@link #cellList}. Reused between refreshes rather than rebuilt. */
	private final TileMap cells = new TileMap(256);
	private final List<Cell> cellList = new ArrayList<>();

	/** The points on the map, one per filled cell, in step with the first {@link #used} cells. */
	private final List<CellPoint> shown = new ArrayList<>();

	private int used;
	private int tick;

	void startUp()
	{
		face = ImageUtil.loadImageResource(GolemsDontDiePlugin.class, "/golem-map-icon.png");
	}

	/** Takes every golem off the map. */
	void clear()
	{
		for (CellPoint point : shown)
		{
			mapPoints.remove(point);
		}
		shown.clear();
		used = 0;
		cells.clear();
	}

	/**
	 * Brings the map up to date with where the golems are.
	 *
	 * @param named true to show only golems with names
	 */
	void refresh(List<Golem> golems, boolean named, int tick)
	{
		this.tick = tick;
		if (face == null)
		{
			return;
		}

		WorldMap map = client.getWorldMap();
		Widget window = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (map == null || window == null || window.isHidden() || map.getWorldMapZoom() <= 0)
		{
			clear();
			return;
		}

		// One cell per face, so two golems share a point exactly when their faces would have
		// overlapped: a tile each at full zoom, a region each from far out.
		float zoom = map.getWorldMapZoom();
		int cellTiles = Math.max(1, (int) Math.ceil(FACE_PIXELS / zoom));
		Point centre = map.getWorldMapPosition();
		int halfWidth = (int) Math.ceil(window.getBounds().getWidth() / zoom / 2) + MARGIN;
		int halfHeight = (int) Math.ceil(window.getBounds().getHeight() / zoom / 2) + MARGIN;

		gather(golems, named, cellTiles, centre.getX() - halfWidth, centre.getX() + halfWidth,
			centre.getY() - halfHeight, centre.getY() + halfHeight);
		draw();
	}

	/** Sorts the golems on screen into cells, counting each and keeping a few names. */
	private void gather(List<Golem> golems, boolean named, int cellTiles, int minX, int maxX, int minY, int maxY)
	{
		cells.clear();
		used = 0;

		for (Golem golem : golems)
		{
			if (named && !isNamed(golem))
			{
				continue;
			}

			// Read from the golem's own numbers rather than a WorldPoint: ten thousand golems is ten
			// thousand objects a tick otherwise, and most of them are off screen.
			int x = golem.getFineX() / Golem.TILE;
			int y = golem.getFineY() / Golem.TILE;
			if (golem.isSailing(tick))
			{
				// Drawn at the port it is bound for: a raft mid-ocean is nowhere to look for it.
				WorldPoint landing = golem.saveTile();
				x = landing.getX();
				y = landing.getY();
			}
			if (x < minX || x > maxX || y < minY || y > maxY)
			{
				continue;
			}

			long key = ((long) (x / cellTiles) << 32) | (y / cellTiles) & 0xFFFFFFFFL;
			int index = (int) cells.getOrDefault(key, -1);
			if (index < 0)
			{
				if (used >= MOST_POINTS)
				{
					continue;
				}
				if (used >= cellList.size())
				{
					cellList.add(new Cell());
				}
				index = used++;
				Cell fresh = cellList.get(index);
				fresh.x = x;
				fresh.y = y;
				fresh.plane = golem.getPlane();
				fresh.count = 0;
				fresh.first = golem;
				fresh.named.clear();
				cells.add(key, index);
			}
			Cell cell = cellList.get(index);
			cell.count++;
			if (isNamed(golem) && cell.named.size() < NAMES_SHOWN)
			{
				cell.named.add(golem);
			}
		}
	}

	/** Puts a point on each filled cell, keeping the ones already on the map. */
	private void draw()
	{
		while (shown.size() > used)
		{
			mapPoints.remove(shown.remove(shown.size() - 1));
		}
		for (int i = 0; i < used; i++)
		{
			Cell cell = cellList.get(i);
			WorldPoint at = new WorldPoint(cell.x, cell.y, cell.plane);
			if (i < shown.size())
			{
				CellPoint point = shown.get(i);
				point.cell = cell;
				if (!at.equals(point.getWorldPoint()))
				{
					point.setWorldPoint(at);
				}
			}
			else
			{
				CellPoint point = new CellPoint(cell, at, face);
				shown.add(point);
				mapPoints.add(point);
			}
		}
	}

	private static boolean isNamed(Golem golem)
	{
		return golem.getNickname() != null && !golem.getNickname().isEmpty();
	}

	/** The golems drawn as one face: where they are, how many, and a few of their names. */
	private static final class Cell
	{
		int x;
		int y;
		int plane;
		int count;
		Golem first;
		final List<Golem> named = new ArrayList<>(NAMES_SHOWN);
	}

	/** One face on the map, which says who is under it only when asked. */
	private final class CellPoint extends WorldMapPoint
	{
		private Cell cell;

		private CellPoint(Cell cell, WorldPoint at, BufferedImage face)
		{
			super(at, face);
			this.cell = cell;
			setJumpOnClick(true);
		}

		/** What the map's own "Focus on" entry calls this face. */
		@Override
		public String getName()
		{
			if (cell == null || cell.count == 0)
			{
				return "Golems";
			}
			if (cell.count == 1)
			{
				return isNamed(cell.first) ? cell.first.getNickname() : "A golem";
			}
			return cell.count + " golems";
		}

		/**
		 * Asked by the map overlay for the point under the mouse, and only that one, so naming a
		 * place happens once rather than hundreds of times a frame.
		 */
		@Override
		public String getTooltip()
		{
			if (cell == null || cell.count == 0)
			{
				return null;
			}
			String place = whereabouts.of(cell.first, tick);
			if (cell.count == 1)
			{
				return getName() + " — " + place;
			}
			StringBuilder names = new StringBuilder();
			for (Golem golem : cell.named)
			{
				names.append(names.length() == 0 ? "" : ", ").append(golem.getNickname());
			}
			if (cell.count > cell.named.size() && names.length() > 0)
			{
				names.append(" and others");
			}
			return getName() + " — " + place + (names.length() == 0 ? "" : "<br>" + names);
		}
	}
}
