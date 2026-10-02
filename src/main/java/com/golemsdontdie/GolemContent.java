package com.golemsdontdie;

import net.runelite.api.coords.WorldPoint;

/**
 * Golem Crafting identifiers, harvested once from the game cache and fixed here.
 *
 * <p>Read offline from the local OSRS cache: ids, frame counts, terrain, rigs, shortcuts and
 * varbits. The cache's NPC definition carries walk and idle animation IDs RuneLite does not expose
 * on {@code NPCComposition} at all. The four
 * island shortcuts are data, not identity, so they ship in the transport table.
 */
final class GolemContent
{
	private GolemContent()
	{
	}

	/**
	 * The wandering golem produced by the activity: size 1, unscaled, no recolours, no right-click
	 * options. Told from the sixty-odd other golems by its ID block, beside the Wyrmscraig Goat
	 * (16298–16302).
	 */
	static final int GOLEM_NPC_ID = 16304;

	/**
	 * Walking animation, 8 frames over 48 cycles. Also the fallback when a saved golem's is -1,
	 * since a golem with no animation stands frozen.
	 */
	static final int GOLEM_WALK_ANIMATION = 14452;

	/** Standing animation. 12 frames over 225 cycles. */
	static final int GOLEM_IDLE_ANIMATION = 14453;

	/**
	 * The crumble, on no definition - a death is played by a script - so it was found by watching
	 * one die. Golems vanish 6 ticks later; those 6 ticks are the swap's window.
	 */
	static final int GOLEM_DEATH_ANIMATION = 14455;

	/** How long the crumble runs: 18 frames over 90 cycles, three ticks of the six the game leaves. */
	static final int GOLEM_DEATH_CYCLES = 90;

	/**
	 * Played once as the golem steps off its plinth, 27 ticks before the crumble. The handover
	 * trigger: the copy takes over as it ends, the only moment a golem is reliably still.
	 */
	static final int GOLEM_SPAWN_ANIMATION = 14451;

	/**
	 * The examine text, confirmed in game; the cache has none of it, NPC 16304 carrying no ops or
	 * params. A real golem's menu is "Walk here", "Examine", "Cancel".
	 *
	 * @see GolemMenu
	 */
	static final String GOLEM_EXAMINE = "*cough* Golem. *cough* GOHLEM. *cough*";

	/**
	 * The golem's own lighting offsets from the NPC definition: the client lights an NPC with
	 * {@code 64 + ambient} and {@code 850 + contrast}, so bare defaults look wrong.
	 */
	static final int GOLEM_AMBIENT = 10;
	static final int GOLEM_CONTRAST = 60;

	/**
	 * The jeweller's chisel: golem crafting's own rare reward, one craft in three hundred. A golem
	 * crafted can come off the plinth holding one, at the same odds. The item's inventory icon's
	 * model, the only model it has: it is never worn or wielded. See GolemModelFactory.
	 */
	static final int CHISEL_MODEL = 61705;
	static final int CHISEL_ODDS = 300;

	/**
	 * Said when a golem comes off the plinth holding one: the game's own line for the chisel, in its
	 * green, but the golem keeps this one.
	 */
	static final String CHISEL_MESSAGE = "<col=006000>As you complete the golem, it shows you a chisel it found...</col>";

	/**
	 * Played with it: the chime of a gem found while mining, the likeliest of the sounds a plugin can
	 * play to be the game's own for a golem's gift. The game may play a jingle, which a plugin cannot.
	 */
	static final int CHISEL_SOUND = 2655;

	/**
	 * The game's own count of golems this player has crafted, ever: bits 16 to 31 of varp
	 * {@link #GOLEM_COUNT_VARP}, ceiling 65,535. Sixteen bits is the argument that it is a tally,
	 * and the low half of that varp holds the two plinth stations' carving state, which makes it
	 * Golem Crafting's own. RuneLite's {@code VarbitID} names it {@code GOLEM_CRAFTING_COUNT}, but
	 * the cache does not name varbits. No client script reads it: it is server-set per player.
	 *
	 * @see GolemTally
	 */
	static final int GOLEM_COUNT_VARBIT = 15738;

	/**
	 * The varp {@link #GOLEM_COUNT_VARBIT} is packed into. Named because a varp-level change is
	 * reported as its own event carrying the whole varp - carving state and count together.
	 */
	static final int GOLEM_COUNT_VARP = 5709;

