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
 *   <li>Rig compatibility and transport animations — {@code dev-tools/FramemapProbe.java}</li>
 *   <li>The island's own shortcuts — {@code dev-tools/WyrmscraigRecon.java}, turned into
 *       transport rows by {@code dev-tools/BuildWyrmscraigTransports.java}</li>
 *   <li>Varbit layout and meaning — {@code dev-tools/VarbitRecon.java}</li>
 * </ul>
 *
 * <p>The shortcuts are not constants here because they are data, not identity: four
 * crossings, shipped in the transport table like every other one in the game. They live
 * in a table of our own only because no published table has them — Shortest Path maps the
 * whole world and has nothing on Wyrmscraig.
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

	// ---------------------------------------------------------------------------
	// Transport animations
	// ---------------------------------------------------------------------------

	/**
	 * The golem's rig, and why the game's own animations can be played on it.
	 *
	 * <p>An animation's frames are transforms addressed by vertex-group number, and the
	 * numbering belongs to a <i>framemap</i>. Play a frame built for one framemap against
	 * a model rigged for another and every transform lands on the wrong vertices. There is
	 * no retargeting at runtime: the ids match or the golem folds through itself.
	 *
	 * <p>{@code dev-tools/FramemapProbe.java} measured it. The golem's own four sequences
	 * — spawn, walk, idle and crumble — are all on <b>framemap 0</b>, which is the human
	 * rig and the largest in the game: 3,957 of the cache's 13,569 sequences sit on it,
	 * against 109 for the next biggest. The golem is a human-rigged NPC.
	 *
	 * <p>That makes every animation below free. They are shared {@code Animation} objects
	 * from {@link GolemModelFactory}, applied by the client's own
	 * {@code applyTransformations} exactly as the walk cycle already is — no authored
	 * clips, no cloned models, no per-frame re-lighting.
	 *
	 * <p>The ids are hardcoded for the same reason every other id here is: they were
	 * harvested once, and a constant cannot go stale in a way a player would have to
	 * debug. Re-derive with {@code FramemapProbe} after a cache update.
	 */
	static final int GOLEM_FRAMEMAP = 0;

	/**
	 * Reaching for a ladder or stair. Plays in place; the plane change happens under it.
	 *
	 * <p>The biggest single group in the transport network by a wide margin — ladders,
	 * staircases, ships' ladders, ropes and trapdoors together are 2,234 rows.
	 *
	 * <p><b>The downward clip is disputed and nobody has measured it.</b> 833 was taken
	 * from the handover's catalogue on the strength of its name, and three independent
	 * reimplementations of the server — one 2004-era, one 2009-era, one 2019-era — all
	 * play <b>827</b> ({@code HUMAN_PICKUPFLOOR}, a crouch-and-reach-down) for a ladder
	 * descended. Agreement across three eras is a good deal more than a name is worth.
	 *
	 * <p>It is left at 833 rather than quietly switched, because the fix for a guess is a
	 * measurement and not a better guess. Climb down any ladder with {@code ShortcutRecon}
	 * running and the journal will settle it for 613 rows. The presence of a modern
	 * {@code HUMAN_REACHFORLADDER_WALKMERGE} (13991) suggests this path has been reworked
	 * since any of those servers, so the answer may be none of the three.
	 */
	static final int ANIM_LADDER_GRAB = 828;
	static final int ANIM_LADDER_GRAB_TOP = 833;

	/**
	 * A sustained climb: mount, loop, dismount.
	 *
	 * <p>The loop carries the root motion, so one clip set covers every length — a
	 * three-tile scramble loops three times and an eight-tile one loops eight. Authoring
	 * a clip per obstacle length would be the standard mistake here.
	 */
	static final int ANIM_CLIMB_READY = 738;
	static final int ANIM_CLIMB_LOOP = 4435;
	static final int ANIM_CLIMB_MERGE = 12338;

	/**
	 * Climbing down, which is a clip of its own and not the ascent in reverse.
	 *
	 * <p>Measured: Wyrmscraig's rock climb is two co-located objects, and the one
	 * approached from the high side plays this rather than the loop above. Corroborated
	 * independently — three reimplementations of the server, spanning 2004 to 2019, all
	 * play 740 for a descent.
	 */
	static final int ANIM_CLIMB_DOWN = 740;

	/**
	 * Vaulting the Wilderness ditch. One clip, and on its own it covers 668 rows — more
	 * than every jump, stile, squeeze and swing in the game combined.
	 */
	static final int ANIM_DITCH_VAULT = 6132;

	/**
	 * Hopping between stepping stones — <b>measured</b>, not assumed.
	 *
	 * <p>Watching a player cross Wyrmscraig's basalt stones showed 741, a 38-cycle clip,
	 * repeated once per stone two ticks apart. The catalogue had named
	 * {@code HUMAN_STEPPINGSTONEJUMP} (769) for this, which is a real animation and 68
	 * cycles long — nearly double — so every hop ran at half speed until it was measured.
	 *
	 * <p>769 is kept below for the longer leaps it does suit.
	 */
	static final int ANIM_JUMP_STEPPINGSTONE = 741;

	/** The longer, higher jump the catalogue originally named for stepping stones. */
	static final int ANIM_JUMP_GAP = 769;
	static final int ANIM_JUMP_STONES = 1604;
	static final int ANIM_JUMP_LONG = 807;

	/** Climbing over a wall, stile or broken fence. */
	static final int ANIM_WALL_JUMP = 2583;
	static final int ANIM_STILE = 14235;

	/** Squeezing through a pipe or crevice. Mount, loop, dismount, as with the climb. */
	static final int ANIM_SQUEEZE_READY = 747;
	static final int ANIM_SQUEEZE_LOOP = 746;
	static final int ANIM_SQUEEZE_END = 748;

	/** Crossing a log, rope bridge or tightrope — slow, arms out. */
	static final int ANIM_BALANCE_WALK = 762;
	static final int ANIM_BALANCE_WALK_LOOP = 7134;
	static final int ANIM_TIGHTROPE = 4772;

	/**
	 * Three animations the golem deliberately does not play, and why.
	 *
	 * <p>The handover's catalogue listed these alongside the human clips, but the probe
	 * shows they are on other rigs entirely: {@code DOCK_GANGPLANK01} (13562) is on
	 * framemap 2503 and the raft helm pair (13335/13336) on framemap 2486. They are not
	 * player animations that happen to be incompatible — they are <i>prop</i> animations,
	 * belonging to the gangplank and the boat. Nothing is lost.
	 *
	 * <ul>
	 *   <li><b>Gangplank.</b> Walking a plank is walking. The golem keeps its own gait and
	 *       the boarding reads from the geometry, which is what a real boarding looks
	 *       like — the plank animates, not the person on it.</li>
	 *   <li><b>Helm grip.</b> Belongs to the boat model, not its passenger.</li>
	 * </ul>
	 *
	 * <p>The helm pair is still worth having: {@link FakeRaft} draws a boat model, and a
	 * boat model is on framemap 2486 — the same rig these clips were built for. So the
	 * raft can play its own helm animation even though the golem cannot.
	 */
	static final int ANIM_RAFT_HELM = 13335;
	static final int ANIM_RAFT_HELM_LOOP = 13336;
	static final int RAFT_FRAMEMAP = 2486;

	/**
	 * The boat drawn under a golem at sea: the classic rowing boat NPC, "Boat".
	 *
	 * <p>Chosen by measuring models with {@code dev-tools/BoatRecon.java}, because the obvious
	 * sources are not boats. Sailing's boat table lists crews — "Pirate", "Trader Crewmember"
	 * — so a search of it for anything named like a vessel never found one, and golems sailed
	 * on nothing. The NPCs RuneLite calls {@code SAILING_BOAT_SAIL01_*} are sails: models 58237
	 * and 58238 are flat sheets a thousand units wide, 1,159 tall and 83 thick. This one's hull,
	 * model 17556, is 144 wide, 94 tall and 384 long — a rowing boat three tiles long, which is
	 * about the size of a golem's ride.
	 */
	static final int RAFT_NPC = 3834;

	/** The boat's own standing animation, on its own rig. */
	static final int RAFT_ANIM = 4756;

	/**
	 * Animations that belong to scenery, played on a copy of the object rather than on
	 * the golem.
	 *
	 * <p>These are the two the probe found on other rigs. Neither can be applied to the
	 * golem — framemaps 2503 and 2486 against the golem's 0 — but both are perfectly
	 * playable on the thing they were authored for, which is what {@link FakeProp} draws.
	 * The gangplank lowers, the golem walks up it, and the two are separate actors exactly
	 * as they are in the real game.
	 */
	/**
	 * Kept for the record rather than used. {@link PropFactory} reads each object's own
	 * {@code animationID} out of the cache, so a plank, a gate and a fairy ring each get
	 * the motion they were authored with — which is strictly better than naming one clip
	 * here and applying it to all of them.
	 */
	static final int ANIM_PROP_GANGPLANK = 13562;
	static final int PROP_GANGPLANK_FRAMEMAP = 2503;

	/** How long a scenery animation is drawn for, in client cycles. */
	static final int PROP_CYCLES = 60;

	/** True if this NPC is the crafted golem. */
	static boolean isGolem(int npcId)
	{
		return npcId == GOLEM_NPC_ID;
	}

}
