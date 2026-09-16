package com.golemsdontdie;

import java.util.ArrayList;
import java.util.List;

/**
 * A traversal as it actually happened, cycle by cycle, ready to be performed again.
 *
 * <p>This replaces a parametric model, and the reason is worth recording. Reducing a
 * traversal to a clip, a delay and a duration meant rebuilding the motion from three
 * numbers — and every obstacle shape needed the rebuild to work differently. A hop wanted
 * its clip played at natural length with movement in the middle; a climb wanted the clip
 * repeated per tile; a door wanted the whole animation and then a teleport. Each time the
 * model was fitted to one of them it came apart on another, and the same two obstacles were
 * still oscillating after a day of it.
 *
 * <p>None of those decisions exist here. The player's position is already recorded every
 * 20ms, so the motion is simply kept and replayed. There is no delay to choose, no duration
 * to stretch, and no classifying an obstacle as walked or glided or teleported — a teleport
 * is a curve that does not move and then does, and a climb is a curve that moves steadily.
 * The difference is in the data rather than in a branch.
 *
 * <h2>Why it is stored rotated</h2>
 *
 * <p>Samples are kept along the traversal's own axis — how far <b>forward</b> toward the
 * destination, and how far <b>sideways</b> — rather than as north and east. One recording
 * then serves every instance of that obstacle whichever way it faces, which is what makes
 * a handful of recordings able to cover thousands of rows. A stepping stone crossed
 * northward teaches the shape of every stepping stone.
 */
final class MotionCurve
{
	/**
	 * Client cycles between stored samples.
	 *
	 * <p>Two. Movement is interpolated between samples anyway, and at 40ms a hop is twenty
	 * numbers rather than forty — small enough to keep hundreds of these in a config value.
	 */
	static final int SAMPLE_EVERY = 2;

	/** Longest curve worth keeping, in cycles. Beyond this it is not a traversal. */
	static final int MAX_CYCLES = 600;

	/**
	 * Distance along the traversal axis at each sample, in 128ths of a tile.
	 *
	 * <p>Forward is toward the destination. A curve that ends short of its destination is
	 * normal — the last part of some traversals is a teleport, which shows up as the final
	 * sample being nowhere near the end.
	 */
	private final short[] forward;

	/** Sideways offset at each sample. Nonzero for anything that swings or staggers. */
	private final short[] lateral;

	/**
	 * {cycle, animationId, startingFrame} at each point the animation changed.
	 *
	 * <p>The frame matters because a golem's animation runs on its own clock. We hand the
	 * client an id and it advances the clip at the render rate, while the position advances
	 * on the simulation's cycle count — two clocks for one motion, and any disagreement
	 * between them reads as the animation skipping. Recording where the clip actually was
	 * lets the two be tied together instead of assumed to agree.
	 */
	private final int[][] animations;

	private final int cycles;

	/**
	 * Which way the player faced while traversing, relative to the direction of travel,
	 * in the game's orientation units — 0 forwards, 1024 backwards — or -1 if not recorded.
	 *
	 * <p>Not always forwards. Climbing down the rockslide the player faces the rock and
	 * moves away from it; a golem facing its direction of travel climbs down outwards,
	 * off the cliff.
	 */
	private final int facing;

	/**
	 * The keyframe the player's animation was on at each sample, -1 where none, or null
	 * for a recording made before keyframes were kept.
	 *
	 * <p>The player's clip does not run at its authored speed. On a basalt stone it sits on
	 * its first keyframe for about twenty-five cycles and then runs the rest during the
	 * jump; played at authored speed a golem finished the hop before it left the ground.
	 */
	private final short[] keyframes;

	MotionCurve(short[] forward, short[] lateral, int[][] animations, int cycles, int facing,
		short[] keyframes)
	{
		this.forward = forward;
		this.lateral = lateral;
		this.animations = animations;
		this.cycles = cycles;
		this.facing = facing;
		this.keyframes = keyframes;
	}

