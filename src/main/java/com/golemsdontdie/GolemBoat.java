package com.golemsdontdie;

import lombok.Getter;

/**
 * The boats golems sail, and what each is made of.
 *
 * <p>Sailing's boats are scene objects, so their models live only in the cache and are read there
 * rather than named by the client: {@code dev-tools/BoatRecon.java} lists every object built from
 * a model in the boat family, which sorts them into three sizes seven variants apiece. The raft is
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
		128, 1, new int[][]{{0, 0}}),

	/**
	 * The 2x6 boat, hull 58218 from object 59501: three or four golems, one at the helm and the
	 * rest along a deck 324 units across and 714 long.
	 */
	SKIFF(58218, 58257, 60457, Palette.LARGE_FROM, Palette.LARGE_TO, 320, 4,
		new int[][]{{0, 0}, {-70, 180}, {70, 300}, {-40, 420}}),

	/**
	 * The 3x8 boat, hull 58220 from object 59508: up to eight, on a deck 480 units across and
	 * 1,090 long.
	 */
	SLOOP(58220, 58267, 60470, Palette.LARGE_FROM, Palette.LARGE_TO, 460, 8,
		new int[][]{{0, 0}, {-110, 170}, {110, 170}, {-110, 380}, {110, 380}, {-110, 590},
			{110, 590}, {0, 780}});

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

	/** How many golems this boat carries, the one at the helm included. */
	private final int berths;

	/**
	 * Where each of them stands, from the middle of the hull: across, then along. The first is the
	 * helm, and is the offset every other is measured against, so a crew rides where the boat is
	 * rather than beside it.
	 */
	private final int[][] deck;

	GolemBoat(int hullModel, int mastModel, int clothModel, short[] hullFrom, short[] hullTo,
		int helmOffset, int berths, int[][] deck)
	{
		this.hullModel = hullModel;
		this.mastModel = mastModel;
		this.clothModel = clothModel;
		this.hullFrom = hullFrom;
		this.hullTo = hullTo;
		this.helmOffset = helmOffset;
		this.berths = berths;
		this.deck = deck;
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
