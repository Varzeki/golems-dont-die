package com.golemsdontdie;

import java.io.FileReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.util.Properties;

/** Prints what the maps say about Wyrmscraig's cave lake and the saved golems standing near it. */
public class LakeProbe
{
	public static void main(String[] args) throws Exception
	{
		Properties profile = new Properties();
		try (Reader in = new FileReader(args[0]))
		{
			profile.load(in);
		}
		WorldMesh mesh = new WorldMesh();
		mesh.load();
		IslandMemory memory = new IslandMemory();
		Field f = IslandMemory.class.getDeclaredField("worldMesh");
		f.setAccessible(true);
		f.set(memory, mesh);
		memory.deserialise(profile.getProperty(GolemsDontDieConfig.GROUP + ".islandMap"));
		memory.loadBundled();

		int lake = mesh.componentAt(GolemContent.CAVE_LAKE_MOUTH_X, GolemContent.CAVE_LAKE_MOUTH_Y, 0);
		System.out.println("lake component " + lake);
		// A strip across the cave, west to east through the lake mouth.
		for (int y = 8620; y >= 8590; y -= 2)
		{
			StringBuilder row = new StringBuilder(String.format("%d ", y));
			for (int x = 2540; x <= 2600; x++)
			{
				int c = mesh.componentAt(x, y, 0);
				boolean known = memory.isKnownWalkable(x, y, 0);
				boolean land = mesh.isLandWalkable(x, y, 0);
				row.append(c == lake && c != 0 ? (known ? 'W' : 'w') : known ? '#' : land ? '+' : '.');
			}
			System.out.println(row);
		}
		System.out.println("W = lake the golems' own map calls walkable, w = lake it does not, # = known ground, + = mesh ground only");

		String saved = profile.getProperty(GolemsDontDieConfig.GROUP + "." + GolemsDontDieConfig.SAVED_GOLEMS_KEY, "");
		int onLake = 0, near = 0;
		for (String entry : saved.split(";"))
		{
			String[] p = entry.split(",", -1);
			if (p.length < 4)
			{
				continue;
			}
			try
			{
				int x = Integer.parseInt(p[1]);
				int y = Integer.parseInt(p[2]);
				int plane = Integer.parseInt(p[3]);
				if (y > 8500 && y < 8700 && x > 2500 && x < 2650 && plane == 0)
				{
					near++;
					if (mesh.componentAt(x, y, 0) == lake)
					{
						onLake++;
						System.out.println("saved golem on the lake at " + x + "," + y);
					}
				}
			}
			catch (NumberFormatException e)
			{
				// not a golem entry
			}
		}
		System.out.println(near + " saved golems in the cave, " + onLake + " of them on the lake");
	}
}
