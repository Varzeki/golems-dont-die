package com.golemsdontdie;

import java.util.Random;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
enum GolemDance
{
	CHEER(862),
	DANCE(866),
	JIG(2106),
	SPIN(2107),
	HEADBANG(2108),
	ZOMBIE_DANCE(3543),
	SMOOTH_DANCE(7533),
	CRAZY_DANCE(7537),
	JUMP_FOR_JOY(2109),
	CHICKEN_DANCE(1835),
	AIR_GUITAR(4751),
	GOBLIN_SALUTE(2128),
	;

	private final int animationId;

	/** Uses the golem's own generator so a crowd does not dance in lockstep. */
	static GolemDance random(Random random)
	{
		GolemDance[] moves = values();
		return moves[random.nextInt(moves.length)];
	}
}
