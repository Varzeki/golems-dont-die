package com.golemsdontdie;

/**
 * Shortcuts whose real animation was watched in game, keyed by the object.
 *
 * <p>Which clip an obstacle plays is decided server-side and appears in no file: object
 * definitions carry the object's own animation, not the person's, and client scripts are
 * interface logic. The only way to know is to use one and look, which is what
 * {@code ShortcutRecon} is for and where every entry below came from.
 *
 * <p>It is worth being blunt about why this table exists. The archetype mapping in
 * {@link GolemTransport} is a reasonable guess from the menu text, and measuring showed it
 * was wrong in ways that were plainly visible:
 *
 * <ul>
 *   <li>A basalt stepping stone plays <b>741</b>, a 38-cycle clip — not the 68-cycle
 *       {@code HUMAN_STEPPINGSTONEJUMP} the archetype assumed. That single error made
 *       every hop take twice as long as it should.</li>
 *   <li>Wyrmscraig's rock climb plays <b>4435</b> alone, not the mount-loop-dismount trio
 *       the climb archetype builds.</li>
 *   <li>The cave plays <b>2796</b>, which is not a climbing animation at all.</li>
 * </ul>
 *
 * <p>Measurements win over archetypes, always. The archetype remains the answer for the
 * thirteen thousand shortcuts nobody has stood in front of yet.
 */
final class MeasuredShortcuts
{
	private MeasuredShortcuts()
	{
	}

	/**
	 * {objectId, animationId, ticks}.
	 *
	 * <p>Ticks of zero mean the clip's own length should be used — which is the right
	 * answer more often than not, since the two agreed closely wherever both were
	 * observed: the cathedral door measured one tick against a 30-cycle clip, and the cave
	 * three ticks against 96.
	 */
	private static final int[][] MEASURED = {
		// Wyrmscraig basalt stepping stones. Hops observed two ticks apart, which is the
		// 38-cycle clip plus a breath between hops; the hop itself is the clip.
		{62260, 741, 0},
		{62261, 741, 0},
		{62262, 741, 0},

		// Wyrmscraig's rock climb, which turns out to be two objects and two animations.
		//
		// The recon found only 62267 and the transport rows were built from it alone, both
		// directions carrying the same id. The journal says otherwise: a player standing
		// west at (2550, 2209) clicks 62267 and plays 4435, the climbing loop, while one
		// standing east at (2554, 2209) clicks 62265 and plays 740, HUMAN_CLIMBING_DOWN.
		// Two co-located objects, one per approach, and the east side is the top.
		//
		// So a climb is directional, which the archetype had no idea about. Nothing else
		// on the island is built this way and it would have been very hard to notice by
		// eye — the golem played a climb either way, just the wrong one going down.
		{62267, 4435, 0},
		{62265, 740, 0},

		// The cave mouth, both ends. Three ticks, and a 96-cycle clip that agrees.
		{62219, 2796, 3},
		{62220, 2796, 3},

		// A door, for calibration: one tick, 30-cycle clip, exactly in step.
		{62368, 4282, 1},
		{62369, 4282, 1},
	};

	/** The measured animation for an object, or -1 if nobody has watched this one. */
	static int animationFor(int objectId)
	{
		for (int[] row : MEASURED)
		{
			if (row[0] == objectId)
			{
				return row[1];
			}
		}
		return -1;
	}

	/** The measured duration in game ticks, or 0 to use the clip's own length. */
	static int ticksFor(int objectId)
	{
		for (int[] row : MEASURED)
		{
			if (row[0] == objectId)
			{
				return row[2];
			}
		}
		return 0;
	}

	static boolean isMeasured(int objectId)
	{
		return animationFor(objectId) != -1;
	}
}