	boolean hasKeyframes()
	{
		return keyframes != null;
	}

	/** Facing relative to travel, or -1 if the recording predates it. */
	int facing()
	{
		return facing;
	}

	boolean hasFacing()
	{
		return facing >= 0;
	}

	int cycles()
	{
		return cycles;
	}

	boolean isEmpty()
	{
		return forward.length == 0;
	}

	/**
	 * The animation that should be playing at this point, or -1.
	 *
	 * <p>Read from the recorded timeline rather than decided. An obstacle that plays three
	 * clips in sequence does so because the recording did.
	 */
	int animationAt(int cycle)
	{
		int playing = -1;
		for (int[] change : animations)
		{
			if (change[0] > cycle)
			{
				break;
			}
			playing = change[1];
		}
		return playing;
	}

	/** The frame the animation should be on at this point, or -1 if not recorded. */
	int frameAt(int cycle)
	{
		// Between clips there is no frame to drive, and the golem's own idle runs on the
		// client's clock as usual. So does any recording made before keyframes were kept.
		if (keyframes == null || animationAt(cycle) == -1)
		{
			return -1;
		}
		return keyframes[Math.max(0, Math.min(cycle / SAMPLE_EVERY, keyframes.length - 1))];
	}

	/** How far along its axis the recording travelled in total, in fine units. */
	int reach()
	{
		return forward.length == 0 ? 0 : forward[forward.length - 1];
	}

	/** Movement smaller than this, in fine units, is sampling rather than going anywhere. */
	private static final int STILL = 8;

	/**
	 * Samples of stillness that separate two movements.
	 *
	 * <p>Six samples is twelve cycles. The quantised source stalls for a cycle or two in the
	 * middle of a climb, which is sampling; the pause between two basalt hops is over
	 * fifteen, which is a second step.
	 */
	private static final int HOLD_SAMPLES = 6;

	/** The furthest the recording strays from its own axis. */
	int maxLateral()
	{
		int most = 0;
		for (short value : lateral)
		{
			most = Math.max(most, Math.abs(value));
		}
		return most;
	}

	/**
	 * How many separate movements the recording holds.
	 *
	 * <p>One for any single obstacle. More means a chained crossing was captured as a single
	 * traversal, which a transport covering one step of it cannot replay.
	 */
	int bursts()
	{
		int count = 0;
		int still = HOLD_SAMPLES;
		for (int i = 1; i < forward.length; i++)
		{
			boolean moved = Math.abs(forward[i] - forward[i - 1])
				+ Math.abs(lateral[i] - lateral[i - 1]) > 1;
			if (moved)
			{
				if (still >= HOLD_SAMPLES)
				{
					count++;
				}
				still = 0;
			}
			else
			{
				still++;
			}
		}
		return count;
	}

	/** The cycle the first animation starts on, or 0 if there is none. */
	int firstAnimationCycle()
	{
		for (int[] change : animations)
		{
			if (change[1] != -1)
			{
				return change[0];
			}
		}
		return 0;
	}

	/**
	 * The cycle the recording first moves once its animation has begun, or -1 if it never
	 * does. From the animation, not the start: a walk onto the obstacle may come first, and
	 * the wind-up this measures is the pause between the clip starting and the jump.
	 */
	int firstMoveCycle()
	{
		int from = Math.min(firstAnimationCycle() / SAMPLE_EVERY, forward.length - 1);
		for (int i = from; i < forward.length; i++)
		{
			if (Math.abs(forward[i] - forward[from]) + Math.abs(lateral[i] - lateral[from]) > STILL)
			{
				return i * SAMPLE_EVERY;
			}
		}
		return -1;
	}

	/** The cycle the recording arrives where it finishes, or -1 if it never moved. */
	int lastMoveCycle()
	{
		int end = forward.length - 1;
		for (int i = end; i >= 0; i--)
		{
			if (Math.abs(forward[i] - forward[end]) + Math.abs(lateral[i] - lateral[end]) > STILL)
			{
				return Math.min(cycles, (i + 1) * SAMPLE_EVERY);
			}
		}
		return -1;
	}