	/** Wyrmscraig, from the world map element at (2600, 2240). */
	static final int WYRMSCRAIG_REGION = 10275;

	/** The plinth golems are made on, observed; the spawn point for golems never seen made. */
	static final int PLINTH_X = 2596;
	static final int PLINTH_Y = 2256;

	/**
	 * Where a golem that had to be fetched is put down: in front of the tent south of the plinth.
	 * Not the plinth itself - a golem appearing there looks like one just crafted, to a player who
	 * crafted nothing - and a golem stepping out by a tent is a golem that was only ever resting.
	 */
	static final WorldPoint RECOVERY = new WorldPoint(2597, 2224, 0);

	/**
	 * Where a revived golem appears: the tile beside each of the two plinths that a crafted golem
	 * steps off onto, not the plinths themselves. Each revived golem takes one at random.
	 */
	static final WorldPoint[] REVIVE_TILES = {
		new WorldPoint(2595, 2254, 0),
		new WorldPoint(2596, 2256, 0),
	};

	/**
	 * The two plinths a golem is carved on, and the six states each one shows: Empty plinth,
	 * Golem base, three Unfinished golems and an Unpowered golem.
	 *
	 * <p>The scene holds the two stations, 62351 and 62352, and the client swaps in whichever
	 * state the carving varbit says - which is why a menu has to know all eight. Found by asking
	 * the cache which objects the carving halves of {@link #GOLEM_COUNT_VARP} drive.
	 */
	private static final int[] PLINTHS = {62351, 62352, 62353, 62354, 62355, 62356, 62357, 62358};

