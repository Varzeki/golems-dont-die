package com.golemsdontdie;

import java.util.Random;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * The emotes a golem dances, one picked per move.
 *
 * <p>All of them are the game's own human emotes, and the golem is rigged to the human framemap
 * (see {@link GolemContent#GOLEM_FRAMEMAP}), so they play against it untouched. Checked one by one
 * against the cache: an emote on another framemap would fold the golem through
 * itself rather than look slightly wrong.
 *
 * <p>Two of them carry something the golem has not got. The air guitar's guitar is a spot
 * animation the client plays on the actor, and golems are not actors, so it is drawn beside the
 * golem as scenery; see {@link #getSpotanim()} and GolemsDontDiePlugin.spawnDanceProp.
 *
 * <p>From the dancing contributed in
 * <a href="https://github.com/Varzeki/golems-dont-die/pull/1">pull request 1</a>, which took the
 * idea from dekvall's Dance Party.
 */
@Getter
@AllArgsConstructor
enum GolemDance
{
	/** EMOTE_CHEER. */
	CHEER(862, -1),
	/** EMOTE_DANCE. */
	DANCE(866, -1),
	/** EMOTE_DANCE_SCOTTISH, the jig. */
	JIG(2106, -1),
	/** EMOTE_DANCE_SPIN. */
	SPIN(2107, -1),
	/** EMOTE_DANCE_HEADBANG. */
	HEADBANG(2108, -1),
	/** ZOMBIE_DANCE. */
	ZOMBIE_DANCE(3543, -1),
	/** BDAY17_BLING, the smooth dance. */
	SMOOTH_DANCE(7533, -1),
	/** BDAY17_LASSO, the crazy dance. */
	CRAZY_DANCE(7537, -1),
	/** EMOTE_JUMP_WITH_JOY. */
	JUMP_FOR_JOY(2109, -1),
	/** HUMAN_CHICKEN_DANCE. */
	CHICKEN_DANCE(1835, -1),
	/** EMOTE_AIR_GUITAR, with the guitar it is played on. */
	AIR_GUITAR(4751, GolemContent.SPOTANIM_AIR_GUITAR),
	/** HUMAN_CAVE_GOBLIN_DANCE. */
	GOBLIN_DANCE(2128, -1),
	;

	private final int animationId;

	/** The spot animation that goes with the emote, or -1 where the golem needs no props. */
	private final int spotanim;

	private static final GolemDance[] ALL = values();

	/**
	 * A move at random.
	 *
	 * @param random the golem's own dancing generator, not the one its roaming comes out of: a
	 *               crowd dancing in lockstep looks wrong, and a golem whose walk changed because
	 *               it danced would be worse. See Golem.danceRandom.
	 */
	static GolemDance random(Random random)
	{
		return ALL[random.nextInt(ALL.length)];
	}
}
