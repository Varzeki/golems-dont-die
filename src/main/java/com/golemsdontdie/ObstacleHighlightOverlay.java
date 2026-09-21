package com.golemsdontdie;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Colours the shortcuts around the player by how well the plugin knows them.
 *
 * <p>Golems only perform animations that have been measured, and the set grows as the
 * player is seen using obstacles. Nothing announces that, so this is the only way to tell
 * which would benefit from being used once. Green is confirmed here; orange is inferred
 * from similar objects elsewhere, and using it once turns it green, so those are the ones
 * worth a detour; red has no usable animation and golems route around it.
 *
 * <p>Off by default: a diagnostic, not decoration.
 */
class ObstacleHighlightOverlay extends Overlay
{
	/**
	 * How far out obstacles are gathered, in tiles. Deliberately larger than anything
	 * drawable: the scene is 104 tiles square, so {@link Perspective} declines to project
	 * beyond about fifty. Gathering further costs nothing and has highlights ready the
	 * moment a tile scrolls in.
	 */
	private static final int RADIUS = 100;

	/**
	 * Tiles the player may move before the gathered set is rebuilt. A rebuild sweeps the
	 * whole transport table, thirteen thousand rows — far too much at fifty frames a second.
	 * Ten tiles is about one rebuild a second at a run, and the radius is a hundred, so
	 * nothing scrolls into view between them.
	 */
	private static final int REBUILD_DISTANCE = 10;

	private static final Color CONFIRMED = new Color(0, 200, 60, 180);
	private static final Color INFERRED = new Color(255, 150, 0, 180);
	private static final Color UNUSABLE = new Color(220, 40, 40, 180);

	/** Fill is much fainter than the outline; a solid tile hides the obstacle under it. */
	private static final int FILL_ALPHA = 40;

	@Inject
	private Client client;

	@Inject
	private TransportNetwork transports;

	@Inject
	private ObstacleIndex obstacles;

	@Inject
	private ObstacleKnowledge knowledge;

	@Inject
	private GolemsDontDieConfig config;

	/** One tile to outline, and the colour it earned. */
	private static final class Marked
	{
		final int x;
		final int y;
		final int plane;
		final Color colour;

		/** The same colour at the fill's alpha, made once rather than every frame. */
		final Color fill;

