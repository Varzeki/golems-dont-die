package com.golemsdontdie;

import java.awt.*;
import java.awt.image.*;
import java.util.*;
import javax.inject.*;
import javax.inject.Inject;
import net.runelite.api.*;

/**
 * A picture of one golem, drawn from its own model: the golem's page has a portrait of the golem
 * it is about rather than the same picture twenty thousand times.
 *
 * <p>The pose and the angle come out of the golem's seed, so each golem stands a little
 * differently and always the same way — one caught mid-idle, another square on, another half
 * turned away.
 *
 * <p>Drawn here rather than by the client. The scene renderer draws what is in the scene, and a
 * golem on the other side of the world is not; the model itself is all that is needed, so the
 * faces are sorted by depth and filled flat, which at this size reads as the low-poly figure it
 * is. Client thread only — the models and the animation frames belong to it.
 */
@Singleton
class GolemPortrait
{
	/** The picture's size, which is also what the page's frame is built around. */
	static final int WIDTH = 110;
	static final int HEIGHT = 140;

	/**
	 * Which way round the model has to be turned to face the viewer, in degrees. Found by drawing
	 * one golem the whole way round.
	 */
	private static final int FACING = 0;

	/** How far a golem may be turned off facing the viewer, either way, in degrees. */
	private static final int TURN = 12;

	/**
	 * The poses a golem may be caught in, one frame of each: standing mostly, since a portrait is
	 * a golem stood for its portrait, and now and then mid-stride, arms out, or one hand raised.
	 *
	 * <p>Picked from what the golem already plays in the world. Others were tried and left out:
	 * squeezing through a gap leans the whole model at the viewer, a climb turns its head away, and
	 * the beam-walking poses hold both arms straight out, which a picture cut at the waist loses.
	 * The listing is the weighting — a pose named twice comes up twice as often.
	 */
	private static final int[] POSES = {
		GolemContent.GOLEM_IDLE_ANIMATION,
		GolemContent.GOLEM_IDLE_ANIMATION,
		GolemContent.GOLEM_IDLE_ANIMATION,
		GolemContent.GOLEM_IDLE_ANIMATION,
		GolemContent.GOLEM_WALK_ANIMATION,
		GolemContent.GOLEM_WALK_ANIMATION,
		GolemContent.ANIM_LADDER_GRAB,
		GolemContent.ANIM_JUMP_STEPPINGSTONE,
	};

	/** What the life of the party is doing in its picture. The emote, as a player would dance it. */
	private static final int DANCING = 10031;

	/** How far it may lean, either way, in degrees: enough to tell two golems apart. */
	private static final int LEAN = 3;

	/** The closest and furthest a golem stands, as a share of the room in the frame. */
	private static final float NEAREST = 1f;
	private static final float FURTHEST = 0.86f;

	/** Room left around the golem, in pixels, so nothing touches the frame. */
	private static final int MARGIN = 10;

	/**
	 * How much of the golem the picture holds, from the top down: head, shoulders and chest, cut
	 * somewhere around the waist, as a portrait is.
	 */
	private static final float SHOWN = 0.52f;

	/** Gamma the client's own colour palette is built with, and so this one. */
	private static final double BRIGHTNESS = 0.8;

	@Inject
	private Client client;

	@Inject
	private GolemModelFactory models;

	/** A golem's portrait, or null if its model is not to hand. */
	BufferedImage of(Golem golem)
	{
		if (golem == null || client == null || models == null)
		{
			return null;
		}
		Model model = models.modelFor(golem.getSnapshot());
		if (model == null)
		{
			return null;
		}

		// Its own pose, out of its own number: one of the animations it plays, held at one frame.
		long seed = golem.getId();
		int pose = GolemTrait.LIFE_OF_THE_PARTY.in(golem.getTraits()) ? DANCING
			: POSES[(int) Math.floorMod(seed >> 3, POSES.length)];
		int turn = FACING + (int) Math.floorMod(seed >> 17, TURN * 2L) - TURN;
		return draw(posed(model, pose, (int) (seed >> 5)), model, turn, seed, SHOWN);
	}

	/**
	 * The same picture at an angle, a pose and a crop of the caller's choosing, for the developer
	 * export: which way the model faces, and what an animation does to a golem held still.
	 *
	 * @param shown how much of the golem to frame, from the top: {@link #SHOWN} for a portrait as
	 *              the page has it, 1 for the whole figure.
	 */
	BufferedImage of(Golem golem, int degrees, int animation, int frame, float shown)
	{
		Model model = golem == null || models == null ? null : models.modelFor(golem.getSnapshot());
		return model == null ? null : draw(posed(model, animation, frame), model, degrees, 0, shown);
	}

	/**
	 * Any model, posed by any animation, for the developer export: what the fireworks and the
	 * guitar look like is worth seeing before they are drawn over somebody's golems.
	 */
	BufferedImage of(Model model, int animation, int frame)
	{
		return model == null ? null : draw(posed(model, animation, frame), model, 0, 0, 1f);
	}

