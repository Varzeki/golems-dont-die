package com.golemsdontdie;

import net.runelite.api.WorldView;

/**
 * How much of a golem needs simulating, decided by how close the player is to it.
 *
 * <p>The organising idea of the whole world-roaming change: a golem nobody can see costs
 * nothing. Not a cheaper tick — <em>no tick</em>. The plugin used to gate on whether the
 * player was anywhere near Wyrmscraig, which was the right answer while golems could only
 * be on Wyrmscraig. Once they can be anywhere, the question has to be asked per golem.
 *
 * <p>The tiers are spatial rather than scheduled. Nothing runs on a timer deciding to
 * demote things; a golem is in whichever tier its distance puts it in, every frame, and
 * crossing a boundary costs one action rather than an ongoing one.
 */
enum GolemTier
{
	/**
	 * Inside the loaded scene, on the player's plane. Drawn, posed, clickable, and
	 * simulated tile by tile every frame exactly as before.
	 */
	SCENE,

	/**
	 * Outside the scene but close enough that the player could walk into view shortly.
	 *
	 * <p>Simulated in full, the same as {@link #SCENE}, just not drawn.
	 *
	 * <p>The design this came from called for a coarse once-a-second step here instead.
	 * That was dropped deliberately, for two reasons. The first is that the saving is not
	 * needed: the previous version of this plugin stepped <em>every</em> golem in full
	 * whenever the player was anywhere near Wyrmscraig, and handled hundreds, so stepping
	 * the far smaller number that happen to be in this band is strictly less work than
	 * what already shipped. The second is that a throttle costs something real — a golem
	 * stepped once a second arrives in view having jumped up to a second of movement, and
	 * the boundary is precisely where that jump would be seen.
	 *
	 * <p>Simulating properly means a golem walking into the scene is already on the exact
	 * tile it should be, with nothing to snap and nothing to hide.
	 */
	NEAR,

	/**
	 * Everywhere else, land or sea. Stored as a route and a start time, resolved only
	 * when something asks. Costs nothing at all between those moments.
	 */
	FAR;

	/**
	 * Tiles beyond the scene edge that still count as near.
	 *
	 * <p>Roughly two regions. Wide enough that a golem has somewhere to be while the
	 * player walks toward it, narrow enough that the number of golems being stepped stays
	 * small however many exist.
	 */
	private static final int NEAR_RANGE = 128;

	/**
	 * Which tier a golem at these world coordinates falls into.
	 *
	 * <p>Plane is deliberately part of the scene test: a golem two floors up is not in the
	 * player's scene in any sense that matters, even though its tile is.
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

		// Chebyshev rather than Euclidean: the scene is a square and so is the ring
		// around it, so a diagonal golem is no further away than an axial one.
		int distance = Math.max(Math.abs(worldX - playerX), Math.abs(worldY - playerY));
		return distance <= NEAR_RANGE ? NEAR : FAR;
	}
}
