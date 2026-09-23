package com.golemsdontdie;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import java.io.File;
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
	/**
	 * A golem's picture. One the dev client exported if there is one — see PortraitExport — and a
	 * grey figure of the same size if not, since nothing here can draw a model.
	 */
	private static java.awt.image.BufferedImage standIn() throws Exception
	{
		File exported = new File(net.runelite.client.RuneLite.RUNELITE_DIR,
			"golem-exports/portrait-0.png");
		if (exported.isFile())
		{
			return javax.imageio.ImageIO.read(exported);
		}
		java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
			GolemPortrait.WIDTH, GolemPortrait.HEIGHT, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = image.createGraphics();
		g.setColor(new java.awt.Color(90, 88, 84));
		g.fillOval(GolemPortrait.WIDTH / 2 - 16, 14, 32, 30);
		g.fillRect(GolemPortrait.WIDTH / 2 - 22, 46, 44, 60);
		g.fillRect(GolemPortrait.WIDTH / 2 - 34, 50, 12, 44);
		g.fillRect(GolemPortrait.WIDTH / 2 + 22, 50, 12, 44);
		g.dispose();
		return image;
	}

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

		// A record to look at, since nothing here plays the game.
		golem.getHistory().restore(System.currentTimeMillis() - 86400000L * 96, 1483, 37, 214_500,
			2412, 3812, new WorldPoint(2596, 2256, 0));

		java.awt.image.BufferedImage picture = standIn();
		GolemNames names = new GolemNames();
		names.load();
		PlaceNames places = new PlaceNames();
		places.load();

		// A few journeys to read back, walked through the census the way the plugin does: the
		// journal is written by arriving somewhere, so arriving is what the probe has to do.
		int[][] been = {
			{2596, 2256, 0}, {2660, 2256, 0}, {2816, 3264, 0}, {2848, 3424, 0},
			{2884, 9798, 0}, {2884, 9860, 0}, {3222, 3218, 0}, {3290, 3290, 0},
			{2440, 3096, 0}, {2528, 3096, 0},
		};
		for (int[] at : been)
		{
			golem.getHistory().sample(at[0], at[1], at[2], new WorldPoint(2596, 2256, 0));
		}

		GolemPage page = new GolemPage(g -> System.out.println("find " + g.getNickname()), names, places);
		SwingUtilities.invokeAndWait(() ->
		{
			page.show(golem, null);
			page.showPlace("Neitiznot", true);
			page.setFurthest("Neitiznot");
			// The real picture is drawn from the golem's model, which needs a running client; this
			// is a stand-in of the same size, to see the frame it hangs in.
			page.showPicture(golem, picture);
			// -Dtab=journal to look at the other one. Reached by reflection rather than by an
			// opener on the page itself: nothing in the client needs one.
			if ("journal".equals(System.getProperty("tab")))
			{
				try
				{
					java.lang.reflect.Field field = GolemPage.class.getDeclaredField("tabs");
					field.setAccessible(true);
					((javax.swing.JTabbedPane) field.get(page)).setSelectedIndex(1);
				}
				catch (ReflectiveOperationException e)
				{
					System.out.println("no tabs: " + e);
				}
			}
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
