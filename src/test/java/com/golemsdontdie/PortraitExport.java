package com.golemsdontdie;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a few golems' portraits to {@code .runelite/golem-exports/}, so the drawing in
 * {@link GolemPortrait} can be looked at without playing the game.
 *
 * <p>Runs once the client reaches the login screen. Dev client only: this lives in the test source
 * set and is never in the plugin jar.
 */
@PluginDescriptor(name = "Portrait Export (dev)", description = "Draws golem portraits to disk",
	enabledByDefault = true)
public class PortraitExport extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(PortraitExport.class);

	/** How many golems to draw on the first sheet: different poses and angles, same model. */
	private static final int GOLEMS = 12;

	/** Seeds to draw: the first few that draw a distinct hand, and one life of the party. */
	private static long[] seeds()
	{
		long[] seeds = new long[GOLEMS];
		int found = 0;
		for (long seed = 0; found < GOLEMS - 1 && seed < 100_000; seed += 7)
		{
			seeds[found++] = seed;
		}
		for (long seed = 0; seed < 100_000; seed++)
		{
			if (GolemTrait.LIFE_OF_THE_PARTY.in(GolemTrait.of(seed)))
			{
				seeds[GOLEMS - 1] = seed;
				break;
			}
		}
		return seeds;
	}

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private GolemPortrait portraits;

	@Override
	protected void startUp()
	{
		clientThread.invokeLater(() ->
		{
			if (client.getGameState().getState() < GameState.LOGIN_SCREEN.getState())
			{
				return false;
			}
			try
			{
				export();
			}
			catch (Exception e)
			{
				log.warn("Portrait export failed", e);
			}
			return true;
		});
	}

	private void export() throws Exception
	{
		WorldPoint plinth = new WorldPoint(GolemContent.PLINTH_X, GolemContent.PLINTH_Y, 0);
		GolemSnapshot snapshot = GolemSnapshot.restore(client, GolemContent.GOLEM_NPC_ID, -1, -1, -1,
			plinth, 0);
		if (snapshot == null)
		{
			log.warn("No golem in the cache to draw");
			return;
		}
		File dir = new File(RuneLite.RUNELITE_DIR, "golem-exports");
		dir.mkdirs();

		// One sheet of them all, side by side, and each on its own.
		long[] seeds = seeds();
		BufferedImage sheet = new BufferedImage(GolemPortrait.WIDTH * 6, GolemPortrait.HEIGHT * 2,
			BufferedImage.TYPE_INT_ARGB);
		for (int i = 0; i < seeds.length; i++)
		{
			Golem golem = Golem.onTile(snapshot, plinth, seeds[i], plinth);
			BufferedImage drawn = portraits.of(golem);
			if (drawn == null)
			{
				log.warn("No portrait for seed {}", seeds[i]);
				continue;
			}
			ImageIO.write(drawn, "png", new File(dir, "portrait-" + seeds[i] + ".png"));
			sheet.getGraphics().drawImage(drawn, (i % 6) * GolemPortrait.WIDTH,
				(i / 6) * GolemPortrait.HEIGHT, null);
		}
		ImageIO.write(sheet, "png", new File(dir, "portraits.png"));

		// Which way the model faces, and what its animations look like held still: a golem turned
		// every thirty degrees, and a strip of frames from each animation worth drawing.
		Golem golem = Golem.onTile(snapshot, plinth, 0, plinth);
		sheet = new BufferedImage(GolemPortrait.WIDTH * 12, GolemPortrait.HEIGHT, BufferedImage.TYPE_INT_ARGB);
		for (int at = 0; at < 12; at++)
		{
			BufferedImage drawn = portraits.of(golem, at * 30, GolemContent.GOLEM_IDLE_ANIMATION, 0, 1f);
			if (drawn != null)
			{
				sheet.getGraphics().drawImage(drawn, at * GolemPortrait.WIDTH, 0, null);
			}
		}
		ImageIO.write(sheet, "png", new File(dir, "portraits-around.png"));

		// Everything worth holding still: the golem's own two, the making of it, and the human
		// animations it plays at obstacles, which are the only ones with any attitude in them.
		int[] animations = {
			GolemContent.GOLEM_IDLE_ANIMATION, GolemContent.GOLEM_WALK_ANIMATION,
			GolemContent.ANIM_LADDER_GRAB, GolemContent.ANIM_BALANCE_WALK,
			GolemContent.ANIM_TIGHTROPE, GolemContent.ANIM_JUMP_STEPPINGSTONE, 10031,
		};

		sheet = new BufferedImage(GolemPortrait.WIDTH * 8, GolemPortrait.HEIGHT * animations.length,
			BufferedImage.TYPE_INT_ARGB);
		for (int row = 0; row < animations.length; row++)
		{
			for (int frame = 0; frame < 8; frame++)
			{
				// Spread across the animation rather than the first eight frames of it.
				net.runelite.api.Animation loaded = client.loadAnimation(animations[row]);
				int at = loaded == null ? frame : frame * Math.max(1, loaded.getNumFrames()) / 8;
				BufferedImage drawn = portraits.of(golem, 0, animations[row], at, 1f);
				if (drawn != null)
				{
					sheet.getGraphics().drawImage(drawn, frame * GolemPortrait.WIDTH,
						row * GolemPortrait.HEIGHT, null);
				}
			}
		}
		ImageIO.write(sheet, "png", new File(dir, "portraits-poses.png"));

		// The dance emotes from the dancing pull request, to see what they do to a stone golem.
		int[] dances = {862, 866, 2106, 2107, 2108, 3543, 7533, 7537, 2109, 1835, 4751, 2128};
		sheet = new BufferedImage(GolemPortrait.WIDTH * 6, GolemPortrait.HEIGHT * dances.length,
			BufferedImage.TYPE_INT_ARGB);
		for (int row = 0; row < dances.length; row++)
		{
			net.runelite.api.Animation loaded = client.loadAnimation(dances[row]);
			for (int frame = 0; frame < 6; frame++)
			{
				int at = loaded == null ? frame : frame * Math.max(1, loaded.getNumFrames()) / 6;
				BufferedImage drawn = portraits.of(golem, 0, dances[row], at, 1f);
				if (drawn != null)
				{
					sheet.getGraphics().drawImage(drawn, frame * GolemPortrait.WIDTH,
						row * GolemPortrait.HEIGHT, null);
				}
			}
		}
		ImageIO.write(sheet, "png", new File(dir, "portraits-dances.png"));
		log.info("Portraits written to {}", dir);
	}
}
