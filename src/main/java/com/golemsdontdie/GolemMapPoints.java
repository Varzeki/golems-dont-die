package com.golemsdontdie;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
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
import net.runelite.client.ui.FontManager;
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

	/** How far below the surface the underground is drawn, in tiles. */
	private static final int UNDERGROUND = 6400;

	/** Named faces kept before the oldest are dropped: renaming in the sidebar makes one a keystroke. */
	private static final int MOST_LABELS = 256;

	@Inject
	private Client client;

	@Inject
	private WorldMapPointManager mapPoints;

	@Inject
	private Whereabouts whereabouts;

	@Inject
	private GolemsDontDieConfig config;

	/** Faces with a name written over them, by the name. A player names a few dozen golems at most. */
	private final Map<String, BufferedImage> labelled = new HashMap<>();

	private BufferedImage face;

	/** Cell key to its place in {@link #cellList}. Reused between refreshes rather than rebuilt. */
	private final TileMap cells = new TileMap(256);
	private final List<Cell> cellList = new ArrayList<>();

	/** The points on the map, one per filled cell, in step with the first {@link #used} cells. */
	private final List<CellPoint> shown = new ArrayList<>();

	private int used;
	private int tick;

	/** True while one golem is being looked for, which is drawn stuck to the map's edge. */
	private boolean pinned;

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
		labelled.clear();
	}

	/**
	 * Brings the map up to date with where the golems are.
	 *
	 * @param named true to show only golems with names
	 */
	void refresh(List<Golem> golems, boolean named, int tick)
	{
		refresh(golems, named, tick, null);
	}

	/**
	 * As {@link #refresh(List, boolean, int)}, but showing one golem alone.
	 *
	 * <p>While a golem is being looked for it is the only thing on the map, and its face sticks to
	 * the edge when the map is panned away from it, the way a clue scroll's marker does — the point
	 * of looking for a golem is to be told which way it lies.
	 */
	void refresh(List<Golem> golems, boolean named, int tick, Golem only)
	{
		this.tick = tick;
		if (face == null)
		{
			return;
		}
		if (only != null)
		{
			golems = java.util.Collections.singletonList(only);
			named = false;
		}
		this.pinned = only != null;

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
				// Where the raft is now, which on a map is where you would look for it. The golem's
				// own tile is the port it is bound for — that is what a crossing saves — so the
				// crossing itself is asked where it has got to.
				WorldPoint at = golem.seaPosition(tick);
				if (at != null)
				{
					x = at.getX();
					y = at.getY();
				}
			}
			// The map draws a dungeon over the ground above it, so a golem underground belongs at
			// the surface coordinates it is beneath. Left where it stood it sat a hundred regions
			// north of the map, where nobody ever saw it.
			if (y >= UNDERGROUND)
			{
				y -= UNDERGROUND;
			}
			// The golem being looked for is kept whether or not the map is looking at it: its face
			// snaps to the edge to say which way it lies.
			if (!pinned && (x < minX || x > maxX || y < minY || y > maxY))
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
				cells.add(key, index);
			}
			Cell cell = cellList.get(index);
			cell.count++;
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
			CellPoint point;
			if (i < shown.size())
			{
				point = shown.get(i);
				point.cell = cell;
				if (!at.equals(point.getWorldPoint()))
				{
					point.setWorldPoint(at);
				}
			}
			else
			{
				point = new CellPoint(cell, at, face);
				shown.add(point);
				mapPoints.add(point);
			}
			point.setSnapToEdge(pinned);
			dress(point, cell);
		}
	}

	/**
	 * Gives a point its picture: a plain face, or the face with the golem's name written over it.
	 *
	 * <p>The name is part of the image because the map overlay draws images and nothing else. The
	 * image is anchored on the face rather than its middle, so the name sits above the golem's tile
	 * rather than pushing the face off it.
	 */
	private void dress(CellPoint point, Cell cell)
	{
		String name = cell.count == 1 && isNamed(cell.first) ? cell.first.getNickname().trim() : null;
		if (name == null)
		{
			BufferedImage head = faceFor(cell.count);
			if (point.getImage() != head)
			{
				point.setImage(head);
				point.setImagePoint(null);
			}
			return;
		}
		if (labelled.size() > MOST_LABELS)
		{
			labelled.clear();
		}
		BufferedImage withName = labelled.computeIfAbsent(name, this::label);
		if (point.getImage() != withName)
		{
			point.setImage(withName);
			point.setImagePoint(new Point(withName.getWidth() / 2,
				withName.getHeight() - face.getHeight() / 2));
		}
	}

	/**
	 * How many golems a face has to stand for before it is drawn larger, and how much larger.
	 *
	 * <p>Zooming out puts more golems in a cell, which is what makes the heads grow as the map
	 * pulls back and split again as it comes in.
	 */
	private static final int[] CROWDS = {1, 3, 10, 40};
	private static final float[] CROWD_SIZES = {1f, 1.35f, 1.8f, 2.3f};

	/** The faces, one per size, drawn from the shipped one the first time each is wanted. */
	private final BufferedImage[] faces = new BufferedImage[CROWDS.length];

	/** The face to draw for a cell holding this many golems. */
	private BufferedImage faceFor(int count)
	{
		int size = 0;
		for (int i = 1; i < CROWDS.length; i++)
		{
			if (count >= CROWDS[i])
			{
				size = i;
			}
		}
		if (faces[size] == null)
		{
			faces[size] = size == 0 ? face
				: ImageUtil.resizeImage(face, Math.round(face.getWidth() * CROWD_SIZES[size]),
					Math.round(face.getHeight() * CROWD_SIZES[size]), true);
		}
		return faces[size];
	}

	/** The face with a name above it, drawn once per name and kept. */
	private BufferedImage label(String name)
	{
		Font font = FontManager.getRunescapeSmallFont();
		BufferedImage measuring = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D measure = measuring.createGraphics();
		measure.setFont(font);
		FontMetrics metrics = measure.getFontMetrics();
		int textWidth = metrics.stringWidth(name);
		int textHeight = metrics.getHeight();
		measure.dispose();

		int width = Math.max(face.getWidth(), textWidth + 2);
		BufferedImage image = new BufferedImage(width, textHeight + face.getHeight(),
			BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		graphics.setFont(font);
		int textX = (width - textWidth) / 2;
		int baseline = metrics.getAscent();
		// The game's own shadow, a pixel down and right, so the name reads over any map colour.
		graphics.setColor(Color.BLACK);
		graphics.drawString(name, textX + 1, baseline + 1);
		graphics.setColor(config.nameplateColour());
		graphics.drawString(name, textX, baseline);
		graphics.drawImage(face, (width - face.getWidth()) / 2, textHeight, null);
		graphics.dispose();
		return image;
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
	}

	/** One face on the map, which says who is under it only when asked. */
	private final class CellPoint extends WorldMapPoint
	{
		private Cell cell;

		private CellPoint(Cell cell, WorldPoint at, BufferedImage face)
		{
			super(at, face);
			this.cell = cell;
			// No "Focus on" entry: it is a second thing to read beside the tooltip, in a menu the
			// map puts up whether or not anything was asked of it.
			setJumpOnClick(false);
		}

		/** What this face is of: a golem's name, or how many golems are standing together. */
		private String title()
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
			// Never nothing while the face is on the map: with no tooltip of ours the map falls
			// back on its own, which is the game's white one over whatever is underneath.
			if (cell == null || cell.count == 0 || cell.first == null)
			{
				return "Golems";
			}
			String place = whereabouts.of(cell.first, tick);
			// A golem with a name already wears it on the map, so the tooltip only adds where it is.
			// One without a name is just a golem: the place is the whole of what there is to say.
			return cell.count == 1 && !isNamed(cell.first) ? place : title() + " — " + place;
		}
	}
}