	/** The model held at one frame of an animation, or as it rests if there is no such animation. */
	private Model posed(Model model, int animation, int frame)
	{
		Animation loaded = animation < 0 ? null : client.loadAnimation(animation);
		if (loaded == null || loaded.getNumFrames() <= 0)
		{
			return model;
		}
		AnimationController controller = new AnimationController(client, loaded);
		controller.setFrame(Math.floorMod(frame, loaded.getNumFrames()));
		Model animated = controller.animate(model);
		return animated == null ? model : animated;
	}

	/**
	 * Draws the model turned to face the viewer, give or take, with the lean and the distance a
	 * golem's own number gives it.
	 */
	private static BufferedImage draw(Model model, Model standing, int degrees, long seed, float shown)
	{
		double turn = Math.toRadians(degrees);
		double lean = Math.toRadians(Math.floorMod(seed >> 29, LEAN * 2L) - LEAN);
		float zoom = NEAREST - (NEAREST - FURTHEST) * (Math.floorMod(seed >> 41, 16L) / 15f);
		return draw(model, standing, turn, lean, zoom, shown);
	}

	/**
	 * @param standing the same golem unposed, which sets both the size it is drawn at and where the
	 *                 picture is cut. Framed on the pose instead, a golem with a hand raised came
	 *                 out smaller and lower than the one beside it.
	 */
	private static BufferedImage draw(Model model, Model standing, double turn, double lean, float zoom,
		float shown)
	{
		int count = model.getVerticesCount();
		float[] modelX = model.getVerticesX();
		float[] modelY = model.getVerticesY();
		float[] modelZ = model.getVerticesZ();
		if (count == 0 || modelX == null || modelY == null || modelZ == null)
		{
			return null;
		}

		// Turned about the upright axis, which in a model is y and runs downwards.
		float[] x = new float[count];
		float[] y = new float[count];
		float[] depth = new float[count];
		float sin = (float) Math.sin(turn);
		float cos = (float) Math.cos(turn);
		float leftmost = Float.MAX_VALUE;
		float rightmost = -Float.MAX_VALUE;
		float top = Float.MAX_VALUE;
		float bottom = -Float.MAX_VALUE;
		float leanSin = (float) Math.sin(lean);
		float leanCos = (float) Math.cos(lean);
		for (int v = 0; v < count; v++)
		{
			float across = modelX[v] * cos - modelZ[v] * sin;
			depth[v] = modelX[v] * sin + modelZ[v] * cos;
			// And a lean, about the axis into the picture: a golem stood a little off true.
			x[v] = across * leanCos - modelY[v] * leanSin;
			y[v] = across * leanSin + modelY[v] * leanCos;
			leftmost = Math.min(leftmost, x[v]);
			rightmost = Math.max(rightmost, x[v]);
			top = Math.min(top, y[v]);
			bottom = Math.max(bottom, y[v]);
		}

		// Framed on the golem standing: its top half, at the size that fills the frame. Whatever
		// the pose puts outside that — a raised hand, the legs below the cut — falls off the edge.
		float[] framing = framing(standing, shown);
		float scale = zoom * framing[0];
		float offsetX = WIDTH / 2f;
		float offsetY = HEIGHT / 2f - framing[1] * scale;

		BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// Something behind it, so the figure is stood in a place rather than cut out of the air.
		g.setPaint(new GradientPaint(0, 0, BEHIND_TOP, 0, HEIGHT, BEHIND_BOTTOM));
		g.fillRect(0, 0, WIDTH, HEIGHT);

		int faces = model.getFaceCount();
		int[] first = model.getFaceIndices1();
		int[] second = model.getFaceIndices2();
		int[] third = model.getFaceIndices3();
		int[] colour1 = model.getFaceColors1();
		int[] colour2 = model.getFaceColors2();
		int[] colour3 = model.getFaceColors3();
		byte[] clear = model.getFaceTransparencies();

		// Furthest first, so nearer faces cover them: a depth buffer for two hundred triangles at
		// this size would be the same picture and a great deal more of it.
		Integer[] order = new Integer[faces];
		float[] away = new float[faces];
		for (int f = 0; f < faces; f++)
		{
			order[f] = f;
			away[f] = depth[first[f]] + depth[second[f]] + depth[third[f]];
		}
		Arrays.sort(order, (one, other) -> Float.compare(away[other], away[one]));

		int[] pointsX = new int[3];
		int[] pointsY = new int[3];
		for (int index = 0; index < faces; index++)
		{
			int f = order[index];
			if (clear != null && (clear[f] & 0xFF) >= 254)
			{
				continue;
			}
			// The third colour is not always a colour: -1 means the face is one flat shade, and -2
			// that it is not drawn at all. Taken for colour, -1 came out white and washed a golden
			// golem out to sand.
			int shade = colour3[f];
			if (shade == -2)
			{
				continue;
			}
			int a = first[f];
			int b = second[f];
			int c = third[f];
			pointsX[0] = Math.round(x[a] * scale + offsetX);
			pointsX[1] = Math.round(x[b] * scale + offsetX);
			pointsX[2] = Math.round(x[c] * scale + offsetX);
			pointsY[0] = Math.round(y[a] * scale + offsetY);
			pointsY[1] = Math.round(y[b] * scale + offsetY);
			pointsY[2] = Math.round(y[c] * scale + offsetY);

			// One colour for the face, the mean of its three: the client shades across a triangle,
			// and a golem is small enough here that the difference is a pixel either way.
			g.setColor(new Color(shade == -1 ? rgb(colour1[f])
				: mean(colour1[f], colour2[f], shade)));
			g.fillPolygon(pointsX, pointsY, 3);
		}
		g.dispose();
		return image;
	}

