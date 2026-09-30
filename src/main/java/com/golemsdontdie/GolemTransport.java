package com.golemsdontdie;

import lombok.*;
import net.runelite.api.coords.*;

/**
 * One way of getting from one tile to another that is not walking: a door, a ladder, a stile,
 * a ferry.
 *
 * <p>From Shortest Path's tables, reduced offline to the rows a golem could use, which is most of them. Immutable and shared: ~13,000 rows, one set
 * for every golem, built once at startup. Requirements are parsed arrays, not strings, because a
 * golem tests them on every tile it enters near a transport.
 */
final class GolemTransport
{
	// Archetypes. Written into the shipped table; the numbering is a file format, so append only -
	// changing a value silently remaps every row in the shipped resource.
	static final int ARCHETYPE_NONE = 0;
	static final int ARCHETYPE_DOOR = 1;
	static final int ARCHETYPE_LADDER = 2;
	static final int ARCHETYPE_CLIMB = 3;
	static final int ARCHETYPE_DITCH = 4;
	static final int ARCHETYPE_JUMP = 5;
	static final int ARCHETYPE_GANGPLANK = 6;
	static final int ARCHETYPE_CLIMB_OVER = 7;
	static final int ARCHETYPE_SQUEEZE = 8;
	static final int ARCHETYPE_BALANCE = 9;
	static final int ARCHETYPE_STILE = 10;
	static final int ARCHETYPE_TIGHTROPE = 11;

	/**
	 * Stairs: walked up or down, nothing played. They were ladders before, so a golem reached up
	 * for a rung at the foot of every staircase in the game.
	 */
	static final int ARCHETYPE_STAIRS = 12;

	@Getter
	private final int fromX;
	@Getter
	private final int fromY;
	@Getter
	private final int fromPlane;
	@Getter
	private final int toX;
	@Getter
	private final int toY;
	@Getter
	private final int toPlane;

	/** Travel time in game ticks, as authored for a player. */
	@Getter
	private final int duration;

	/** Which animation set to play, decided offline from the menu text. */
	@Getter
	private final int archetype;

	/**
	 * The scene object this transport is, or -1, read from the menu column offline ("Cross
	 * Gangplank 12164"). It finds the object's model and animation, which
	 * {@code ObjectComposition} does not expose at runtime.
	 */
	@Getter
	private final int objectId;

	/** Pairs of (level, skill ordinal). Empty if ungated. */
	private final int[] skillRequirements;

	/** Quest ordinals that must be finished. Empty if ungated. */
	private final int[] questRequirements;

	/** Triples of (id, operator, value). See {@link GolemAbilities}. */
	private final int[] varbitRequirements;
	private final int[] varpRequirements;

	/**
	 * Index of the row that undoes this one, or -1. Resolved once when the network is built,
	 * because searching per decision would scan 13,000 rows per golem per tile.
	 */
	@Getter
	private int reverse = -1;

	/**
	 * This row's own index in the network, carried rather than looked up: cooldowns are keyed by
	 * index and tested for every candidate on every tile a golem enters, so a lookup would scan
	 * the whole table on the hottest path here.
	 */
	@Getter
	private int index = -1;

	GolemTransport(int fromX, int fromY, int fromPlane, int toX, int toY, int toPlane,
		int duration, int archetype, int objectId, int[] skills, int[] quests, int[] varbits,
		int[] varps)
	{
		this.objectId = objectId;
		this.fromX = fromX;
		this.fromY = fromY;
		this.fromPlane = fromPlane;
		this.toX = toX;
		this.toY = toY;
		this.toPlane = toPlane;
		this.duration = duration;
		this.archetype = archetype;
		this.skillRequirements = skills;
		this.questRequirements = quests;
		this.varbitRequirements = varbits;
		this.varpRequirements = varps;
	}

	/** A learned route that goes into an instance, or comes out of one. See {@link InstanceMap}. */
	static final int INTO_INSTANCE = 1;
	static final int OUT_OF_INSTANCE = 2;

	/**
	 * {@link #INTO_INSTANCE}, {@link #OUT_OF_INSTANCE}, or neither. A golem through the pew is
	 * where nobody outside can see it, and one in the instance's copy of the island is not in
	 * the instance at all - identical in coordinates, since an instance is its template's.
	 */
	@Getter
	private int instanceFlags;

	/**
	 * True for a row the tables infer rather than calculate - a pad's partner worked out by
	 * nearness, the far side of an agility object - shipped so the ground behind it is known, but
	 * not used until golems have seen a player use it. See {@link ObstacleKnowledge#isUnlocked}.
	 */
	@Getter
	private boolean learnFirst;

