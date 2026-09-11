package com.golemsdontdie;

import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

/**
 * One way of getting from one tile to another that is not walking.
 *
 * <p>A door, a ladder, a stile, a ferry. These come from Shortest Path's transport
 * tables, reduced offline by {@code dev-tools/BuildTransports.java} to the rows a golem
 * could plausibly use — which is most of them, because most of the network is scenery you
 * interact with rather than items you carry.
 *
 * <p>Immutable and shared. There are around thirteen thousand of these and every golem
 * sees the same set, so they are built once at startup and never copied.
 *
 * <p>Requirements are kept as parsed arrays rather than re-read strings: a golem tests
 * these when it considers a transport, and a golem considers a transport every time it
 * enters a tile near one.
 */
final class GolemTransport
{
	// Archetypes. Written by BuildTransports; the numbering is a file format, so append
	// only — changing an existing value silently remaps every row in the shipped resource.
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
	 * The scene object this transport is, or -1.
	 *
	 * <p>Read from the menu column offline — the tables spell it as "Cross Gangplank
	 * 12164". It is what lets the plugin find the object's own model and animation, since
	 * {@code ObjectComposition} exposes no model ids at runtime.
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
	 * Index of the row that undoes this one, or -1.
	 *
	 * <p>Resolved once when the network is built. A golem that has just come through a
	 * door should not immediately go back through it, and finding the reverse by searching
	 * on each decision would be a scan of thirteen thousand rows per golem per tile.
	 */
	@Getter
	private int reverse = -1;

	/**
	 * This row's own index in the network.
	 *
	 * <p>Carried on the row rather than looked up. Cooldowns are keyed by index and are
	 * tested for every candidate transport every time a golem enters a tile, so asking the
	 * network to find the index would be a linear scan of the whole table on the hottest
	 * path in the plugin.
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

	void setReverse(int index)
	{
		this.reverse = index;
	}

	void setIndex(int index)
	{
		this.index = index;
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

	/** True if this transport has no requirements at all — most of the network. */
	boolean isUnconditional()
	{
		return skillRequirements.length == 0 && questRequirements.length == 0
			&& varbitRequirements.length == 0 && varpRequirements.length == 0;
	}

	/**
	 * How far the golem is carried, in tiles, or -1 where the question is meaningless.
	 *
	 * <p>Underground maps are laid out roughly 6,400 tiles north of the surface they sit
	 * under, so the straight-line distance across a trapdoor is nonsense — a ladder down
	 * measures as a continent-wide journey. Anything that big is reported as unknown
	 * rather than as a number that will be quietly wrong in a comparison somewhere.
	 */
	int travelDistance()
	{
		int d = Math.abs(fromX - toX) + Math.abs(fromY - toY);
		return d > 64 ? -1 : d;
	}

	/**
	 * The animation that plays while the golem uses this, or -1 for none.
	 *
	 * <p>Doors are deliberately silent. The door animates, not the player — a golem that
	 * walks onto the tile and out the other side is already doing the right thing, and it
	 * is the second largest group in the network, so getting this wrong would be very
	 * visible. Teleport-like transports are also -1: the golem holds its pose and goes.
	 */
	int animation()
	{
		int[] clips = animations();
		return clips.length == 0 ? -1 : clips[0];
	}

	/**
	 * The clips to play, in order, while the golem uses this.
	 *
	 * <p>OSRS builds its obstacle animations in threes — mount, loop, dismount — and using
	 * only the middle one throws away most of what was harvested. Nine of the twenty clips
	 * in the catalogue were going unused, and a ladder played the same reach whether the
	 * golem was going up or down.
	 *
	 * <p>So the set is chosen from what is actually known about this transport: which way
	 * the plane changes, and how far it carries the golem. The clips are shared
	 * {@code Animation} objects either way, so a set of three costs no more than one.
	 */
	int[] animations()
	{
		// A shortcut somebody has actually watched beats any guess made from its menu
		// text. The archetype below is a good guess and was measurably wrong for three of
		// the first four obstacles anyone stood in front of.
		int measured = MeasuredShortcuts.animationFor(objectId);
		if (measured != -1)
		{
			return new int[]{measured};
		}

		switch (archetype)
		{
			case ARCHETYPE_LADDER:
				// Reaching up for a ladder and reaching down off the top of one are
				// different clips, and which applies is written in the plane change.
				return new int[]{toPlane > fromPlane
					? GolemContent.ANIM_LADDER_GRAB
					: GolemContent.ANIM_LADDER_GRAB_TOP};

			case ARCHETYPE_CLIMB:
				if (toPlane < fromPlane)
				{
					// Going down is its own clip, not the ascent run backwards.
					//
					// Measured on Wyrmscraig, where the climb is two objects: the west
					// approach plays the climbing loop and the east approach plays 740,
					// HUMAN_CLIMBING_DOWN. Three independent reimplementations of the
					// server use 740 for a descent too, so this is not a local quirk.
					//
					// Only the plane can say which way a climb goes, so this catches the
					// ones that change floor and misses the ones that do not — a cliff on
					// flat ground still gets the ascent both ways unless the object itself
					// has been measured. That is the better failure: an ascent played
					// descending reads as effortful, where the reverse reads as falling.
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
				// A hop between stones and a running leap across a chasm are not the same
				// movement, and distance is the only thing here that can tell them apart.
				//
				// The thresholds themselves are unverified. Only the shortest rung has
				// been measured — Wyrmscraig's basalt stones, at two tiles, play 741 —
				// and the three longer clips come from the handover's catalogue by name.
				// There is reason to doubt the split exists at all: a 2019
				// reimplementation of the server plays that same 741 for every stepping
				// stone in the game regardless of span, and the single obstacle it does
				// hand a longer clip to is the one obstacle it demonstrably gets wrong.
				//
				// Worth one measurement on a four-tile crossing to settle.
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
				// Stepped over deliberately, one leg then the other, where a broken wall
				// is vaulted. Told apart offline from the menu text.
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
				// Walking the plank is walking. The golem keeps its gait and the boarding
				// reads from the geometry, which is what the real thing looks like.
				return new int[]{GolemContent.GOLEM_WALK_ANIMATION};

			default:
				return new int[0];
		}
	}

	/**
	 * True if this transport is worth drawing an animated copy of the object for.
	 *
	 * <p>The object's own animation is looked up at runtime from {@link PropFactory}, which
	 * holds whatever that specific plank, gate or ring actually plays — so nothing here
	 * needs to name clips. What this decides is only whether it is <i>worth</i> it.
	 *
	 * <p>Doors are excluded despite being the second largest group and despite animating.
	 * A door's swing is driven by the real object when a real player opens it; drawing a
	 * second copy swinging on top of a closed one would be a ghost door, which is worse
	 * than a golem walking through.
	 */
	boolean wantsPropAnimation()
	{
		return objectId > 0 && archetype != ARCHETYPE_NONE && archetype != ARCHETYPE_DOOR;
	}

	@Override
	public String toString()
	{
		return "(" + fromX + "," + fromY + "," + fromPlane + ") -> ("
			+ toX + "," + toY + "," + toPlane + ") arch=" + archetype;
	}
}
