package com.golemsdontdie;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.PluginPanel;

/**
 * Renders the sidebar to a PNG, so its layout can be looked at without the game running.
 *
 * <p>Drawn at the width the client gives it, with enough golems to bring the scrollbar out: the
 * row has to fit beside the scrollbar rather than under it.
 *
 * <pre>
 * java -cp "probe;classes;resources;client" com.golemsdontdie.PanelProbe [golems] [missing] [out.png]
 * </pre>
 */
public class PanelProbe
{
	public static void main(String[] args) throws Exception
	{
		int count = args.length > 0 ? Integer.parseInt(args[0]) : 20;
		int missing = args.length > 1 ? Integer.parseInt(args[1]) : 3;
		String out = args.length > 2 ? args[2] : "build/panel.png";

		GolemNames names = new GolemNames();
		names.load();
		// The setting is read through a config proxy in the client; here it is simply on.
		java.lang.reflect.Field config = GolemNames.class.getDeclaredField("config");
		config.setAccessible(true);
		config.set(names, new GolemsDontDieConfig()
		{
			@Override
			public boolean autoName()
			{
				return true;
			}
		});

		WorldPoint plinth = new WorldPoint(GolemContent.PLINTH_X, GolemContent.PLINTH_Y, 0);
		GolemSnapshot snapshot = new GolemSnapshot(1234, "Golem", new int[0], new short[0], new short[0],
			128, 128, 1, -1, -1, -1, plinth, 0);
		List<Golem> golems = new ArrayList<>();
		List<String> places = new ArrayList<>();
		for (int i = 0; i < count; i++)
		{
			Golem golem = Golem.onTile(snapshot, plinth, i * 7919 + 3, plinth);
			if (i % 4 == 0)
			{
				golem.setNickname(i == 0 ? "A golem with a very long name" : "Named " + i);
			}
			golems.add(golem);
			places.add(i % 3 == 0 ? "Sailing to Port Khazard" : "Taverley Dungeon");
		}

		SwingUtilities.invokeAndWait(() ->
		{
			GolemListPanel panel = new GolemListPanel(names, golem ->
			{
			}, (golem, name) ->
			{
			}, () ->
			{
			}, golem ->
			{
			}, golem ->
			{
			});
			panel.refresh(golems, missing, true);
			panel.showPlaces(golems, places);

			JFrame frame = new JFrame("Golems");
			frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
			frame.setContentPane(panel);
			frame.setSize(PluginPanel.PANEL_WIDTH + PluginPanel.SCROLLBAR_WIDTH, 600);
			frame.setVisible(true);
		});
		Thread.sleep(700);
		SwingUtilities.invokeAndWait(() ->
		{
			for (java.awt.Window window : java.awt.Window.getWindows())
			{
				if (window instanceof JFrame && window.isVisible())
				{
					BufferedImage shot = new BufferedImage(window.getWidth(), window.getHeight(),
						BufferedImage.TYPE_INT_RGB);
					window.paint(shot.getGraphics());
					try
					{
						new File(out).getParentFile().mkdirs();
						ImageIO.write(shot, "png", new File(out));
						System.out.println("wrote " + out);
					}
					catch (java.io.IOException e)
					{
						e.printStackTrace();
					}
				}
			}
		});
		System.exit(0);
	}
}
