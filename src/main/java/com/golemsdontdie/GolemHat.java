package com.golemsdontdie;

import lombok.*;

/**
 * Hats a golem can be given, from its page: real head-slot items, worn as the game wears them.
 *
 * <p>Each is the item's own worn model and the recolour its item applies, read from the cache. A
 * golem is as tall as a player and shares the player's skeleton, so a hat sits on its head where
 * it sits on a player's, and nods and turns with it through every dance and climb: the hats are
 * tied to the same head as the golem's own. Saved by item id, so the list can grow and reorder.
 */
@Getter
@AllArgsConstructor
enum GolemHat
{
	NONE("None", 0, -1, null, null),
	RED_PARTYHAT("Red partyhat", 1038, 187, null, null),
	YELLOW_PARTYHAT("Yellow partyhat", 1040, 187, new short[]{926}, new short[]{11200}),
	BLUE_PARTYHAT("Blue partyhat", 1042, 187, new short[]{926}, new short[]{-21568}),
	GREEN_PARTYHAT("Green partyhat", 1044, 187, new short[]{926}, new short[]{22464}),
	PURPLE_PARTYHAT("Purple partyhat", 1046, 187, new short[]{926}, new short[]{-14400}),
	WHITE_PARTYHAT("White partyhat", 1048, 187, new short[]{926}, new short[]{127}),
	SANTA_HAT("Santa hat", 1050, 189, null, null),
	RED_HALLOWEEN_MASK("Red halloween mask", 1057, 3188, null, null),
	GREEN_HALLOWEEN_MASK("Green halloween mask", 1053, 3188, new short[]{926}, new short[]{22420}),
	BLUE_HALLOWEEN_MASK("Blue halloween mask", 1055, 3188, new short[]{926}, new short[]{-21602}),
	CHEFS_HAT("Chef's hat", 1949, 199, new short[]{61}, new short[]{127}),
	WIZARD_HAT("Wizard hat", 579, 202, null, null),
	PIRATE_HAT("Pirate hat", 8950, 190, new short[]{7690}, new short[]{414}),
	BLACK_CAVALIER("Black cavalier", 2643, 186, new short[]{7073}, new short[]{8}),
	BEARHEAD("Bearhead", 4502, 5669, new short[]{61}, new short[]{-22239});

	/** As the picker shows it. */
	private final String label;

	/** The item it is, which is what a save keeps; 0 for none. */
	private final int itemId;

	/** The worn model, or -1 for none. */
	private final int model;

	/** The colours its item swaps in over the model's, or null for none. */
	private final short[] recolourFrom;
	private final short[] recolourTo;

	/** The hat a save names by item id; none for 0 or an item no longer on the list. */
	static GolemHat byItem(int itemId)
	{
		for (GolemHat hat : values())
		{
			if (hat.itemId == itemId)
			{
				return hat;
			}
		}
		return NONE;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
