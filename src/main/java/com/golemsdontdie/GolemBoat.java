package com.golemsdontdie;

import lombok.Getter;

/**
 * The boats golems sail, and what each is made of.
 *
 * <p>Sailing's boats are scene objects, so their models live only in the cache and are read there
 * rather than named by the client. Every object built from a model in the boat family sorts into
 * three sizes, seven variants apiece. The raft is
 * the one a golem takes alone; the other two carry a crew.
 *
 * <p>Every hull is painted over a place-holder palette of purple arrows, as the raft's was, and the
 * two larger ones share a palette with each other.
 */
@Getter
enum GolemBoat
{
	/**
	 * Sailing's 1x3 raft: one golem at the helm, hull 58216 from object 59494. The boat golems
	 * have sailed since sailing was added, and still what one leaving on its own takes.
	 */
	RAFT(58216, 58248, 60445, GolemContent.RAFT_HULL_RECOLOUR_FROM, GolemContent.RAFT_HULL_RECOLOUR_TO,
		128, -90, 94, -221, 228, 1, new int[][]{{0, 0}}),

	/**
	 * The 2x6 boat, hull 58218 from object 59501: three or four golems, one at the helm and the
	 * rest along a deck 324 units across and 714 long.
	 */
	SKIFF(58218, 58257, 60457, Palette.LARGE_FROM, Palette.LARGE_TO, 220, -162, 162, -438, 276, 4,
		new int[][]{{0, 0}, {-80, 170}, {80, 300}, {-40, 430}}),

	/**
	 * The 3x8 boat, hull 58220 from object 59508: up to eight, on a deck 480 units across and
	 * 1,090 long.
	 */
	SLOOP(58220, 58267, 60470, Palette.LARGE_FROM, Palette.LARGE_TO, 460, -240, 240, -554, 536, 8,
		new int[][]{{0, 0}, {-120, 180}, {120, 180}, {-120, 400}, {120, 400}, {-120, 620},
			{120, 620}, {0, 830}});

	/**
	 * The palette both larger hulls are painted from. Held in a class of its own because a
	 * constant of the enum may not name a field of it: the constants are built first.
	 *
	 * <p>The raft has a palette of its own, in {@link GolemContent}, where it was read before
	 * these two were known about.
	 */
	private static final class Palette
	{
		static final short[] LARGE_FROM = {-11372, -11362, -11353, -11343, -11333, -11322};
		static final short[] LARGE_TO = {6558, 6563, 6565, 6569, 6573, 6577};
	}

	/** The hull, the mast, and the sail's cloth, which is a separate object from the mast. */
	private final int hullModel;
	private final int mastModel;
	private final int clothModel;

	/** The place-holder colours the hull's object swaps out, and what it swaps in. */
	private final short[] hullFrom;
	private final short[] hullTo;

	/**
	 * Where the helm sits from the middle of the hull, along its length, in model units. Behind is
	 * +z, a model facing -z, and the golem steering stands on it.
	 */
	private final int helmOffset;

	/**
	 * How far the hull reaches, in its own model units: across, then along, bow first. Read out of
	 * the cache offline, and here so that a berth can be checked
	 * against the deck it is meant to be standing on rather than guessed at.
	 */
	private final int hullMinX;
	private final int hullMaxX;
	private final int hullMinZ;
	private final int hullMaxZ;

	/** How many golems this boat carries, the one at the helm included. */
	private final int berths;

	/**
	 * Where each of them stands, from the middle of the hull: across, then along. The first is the
	 * helm, and is the offset every other is measured against, so a crew rides where the boat is
	 * rather than beside it.
	 */
	private final int[][] deck;

	GolemBoat(int hullModel, int mastModel, int clothModel, short[] hullFrom, short[] hullTo,
		int helmOffset, int hullMinX, int hullMaxX, int hullMinZ, int hullMaxZ, int berths, int[][] deck)
	{
		this.hullModel = hullModel;
		this.mastModel = mastModel;
		this.clothModel = clothModel;
		this.hullFrom = hullFrom;
		this.hullTo = hullTo;
		this.helmOffset = helmOffset;
		this.hullMinX = hullMinX;
		this.hullMaxX = hullMaxX;
		this.hullMinZ = hullMinZ;
		this.hullMaxZ = hullMaxZ;
		this.berths = berths;
		this.deck = deck;
	}

	/**
	 * Where a berth stands in the hull's own frame: across, and along with the stern positive. The
	 * helm is at {@link #helmOffset} and everyone else is forward of it.
	 */
	int[] berthInHull(int berth)
	{
		int[] slot = deck[berth];
		return new int[]{slot[0], helmOffset - slot[1]};
	}

	/**
	 * How far the boat reaches from its middle, in model units, which is what the client culls and
	 * sorts it by. Taken from the hull rather than assumed: a sloop is three times the raft's
	 * length, and at the raft's radius most of it would be clipped away.
	 */
	int drawRadius()
	{
		return Math.max(Math.max(-hullMinX, hullMaxX), Math.max(-hullMinZ, hullMaxZ));
	}

	/** The smallest boat that carries this many golems; the raft for one. */
	static GolemBoat forCrew(int golems)
	{
		if (golems <= RAFT.berths)
		{
			return RAFT;
		}
		return golems <= SKIFF.berths ? SKIFF : SLOOP;
	}
}
