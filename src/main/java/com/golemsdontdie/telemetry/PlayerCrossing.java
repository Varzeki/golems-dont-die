package com.golemsdontdie.telemetry;

import lombok.*;

/**
 * One crossing of an obstacle by the player, as it is kept on disk.
 *
 * <p>Plain numbers and the game's own text for the object. Positions are world tiles of the obstacle
 * itself; the path across it is measured relative to them, so it says how the obstacle moves a
 * player and nothing about where the player was otherwise.
 *
 * <p>Distances are in 128ths of a tile and times in client cycles (20 ms) unless a field says
 * otherwise — the units the game animates in.
 */
@AllArgsConstructor
public final class PlayerCrossing
{
	/** The scene object used. */
	public final int objectId;

	/** The object's name, from the game cache. */
	public final String name;

	/** The menu option and target, e.g. "Climb-over Stile". */
	public final String menu;

	/** Every animation played, in order. */
	public final int[] animations;

	/** Game ticks (600 ms) from the first animation starting to standing still somewhere new. */
	public final int ticks;

	/** Cycles from the first animation starting until the player first moved. */
	public final int moveDelay;

	/** Cycles the movement itself lasted. */
	public final int moveSpan;

	/** The tile the crossing starts from and the tile it ends on: x, y and floor. */
	public final int fromX;
	public final int fromY;
	public final int fromPlane;
	public final int toX;
	public final int toY;
	public final int toPlane;

	/** How far the obstacle itself moved the player, in whole tiles east and north. */
	public final int lineX;
	public final int lineY;

	/** True if it happened inside an instance, whose coordinates are not the world's. */
	public final boolean instance;

	/**
	 * The path across, one entry every two cycles: {cycle, along, side}. Along is the distance
	 * towards the far tile, side the distance off to the left (negative) or right of the straight
	 * line between the two tiles. Empty if it could not be recorded.
	 */
	public final int[][] path;

	/** Each point the animation changed: {cycle, animation}. */
	public final int[][] animationChanges;

	/** Which way the player faced relative to the way they moved, 0 to 2047 (1024 is backwards); -1 unknown. */
	public final int facing;
}
