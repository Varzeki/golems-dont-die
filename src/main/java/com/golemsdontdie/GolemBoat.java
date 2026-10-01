package com.golemsdontdie;

import lombok.*;

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
@AllArgsConstructor
enum GolemBoat
{
	/**
	 * Sailing's 1x3 raft: one golem at the helm, hull 58216 from object 59494. The boat golems
	 * have sailed since sailing was added, and still what one leaving on its own takes.
	 */
	RAFT(58216, 58248, 60445, GolemContent.RAFT_HULL_RECOLOUR_FROM, GolemContent.RAFT_HULL_RECOLOUR_TO,
		128, -90, 94, -221, 228, 1, new int[][]{{0, 0}}, -1, 0, 0, 0, 0, 0, 256, -1, 13373, 13881,
		GolemContent.RAFT_HELM_MODEL, GolemContent.RAFT_HELM_RECOLOUR_FROM, GolemContent.RAFT_HELM_RECOLOUR_TO, 128,
		GolemContent.ANIM_GOLEM_HELM),

	/**
	 * The 2x6 boat, hull 58218 from object 59501: three or four golems, one at the helm and the
	 * rest along a deck 324 units across and 714 long.
	 */
	SKIFF(58218, 58257, 60457, Palette.LARGE_FROM, Palette.LARGE_TO, 220, -162, 162, -438, 276, 4,
		new int[][]{{0, 0}, {-80, 42}, {80, 172}, {-40, 302}}, 58227, -192, -320, 64, 64, 64, 192, 58209, 13382, 13890,
		58204, Palette.HELM_FROM, Palette.HELM_TO, 92, 13352),

	/**
	 * The 3x8 boat, hull 58220 from object 59508: up to eight, on a deck 480 units across and
	 * 1,090 long.
	 */
	SLOOP(58220, 58267, 60470, Palette.LARGE_FROM, Palette.LARGE_TO, 460, -240, 240, -554, 536, 8,
		new int[][]{{0, 0}, {-120, 52}, {120, 52}, {-120, 272}, {120, 272}, {-120, 492},
			{120, 492}, {0, 702}}, 58228, -256, -448, 128, 448, 128, 576, 58210, 13391, 13899,
		58206, Palette.HELM_FROM, Palette.HELM_TO, 332, 13363);

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

		/** The bronze keel's own swap, from its object: the lowest keel a boat is built with. */
		static final short[] KEEL_FROM = {21435, 21464};
		static final short[] KEEL_TO = {5652, 5656};

		/** The Merchants' trim, the gold one: the same model as the wooden trim, painted gold. */
		static final short[] TRIM_FROM = {-31833, -31813, -31784};
		static final short[] TRIM_TO = {7104, 7104, 7114};

		/** The larger helms' own swap, as their basic wood objects paint them: wood, bronze fittings. */
		static final short[] HELM_FROM = {-11322, -11333, -11343, 21464, 21435, -31784, -31813, -31833};
		static final short[] HELM_TO = {6581, 6573, 6569, 5656, 5652, 6581, 6573, 6569};
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
	 * +z, a model facing -z. Only a raft's is stood on; see steerOffset.
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
	 * Where each of them stands, across then along, measured from the helm: the first of them, at
	 * nothing. The boat is drawn under the golem at the helm, so a crew placed from it rides where
	 * the boat is rather than beside it.
	 */
	private final int[][] deck;

	/**
	 * The keel under the larger hulls, with the bowsprit at its front, or -1 for the raft, which has
	 * none: without it a skiff and a sloop sailed with no spar at the bow. The bronze keel's model.
	 *
	 * <p>A keel is an object of its own on the boat, a tile off the hull, so its model is moved by
	 * that much to sit under the hull. The sloop's is as a sloop's world lays its parts out (keel
	 * on tile 1,3, the 3x8 hull from 2,3; a skiff's keel on 2,1, its 2x6 hull from 3,1).
	 */
	private final int keelModel;
	private final int keelX;
	private final int keelZ;

	/**
	 * Where the mast and the sail's cloth stand from the middle of the hull, across then along. Each
	 * is an object of its own on a tile of its own; on the raft that is the middle tile, but a
	 * sloop's rig stands three and a half tiles along and one across, and drawn at the middle it
	 * stood out over the sea beside its own hull. Both as the game lays its own boats out.
	 */
	private final int mastX;
	private final int mastZ;
	private final int clothX;
	private final int clothZ;

	/**
	 * The trim round the edge of the hull, or -1 for the raft, which has none: the Merchants'
	 * trim, which golem boats wear. A player's own boat wears whichever they chose.
	 */
	private final int trimModel;

	/**
	 * What the mast and the cloth play while the boat is under way: the sail full. Unanimated, a
	 * sail stood in its bind pose, which is neither up nor down and in the wrong place, and a skiff's
	 * foresail was not there at all. The cloth's is an _OFFSET animation, made for cloth standing
	 * along from its mast as it does on every boat - on a raft two tiles, past the helm: drawn at
	 * the mast, a raft's sail flew off the raft.
	 */
	private final int mastAnimation;
	private final int clothAnimation;

	/**
	 * The helm, and the colours its object swaps in. A raft's is a stub at the stern; a skiff's and
	 * a sloop's are tillers whose pole reaches a tile forward, to whoever is steering. Drawn with the
	 * raft's stub, a skiff's helmsman stood on the stern holding nothing.
	 */
	private final int helmModel;
	private final short[] helmFrom;
	private final short[] helmTo;

	/**
	 * Where the golem steering stands from the middle of the hull, along its length: on a raft's
	 * helm, and a tile before a tiller, toward the bow, where a player stands to steer one. The rest
	 * of the deck is measured from here.
	 */
	private final int steerOffset;

	/** The pose a player steers this boat in, which the golem at the helm takes: one per helm. */
	private final int helmPose;

	static short[] trimFrom()
	{
		return Palette.TRIM_FROM;
	}

	static short[] trimTo()
	{
		return Palette.TRIM_TO;
	}

	static short[] keelFrom()
	{
		return Palette.KEEL_FROM;
	}

	static short[] keelTo()
	{
		return Palette.KEEL_TO;
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
