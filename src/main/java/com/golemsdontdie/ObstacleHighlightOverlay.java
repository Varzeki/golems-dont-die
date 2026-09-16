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
 * <p>Golems only perform animations that have been measured, and the set of obstacles they
 * can use grows as the player is seen using them. That is invisible by design — nothing is
 * announced — which leaves no way to tell which obstacles would benefit from being used
 * once. This is that way, for anyone who wants it.
 *
 * <ul>
 *   <li><b>Green.</b> Confirmed: the player has been seen using this exact obstacle, here.
 *       Golems copy what was observed.</li>
 *   <li><b>Orange.</b> Inferred: golems will use it already, on data generalised from
 *       similar objects elsewhere. Using it once turns it green and replaces a
 *       generalisation with a fact — these are the ones worth a detour.</li>
 *   <li><b>Red.</b> No usable animation, so golems route around it entirely.</li>
 * </ul>
 *
 * <p>Off by default. It is a diagnostic for people who want to help, not decoration.
 */
class ObstacleHighlightOverlay extends Overlay
{
	/**
	 * How far out obstacles are gathered, in tiles.
	 *
	 * <p>Larger than anything that can actually be drawn, deliberately. The client loads a
	 * scene of 104 tiles square, so nothing beyond roughly fifty tiles has a position on
	 * the canvas at all and {@link Perspective} declines to project it. Gathering further
	 * out than that costs nothing here and means the highlights are already in hand the
	 * moment a tile scrolls into the scene, rather than appearing a frame later.
	 */
	private static final int RADIUS = 100;

	/**
	 * Tiles the player may move before the gathered set is rebuilt.
	 *
	 * <p>The set is rebuilt by sweeping the whole transport table, which is thirteen
	 * thousand rows — fine occasionally, far too much every frame at fifty frames a second.
	 * Ten tiles of slack means a running player rebuilds about once a second, and the
	 * radius is a hundred, so nothing can scroll into view between rebuilds.
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

		Marked(int x, int y, int plane, Color colour)
		{
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.colour = colour;
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
		if (!config.highlightObstacles())
		{
			// Dropped rather than kept, so turning the overlay off does not leave a
			// hundred tiles of stale highlights waiting to be drawn again.
			marked.clear();
			builtPlane = -1;
			builtVersion = -1;
			return null;
		}

		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return null;
		}

		WorldPoint at = local.getWorldLocation();
		if (at == null)
		{
			return null;
		}

		// Inside an instance, everything known about obstacles is known by where the instance
		// was copied from. Looking the instance's own coordinates up found nothing, so no
		// obstacle in the Mad Angel's room was ever coloured. See InstanceMap.
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

		for (Marked mark : marked)
		{
			draw(graphics, mark);
		}

		return null;
	}

	/** Where the instance the set was built for was loaded, or MIN_VALUE outside one. */
	private int builtBaseX = Integer.MIN_VALUE;
	private int builtBaseY = Integer.MIN_VALUE;

	private boolean needsRebuild(WorldPoint at, WorldView instance)
	{
		// Movement is not the only thing that invalidates the set. Using an obstacle
		// changes its colour while the player stands still beside it, which is exactly
		// when a distance-keyed cache would never notice. Nor is it the only thing that moves
		// the tiles: a new instance of the same room is somewhere else each visit.
		int baseX = instance == null ? Integer.MIN_VALUE : instance.getBaseX();
		int baseY = instance == null ? Integer.MIN_VALUE : instance.getBaseY();
		return knowledge.getVersion() != builtVersion
			|| baseX != builtBaseX || baseY != builtBaseY
			|| at.getPlane() != builtPlane
			|| Math.abs(at.getX() - builtX) >= REBUILD_DISTANCE
			|| Math.abs(at.getY() - builtY) >= REBUILD_DISTANCE;
	}

	/**
	 * Gathers every obstacle within range, one colour per tile.
	 *
	 * <p>Drawn from the obstacle index rather than the transport table, and that is the
	 * point of the index existing. The table only holds obstacles somebody has worked out a
	 * destination for; the index holds every obstacle in the game. An obstacle in neither
	 * the table nor the learned set comes out red, which is accurate — golems cannot use
	 * it — and is precisely the obstacle worth walking over to and using once.
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

		// A tile can start several transports — a ladder that is also a door, or one
		// obstacle recorded in both directions. Drawing each would stack outlines on one
		// tile, so the most confident wins: anything confirmed makes the tile green, and
		// only a tile where nothing at all is usable comes out red.
		Map<Long, ObstacleKnowledge.Status> best = new HashMap<>();

		for (ObstacleIndex.Obstacle obstacle : obstacles.near(builtX, builtY, builtPlane, RADIUS))
		{
			ObstacleKnowledge.Status status = knowledge.statusAt(obstacle.objectId,
				obstacle.x, obstacle.y, obstacle.plane, obstacle.sizeX, obstacle.sizeY,
				transports.archetypeFor(obstacle.objectId));

			// Ordinary doors are not obstacles a golem can perform and there are three
			// and a half thousand of them; outlining the lot would bury everything else in
			// red. The few that are really transports — a door that teleports you through
			// instead of opening — are shown once something is known about them.
			if (obstacle.wall && status == ObstacleKnowledge.Status.UNUSABLE)
			{
				continue;
			}

			// Every tile the object stands on, not just the corner the cache records it
			// at. A church pew is two tiles and a cave mouth can be three; outlining one
			// of them reads as the plugin not knowing about the rest.
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

		// Obstacles the player has used that the index has never heard of. The index is read
		// from the world's fixed placements, and some obstacles are only ever placed in an
		// instance — the Mad Angel's exit pew is one — so the only record of them is the player
		// having used one there.
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
	private void draw(Graphics2D graphics, Marked mark)
	{
		LocalPoint localPoint = LocalPoint.fromWorld(client.getTopLevelWorldView(),
			new WorldPoint(mark.x, mark.y, mark.plane));
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
		graphics.setColor(new Color(mark.colour.getRed(), mark.colour.getGreen(),
			mark.colour.getBlue(), FILL_ALPHA));
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
