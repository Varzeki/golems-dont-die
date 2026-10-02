package com.golemsdontdie;

import net.runelite.api.*;

/**
 * Something a golem holds that the game never made to be held: a jeweller's chisel, whose only
 * model is its inventory icon. A held item's vertices are tied to the hand in the skeleton and
 * move with it; this one's are tied to nothing, and RuneLite gives no way to tie them. So it is
 * merged into the golem's model placed in the hand as the golem stands, and each time the golem
 * is posed it is carried along by hand: three of the hand's own vertices say where the hand has
 * gone and how it has turned, and the item's vertices are put back where they sit relative to them.
 *
 * <p>The posed model is a buffer the client reuses for the next pose, so writing into it changes
 * only the frame being drawn. The client does not light a posed model again, so neither is this.
 */
final class HeldItem
{
	/** Where the item's vertices start in the model, and how many there are. */
	private final int first;
	private final int count;

	/** The three hand vertices that frame it. */
	private final int a;
	private final int b;
	private final int c;

	/** Each of the item's vertices as the golem stands, in the hand's own frame. */
	private final float[] along;
	private final float[] across;
	private final float[] up;

	private HeldItem(int first, int count, int a, int b, int c, float[] along, float[] across, float[] up)
	{
		this.first = first;
		this.count = count;
		this.a = a;
		this.b = b;
		this.c = c;
		this.along = along;
		this.across = across;
		this.up = up;
	}

	/**
	 * The item held in a standing model whose last {@code count} vertices are the item, framed by
	 * the three hand vertices nearest these points; null if the hand is not where it is expected.
	 */
	static HeldItem in(Model standing, int count, int handVertices, float[][] handPoints)
	{
		float[] x = standing.getVerticesX();
		float[] y = standing.getVerticesY();
		float[] z = standing.getVerticesZ();
		int total = standing.getVerticesCount();
		int[] frame = new int[3];
		for (int k = 0; k < 3; k++)
		{
			frame[k] = nearest(x, y, z, Math.min(handVertices, total), handPoints[k]);
			if (frame[k] < 0)
			{
				return null;
			}
		}
		float[][] axes = axes(x, y, z, frame[0], frame[1], frame[2]);
		if (axes == null)
		{
			return null;
		}
		int first = total - count;
		float[] along = new float[count];
		float[] across = new float[count];
		float[] up = new float[count];
		for (int i = 0; i < count; i++)
		{
			float dx = x[first + i] - x[frame[0]];
			float dy = y[first + i] - y[frame[0]];
			float dz = z[first + i] - z[frame[0]];
			along[i] = dx * axes[0][0] + dy * axes[0][1] + dz * axes[0][2];
			across[i] = dx * axes[1][0] + dy * axes[1][1] + dz * axes[1][2];
			up[i] = dx * axes[2][0] + dy * axes[2][1] + dz * axes[2][2];
		}
		return new HeldItem(first, count, frame[0], frame[1], frame[2], along, across, up);
	}

	/** Moves the item to where the hand is in a posed model. */
	void follow(Model posed)
	{
		if (posed.getVerticesCount() < first + count)
		{
			return;
		}
		float[] x = posed.getVerticesX();
		float[] y = posed.getVerticesY();
		float[] z = posed.getVerticesZ();
		float[][] axes = axes(x, y, z, a, b, c);
		if (axes == null)
		{
			return;
		}
		for (int i = 0; i < count; i++)
		{
			int v = first + i;
			x[v] = x[a] + along[i] * axes[0][0] + across[i] * axes[1][0] + up[i] * axes[2][0];
			y[v] = y[a] + along[i] * axes[0][1] + across[i] * axes[1][1] + up[i] * axes[2][1];
			z[v] = z[a] + along[i] * axes[0][2] + across[i] * axes[1][2] + up[i] * axes[2][2];
		}
	}

	/** Three square axes from three points: along the first edge, across in their plane, and up out of it. */
	private static float[][] axes(float[] x, float[] y, float[] z, int a, int b, int c)
	{
		float[] e1 = {x[b] - x[a], y[b] - y[a], z[b] - z[a]};
		float[] side = {x[c] - x[a], y[c] - y[a], z[c] - z[a]};
		float[] e3 = cross(e1, side);
		if (!normalise(e1) || !normalise(e3))
		{
			return null;
		}
		float[] e2 = cross(e3, e1);
		return new float[][]{e1, e2, e3};
	}

	private static float[] cross(float[] p, float[] q)
	{
		return new float[]{p[1] * q[2] - p[2] * q[1], p[2] * q[0] - p[0] * q[2], p[0] * q[1] - p[1] * q[0]};
	}

	private static boolean normalise(float[] v)
	{
		float length = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
		if (length < 1e-3f)
		{
			return false;
		}
		v[0] /= length;
		v[1] /= length;
		v[2] /= length;
		return true;
	}

	/** The vertex among the first {@code limit} within two units of a point, or -1. */
	private static int nearest(float[] x, float[] y, float[] z, int limit, float[] point)
	{
		int best = -1;
		float bestDistance = 4f;
		for (int i = 0; i < limit; i++)
		{
			float dx = x[i] - point[0];
			float dy = y[i] - point[1];
			float dz = z[i] - point[2];
			float distance = dx * dx + dy * dy + dz * dz;
			if (distance <= bestDistance)
			{
				bestDistance = distance;
				best = i;
			}
		}
		return best;
	}
}
