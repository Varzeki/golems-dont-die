package com.golemsdontdie;

import net.runelite.api.WorldView;

/**
 * How much of a golem needs simulating, decided by how close the player is to it.
 *
 * <p>A golem nobody can see costs <em>no tick</em> at all. The gate used to be whether the
 * player was near Wyrmscraig; once golems can be anywhere it is asked per golem. Tiers are
 * spatial, not scheduled: a golem is in whichever tier its distance puts it in each frame,
 * so crossing a boundary costs one action, not an ongoing one.
 */
enum GolemTier
{
	/** Inside the loaded scene, on the player's plane. Drawn, clickable, stepped each frame. */
	SCENE,

	/**
	 * Outside the scene but close enough to walk into view shortly. Simulated in full like
	 * {@link #SCENE}, just not drawn.
	 *
	 * <p>A coarse once-a-second step here was dropped: the previous version stepped every
	 * golem in full near Wyrmscraig and handled hundreds, so the saving is not needed, and
	 * a throttled golem would enter the scene up to a second out of place — precisely the
	 * boundary where that shows.
	 */
	NEAR,

	/** Everywhere else, land or sea. A route and a start time, resolved only when asked. */
	FAR;

	/**
	 * Tiles beyond the scene edge that still count as near: roughly two regions. Wide enough
	 * that a golem has somewhere to be as the player approaches, narrow enough to stay cheap.
	 */
	private static final int NEAR_RANGE = 128;

	/**
	 * Which tier a golem at these world coordinates falls into. Plane is part of the scene
	 * test: a golem two floors up is not in the player's scene, even though its tile is.
	 */
	static GolemTier of(WorldView wv, int worldX, int worldY, int plane,
		int playerX, int playerY, int playerPlane)
	{
		if (wv == null)
		{
			return FAR;
		}

		int localX = worldX - wv.getBaseX();
		int localY = worldY - wv.getBaseY();
		boolean inScene = localX >= 0 && localY >= 0
			&& localX < wv.getSizeX() && localY < wv.getSizeY();

		if (inScene && plane == playerPlane)
		{
			return SCENE;
		}

		// Chebyshev, not Euclidean: the scene and the ring around it are squares.
		int distance = Math.max(Math.abs(worldX - playerX), Math.abs(worldY - playerY));
		return distance <= NEAR_RANGE ? NEAR : FAR;
	}
}
