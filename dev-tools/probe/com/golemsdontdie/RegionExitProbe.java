package com.golemsdontdie;

import java.io.FileReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Properties;

/**
 * Lists every transport into and out of a region, with whether golems may use it, whether its
 * landing is ground a golem can stand on, and the mesh components at both ends — for finding why
 * golems gather somewhere.
 *
 *   java com.golemsdontdie.RegionExitProbe profile.properties region [region...]
 */
public class RegionExitProbe
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
		set(memory, "worldMesh", mesh);
		memory.deserialise(value(profile, "islandMap"));
		memory.loadBundled();
		ObstacleKnowledge knowledge = new ObstacleKnowledge();
		knowledge.deserialise(value(profile, ObstacleKnowledge.LEARNED_KEY));
		knowledge.deserialiseConfirmed(value(profile, ObstacleKnowledge.CONFIRMED_KEY));
		knowledge.deserialiseRoutes(value(profile, ObstacleKnowledge.ROUTES_KEY));
		knowledge.deserialiseLines(value(profile, ObstacleKnowledge.LINES_KEY));
		knowledge.setRouteFilter(r -> r[1] < 6400 && r[4] < 6400);
		TransportNetwork network = new TransportNetwork();
		network.load();
		network.setLearnedRoutes(knowledge.learnedRoutes());
		mesh.admitTransportEnds(network.all());
		Method maxed = RoamSim.class.getDeclaredMethod("maxedClient");
		maxed.setAccessible(true);
		GolemAbilities abilities = new GolemAbilities();
		set(abilities, "client", maxed.invoke(null));
		set(abilities, "knowledge", knowledge);

		for (int i = 1; i < args.length; i++)
		{
			int region = Integer.parseInt(args[i]);
			System.out.println("== region " + region);
			for (GolemTransport t : network.all())
			{
				int from = (t.getFromX() >> 6) << 8 | (t.getFromY() >> 6);
				int to = (t.getToX() >> 6) << 8 | (t.getToY() >> 6);
				if ((from != region && to != region) || !network.isOffered(t))
				{
					continue;
				}
				System.out.printf("  %s obj %6d arch %2d dur %2d  %d,%d,%d (r%d c%d) -> %d,%d,%d (r%d c%d)  usable %b  lands safe %b%n",
					from == region && to == region ? "inside" : from == region ? "OUT   " : "IN    ",
					t.getObjectId(), t.getArchetype(), t.getDuration(),
					t.getFromX(), t.getFromY(), t.getFromPlane(), from, mesh.componentAt(t.getFromX(), t.getFromY(), t.getFromPlane()),
					t.getToX(), t.getToY(), t.getToPlane(), to, mesh.componentAt(t.getToX(), t.getToY(), t.getToPlane()),
					abilities.canUse(t), safe(memory, mesh, t.getToX(), t.getToY(), t.getToPlane()));
			}
		}
	}

	private static boolean safe(IslandMemory memory, WorldMesh mesh, int x, int y, int plane)
	{
		return memory.isKnownWalkable(x, y, plane) || (mesh.isLandWalkable(x, y, plane) && !mesh.isIsolated(x, y, plane));
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
