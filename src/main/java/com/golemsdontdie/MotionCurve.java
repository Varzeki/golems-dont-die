package com.golemsdontdie;

import java.util.*;
import lombok.*;

/**
 * A traversal as it actually happened, cycle by cycle, ready to be performed again.
 *
 * <p>This replaces a parametric model of clip, delay and duration, which had to rebuild the
 * motion differently for every obstacle shape - a hop, a climb and a door all want something
 * else - so fitting it to one broke it on another. The position is already recorded every
 * 20ms, so the motion is kept and replayed instead; a teleport is a curve that does not move
 * and then does.
 *
 * <h2>Why it is stored rotated</h2>
 *
 * <p>Samples run along the traversal's own axis, <b>forward</b> toward the destination and
 * <b>sideways</b>, not north and east, so one recording serves every instance of that
 * obstacle whichever way it faces.
 */
@AllArgsConstructor
final class MotionCurve
{
	/**
	 * Client cycles between stored samples. Two: movement is interpolated between samples
	 * anyway, and at 40ms a hop is twenty numbers rather than forty, small enough to keep
	 * hundreds of these in a config value.
	 */
	static final int SAMPLE_EVERY = 2;

	/** Longest curve worth keeping, in cycles. Beyond this it is not a traversal. */
	static final int MAX_CYCLES = 600;

	/**
	 * Distance along the traversal axis at each sample, in 128ths of a tile, forward being
	 * toward the destination. Ending short is normal: the last part of some traversals is a
	 * teleport, so the final sample is nowhere near the end.
	 */
	private final short[] forward;

	/** Sideways offset at each sample. Nonzero for anything that swings or staggers. */
	private final short[] lateral;

	/**
	 * {cycle, animationId, startingFrame} at each point the animation changed. The frame
	 * matters because the client advances the clip at the render rate while position
	 * advances on cycle count; any disagreement reads as the animation skipping.
	 */
	private final int[][] animations;

	private final int cycles;

	/**
	 * Which way the player faced while traversing, relative to the direction of travel, in
	 * orientation units - 0 forwards, 1024 backwards - or -1 if not recorded. Not always
	 * forwards: on the rockslide they face the rock and move away from it, and a golem
	 * facing its travel climbs off the cliff.
	 */
	private final int facing;

	/**
	 * The keyframe the player's animation was on at each sample, -1 where none, or null for
	 * a recording predating keyframes. The clip does not run at authored speed: on a basalt
	 * stone it holds its first keyframe about twenty-five cycles, so played as authored a
	 * golem finished the hop before leaving the ground.
	 */
	private final short[] keyframes;

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

	/**
	 * The recording as plain numbers, one entry per sample: {cycle, forward, lateral}. For
	 * the obstacle data file, which depends on nothing in this class.
	 */
	int[][] samples()
	{
		int[][] out = new int[forward.length][];
		for (int i = 0; i < forward.length; i++)
		{
			out[i] = new int[]{i * SAMPLE_EVERY, forward[i], i < lateral.length ? lateral[i] : 0};
		}
		return out;
	}

	/** Each point the animation changed, as plain numbers: {cycle, animation}. */
	int[][] animationChanges()
	{
		int[][] out = new int[animations.length][];
		for (int i = 0; i < animations.length; i++)
		{
			out[i] = new int[]{animations[i][0], animations[i][1]};
		}
		return out;
	}

	boolean isEmpty()
	{
		return forward.length == 0;
	}

	/**
	 * The animation that should be playing at this point, or -1. Read from the recorded
	 * timeline rather than decided: an obstacle that plays three clips in sequence does so
	 * because the recording did.
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
		// Between clips there is no frame to drive: the golem's idle runs on the client's
		// clock, as does any recording made before keyframes were kept.
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
	 * Samples of stillness that separate two movements. Six is twelve cycles: the quantised
	 * source stalls a cycle or two mid-climb, which is sampling, while the pause between two
	 * basalt hops is over fifteen, which is a second step.
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
	 * How many separate movements the recording holds. One for any single obstacle; more
	 * means a chained crossing was captured as one traversal, which a transport covering
	 * one step of it cannot replay.
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
	 * does. From the animation, not the start, since a walk onto the obstacle may come
	 * first; the wind-up measured is the pause between the clip starting and the jump.
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
	 * Where the golem should be at this cycle, as an offset from where it started, into
	 * {@code out} in world fine units. {@code axisX, axisY} is the unit vector toward the
	 * destination, turning the stored forward-and-sideways pair back into a direction.
	 */
	void offsetAt(int cycle, float axisX, float axisY, int[] out)
	{
		offsetAt(cycle, axisX, axisY, 1f, out);
	}

	/**
	 * As {@link #offsetAt}, with the forward distance scaled. A golem may take an obstacle
	 * from a tile or two from where the player did, so scaling lands it where its own
	 * transport says, keeping the recording's timing and sideways movement.
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

		// Except across a teleport, which is not interpolated. A door moves the player a
		// whole tile in one cycle and samples are two cycles apart, so interpolating slid
		// the golem through the door instead of putting it on one side and then the other.
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
		// at all - a ladder, which animates and then teleports - north stands in; nothing is
		// projected onto it anyway.
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
		// from config play on the recording's, so one obstacle behaved two ways.
		List<int[]> kept = new ArrayList<>();
		for (int i = 0; i < anims.size(); i++)
		{
			int[] change = anims.get(i);
			// A looping clip reports -1 for a cycle between repeats. Not the animation
			// ending; honouring it would flash the golem out of its climb.
			if (change[1] == -1 && i + 1 < anims.size() && anims.get(i + 1)[0] - change[0] <= 4)
			{
				continue;
			}
			kept.add(new int[]{Math.max(0, change[0] - base), change[1], 0});
		}
		int[][] timeline = kept.toArray(new int[0][]);

		// Facing relative to the way the traversal goes, from the moment it animates. The
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
	 * The largest step that can be sampling noise rather than real movement. Three quarters
	 * of a tile: climb artefacts came back as steps of forty to sixty units, a door's
	 * teleport is a whole tile in one sample, and nothing observed sits between.
	 */
	private static final int NOISE_STEP = Golem.TILE * 3 / 4;

	/**
	 * Evens out the sampling, which is coarser than the motion it samples, but only where
	 * the motion really is continuous.
	 *
	 * <p>{@code getLocalLocation} does not report the smooth position the client draws, so a
	 * recorded climb came back as plateaus of two or three samples and then a jump of forty
	 * to sixty, which a golem performed as holding still and lurching. Averaging all of it
	 * was wrong the other way, beginning a door's teleport before its animation finished, so
	 * a step larger than {@link #NOISE_STEP} is taken to be real and never averaged across.
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