	/**
	 * Where the golem should be at this cycle, as an offset from where it started.
	 *
	 * <p>{@code out} receives the offset in world fine units. {@code axisX, axisY} is the
	 * unit vector toward the destination, in the same units, which is what turns the stored
	 * forward-and-sideways pair back into a direction on the ground.
	 */
	void offsetAt(int cycle, float axisX, float axisY, int[] out)
	{
		offsetAt(cycle, axisX, axisY, 1f, out);
	}

	/**
	 * As {@link #offsetAt}, with the forward distance scaled.
	 *
	 * <p>A golem may take an obstacle from a tile or two away from where the player did,
	 * so the distance to cover is not always the distance recorded. Scaling the forward
	 * component lands the golem where its own transport says it should end up, while
	 * keeping the recording's timing and its sideways movement intact.
	 */
	void offsetAt(int cycle, float axisX, float axisY, float scale, int[] out)
	{
		if (forward.length == 0)
		{
			out[0] = 0;
			out[1] = 0;
			return;
		}

		// Between samples, because the samples are every other cycle and a golem is drawn
		// every one of them.
		float at = Math.max(0, Math.min(cycle, cycles)) / (float) SAMPLE_EVERY;
		int i = Math.min((int) at, forward.length - 1);
		int j = Math.min(i + 1, forward.length - 1);
		float t = at - i;

		// Except across a teleport, which is not interpolated.
		//
		// A door moves the player a whole tile in one cycle, and samples are two cycles
		// apart, so interpolating put the golem halfway through the door on the cycle in
		// between: a slide so quick it was almost, but not quite, a teleport. Holding the
		// earlier sample until the later one is due puts the golem on one side and then
		// the other, as the player was drawn.
		if (Math.abs(forward[j] - forward[i]) + Math.abs(lateral[j] - lateral[i]) > NOISE_STEP)
		{
			t = 0f;
		}

		float f = (forward[i] + (forward[j] - forward[i]) * t) * scale;
		float l = lateral[i] + (lateral[j] - lateral[i]) * t;

		// Forward along the axis, sideways across it.
		out[0] = Math.round(f * axisX - l * axisY);
		out[1] = Math.round(f * axisY + l * axisX);
	}

