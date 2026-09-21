package com.golemsdontdie;

import java.io.FileReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

/**
 * Checks RoamPlanner.wayHome against the profile's learned routes: a golem in Wyrmscraig's cave
 * with the exit learned stays put, and with the exit forgotten goes back out through the entrance.
 *
 *   java com.golemsdontdie.CaveExitProbe profile.properties
 */
public class CaveExitProbe
{
	private static final int CAVE_EXIT = 62220;
	private static int failures;

	public static void main(String[] args) throws Exception
	{
		Properties profile = new Properties();
		try (Reader in = new FileReader(args[0]))
		{
			profile.load(in);
		}
		WorldMesh mesh = new WorldMesh();
		mesh.load();
		IslandMemory islandMemory = new IslandMemory();
		set(islandMemory, "worldMesh", mesh);
		islandMemory.deserialise(value(profile, "islandMap"));
		islandMemory.loadBundled();
		ObstacleKnowledge knowledge = new ObstacleKnowledge();
		knowledge.deserialise(value(profile, ObstacleKnowledge.LEARNED_KEY));
		knowledge.deserialiseConfirmed(value(profile, ObstacleKnowledge.CONFIRMED_KEY));
		knowledge.deserialiseRoutes(value(profile, ObstacleKnowledge.ROUTES_KEY));
		knowledge.deserialiseLines(value(profile, ObstacleKnowledge.LINES_KEY));
		TransportNetwork network = new TransportNetwork();
		network.load();
		network.setLearnedRoutes(knowledge.learnedRoutes());

		Method maxed = RoamSim.class.getDeclaredMethod("maxedClient");
		maxed.setAccessible(true);
		GolemAbilities abilities = new GolemAbilities();
		set(abilities, "client", maxed.invoke(null));
		set(abilities, "knowledge", knowledge);
		RoamPlanner planner = new RoamPlanner();
		set(planner, "mesh", mesh);
		set(planner, "transports", network);
		set(planner, "abilities", abilities);
		set(planner, "memory", islandMemory);

		Random random = new Random(1);
		TransportMemory memory = new TransportMemory();
		long started = System.nanoTime();
		planner.wayHome(2564, 8631, 0, 1000, memory, random);
		System.out.printf("  component index built in %.1f ms%n", (System.nanoTime() - started) / 1e6);
		check("exit learned: a golem in the cave stays", planner.wayHome(2564, 8631, 0, 1000, memory, random) == null);
		check("on the surface by the entrance: nothing to go back through", planner.wayHome(2535, 2208, 0, 1000, memory, random) == null);

		// Take the exit away, as if the player had never walked out: suppress its rows and rebuild.
		Field suppressedField = TransportNetwork.class.getDeclaredField("suppressed");
		suppressedField.setAccessible(true);
		@SuppressWarnings("unchecked")
		java.util.Set<GolemTransport> suppressed = (java.util.Set<GolemTransport>) suppressedField.get(network);
		for (GolemTransport t : network.all())
		{
			if (t.getObjectId() == CAVE_EXIT)
			{
				suppressed.add(t);
			}
		}
		Method link = TransportNetwork.class.getDeclaredMethod("link");
		link.setAccessible(true);
		link.invoke(network);

		GolemTransport home = planner.wayHome(2564, 8631, 0, 1000, memory, random);
		System.out.println("  way home: " + (home == null ? "none" : home.getObjectId() + " "
			+ home.getFromX() + "," + home.getFromY() + " -> " + home.getToX() + "," + home.getToY()));
		check("exit forgotten: a golem in the cave goes back out", home != null);
		check("through the entrance, to the surface", home != null && home.getToY() < 4160);
		check("still nothing on the surface", planner.wayHome(2535, 2208, 0, 1000, memory, random) == null);

		// Just come in: the way back is on the cooldown of the way in.
		if (home != null)
		{
			GolemTransport in = home.backThrough();
			memory.used(in, 1000);
			check("not straight after coming in", planner.wayHome(2564, 8631, 0, 1001, memory, random) == null);
		}
		System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}

	private static void check(String what, boolean ok)
	{
		System.out.println((ok ? "ok   " : "FAIL ") + what);
		failures += ok ? 0 : 1;
	}

	private static String value(Properties profile, String key)
	{
		return profile.getProperty(GolemsDontDieConfig.GROUP + "." + key);
	}

	private static void set(Object target, String name, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