	/** Whether an object is one of the carving plinths, whatever state it is showing. */
	static boolean isPlinth(int objectId)
	{
		for (int plinth : PLINTHS)
		{
			if (plinth == objectId)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The regions the bundled island map covers: (39..41, 34..36) around the site.
	 *
	 * <p>From the Shortest Path world collision map (cache-generated, with XTEA keys this machine
	 * lacks), cross-checked against collision recorded live - 95% agreement overall, 99.9% away
	 * from the site. Shipping it lets golems roam unwalked ground.
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
	 * <p>Animation frames are transforms addressed by vertex-group number and the numbering
	 * belongs to a <i>framemap</i>, with no retargeting at runtime, so a frame played against a
	 * model rigged for another lands every transform on the wrong vertices. {@code FramemapProbe}
	 * put the golem's four sequences on <b>framemap 0</b>, the human rig, so every animation below
	 * plays free.
	 */
	static final int GOLEM_FRAMEMAP = 0;

	/**
	 * Reaching for a ladder or stair; plays in place, the plane change happening under it. The
	 * biggest group in the network by far, 2,234 rows with staircases, ropes and trapdoors.
	 *
	 * <p>The downward clip, 833, is a guess from the catalogue's name and unmeasured; three server
	 * reimplementations, 2004 to 2019, play <b>827</b> ({@code HUMAN_PICKUPFLOOR}) instead, and a
	 * modern {@code HUMAN_REACHFORLADDER_WALKMERGE} (13991) suggests neither. It stays until a
	 * ladder is climbed in game, settling 613 rows.
	 */
	static final int ANIM_LADDER_GRAB = 828;
	static final int ANIM_LADDER_GRAB_TOP = 833;

	/**
	 * A sustained climb: mount, loop, dismount. The loop carries the root motion, so one clip set
	 * covers every length: a three-tile scramble loops three times.
	 */
	static final int ANIM_CLIMB_READY = 738;
	static final int ANIM_CLIMB_LOOP = 4435;
	static final int ANIM_CLIMB_MERGE = 12338;

	/**
	 * Climbing down, a clip of its own and not the ascent in reverse. Measured: Wyrmscraig's rock
	 * climb is two co-located objects and the one approached from above plays this, not the loop.
	 * Three server reimplementations also play 740.
	 */
	static final int ANIM_CLIMB_DOWN = 740;

	/**
	 * Vaulting the Wilderness ditch. One clip covering 668 rows, more than every jump, stile,
	 * squeeze and swing combined.
	 */
	static final int ANIM_DITCH_VAULT = 6132;

	/**
	 * Hopping between stepping stones - <b>measured</b>, not assumed. A player crossing
	 * Wyrmscraig's basalt stones played 741, a 38-cycle clip, once per stone. The catalogue named
	 * {@code HUMAN_STEPPINGSTONEJUMP} (769), real but 68 cycles, so every hop ran at half speed.
	 */
	static final int ANIM_JUMP_STEPPINGSTONE = 741;

	/** The longer, higher jump the catalogue originally named for stepping stones. */
	static final int ANIM_JUMP_GAP = 769;
	static final int ANIM_JUMP_STONES = 1604;
	static final int ANIM_JUMP_LONG = 807;

	/** Climbing over a wall, stile or broken fence. */
	static final int ANIM_WALL_JUMP = 2583;

	/**
	 * Going through a door without opening it: the Stronghold of Security's door drag, pushed
	 * through on the spot and out the far side. What the player was seen playing at Wyrmscraig's
	 * cathedral doors, and what every door a golem passes plays.
	 */
	static final int ANIM_DOOR_THROUGH = 4282;
	static final int ANIM_STILE = 14235;

	/** Squeezing through a pipe or crevice. Mount, loop, dismount, as with the climb. */
	static final int ANIM_SQUEEZE_READY = 747;
	static final int ANIM_SQUEEZE_LOOP = 746;
	static final int ANIM_SQUEEZE_END = 748;

	/** Crossing a log, rope bridge or tightrope - slow, arms out. */
	static final int ANIM_BALANCE_WALK = 762;
	static final int ANIM_BALANCE_WALK_LOOP = 7134;
	static final int ANIM_TIGHTROPE = 4772;

	/**
	 * Three animations the golem does not play: they are <i>prop</i> animations on other rigs -
	 * {@code DOCK_GANGPLANK01} (13562) on framemap 2503, the raft helm pair (13335/13336) on
	 * framemap 2486. Nothing is lost; walking a plank is walking. The pair is kept because
	 * {@link FakeRaft} draws a boat model, itself on framemap 2486.
	 */
	static final int ANIM_RAFT_HELM = 13335;
	static final int ANIM_RAFT_HELM_LOOP = 13336;
	static final int RAFT_FRAMEMAP = 2486;

	/**
	 * Sailing's own raft, the 1x3 boat, as the three models it is built from.
	 *
	 * <p>Read offline from the cache: Sailing's boats are scene objects, whose models live only in
	 * the cache - {@code SAILING_BOAT_HULL_KANDARIN_1X3_WOOD} (59494),
	 * {@code SAILING_BOAT_SAIL_KANDARIN_1X3_WOOD} (59530) and
	 * {@code SAILING_BOAT_STEERING_KANDARIN_1X3_WOOD_IN_USE} (59555). The hull is centred along z,
	 * 450 units long; the sail spans it from the middle tile; the helm is one tile at the stern.
	 * Searching NPCs gave the swamp rowing boat, 3834.
	 */
	static final int RAFT_HULL_MODEL = 58216;
	static final int RAFT_SAIL_MODEL = 58248;
	static final int RAFT_HELM_MODEL = 58197;

	/**
	 * The sail's cloth, from the linen "Sails" object ({@code SAILING_BOAT_SAIL_KANDARIN_1X3_LINEN},
	 * 29506). The wood sail object is only the mast; without this the raft had no sail.
	 */
	static final int RAFT_SAIL_CLOTH_MODEL = 60445;

	/**
	 * Colours the hull and helm objects swap in, over their models' place-holder palette of
	 * purple arrows. Read from the cache with the models.
	 */
	static final short[] RAFT_HULL_RECOLOUR_FROM = {-11372, -11362, -11353, 6086, 21435};
	static final short[] RAFT_HULL_RECOLOUR_TO = {6682, 6930, 6697, 6697, 5652};
	static final short[] RAFT_HELM_RECOLOUR_FROM = {-11322, -11353};
	static final short[] RAFT_HELM_RECOLOUR_TO = {6693, 6689};

	/**
	 * The sailable cave under Wyrmscraig: the water inside the cave mouth at each end, and the way
	 * a boat faces coming out. The cave dock is on a lake on the underground map; a boat sails to
	 * the lake mouth ({@code WYRMSCRAIG_CAVE_EXIT_SAILABLE}, 2565,8604) and is put out on the real
	 * sea at the north coast ({@code WYRMSCRAIG_CAVE_ENTRANCE_SAILABLE}, 2562,2203). Read from the
	 * cache.
	 */
	static final int CAVE_LAKE_MOUTH_X = 2567;
	static final int CAVE_LAKE_MOUTH_Y = 8606;
	static final int CAVE_SEA_MOUTH_X = 2561;
	static final int CAVE_SEA_MOUTH_Y = 2203;
	/** Out of the sea mouth is west; into the lake from its mouth is east. */
	static final int CAVE_SEA_MOUTH_OUTWARD = 512;
	static final int CAVE_LAKE_MOUTH_INWARD = 1536;

	/**
	 * Where the helm's tile sits from the middle of the raft, along its length, in model units:
	 * the stern, one tile behind. Behind is +z, a model facing -z. This was -128, which put the
	 * helm two tiles from the golem meant to be holding it.
	 */
	static final int RAFT_HELM_OFFSET = 128;

	/**
	 * The player's pose at a raft's helm, {@code HUMAN_SAILING_ALPHA_HELM_RAFT01_ACTIVE01_LOOP}.
	 * On framemap 0, the golem's own rig; the raft-side clips (13334–13336) are on 2486.
	 */
	static final int ANIM_GOLEM_HELM = 13341;

	/**
	 * Scenery animations belong to the scenery's own framemaps, 2503 and 2486 against the golem's
	 * 0, so only {@link FakeProp} can draw them.
	 *
	 * <p>Kept for the record rather than used: {@link PropFactory} reads each object's own
	 * {@code animationID} from the cache, so a plank, a gate and a fairy ring each get their own.
	 */
	static final int ANIM_PROP_GANGPLANK = 13562;
	static final int PROP_GANGPLANK_FRAMEMAP = 2503;

	/** How long a scenery animation is drawn for, in client cycles. */
	static final int PROP_CYCLES = 60;

	// ---------------------------------------------------------------------------
	// Celebrations
	// ---------------------------------------------------------------------------

	/** EMOTE_WAVE, which a friendly golem gives the player. Framemap 0, as the dances are. */
	static final int ANIM_EMOTE_WAVE = 863;

	/**
	 * The emote tab's own animations, which a golem may copy off the player.
	 *
	 * <p>Two runs of ids, and every one of them checked against the cache: all are framemap 0, the human rig the golem is built on, so they play against it untouched.
	 * Anything outside these runs is left alone - a combat or skilling clip on the wrong framemap
	 * folds a golem through itself rather than looking merely wrong.
	 */
	private static final int EMOTES_FROM = 855;
	private static final int EMOTES_TO = 868;
	private static final int LATER_EMOTES_FROM = 2105;
	private static final int LATER_EMOTES_TO = 2113;

	/**
	 * The one id in the first run that is not an emote: chopping with a rune axe, which sits between
	 * the dance and running on the spot. Copied, a golem would chop the air beside a woodcutter.
	 */
	private static final int RUNE_AXE_CHOP = 867;

	static boolean isEmote(int animation)
	{
		return animation >= EMOTES_FROM && animation <= EMOTES_TO && animation != RUNE_AXE_CHOP
			|| animation >= LATER_EMOTES_FROM && animation <= LATER_EMOTES_TO;
	}

	/**
	 * The fireworks that go off over a levelling player, drawn over a dancing golem instead.
	 *
	 * <p>Spot animation 199, which the client plays on an actor: golems are not actors, so its
	 * model and sequence are drawn as scenery like any other prop. Read from the cache: the
	 * sequence is framemap 877, the spot
	 * animation's own rig, which is why it is played against its own model and not the golem.
	 *
	 * <p>The three fireworks Death Party offers are 199, 1388 (the 99) and 1389 (max total).
	 * This is the first of them, being the one a player sees most.
	 */
	static final int SPOTANIM_FIREWORK = 199;
	static final int FIREWORK_MODEL = 411;
	static final int FIREWORK_ANIMATION = 913;

	/** How long the fireworks are drawn for, in client cycles: the sequence is 1.8 seconds. */
	static final int FIREWORK_CYCLES = 90;

	/**
	 * The guitar an air guitar is played on. Spot animation 1239, whose sequence runs the same
	 * three seconds as the emote, so the two start together and strum together.
	 */
	static final int SPOTANIM_AIR_GUITAR = 1239;
	static final int AIR_GUITAR_MODEL = 29315;
	static final int AIR_GUITAR_ANIMATION = 4752;

	/** How long a dance prop is drawn for, in client cycles: the guitar's sequence is 3 seconds. */
	static final int DANCE_PROP_CYCLES = 150;

	static boolean isGolem(int npcId)
	{
		return npcId == GOLEM_NPC_ID;
	}

}