	/**
	 * Builds a curve from positions sampled during a traversal.
	 *
	 * @param samples  {cycle, worldFineX, worldFineY} for each observation, in order
	 * @param anims    {cycle, animationId} for each animation change
	 * @param toFineX  the destination, for working out which way is forward
	 */
	static MotionCurve record(List<int[]> samples, List<int[]> anims,
		int fromFineX, int fromFineY, int toFineX, int toFineY)
	{
		if (samples.isEmpty())
		{
			return null;
		}

		int span = samples.get(samples.size() - 1)[0] - samples.get(0)[0];
		if (span <= 0 || span > MAX_CYCLES)
		{
			return null;
		}

		// The axis is the straight line to the destination. Where a traversal does not move
		// at all — a ladder, which animates and then teleports — there is no axis to speak
		// of and north stands in; nothing is being projected onto it anyway.
		float dx = toFineX - fromFineX;
		float dy = toFineY - fromFineY;
		float length = (float) Math.sqrt(dx * dx + dy * dy);
		float axisX = length < 1 ? 0f : dx / length;
		float axisY = length < 1 ? 1f : dy / length;

		int base = samples.get(0)[0];
		int count = span / SAMPLE_EVERY + 1;
		short[] forward = new short[count];
		short[] lateral = new short[count];

		short[] keys = new short[count];
		boolean[] seen = new boolean[count];
		boolean anyKey = false;

		int next = 0;
		for (int[] sample : samples)
		{
			int slot = (sample[0] - base) / SAMPLE_EVERY;
			if (slot < next || slot >= count)
			{
				continue;
			}
			float ox = sample[1] - fromFineX;
			float oy = sample[2] - fromFineY;
			forward[slot] = (short) Math.round(ox * axisX + oy * axisY);
			lateral[slot] = (short) Math.round(-ox * axisY + oy * axisX);
			keys[slot] = (short) (sample.length > 5 ? sample[5] : -1);
			anyKey |= keys[slot] >= 0;
			seen[slot] = true;
			next = slot + 1;
		}
		for (int i = 0; i < count; i++)
		{
			if (!seen[i])
			{
				keys[i] = i == 0 ? -1 : keys[i - 1];
			}
		}

		// Any slot no sample landed in carries the one before it, so a gap in the
		// observations holds still rather than snapping back to the start.
		for (int i = 1; i < count; i++)
		{
			if (forward[i] == 0 && lateral[i] == 0 && (forward[i - 1] != 0 || lateral[i - 1] != 0))
			{
				forward[i] = forward[i - 1];
				lateral[i] = lateral[i - 1];
			}
		}

		smooth(forward);
		smooth(lateral);

		// Starting frames are stored as zero rather than left out. Leaving them out made a
		// curve recorded this session play on the client's clock and the same curve loaded
		// from the config play on the recording's, so one obstacle behaved two ways depending
		// on whether the client had been restarted since it was learned.
		List<int[]> kept = new ArrayList<>();
		for (int i = 0; i < anims.size(); i++)
		{
			int[] change = anims.get(i);
			// A looping clip reports -1 for a cycle between repeats. That is not the
			// animation ending, and honouring it would flash the golem out of its climb.
			if (change[1] == -1 && i + 1 < anims.size() && anims.get(i + 1)[0] - change[0] <= 4)
			{
				continue;
			}
			kept.add(new int[]{Math.max(0, change[0] - base), change[1], 0});
		}
		int[][] timeline = kept.toArray(new int[0][]);

		// Facing, relative to the way the traversal goes, from the moment it animates. The
		// most common of eight directions: the player turns once at the start and holds it.
		int facing = -1;
		if (length >= Golem.TILE / 2f)
		{
			int heading = (int) (1024 + Math.round(Math.atan2(dx, dy) / Math.PI * 1024)) & 2047;
			int from = anims.isEmpty() ? base : anims.get(0)[0];
			int[] bins = new int[8];
			int counted = 0;
			for (int[] sample : samples)
			{
				if (sample.length > 4 && sample[4] >= 0 && sample[0] >= from)
				{
					bins[((sample[4] - heading + 128) & 2047) / 256]++;
					counted++;
				}
			}
			if (counted > 0)
			{
				int best = 0;
				for (int b = 1; b < bins.length; b++)
				{
					if (bins[b] > bins[best])
					{
						best = b;
					}
				}
				facing = best * 256;
			}
		}

		return new MotionCurve(forward, lateral, timeline, span, facing, anyKey ? keys : null);
	}

	/**
	 * The largest step that can be sampling noise rather than real movement.
	 *
	 * <p>Three quarters of a tile. The two obstacle shapes are well separated: a climb's
	 * sampling artefacts came back as steps of forty to sixty units, while a door's
	 * teleport is a whole tile in a single sample. Nothing observed sits between them.
	 */
	private static final int NOISE_STEP = Golem.TILE * 3 / 4;

