package com.golemsdontdie;

/**
 * Golem Crafting identifiers, harvested once from the game cache and fixed here.
 *
 * <p>These were read out of the local OSRS cache with the tools in {@code dev-tools/}
 * rather than guessed or discovered at runtime. The cache is authoritative for all of
 * it, and {@code NpcDefinition} carries the walk and idle animation IDs that the
 * RuneLite client API does not expose on {@code NPCComposition} at all — so hardcoding
 * beats detection here, and does not cost a first-golem calibration.
 *
 * <p>Sources, for whoever has to re-derive these after a content update:
 * <ul>
 *   <li>NPC and animation IDs — {@code dev-tools/CacheRecon.java}</li>
 *   <li>Animation frame counts and durations — {@code dev-tools/RegionRecon.java}</li>
 *   <li>Island terrain and extent — {@code dev-tools/TerrainProbe.java}</li>
 *   <li>Varbit layout and meaning — {@code dev-tools/VarbitRecon.java}</li>
 * </ul>
 */
final class GolemContent
{
	private GolemContent()
	{
	}

	/**
	 * The wandering golem produced by the activity.
	 *
	 * <p>Size 1, unscaled, no recolours, and — tellingly — no right-click options at
	 * all, which is what a purely decorative NPC looks like. It sits in the same ID
	 * block as the Wyrmscraig Goat (16298–16302), which is how it was identified as
	 * this content's golem rather than one of the sixty-odd other golems in the game.
	 */
	static final int GOLEM_NPC_ID = 16304;

	/**
	 * Walking animation. 8 frames over 48 cycles — a brisk, heavy gait.
	 *
	 * <p>Used as the fallback when restoring a golem whose saved animation is missing.
	 * Normally the animations come off the live actor at handover and are written to
	 * the save, but a save written by an older version, or one that caught a golem
	 * mid-transition, can carry -1 — and a golem with no animation stands frozen.
	 */
	static final int GOLEM_WALK_ANIMATION = 14452;

	/** Standing animation. 12 frames over 225 cycles — a slow idle shift. */
	static final int GOLEM_IDLE_ANIMATION = 14453;

	/**
	 * The crumble. Confirmed in game: both golems in the dry run played it and then
	 * vanished, at ticks 423→429 and 534→540.
	 *
	 * <p>It is on no definition — a death is played by a script, not declared as a
	 * pose — so it was found by narrowing the sequences either side of the golem's own
	 * and then watching a real golem die.
	 *
	 * <p>The gap between the crumble starting and the NPC despawning is <b>6 game
	 * ticks</b>, about 3.6 seconds. That is the window the swap has to happen in, and
	 * it is generous: the plugin acts on the first frame.
	 */
	static final int GOLEM_DEATH_ANIMATION = 14455;

	/**
	 * How long the crumble runs, in client cycles. 18 frames over 90 cycles, read from
	 * the sequence definition and matching the 6 game ticks observed between a real
	 * golem starting to crumble and vanishing.
	 */
	static final int GOLEM_DEATH_CYCLES = 90;

	/**
	 * Played once as the golem steps off its plinth, 27 ticks before the crumble.
	 *
	 * <p>The handover trigger: the copy takes over on the tick this animation ends,
	 * which is the only moment a golem is reliably standing still.
	 */
	static final int GOLEM_SPAWN_ANIMATION = 14451;

	/**
	 * The examine text, confirmed in game.
	 *
	 * <p>The cache has none of this: NPC 16304 carries no ops of any kind and no
	 * params. It is flagged interactable, which is what makes the client offer Examine
	 * at all, but the string itself is server-side.
	 *
	 * <p>A real golem's menu is exactly "Walk here", "Examine", "Cancel" — no action
	 * option. The copies match that.
	 *
	 * @see GolemMenu
	 */
	static final String GOLEM_EXAMINE = "*cough* Golem. *cough* GOHLEM. *cough*";

	/**
	 * Lighting offsets from the NPC definition, applied on top of the client's
	 * defaults for actors.
	 *
	 * <p>Easy to miss and very visible: the client lights an NPC's model with
	 * {@code 64 + ambient} and {@code 850 + contrast}, so a copy lit with the bare
	 * defaults is subtly but consistently wrong next to the real thing — flatter, and
	 * the wrong brightness. These are the golem's own values.
	 */
	static final int GOLEM_AMBIENT = 10;
	static final int GOLEM_CONTRAST = 60;

	/**
	 * The game's own count of golems this player has crafted, ever.
	 *
	 * <p>Called {@code GOLEM_CRAFTING_COUNT} in RuneLite's generated {@code VarbitID},
	 * but the cache does not name varbits and a name is not a promise, so it was read
	 * the same way as everything else here: it is sixteen bits wide, bits 16 to 31 of
	 * varp {@link #GOLEM_COUNT_VARP}, ceiling 65,535. Width is the argument. Two plinth
	 * stations would need two bits; sixteen is a tally and nothing else.
	 *
	 * <p>The low half of that same varp holds the two stations' carving state, which is
	 * what makes this Golem Crafting's own count rather than some other thing that
	 * counts. No client script in the cache reads it, so it is server-set and
	 * per-player: a golem another player crafts beside you cannot move it, and one the
	 * player crafts on mobile arrives with the varps at the next login.
	 *
	 * <p>Harvested with {@code dev-tools/VarbitRecon.java}.
	 *
	 * @see GolemTally
	 */
	static final int GOLEM_COUNT_VARBIT = 15738;

	/**
	 * The varp {@link #GOLEM_COUNT_VARBIT} is packed into.
	 *
	 * <p>Worth naming because the client reports a varp-level change as its own event,
	 * carrying the whole varp as the value — carving state and count together, which is
	 * a number in the millions and not a count of anything.
	 */
	static final int GOLEM_COUNT_VARP = 5709;

	/** Wyrmscraig, from the world map element at (2600, 2240). */
	static final int WYRMSCRAIG_REGION = 10275;

	/**
	 * The plinth golems are made on, observed: every golem taken over so far recorded
	 * this as its home. Used as the spawn point when reviving golems the plugin never
	 * saw made.
	 */
	static final int PLINTH_X = 2596;
	static final int PLINTH_Y = 2256;

	/**
	 * The regions the bundled island map covers: (39..41, 34..36) around the site.
	 *
	 * <p>Taken from the Shortest Path world collision map, which is generated from the
	 * cache with XTEA keys this machine does not have, and cross-checked tile by tile
	 * against collision recorded live during the dry run — 95% agreement overall, 99.9%
	 * on the one region away from the crafting site. Shipping it is what lets golems
	 * roam ground the player has never walked.
	 */
	static final int[] ISLAND_REGIONS = {
		10018, 10019, 10020,
		10274, 10275, 10276,
		10530, 10531, 10532,
	};

	/** True if this NPC is the crafted golem. */
	static boolean isGolem(int npcId)
	{
		return npcId == GOLEM_NPC_ID;
	}

}