	/**
	 * How the picture is framed on a golem standing: the scale to draw it at, and the height in the
	 * model to put in the middle of the frame.
	 *
	 * <p>Only the top {@code shown} of it is considered, which is what makes this a portrait and
	 * not a figure. Width is measured at any angle rather than as the model happens to face, so
	 * turning a golem does not change its size.
	 */
	private static float[] framing(Model standing, float shown)
	{
		float[] modelX = standing.getVerticesX();
		float[] modelY = standing.getVerticesY();
		float[] modelZ = standing.getVerticesZ();
		if (modelX == null || modelY == null || modelZ == null)
		{
			return new float[]{1f, 0f};
		}
		int count = standing.getVerticesCount();
		float top = Float.MAX_VALUE;
		float bottom = -Float.MAX_VALUE;
		for (int v = 0; v < count; v++)
		{
			top = Math.min(top, modelY[v]);
			bottom = Math.max(bottom, modelY[v]);
		}
		float cut = top + (bottom - top) * shown;

		float across = 1f;
		for (int v = 0; v < count; v++)
		{
			if (modelY[v] <= cut)
			{
				across = Math.max(across, (float) Math.hypot(modelX[v], modelZ[v]));
			}
		}
		float tall = Math.max(1f, cut - top);
		return new float[]{
			Math.min((WIDTH - 2f * MARGIN) / (2f * across), (HEIGHT - 2f * MARGIN) / tall),
			(top + cut) / 2f,
		};
	}

	/** Behind the golem: dark above, and the ground it stands on catching a little light. */
	private static final Color BEHIND_TOP = new Color(24, 23, 22);
	private static final Color BEHIND_BOTTOM = new Color(46, 44, 41);

	private static int mean(int one, int two, int three)
	{
		int first = rgb(one);
		int second = rgb(two);
		int third = rgb(three);
		int red = ((first >> 16 & 0xFF) + (second >> 16 & 0xFF) + (third >> 16 & 0xFF)) / 3;
		int green = ((first >> 8 & 0xFF) + (second >> 8 & 0xFF) + (third >> 8 & 0xFF)) / 3;
		int blue = ((first & 0xFF) + (second & 0xFF) + (third & 0xFF)) / 3;
		return red << 16 | green << 8 | blue;
	}

	/**
	 * A model's colour as RGB. Models carry the game's own packed HSL, and the client turns it into
	 * colour through a palette built once at startup; this is that conversion for one colour, with
	 * the game's offsets to hue and saturation and the same gamma.
	 */
	private static int rgb(int hsl)
	{
		double hue = (double) JagexColor.unpackHue((short) hsl) / 64.0 + 0.0078125;
		double saturation = (double) JagexColor.unpackSaturation((short) hsl) / 8.0 + 0.0625;
		double lightness = (double) JagexColor.unpackLuminance((short) hsl) / 128.0;

		double red = lightness;
		double green = lightness;
		double blue = lightness;
		if (saturation != 0)
		{
			double q = lightness < 0.5 ? lightness * (1.0 + saturation)
				: lightness + saturation - lightness * saturation;
			double p = 2.0 * lightness - q;
			red = component(p, q, hue + 1.0 / 3.0);
			green = component(p, q, hue);
			blue = component(p, q, hue - 1.0 / 3.0);
		}
		return byteOf(red) << 16 | byteOf(green) << 8 | byteOf(blue);
	}

	/** One channel, gamma-corrected and kept inside a byte: the palette's 256 rounds up to it. */
	private static int byteOf(double channel)
	{
		return Math.min(255, (int) (Math.pow(channel, BRIGHTNESS) * 256));
	}

	private static double component(double p, double q, double hue)
	{
		double at = hue < 0 ? hue + 1 : hue > 1 ? hue - 1 : hue;
		if (at < 1.0 / 6.0)
		{
			return p + (q - p) * 6.0 * at;
		}
		if (at < 0.5)
		{
			return q;
		}
		if (at < 2.0 / 3.0)
		{
			return p + (q - p) * (2.0 / 3.0 - at) * 6.0;
		}
		return p;
	}
}