		Marked(int x, int y, int plane, Color colour)
		{
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.colour = colour;
			this.fill = new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), FILL_ALPHA);
		}
	}

	private final List<Marked> marked = new ArrayList<>();

	private int builtX = Integer.MIN_VALUE;
	private int builtY = Integer.MIN_VALUE;
	private int builtPlane = -1;

	/** The knowledge version this set was built from, so a change to it forces a rebuild. */
	private int builtVersion = -1;

	@Inject
	ObstacleHighlightOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!DevOptions.HIGHLIGHT_OBSTACLES)
		{
			// Dropped, so turning the overlay off does not leave a hundred tiles
			// of stale highlights waiting to be drawn again.
			marked.clear();
			builtPlane = -1;
			builtVersion = -1;
			return null;
		}

		// In the main world, aboard a boat too; see PlayerPosition.
		WorldPoint at = PlayerPosition.of(client);
		if (at == null)
		{
			return null;
		}

		// Inside an instance, obstacle knowledge is keyed by where it was copied from;
		// looking up its own coordinates left the Mad Angel's room uncoloured. See
		// InstanceMap.
		WorldView wv = client.getTopLevelWorldView();
		WorldView instance = wv != null && wv.isInstance() ? wv : null;
		WorldPoint centre = instance != null ? InstanceMap.templateOf(instance, at) : at;
		if (centre == null)
		{
			return null;
		}

		if (needsRebuild(centre, instance))
		{
			rebuild(centre, instance);
		}

		// Once for every tile rather than once per tile: there can be thousands.
		WorldView top = client.getTopLevelWorldView();
		for (Marked mark : marked)
		{
			if (top == null)
			{
				break;
			}
			draw(graphics, top, mark);
		}

		return null;
	}

	/** Where the instance the set was built for was loaded, or MIN_VALUE outside one. */
	private int builtBaseX = Integer.MIN_VALUE;
	private int builtBaseY = Integer.MIN_VALUE;

	private boolean needsRebuild(WorldPoint at, WorldView instance)
	{
		// Using an obstacle changes its colour while the player stands still beside it,
		// which a distance-keyed cache would miss; and a new instance of the same room is
		// somewhere else each visit.
		int baseX = instance == null ? Integer.MIN_VALUE : instance.getBaseX();
		int baseY = instance == null ? Integer.MIN_VALUE : instance.getBaseY();
		return knowledge.getVersion() != builtVersion
			|| baseX != builtBaseX || baseY != builtBaseY
			|| at.getPlane() != builtPlane
			|| Math.abs(at.getX() - builtX) >= REBUILD_DISTANCE
			|| Math.abs(at.getY() - builtY) >= REBUILD_DISTANCE;
	}

	/**
	 * Gathers every obstacle within range, one colour per tile. Drawn from the obstacle
	 * index, not the transport table, which is why the index exists: the table holds only
	 * obstacles with a worked-out destination. One in neither comes out red, which is
	 * accurate and is precisely the obstacle worth using once.
	 */
	private void rebuild(WorldPoint at, WorldView instance)
	{
		builtX = at.getX();
		builtY = at.getY();
		builtPlane = at.getPlane();
		builtVersion = knowledge.getVersion();
		builtBaseX = instance == null ? Integer.MIN_VALUE : instance.getBaseX();
		builtBaseY = instance == null ? Integer.MIN_VALUE : instance.getBaseY();
		marked.clear();

		// A tile can start several transports — a ladder that is also a door — so the most
		// confident wins rather than stacking outlines: only a tile where nothing at all
		// is usable comes out red.
		Map<Long, ObstacleKnowledge.Status> best = new HashMap<>();

		for (ObstacleIndex.Obstacle obstacle : obstacles.near(builtX, builtY, builtPlane, RADIUS))
		{
			ObstacleKnowledge.Status status = knowledge.statusAt(obstacle.objectId,
				obstacle.x, obstacle.y, obstacle.plane, obstacle.sizeX, obstacle.sizeY,
				transports.archetypeFor(obstacle.objectId));

			// Ordinary doors are not obstacles a golem can perform and there are three and a
			// half thousand of them; outlining the lot would bury everything else in red.
			if (obstacle.wall && status == ObstacleKnowledge.Status.UNUSABLE)
			{
				continue;
			}

			// Every tile the object stands on, not just the corner the cache records. A church
			// pew is two tiles and a cave mouth can be three.
			for (int dx = 0; dx < obstacle.sizeX; dx++)
			{
				for (int dy = 0; dy < obstacle.sizeY; dy++)
				{
					long tile = ((long) (obstacle.x + dx) << 32)
						| ((obstacle.y + dy) & 0xffffffffL);
					ObstacleKnowledge.Status current = best.get(tile);

					// The enum is ordered most confident first, so the lowest wins.
					if (current == null || status.ordinal() < current.ordinal())
					{
						best.put(tile, status);
					}
				}
			}
		}

		// Obstacles the player has used that the index has never heard of: it is read from
		// the world's fixed placements, and some exist only in an instance — the Mad
		// Angel's exit pew — so the player's use is the only record.
		for (int[] place : knowledge.confirmedPlaces())
		{
			if (obstacles.knows(place[0]) || place[3] != builtPlane
				|| Math.abs(place[1] - builtX) > RADIUS || Math.abs(place[2] - builtY) > RADIUS)
			{
				continue;
			}
			ObstacleKnowledge.Status status = knowledge.statusAt(place[0], place[1], place[2], place[3], 1, 1,
				transports.archetypeFor(place[0]));
			long tile = ((long) place[1] << 32) | (place[2] & 0xffffffffL);
			ObstacleKnowledge.Status current = best.get(tile);
			if (current == null || status.ordinal() < current.ordinal())
			{
				best.put(tile, status);
			}
		}

		Map<Long, int[]> sceneChunks = instance == null ? null : InstanceMap.sceneChunks(instance);
		for (Map.Entry<Long, ObstacleKnowledge.Status> e : best.entrySet())
		{
			int x = (int) (e.getKey() >> 32);
			int y = (int) (e.getKey() & 0xffffffffL);
			// Gathered in template coordinates; drawn where the instance put that tile.
			WorldPoint drawAt = sceneChunks == null ? new WorldPoint(x, y, builtPlane)
				: InstanceMap.instanceTileOf(instance, sceneChunks, x, y, builtPlane);
			if (drawAt != null)
			{
				marked.add(new Marked(drawAt.getX(), drawAt.getY(), drawAt.getPlane(), colourOf(e.getValue())));
			}
		}
	}

	/** Outlines one tile, or silently skips it if the client cannot place it on screen. */
	private void draw(Graphics2D graphics, WorldView top, Marked mark)
	{
		// LocalPoint.fromWorld without making the point: a tile on another plane is not in
		// this scene.
		LocalPoint localPoint = top.getPlane() != mark.plane ? null : LocalPoint.fromWorld(top, mark.x, mark.y);
		if (localPoint == null)
		{
			// Outside the loaded scene. Most of a hundred-tile radius is, most of the time.
			return;
		}

		Polygon tile = Perspective.getCanvasTilePoly(client, localPoint);
		if (tile == null)
		{
			// On screen in principle, but the client has no height for it.
			return;
		}

		graphics.setColor(mark.colour);
		graphics.drawPolygon(tile);
		graphics.setColor(mark.fill);
		graphics.fillPolygon(tile);
	}

	private static Color colourOf(ObstacleKnowledge.Status status)
	{
		switch (status)
		{
			case CONFIRMED:
				return CONFIRMED;
			case INFERRED:
				return INFERRED;
			default:
				return UNUSABLE;
		}
	}
}
