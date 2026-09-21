package com.golemsdontdie;

import java.io.FileReader;
import java.io.Reader;
import java.util.Properties;
import java.util.TreeSet;

/**
 * Prints the regions "Restrict Golem ambition" keeps golems in, from the shipped transports
 * and a profile's learned routes, so a leak onto the mainland shows up as a region that is
 * obviously not Wyrmscraig.
 *
 *   java com.golemsdontdie.HomeRegionsProbe profile.properties
 */
public class HomeRegionsProbe
{
	public static void main(String[] args) throws Exception
	{
		Properties profile = new Properties();
		try (Reader in = new FileReader(args[0]))
		{
			profile.load(in);
		}
		ObstacleKnowledge knowledge = new ObstacleKnowledge();
		knowledge.deserialise(profile.getProperty("golemsdontdie." + ObstacleKnowledge.LEARNED_KEY));
		knowledge.deserialiseRoutes(profile.getProperty("golemsdontdie." + ObstacleKnowledge.ROUTES_KEY));
		knowledge.deserialiseLines(profile.getProperty("golemsdontdie." + ObstacleKnowledge.LINES_KEY));
		knowledge.setRouteFilter(r -> r[1] < 6400 && r[4] < 6400);

		TransportNetwork network = new TransportNetwork();
		network.load();
		System.out.println("shipped only:");
		print(network);
		network.setLearnedRoutes(knowledge.learnedRoutes());
		System.out.println("with learned routes:");
		print(network);
	}

	private static void print(TransportNetwork network)
	{
		TreeSet<Integer> regions = new TreeSet<>(network.homeRegions());
		System.out.println("  " + regions.size() + " regions");
		for (int region : regions)
		{
			int x = region >> 8 << 6;
			int y = (region & 0xFF) << 6;
			System.out.printf("    %d  x %d-%d  y %d-%d%s%n", region, x, x + 63, y, y + 63,
				y >= 4160 ? "  (underground)" : "");
		}
	}
}
