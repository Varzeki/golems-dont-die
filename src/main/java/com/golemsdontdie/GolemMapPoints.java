package com.golemsdontdie;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import net.runelite.client.util.ImageUtil;

/**
 * Golems on the world map, each a face with its name and whereabouts on it.
 *
 * <p>A point per golem, moved as the golem moves rather than made afresh: the map overlay walks
 * every point it holds each frame the map is open, and a player with a thousand golems would be
 * handing it a thousand new objects every few ticks.
 *
 * <p>How many are shown is the player's choice, and the cap is not: named golems come first, then
 * whoever is nearest, up to {@link #MOST_POINTS}. A map under a thousand faces shows nothing at all.
 */
@Slf4j
@Singleton
class GolemMapPoints
{
	/** The most golems drawn on the map at once. */
	private static final int MOST_POINTS = 100;

	@Inject
	private WorldMapPointManager mapPoints;

	@Inject
	private Whereabouts whereabouts;

	private BufferedImage face;

	/** The point showing each golem, by golem. */
	private final Map<Golem, WorldMapPoint> points = new IdentityHashMap<>();

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
	 * @param golems the roster, as the client thread sees it
	 * @param named  true to show only golems with names
	 * @param at     where the player is, for choosing which golems are worth showing
	 */
	void refresh(List<Golem> golems, boolean named, WorldPoint at, int tick)
	{
		if (face == null)
		{
			return;
		}

		List<Golem> shown = choose(golems, named, at);
		java.util.Set<Golem> wanted = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		wanted.addAll(shown);

		points.entrySet().removeIf(entry ->
		{
			if (wanted.contains(entry.getKey()))
			{
				return false;
			}
			mapPoints.remove(entry.getValue());
			return true;
		});

		for (Golem golem : shown)
		{
			// A golem on a crossing is drawn at the port it is bound for: a raft mid-ocean is
			// nowhere anybody can look for it, and the map would show it adrift on open water.
			WorldPoint where = golem.isSailing(tick) ? golem.saveTile() : golem.currentTile();
			WorldMapPoint point = points.get(golem);
			if (point == null)
			{
				point = new WorldMapPoint(where, face);
				point.setName(name(golem));
				point.setJumpOnClick(false);
				points.put(golem, point);
				mapPoints.add(point);
			}
			else
			{
				point.setWorldPoint(where);
			}
			point.setTooltip(name(golem) + " — " + whereabouts.of(golem, tick));
		}
	}

	/** Named golems first, then the nearest, up to the cap. */
	private List<Golem> choose(List<Golem> golems, boolean named, WorldPoint at)
	{
		List<Golem> chosen = new ArrayList<>();
		for (Golem golem : golems)
		{
			if (golem.getNickname() != null && !golem.getNickname().isEmpty())
			{
				chosen.add(golem);
			}
		}
		if (!named && chosen.size() < MOST_POINTS)
		{
			List<Golem> rest = new ArrayList<>();
			for (Golem golem : golems)
			{
				if (golem.getNickname() == null || golem.getNickname().isEmpty())
				{
					rest.add(golem);
				}
			}
			if (at != null)
			{
				rest.sort(java.util.Comparator.comparingInt(golem -> golem.currentTile().distanceTo2D(at)));
			}
			chosen.addAll(rest.subList(0, Math.min(rest.size(), MOST_POINTS - chosen.size())));
		}
		return chosen.size() > MOST_POINTS ? chosen.subList(0, MOST_POINTS) : chosen;
	}

	private static String name(Golem golem)
	{
		String nickname = golem.getNickname();
		return nickname == null || nickname.isEmpty() ? "Golem" : nickname;
	}
}
