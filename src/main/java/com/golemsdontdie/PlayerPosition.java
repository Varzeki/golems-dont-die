package com.golemsdontdie;

import net.runelite.api.*;
import net.runelite.api.coords.*;

/**
 * Where the player is in the world, including when they are aboard a boat.
 *
 * <p>A boat is a world of its own: aboard one, {@link Player#getWorldLocation()} answers in the
 * boat's coordinates, far off the map, which put every golem out of range the moment the player
 * boarded. So the deck position is carried out to the main world through the boat, found by world
 * view id as RuneLite's own overlays do.
 */
final class PlayerPosition
{
	private PlayerPosition()
	{
	}

	/** The player's tile in the main world, or null if there is no player. */
	static WorldPoint of(Client client)
	{
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return null;
		}
		WorldView view = me.getWorldView();
		WorldView top = client.getTopLevelWorldView();
		if (view == null || top == null || view.isTopLevel())
		{
			return me.getWorldLocation();
		}

		WorldEntity boat = top.worldEntities().byIndex(view.getId());
		LocalPoint onDeck = me.getLocalLocation();
		if (boat == null || onDeck == null)
		{
			return me.getWorldLocation();
		}
		LocalPoint atSea = boat.transformToMainWorld(onDeck);
		if (atSea == null)
		{
			return me.getWorldLocation();
		}
		return WorldPoint.fromLocal(top, atSea.getX(), atSea.getY(), top.getPlane());
	}
}
