package com.golemsdontdie;

import java.awt.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;
import javax.inject.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.*;
import net.runelite.api.worldmap.*;
import net.runelite.api.worldmap.WorldMapData;
import net.runelite.client.ui.*;
import net.runelite.client.ui.overlay.worldmap.*;
import net.runelite.client.util.*;
import static com.golemsdontdie.RouteGeometry.span;

/**
 * Golems on the world map: all of them, as faces that say who is there when hovered.
 *
 * <p>A face per golem does not scale - a player who has crafted for months has ten thousand, and the
 * map overlay looks up a widget and measures it for every point it holds, on every frame the map is
 * open. So golems are gathered by where they would be drawn: the map is cut into cells the size of
 * one face at the current zoom, and each cell holding golems gets one point saying how many. Zoom in
 * and the cells shrink until each golem has a face of its own; zoom out and a crowd becomes one face
 * with a number. Either way the map holds about as many points as there are places to put them.
 *
 * <p>Only while the map is open, and only for the stretch on screen. Any map: a golem in a dungeon
 * goes where that dungeon's map draws its tile and floor (see WorldMapLayers), so golems appear on a
 * dungeon map as readily as on the surface. Closed, the map holds nothing and this costs one widget
 * lookup a tick.
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

	/** How far north of the ground above it a cave is laid out. See WorldLayout. */
	private static final int UNDERGROUND = WorldLayout.CAVE_OFFSET;

	/**
	 * The map being looked at, which is what decides where a golem belongs on it.
	 *
	 * <p>The client draws each map - the surface, every dungeon - in its own coordinate space, and
	 * refuses a point whose coordinates are not in the one on screen. This is the client's own
	 * answer to "is this tile on this map", so it is the one asked, rather than the plugin working
	 * it out from where the player happens to be standing.
	 */
	private WorldMapData showing;

	/** Whether the map was shut last time this ran: the frame it opens is refreshed at once. */
	private boolean wasShut = true;

	/**
	 * Whether the map wants a refresh before the next game tick: on the frame it opens, and on the
	 * frame another map is picked. A tick is 600ms, and asked only once a tick, the golems came onto
	 * a map a second or more after everything else on it.
	 */
	boolean wantsFrame()
	{
		Widget window = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (window == null || window.isHidden())
		{
			return false;
		}
		WorldMap map = client.getWorldMap();
		return wasShut || map != null && map.getWorldMapData() != showing;
	}

	/** Named faces kept before the oldest are dropped: renaming in the sidebar makes one a keystroke. */
	private static final int MOST_LABELS = 256;

	@Inject
	private Client client;

	@Inject
	private WorldMapPointManager mapPoints;

	@Inject
	private Whereabouts whereabouts;

	/** What a golem is called, auto name and all, for the tooltip. See GolemNames. */
	@Inject
	private GolemNames names;

	/** Where each map other than the surface draws the game's tiles. */
	@Inject
	private WorldMapLayers layers;

	/** Where the map draws a golem, reused for every golem. */
	private final int[] spot = new int[2];

	/**
	 * For a map newer than the shipped table, how far it draws its ground from where it really is,
	 * measured from the player, on whom the map centres as it opens; and the map it was measured for.
	 * A map the table knows never needs it.
	 */
	private int offsetX;
	private int offsetY;
	private WorldMapData measuredFor;

	/**
	 * Translations further than this are not believed: a player on the grass looking at a dungeon
	 * map is not on it, a hundred regions off, against the few that separate a dungeon from its place.
	 */
	private static final int MOST_OFFSET = 4096;

	/** Read only to tell sea from land, when the golem being found is shown over the ground above it. */
	@Inject
	private WorldMesh mesh;

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
		layers.load();
	}

	/** Takes every golem off the map. */
	void clear()
	{
		// Called every tick the map is shut, and nearly always there is nothing to take off.
		if (wasShut && shown.isEmpty())
		{
			return;
		}
		// Nothing on the map, so the next refresh that finds one open is a fresh one to measure.
		wasShut = true;
		for (CellPoint point : shown)
		{
			mapPoints.remove(point);
		}
		shown.clear();
		used = 0;
		cells.clear();
		labelled.clear();
		measuredFor = null;
	}

	/**
	 * Brings the map up to date with where the golems are.
	 *
	 * @param golems the whole roster, crumbling golems and all: they are passed over here, so the
	 *               caller need not copy the roster every tick to leave them out
	 * @param named  true to show only golems with names
	 */
	void refresh(List<Golem> golems, boolean named, int tick)
	{
		refresh(golems, named, tick, null);
	}

	/**
	 * As {@link #refresh(List, boolean, int)}, but showing one golem alone.
	 *
	 * <p>While a golem is being looked for it is the only thing on the map, and its face sticks to
	 * the edge when the map is panned away from it, the way a clue scroll's marker does - the point
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
			golems = Collections.singletonList(only);
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
		wasShut = false;

		// How near two golems have to be to share a face, in tiles: half a face's width at this
		// zoom, so two faces touching is what merges them rather than two faces near each other.
		// Zoomed all the way in that is a tile, and every golem has a face of its own.
		float zoom = map.getWorldMapZoom();
		int cellTiles = Math.max(1, Math.round(FACE_PIXELS / zoom / 2f));
		Point centre = map.getWorldMapPosition();
		showing = map.getWorldMapData();
		if (showing != measuredFor)
		{
			measuredFor = showing;
			offsetX = 0;
			offsetY = 0;
			if (layers.layerOf(showing) == null)
			{
				measure(map.getWorldMapPosition());
			}
		}
		int halfWidth = (int) Math.ceil(window.getBounds().getWidth() / zoom / 2) + MARGIN;
		int halfHeight = (int) Math.ceil(window.getBounds().getHeight() / zoom / 2) + MARGIN;

		gather(golems, named, cellTiles, centre.getX() - halfWidth, centre.getX() + halfWidth,
			centre.getY() - halfHeight, centre.getY() + halfHeight);
		// Zoomed all the way in, a cell is a tile and every golem keeps its own face, so there is
		// nothing to join: two golems standing next to each other are two golems, and drawing them
		// as one is what zooming in was meant to undo.
		if (cellTiles > 1)
		{
			join(cellTiles);
		}
		draw();
	}

	/**
	 * Works out where a map the table does not know draws the ground the player is on: on the frame
	 * it opens it is centred on the player, so the centre, in the map's coordinates, against the
	 * player's own tile is the translation. Only believed if it is whole map squares and lands on the
	 * map, so a map of somewhere else is left unmeasured.
	 */
	private void measure(Point centre)
	{
		WorldPoint at = PlayerPosition.of(client);
		if (showing == null || at == null || centre == null || showing.surfaceContainsPosition(at.getX(), at.getY()))
		{
			return;
		}
		int dx = centre.getX() - at.getX();
		int dy = centre.getY() - at.getY();
		if ((dx & 63) == 0 && (dy & 63) == 0 && Math.abs(dx) <= MOST_OFFSET && Math.abs(dy) <= MOST_OFFSET
			&& showing.surfaceContainsPosition(at.getX() + dx, at.getY() + dy))
		{
			offsetX = dx;
			offsetY = dy;
			log.debug("A map newer than the table draws its ground {},{} from where it is", dx, dy);
		}
	}

	/** Sorts the golems on screen into cells, counting each and keeping a few names. */
	private void gather(List<Golem> golems, boolean named, int cellTiles, int minX, int maxX, int minY, int maxY)
	{
		cells.clear();
		used = 0;
		WorldMapLayers.Layer layer = layers.layerOf(showing);

		for (Golem golem : golems)
		{
			// "Named" means the golems a player has marked out: named or starred.
			if (golem.isDying() || named && !isNamed(golem) && !golem.isFavourite())
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
				// own tile is the port it is bound for - that is what a crossing saves - so the
				// crossing itself is asked where it has got to.
				WorldPoint at = golem.seaPosition(tick);
				if (at != null)
				{
					x = at.getX();
					y = at.getY();
				}
			}
			// Where this golem goes on the map that is open, and only that map, as the game shows the
			// player: on a dungeon's map, where that map draws the golem's own tile and floor, which may
			// be far from where it is (see WorldMapLayers); on the surface, where it is. A golem
			// underground is not on the surface map, and one on the surface is not on a dungeon's,
			// unless it is the one being looked for.
			if (layer != null)
			{
				if (!layer.place(x, y, golem.getPlane(), spot))
				{
					continue;
				}
				x = spot[0];
				y = spot[1];
			}
			else if (showing != null && !showing.surfaceContainsPosition(x, y))
			{
				// A map newer than the table, drawn away from its own coordinates by the offset measured
				// for it; anything it does not draw is somewhere else. Except the golem being looked for,
				// underground, on the surface: over the ground above it, so the map still says which way
				// it lies. Most caves are dug at their ground's coordinates plus this; where the fold lands
				// in the sea, as the Observatory's does, it is no place at all.
				if ((offsetX != 0 || offsetY != 0) && showing.surfaceContainsPosition(x + offsetX, y + offsetY))
				{
					x += offsetX;
					y += offsetY;
				}
				else if (pinned && y >= UNDERGROUND && showing.surfaceContainsPosition(x, y - UNDERGROUND)
					&& !mesh.isOcean(x, y - UNDERGROUND, 0))
				{
					y -= UNDERGROUND;
				}
				else
				{
					continue;
				}
			}
			// The golem being looked for is kept whether or not the map is looking at it: its face
			// snaps to the edge to say which way it lies.
			if (!pinned && (x < minX || x > maxX || y < minY || y > maxY))
			{
				continue;
			}

			// The nearest group close enough to take it, looked for in the nine cells around the
			// golem: a group is only ever a cell or so across, so nothing further can reach.
			int index = -1;
			long nearest = Long.MAX_VALUE;
			boolean alone = pinned || cellTiles <= 1;
			for (int dx = -1; dx <= 1 && !alone; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					int at = (int) cells.getOrDefault(bucket(x / cellTiles + dx, y / cellTiles + dy, golem.getPlane()), -1);
					if (at < 0)
					{
						continue;
					}
					Cell group = cellList.get(at);
					if (group.plane != golem.getPlane())
					{
						continue;
					}
					// A group of twenty is drawn with a head twice the size, and reaches as far.
					long reach = (long) (cellTiles * CROWD_SIZES[sizeFor(group.count)]);
					long away = span(group.x - x, group.y - y);
					if (away <= reach && away < nearest)
					{
						nearest = away;
						index = at;
					}
				}
			}

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
				fresh.sumX = 0;
				fresh.sumY = 0;
				fresh.plane = golem.getPlane();
				fresh.count = 0;
				fresh.first = golem;
				// Bucketed where it started, so the golems after it can find it. Once: a second
				// cell in a bucket, its group grown out of reach, was a second entry for one key.
				long key = bucket(x / cellTiles, y / cellTiles, golem.getPlane());
				if (!cells.containsKey(key))
				{
					cells.add(key, index);
				}
			}
			Cell cell = cellList.get(index);
			cell.count++;
			cell.sumX += x;
			cell.sumY += y;
			// A group sits at the middle of the golems in it, which is where a player would say
			// they are. Laid out on the cells instead, a crowd came out as a lattice of heads.
			cell.x = (int) (cell.sumX / cell.count);
			cell.y = (int) (cell.sumY / cell.count);
		}
	}

	/**
	 * Joins up groups whose faces would still overlap.
	 *
	 * <p>Gathering takes each golem into the nearest group it can reach, which leaves groups that
	 * grew towards each other afterwards: two faces a few pixels apart, which is what a player
	 * sees as two heads on top of one another. This is the pass that puts those together, run a
	 * few times over because a joined group is larger and reaches further than either half did.
	 */
	private void join(int cellTiles)
	{
		for (int pass = 0; pass < JOIN_PASSES; pass++)
		{
			boolean joined = false;
			for (int i = 0; i < used; i++)
			{
				Cell one = cellList.get(i);
				if (one.count == 0)
				{
					continue;
				}
				for (int j = i + 1; j < used; j++)
				{
					Cell other = cellList.get(j);
					if (other.count == 0 || other.plane != one.plane)
					{
						continue;
					}
					// Two heads overlap when they are nearer than the two half-widths together.
					int reach = (int) (cellTiles * (CROWD_SIZES[sizeFor(one.count)]
						+ CROWD_SIZES[sizeFor(other.count)]));
					if (span(one.x - other.x, one.y - other.y) > reach)
					{
						continue;
					}
					one.count += other.count;
					one.sumX += other.sumX;
					one.sumY += other.sumY;
					one.x = (int) (one.sumX / one.count);
					one.y = (int) (one.sumY / one.count);
					other.count = 0;
					joined = true;
				}
			}
			if (!joined)
			{
				break;
			}
		}

		// Close the gaps the joining left, so draw() can walk the first `used` of them.
		int kept = 0;
		for (int i = 0; i < used; i++)
		{
			Cell cell = cellList.get(i);
			if (cell.count > 0)
			{
				cellList.set(i, cellList.get(kept));
				cellList.set(kept, cell);
				kept++;
			}
		}
		used = kept;
	}

	/** How many times the joining is run over. A joined group reaches further than its halves. */
	private static final int JOIN_PASSES = 3;

	/** A cell's key, by floor too: a group upstairs shared its bucket with one below, and each golem
	 * up there that found the downstairs group in its bucket made a cell of its own. */
	private static long bucket(int x, int y, int plane)
	{
		return ((long) plane << 60) | ((long) x << 32) | y & 0xFFFFFFFFL;
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

	/** Which of the face sizes a group of this many golems is drawn at. */
	private static int sizeFor(int count)
	{
		int size = 0;
		for (int i = 1; i < CROWDS.length; i++)
		{
			if (count >= CROWDS[i])
			{
				size = i;
			}
		}
		return size;
	}

	/** The face to draw for a cell holding this many golems. */
	private BufferedImage faceFor(int count)
	{
		int size = sizeFor(count);
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
		// The colour without its see-through: a name on the map is written on, as the map's own are.
		graphics.setColor(new Color(config.nameplateColour().getRGB() & 0xFFFFFF));
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
		/** Where the face goes: the middle of the golems in it. */
		int x;
		int y;

		/** Their positions added up, which is how the middle is kept as each one joins. */
		long sumX;
		long sumY;

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

		/**
		 * Only ever itself. RuneLite's point is a value, equal to any other at the same tile with the
		 * same picture and tooltip, and the map takes points off by equality: two unnamed golems on
		 * one tile, and taking one face off took the other's, leaving a face on the map that nothing
		 * held any more, through closing the map and turning the plugin off.
		 */
		@Override
		public boolean equals(Object other)
		{
			return this == other;
		}

		@Override
		public int hashCode()
		{
			return System.identityHashCode(this);
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
				String name = names.of(cell.first);
				return name != null ? name : "A golem";
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
			// What it is called, then where: its own name, or the one auto naming gives it. Only a
			// name the player gave is drawn on the map itself - auto named, every face on the map
			// would carry a label - but the tooltip is for one golem, and says who it is.
			if (cell.count == 1)
			{
				String name = names.of(cell.first);
				return name == null ? place : name + " · " + place;
			}
			return title() + " · " + place;
		}
	}
}