	/**
	 * Which way a climb goes, from its verb, decided offline: 1 up, -1 down, 0 not said. The plane
	 * alone cannot say: a ladder out of a dungeon is plane 0 to plane 0, and 617 of them played the
	 * descent.
	 */
	@Getter
	private int direction;

	void setDirection(int direction)
	{
		this.direction = direction;
	}

	/** True for a climb upward: by its verb where it has one, by the plane change otherwise. */
	boolean goesUp()
	{
		return direction != 0 ? direction > 0 : toPlane > fromPlane;
	}

	/** True for a climb downward, likewise. */
	boolean goesDown()
	{
		return direction != 0 ? direction < 0 : toPlane < fromPlane;
	}

	void setLearnFirst(boolean learnFirst)
	{
		this.learnFirst = learnFirst;
	}

	void setInstanceFlags(int flags)
	{
		this.instanceFlags = flags;
	}

	boolean entersInstance()
	{
		return (instanceFlags & INTO_INSTANCE) != 0;
	}

	boolean leavesInstance()
	{
		return (instanceFlags & OUT_OF_INSTANCE) != 0;
	}

	/**
	 * The same journey the other way, for a golem somewhere it knows no other way out of. Not a
	 * row in the network; only {@link RoamPlanner#wayHome} produces it. The player may have
	 * entered a cave and teleported out, so nothing leaving it was ever learned and golems that
	 * followed gathered underground for good. Not what the game does, but it beats never coming
	 * out.
	 */
	GolemTransport backThrough()
	{
		GolemTransport back = new GolemTransport(toX, toY, toPlane, fromX, fromY, fromPlane, duration, archetype,
			objectId, new int[0], new int[0], new int[0], new int[0]);
		back.instanceFlags = (entersInstance() ? OUT_OF_INSTANCE : 0) | (leavesInstance() ? INTO_INSTANCE : 0);
		back.learnFirst = learnFirst;
		back.direction = -direction;
		return back;
	}

	void setReverse(int index)
	{
		this.reverse = index;
	}

	void setIndex(int index)
	{
		this.index = index;
	}

	/** Both ends of this journey as one number. Rows with the same key go the same way. */
	long endpointKey()
	{
		return TransportNetwork.endpoints(getFromX(), getFromY(), getFromPlane(),
			getToX(), getToY(), getToPlane());
	}

	/** The key of the journey that undoes this one. */
	long reverseKey()
	{
		return TransportNetwork.endpoints(getToX(), getToY(), getToPlane(),
			getFromX(), getFromY(), getFromPlane());
	}

	WorldPoint destination()
	{
		return new WorldPoint(toX, toY, toPlane);
	}

	int[] skills()
	{
		return skillRequirements;
	}

	int[] quests()
	{
		return questRequirements;
	}

	int[] varbits()
	{
		return varbitRequirements;
	}

	int[] varps()
	{
		return varpRequirements;
	}

	/** True if this transport has no requirements at all - most of the network. */
	boolean isUnconditional()
	{
		return skillRequirements.length == 0 && questRequirements.length == 0
			&& varbitRequirements.length == 0 && varpRequirements.length == 0;
	}

	/**
	 * How far the golem is carried, in tiles, or -1 where the question is meaningless:
	 * underground maps sit ~6,400 tiles north of the surface above them, so a ladder down
	 * measures as a continent-wide journey.
	 */
	int travelDistance()
	{
		int d = Math.abs(fromX - toX) + Math.abs(fromY - toY);
		return d > 64 ? -1 : d;
	}

	/**
	 * The animation that plays while the golem uses this, or -1 for none. Doors are deliberately
	 * silent: the door animates, not the player. Teleport-like transports are -1 too.
	 */
	int animation()
	{
		int[] clips = animations();
		return clips.length == 0 ? -1 : clips[0];
	}

