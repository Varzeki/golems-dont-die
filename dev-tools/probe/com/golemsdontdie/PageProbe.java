package com.golemsdontdie;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import net.runelite.api.coords.WorldPoint;

/**
 * Renders a golem's page to a PNG, so its layout can be looked at without the game running.
 *
 * <pre>
 * java -cp "probe;classes;resources;slf4j;api" com.golemsdontdie.PageProbe [seed] [out.png]
 * </pre>
 */
public class PageProbe
{
	public static void main(String[] args) throws Exception
	{
		if (args.length > 0 && args[0].equals("scan"))
		{
			for (long seed = 0; seed < 100_000; seed++)
			{
				int count = GolemTrait.list(GolemTrait.of(seed)).size();
				if (count >= 5)
				{
					System.out.println("seed " + seed + ": " + GolemTrait.list(GolemTrait.of(seed)));
					return;
				}
			}
			return;
		}
		long seed = args.length > 0 ? Long.parseLong(args[0]) : 7;
		String out = args.length > 1 ? args[1] : "build/page.png";

		GolemSnapshot snapshot = new GolemSnapshot(1234, "Golem", new int[0], new short[0], new short[0],
			128, 128, 1, -1, -1, -1, new WorldPoint(2596, 2256, 0), 0);
		Golem golem = Golem.onTile(snapshot, new WorldPoint(2596, 2256, 0), seed,
			new WorldPoint(2596, 2256, 0));
		golem.setNickname(args.length > 2 ? args[2] : "Pebble");
		System.out.println("traits: " + GolemTrait.list(golem.getTraits()));

		GolemPage page = new GolemPage(g -> System.out.println("find " + g.getNickname()));
		SwingUtilities.invokeAndWait(() ->
		{
			page.show(golem, null);
			page.showPlace("Wyrmscraig", true);
		});
		// Painted after the window is up, so the look and feel has done its work.
		Thread.sleep(500);
		SwingUtilities.invokeAndWait(() ->
		{
			JFrame frame = null;
			for (java.awt.Window window : java.awt.Window.getWindows())
			{
				if (window instanceof JFrame && window.isVisible())
				{
					frame = (JFrame) window;
				}
			}
			if (frame == null)
			{
				System.out.println("no window");
				return;
			}
			BufferedImage shot = new BufferedImage(frame.getWidth(), frame.getHeight(),
				BufferedImage.TYPE_INT_RGB);
			frame.paint(shot.getGraphics());
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
		});
		System.exit(0);
	}
}
