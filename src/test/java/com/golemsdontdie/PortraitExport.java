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

	/** Seeds to draw: different poses and angles out of the same model. */
	private static final long[] SEEDS = {0, 7, 12345, -42, 987654321L, 555};

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
		BufferedImage sheet = new BufferedImage(GolemPortrait.WIDTH * SEEDS.length, GolemPortrait.HEIGHT,
			BufferedImage.TYPE_INT_ARGB);
		for (int i = 0; i < SEEDS.length; i++)
		{
			Golem golem = Golem.onTile(snapshot, plinth, SEEDS[i], plinth);
			BufferedImage drawn = portraits.of(golem);
			if (drawn == null)
			{
				log.warn("No portrait for seed {}", SEEDS[i]);
				continue;
			}
			ImageIO.write(drawn, "png", new File(dir, "portrait-" + SEEDS[i] + ".png"));
			sheet.getGraphics().drawImage(drawn, i * GolemPortrait.WIDTH, 0, null);
		}
		ImageIO.write(sheet, "png", new File(dir, "portraits.png"));
		log.info("Portraits written to {}", dir);
	}
}