	/**
	 * The clips to play, in order, while the golem uses this. OSRS builds obstacle animations in
	 * threes - mount, loop, dismount - and using only the middle one left nine of twenty
	 * catalogued clips unused. The set comes from the plane change and the distance carried;
	 * clips are shared, so three cost as one.
	 */
	int[] animations()
	{
		// A shortcut somebody has watched beats a guess from menu text: the archetype below
		// was wrong for three of the first four obstacles tried.
		int measured = MeasuredShortcuts.animationFor(objectId);
		if (measured != -1)
		{
			return new int[]{measured};
		}

		switch (archetype)
		{
			case ARCHETYPE_LADDER:
				// Reaching up for a ladder and reaching down off the top are different
				// clips; the plane change says which.
				return new int[]{goesUp()
					? GolemContent.ANIM_LADDER_GRAB
					: GolemContent.ANIM_LADDER_GRAB_TOP};

			case ARCHETYPE_STAIRS:
				return new int[0];

			case ARCHETYPE_CLIMB:
				if (goesDown())
				{
					// Going down is its own clip, not the ascent reversed. Measured on
					// Wyrmscraig: the west approach (62265) plays 740, HUMAN_CLIMBING_DOWN,
					// the east (62267) the climbing loop; three server reimplementations
					// use 740 for a descent too. Only the plane says which way a climb
					// goes, so a cliff on flat ground gets the ascent both ways unless
					// measured - the better failure, as an ascent played descending reads
					// as effortful.
					return new int[]{GolemContent.ANIM_CLIMB_DOWN};
				}
				// Take hold, haul up, step off.
				return new int[]{
					GolemContent.ANIM_CLIMB_READY,
					GolemContent.ANIM_CLIMB_LOOP,
					GolemContent.ANIM_CLIMB_MERGE,
				};

			case ARCHETYPE_DITCH:
				return new int[]{GolemContent.ANIM_DITCH_VAULT};

			case ARCHETYPE_JUMP:
			{
				// A hop between stones and a running leap across a chasm are different
				// movements, and distance is the only thing here that separates them. The
				// thresholds are unverified: only the shortest rung is measured -
				// Wyrmscraig's basalt stones, two tiles, play 741 - and the longer clips
				// come from the catalogue by name. A 2019 server reimplementation plays 741
				// for every stepping stone regardless of span, so the split may not exist.
				int span = travelDistance();
				if (span < 0 || span <= 2)
				{
					// The short hop, measured on Wyrmscraig's basalt stones.
					return new int[]{GolemContent.ANIM_JUMP_STEPPINGSTONE};
				}
				if (span <= 4)
				{
					return new int[]{GolemContent.ANIM_JUMP_GAP};
				}
				return new int[]{span <= 8
					? GolemContent.ANIM_JUMP_STONES
					: GolemContent.ANIM_JUMP_LONG};
			}

			case ARCHETYPE_CLIMB_OVER:
				return new int[]{GolemContent.ANIM_WALL_JUMP};

			case ARCHETYPE_STILE:
				// Stepped over one leg at a time, where a broken wall is vaulted; told
				// apart offline from the menu text.
				return new int[]{GolemContent.ANIM_STILE};

			case ARCHETYPE_TIGHTROPE:
				return new int[]{
					GolemContent.ANIM_BALANCE_WALK,
					GolemContent.ANIM_TIGHTROPE,
					GolemContent.ANIM_BALANCE_WALK,
				};

			case ARCHETYPE_SQUEEZE:
				// Turn sideways, shuffle through, straighten up.
				return new int[]{
					GolemContent.ANIM_SQUEEZE_READY,
					GolemContent.ANIM_SQUEEZE_LOOP,
					GolemContent.ANIM_SQUEEZE_END,
				};

			case ARCHETYPE_BALANCE:
				// Step on, cross, step off.
				return new int[]{
					GolemContent.ANIM_BALANCE_WALK,
					GolemContent.ANIM_BALANCE_WALK_LOOP,
					GolemContent.ANIM_BALANCE_WALK,
				};

			case ARCHETYPE_GANGPLANK:
				// Walking the plank is walking: the golem keeps its gait and the boarding
				// reads from the geometry.
				return new int[]{GolemContent.GOLEM_WALK_ANIMATION};

			case ARCHETYPE_DOOR:
				// Through the door, not by opening it, as at Wyrmscraig's cathedral doors.
				return new int[]{GolemContent.ANIM_DOOR_THROUGH};

			default:
				return new int[0];
		}
	}

	/**
	 * True if this transport is worth drawing an animated copy of the object for. The object's
	 * animation comes from {@link PropFactory} at runtime, so nothing here names clips. Doors
	 * are excluded despite animating: the real object swings when a player opens it, and a
	 * second copy on top of a closed one is a ghost door.
	 */
	boolean wantsPropAnimation()
	{
		return objectId > 0 && archetype != ARCHETYPE_NONE && archetype != ARCHETYPE_DOOR;
	}

	/**
	 * True for what a golem's page counts as a shortcut: something climbed, jumped, squeezed
	 * through or balanced across. Not a door, a ladder or stairs, a gangplank or a teleport,
	 * which every golem uses to get anywhere and which made the count mostly doorways.
	 */
	boolean isShortcut()
	{
		return archetype != ARCHETYPE_NONE && archetype != ARCHETYPE_DOOR
			&& archetype != ARCHETYPE_LADDER && archetype != ARCHETYPE_STAIRS && archetype != ARCHETYPE_GANGPLANK;
	}

	@Override
	public String toString()
	{
		return "(" + fromX + "," + fromY + "," + fromPlane + ") -> ("
			+ toX + "," + toY + "," + toPlane + ") arch=" + archetype;
	}
}
