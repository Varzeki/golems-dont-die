package com.golemsdontdie;

/**
 * Shortcuts whose real animation was watched in game, keyed by the object.
 *
 * <p>Which clip an obstacle plays is decided server-side and appears in no file - object
 * definitions carry the object's own animation, not the person's - so every entry below was
 * measured in game. Measurements beat the archetype mapping in {@link GolemTransport}, which
 * guesses from the menu text: it put the basalt stones on the 68-cycle
 * {@code HUMAN_STEPPINGSTONEJUMP} rather than the 38-cycle 741, doubling every hop. The
 * archetype still answers for the thirteen thousand nobody has measured.
 */
final class MeasuredShortcuts
{
	private MeasuredShortcuts()
	{
	}

	/**
	 * {objectId, animationId, ticks}. Ticks of zero mean the clip's own length is used, usually
	 * right: the cathedral door measured one tick against a 30-cycle clip, the cave three
	 * against 96.
	 */
	private static final int[][] MEASURED = {
		// Wyrmscraig basalt stepping stones; hops two ticks apart, the 38-cycle clip plus a breath.
		{62260, 741, 0},
		{62261, 741, 0},
		{62262, 741, 0},

		// Wyrmscraig's rock climb: two co-located objects, one per approach, east being the top.
		// West (2550, 2209) uses 62267, the 4435 climbing loop; east (2554, 2209) uses 62265,
		// HUMAN_CLIMBING_DOWN 740. Climbs are directional; the rows once used 62267 both ways
		// and the golem climbed the wrong way down.
		{62267, 4435, 0},
		{62265, 740, 0},

		// The cave mouth, both ends: three ticks, and a 96-cycle clip that agrees.
		{62219, 2796, 3},
		{62220, 2796, 3},

		// A door, for calibration: one tick against a 30-cycle clip.
		{62368, 4282, 1},
		{62369, 4282, 1},
	};

	/** The measured animation for an object, or -1 if unmeasured. */
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

	/** The measured duration in ticks, or 0 to use the clip's own length. */
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
}