	/**
	 * Evens out the sampling, which is coarser than the motion it is sampling — but only
	 * where the motion really is continuous.
	 *
	 * <p>{@code getLocalLocation} does not report the smooth position the client draws, so
	 * a recorded climb came back as {@code [111, 154, 154, 188, 188, 188, 248, ...]}:
	 * plateaus of two or three samples and then a jump of forty to sixty. A golem
	 * performing that holds still and lurches, which is how it looked.
	 *
	 * <p>Averaging all of it was wrong in the other direction. A door does not move and
	 * then covers a whole tile in one sample, and smearing that across its neighbours turned
	 * an instant teleport into a quick glide which began before the animation had finished.
	 *
	 * <p>So a step larger than {@link #NOISE_STEP} is taken to be real and is never averaged
	 * across. Noise is small by definition; a teleport is not. That distinction is in the
	 * data rather than in a per-obstacle decision, which is the whole reason for recording
	 * motion instead of modelling it.
	 */
	private static void smooth(short[] values)
	{
		if (values.length < 3)
		{
			return;
		}

		short[] source = values.clone();
		for (int i = 1; i < values.length - 1; i++)
		{
			if (Math.abs(source[i] - source[i - 1]) > NOISE_STEP
				|| Math.abs(source[i + 1] - source[i]) > NOISE_STEP)
			{
				// A real discontinuity on one side or the other. Leave it alone.
				continue;
			}
			values[i] = (short) Math.round((source[i - 1] + source[i] + source[i + 1]) / 3f);
		}
	}

	// ------------------------------------------------------------------ storage

	/** {@code cycles|f,f,f|l,l,l|cycle:anim:frame,...|facing|k,k,k} */
	String serialise()
	{
		StringBuilder sb = new StringBuilder().append(cycles).append('|');
		for (int i = 0; i < forward.length; i++)
		{
			sb.append(i == 0 ? "" : ",").append(forward[i]);
		}
		sb.append('|');
		for (int i = 0; i < lateral.length; i++)
		{
			sb.append(i == 0 ? "" : ",").append(lateral[i]);
		}
		sb.append('|');
		for (int i = 0; i < animations.length; i++)
		{
			sb.append(i == 0 ? "" : ",").append(animations[i][0]).append(':')
				.append(animations[i][1]).append(':')
				.append(animations[i].length > 2 ? animations[i][2] : 0);
		}
		sb.append('|').append(facing).append('|');
		if (keyframes != null)
		{
			for (int i = 0; i < keyframes.length; i++)
			{
				sb.append(i == 0 ? "" : ",").append(keyframes[i]);
			}
		}
		return sb.toString();
	}

	static MotionCurve parse(String text)
	{
		try
		{
			String[] parts = text.split("\\|", -1);
			if (parts.length < 4)
			{
				return null;
			}

			int cycles = Integer.parseInt(parts[0]);
			short[] forward = shorts(parts[1]);
			short[] lateral = shorts(parts[2]);
			if (forward.length != lateral.length || forward.length == 0)
			{
				return null;
			}

			List<int[]> anims = new ArrayList<>();
			if (!parts[3].isEmpty())
			{
				for (String change : parts[3].split(","))
				{
					String[] pair = change.split(":");
					anims.add(pair.length > 2
						? new int[]{Integer.parseInt(pair[0]), Integer.parseInt(pair[1]),
							Integer.parseInt(pair[2])}
						: new int[]{Integer.parseInt(pair[0]), Integer.parseInt(pair[1])});
				}
			}
			// Recordings made before facing was kept have four fields and load as unknown.
			int facing = parts.length > 4 && !parts[4].isEmpty() ? Integer.parseInt(parts[4]) : -1;
			short[] keyframes = parts.length > 5 && !parts[5].isEmpty() ? shorts(parts[5]) : null;
			if (keyframes != null && keyframes.length != forward.length)
			{
				keyframes = null;
			}
			return new MotionCurve(forward, lateral, anims.toArray(new int[0][]), cycles, facing,
				keyframes);
		}
		catch (RuntimeException e)
		{
			// One unreadable curve costs its own obstacle and nothing else.
			return null;
		}
	}

	private static short[] shorts(String csv)
	{
		if (csv.isEmpty())
		{
			return new short[0];
		}
		String[] parts = csv.split(",");
		short[] out = new short[parts.length];
		for (int i = 0; i < parts.length; i++)
		{
			out[i] = Short.parseShort(parts[i]);
		}
		return out;
	}

	@Override
	public String toString()
	{
		return "curve " + cycles + "c " + forward.length + " samples, "
			+ animations.length + " anim changes";
	}
}
