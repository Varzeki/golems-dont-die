package com.golemsdontdie.telemetry;

import java.io.BufferedReader;
import java.io.StringReader;
import org.junit.Assert;
import org.junit.Test;

/**
 * The obstacle data file on its own, with nothing from the rest of the plugin: what a line holds,
 * how repeats are counted, and that it reads back as it was written.
 */
public class ObstacleDataFileTest
{
	/** The file's contents, or null before it is first written: the plugin's folder, in memory. */
	private String contents;

	private ObstacleDataFile.Store store()
	{
		return new ObstacleDataFile.Store()
		{
			@Override
			public boolean exists()
			{
				return contents != null;
			}

			@Override
			public BufferedReader reader()
			{
				return new BufferedReader(new StringReader(contents));
			}

			@Override
			public void write(String text)
			{
				contents = text;
			}
		};
	}

	@Test
	public void keepsOneLinePerPlayerCrossingAndCountsRepeats() throws Exception
	{
		ObstacleDataFile data = new ObstacleDataFile(store(), "test");
		data.load();
		data.add(stile(3300, 3300, 3300, 3302));
		data.add(stile(3300, 3300, 3300, 3302));
		data.add(stile(3400, 3400, 3400, 3402));
		Assert.assertEquals("the same crossing twice is one line", 2, data.playerLines());
		data.save();

		String[] line = line("P\t12345");
		Assert.assertEquals("the name, with the tab taken out", "Stile here", value("P", line, "name"));
		Assert.assertEquals("seen twice", "2", value("P", line, "seen"));
		Assert.assertEquals("the path, as cycle:along:side", "0:0:0,2:64:0,4:256:0", value("P", line, "path"));

		ObstacleDataFile reloaded = new ObstacleDataFile(store(), "test");
		reloaded.load();
		Assert.assertEquals("both lines survive a restart", 2, reloaded.playerLines());
		reloaded.add(stile(3300, 3300, 3300, 3302));
		reloaded.save();
		Assert.assertEquals("and counting carries on", "3", value("P", line("P\t12345\tStile here\tClimb-over Stile\t839,839\t3\t33\t12\t3300\t3300"), "seen"));
	}

	@Test
	public void measuresHowFarAGolemStrayedFromItsRoute() throws Exception
	{
		ObstacleDataFile data = new ObstacleDataFile(store(), "test");
		data.load();

		// A route two tiles north. From the middle of the start tile the golem drifts half a tile to
		// the east (its right), then lands a quarter of a tile short of the middle of the end tile.
		int x = 3300 * 128 + 64;
		int y = 3300 * 128 + 64;
		int[][] drifted = {{0, x, y}, {10, x + 64, y + 128}, {20, x, y + 224}};
		data.add(new GolemCrossing(12345, 3300, 3300, 0, 3300, 3302, 0, drifted, false));
		int[][] straight = {{0, x, y}, {20, x, y + 256}};
		data.add(new GolemCrossing(12345, 3300, 3300, 0, 3300, 3302, 0, straight, true));
		Assert.assertEquals("one route, one line", 1, data.golemLines());
		data.save();

		String[] line = line("G\t12345");
		Assert.assertEquals("attempts", "2", value("G", line, "attempts"));
		Assert.assertEquals("worst distance off the line: half a tile", "64", value("G", line, "worstSide"));
		Assert.assertEquals("worst landing: a quarter of a tile short", "32", value("G", line, "worstLanding"));
		Assert.assertEquals("started on the start tile", "0", value("G", line, "worstStart"));
		Assert.assertEquals("one attempt hopped over a stone", "1", value("G", line, "overStone"));
		Assert.assertEquals("the latest path, measured along the route", "0:0:0,20:256:0", value("G", line, "path"));
	}

	@Test
	public void sideIsNegativeToTheLeft()
	{
		// Facing north, east is to the right and west to the left.
		Assert.assertArrayEquals(new int[]{0, 64}, ObstacleDataFile.alongAndSide(64, 0, 0, 256));
		Assert.assertArrayEquals(new int[]{0, -64}, ObstacleDataFile.alongAndSide(-64, 0, 0, 256));
		Assert.assertArrayEquals(new int[]{128, 0}, ObstacleDataFile.alongAndSide(0, 128, 0, 256));
	}

	@Test
	public void aFileFromAnotherSchemaIsReplacedNotMisread() throws Exception
	{
		contents = "#schema\t1\nP\t1\t2\t3\n";
		ObstacleDataFile data = new ObstacleDataFile(store(), "test");
		data.load();
		Assert.assertEquals(0, data.playerLines());
	}

	/** A column of a line, by the name the file's own header gives it. */
	private String value(String kind, String[] line, String name) throws Exception
	{
		String[] names = line("#" + kind + "\t");
		for (int i = 0; i < names.length; i++)
		{
			if (names[i].equals(name))
			{
				return line[i];
			}
		}
		throw new AssertionError("no column " + name);
	}

	private String[] line(String startingWith) throws Exception
	{
		for (String line : contents.split("\n"))
		{
			if (line.startsWith(startingWith))
			{
				return line.trim().split("\t", -1);
			}
		}
		throw new AssertionError("no line starting " + startingWith);
	}

	private static PlayerCrossing stile(int fromX, int fromY, int toX, int toY)
	{
		return new PlayerCrossing(12345, "Stile\there", "Climb-over Stile", new int[]{839, 839}, 3, 33, 12,
			fromX, fromY, 0, toX, toY, 0, 0, 2, false,
			new int[][]{{0, 0, 0}, {2, 64, 0}, {4, 256, 0}}, new int[][]{{0, 839}}, 0);
	}
}
