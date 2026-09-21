package com.golemsdontdie;

import java.io.FileReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.util.Properties;

/** Lists instance routes in the profile and the room each lands in, as the plugin's instanceRooms finds it. */
public class RoomProbe
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
		memory.deserialise(profile.getProperty(GolemsDontDieConfig.GROUP + ".islandMap"));
		memory.loadBundled();
		ObstacleKnowledge knowledge = new ObstacleKnowledge();
		knowledge.deserialise(profile.getProperty(GolemsDontDieConfig.GROUP + "." + ObstacleKnowledge.LEARNED_KEY));
		knowledge.deserialiseConfirmed(profile.getProperty(GolemsDontDieConfig.GROUP + "." + ObstacleKnowledge.CONFIRMED_KEY));
		knowledge.deserialiseRoutes(profile.getProperty(GolemsDontDieConfig.GROUP + "." + ObstacleKnowledge.ROUTES_KEY));
		knowledge.deserialiseLines(profile.getProperty(GolemsDontDieConfig.GROUP + "." + ObstacleKnowledge.LINES_KEY));
		knowledge.setRouteFilter(r -> r[1] < 6400 && r[4] < 6400);
		TransportNetwork network = new TransportNetwork();
		network.load();
		network.setLearnedRoutes(knowledge.learnedRoutes());
		GolemPathfinder pathfinder = new GolemPathfinder();
		set(pathfinder, "memory", memory);
		for (GolemTransport t : network.all())
		{
			if (!t.entersInstance() && !t.leavesInstance())
			{
				continue;
			}
			TileMap reached = pathfinder.flood(t.getToX(), t.getToY(), t.getToPlane(), 4000);
			boolean backOnFoot = t.getFromPlane() == t.getToPlane()
				&& reached.containsKey(GolemPathfinder.pack(t.getFromX(), t.getFromY()));
			System.out.printf("%s obj %d %d,%d,%d -> %d,%d,%d  landing floods %d tiles%s%n",
				t.entersInstance() ? "INTO " : "OUT  ", t.getObjectId(), t.getFromX(), t.getFromY(), t.getFromPlane(),
				t.getToX(), t.getToY(), t.getToPlane(), reached.size(),
				t.entersInstance() ? (reached.size() >= 4000 || backOnFoot ? "  -> NOT a room" : "  -> a room") : "");
		}
	}

	private static void set(Object target, String name, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
