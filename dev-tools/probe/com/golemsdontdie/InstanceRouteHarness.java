package com.golemsdontdie;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * The Mad Angel's pews, end to end, without the client.
 *
 * <ul>
 *   <li>Sightings that cross into and out of an instance keep that fact on their routes, and it
 *       survives a save; routes saved before it existed still load.</li>
 *   <li>A pew climbed once from each of its two tiles is learned — each route alone has one
 *       sighting, which used to leave both unusable.</li>
 *   <li>Transports built from those routes know they enter or leave an instance.</li>
 *   <li>With a profile: the room behind the pew floods as a room, and does not reach the
 *       cathedral side.</li>
 *   <li>The golem save reads both its old and new forms.</li>
 * </ul>
 *
 *   java com.golemsdontdie.InstanceRouteHarness [profile.properties]
 */
public class InstanceRouteHarness
{
	private static final int ENTRY = 62250;
	private static final int EXIT = 62251;

	private static int failures;

	public static void main(String[] args) throws Exception
	{
		ObstacleKnowledge knowledge = new ObstacleKnowledge();
		knowledge.setRouteFilter(r -> r[1] < 6400 && r[4] < 6400);

		// Once in and out, from the pew's south tile.
		knowledge.record(pew(ENTRY, new int[]{832}, 2539, 2216, 2537, 2216, -3, false, true));
		knowledge.record(pew(EXIT, new int[0], 2537, 2216, 2539, 2216, 0, true, false));
		check("one use of each pew is not yet a route", find(knowledge.learnedRoutes(), ENTRY, 2539, 2216) == null
			&& find(knowledge.learnedRoutes(), EXIT, 2537, 2216) == null);

		// And again from its north tile.
		knowledge.record(pew(ENTRY, new int[]{832}, 2539, 2215, 2537, 2215, -3, false, true));
		knowledge.record(pew(EXIT, new int[0], 2537, 2215, 2539, 2215, 0, true, false));
		List<int[]> learned = knowledge.learnedRoutes();
		int[] in = find(learned, ENTRY, 2539, 2216);
		int[] out = find(learned, EXIT, 2537, 2215);
		check("both tiles of each pew vouch for each other", in != null && out != null
			&& find(learned, ENTRY, 2539, 2215) != null && find(learned, EXIT, 2537, 2216) != null);
		check("the way in is marked as into an instance", in != null && in[8] == GolemTransport.INTO_INSTANCE);
		check("the way out is marked as out of one", out != null && out[8] == GolemTransport.OUT_OF_INSTANCE);

		String saved = knowledge.serialiseRoutes();
		ObstacleKnowledge reloaded = new ObstacleKnowledge();
		reloaded.deserialiseRoutes(saved);
		int[] again = find(reloaded.learnedRoutes(), ENTRY, 2539, 2216);
		check("marks survive a save (" + saved + ")", again != null && again[8] == GolemTransport.INTO_INSTANCE);

		ObstacleKnowledge old = new ObstacleKnowledge();
		old.deserialiseRoutes("62262,2565,2217,0>2565,2219,0,4");
		int[] stone = find(old.learnedRoutes(), 62262, 2565, 2217);
		check("a route saved without a mark loads unmarked", stone != null && stone[8] == 0);
		check("and saves exactly as it did", old.serialiseRoutes().equals("62262,2565,2217,0>2565,2219,0,4"));

		TransportNetwork network = new TransportNetwork();
		network.load();
		network.setLearnedRoutes(learned);
		check("the pew into the room enters an instance", any(network, ENTRY, 2539, 2216, true));
		check("the pew out of it leaves one", any(network, EXIT, 2537, 2215, false));

		if (args.length > 0)
		{
			IslandMemory memory = new IslandMemory();
			WorldMesh mesh = new WorldMesh();
			mesh.load();
			set(memory, "worldMesh", mesh);
			String map = null;
			for (String line : Files.readAllLines(Paths.get(args[0])))
			{
				if (line.startsWith("golemsdontdie.islandMap="))
				{
					map = line.substring(line.indexOf('=') + 1).replace("\\", "");
				}
			}
			memory.deserialise(map);
			memory.loadBundled();
			GolemPathfinder pathfinder = new GolemPathfinder();
			set(pathfinder, "memory", memory);
			Map<Long, Long> room = pathfinder.flood(2537, 2216, 0, 4000);
			check("the room behind the pew is a room (" + room.size() + " tiles)", room.size() < 4000);
			check("and cannot be walked out of to the cathedral side",
				!room.containsKey(GolemPathfinder.pack(2539, 2216)));
		}

		GolemStore store = new GolemStore();
		List<GolemStore.SavedGolem> golems = store.deserialise(
			"16304,2537,2216,0,512,2596,2256,1,2,3,Bob;16304,2537,2216,0,512,2596,2256,1,2,3,,1;1,2,3");
		check("old and new golem saves both load, a malformed one does not", golems.size() == 2);
		check("an old save is not in an instance", golems.size() == 2 && !golems.get(0).inInstance);
		check("a new save keeps its instance", golems.size() == 2 && golems.get(1).inInstance
			&& golems.get(1).nickname == null);

		List<GolemStore.SavedGolem> sailed = store.deserialise(
			"16304,2537,2216,0,512,2596,2256,1,2,3,Bob,0,1789000000000;16304,2537,2216,0,512,2596,2256,1,2,3,Bob,0,1,9");
		check("a sailing cooldown survives a save; too many fields does not load",
			sailed.size() == 1 && sailed.get(0).shoreLeaveUntil == 1789000000000L && !sailed.get(0).inInstance);

		System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
	}

	private static ObstacleSighting pew(int object, int[] clips, int fx, int fy, int tx, int ty, int lineX,
		boolean fromInstance, boolean toInstance)
	{
		return new ObstacleSighting(object, "Church pew", object == ENTRY ? "Climb Church pew" : "Exit Church pew",
			clips, object == ENTRY ? 3 : 1, fx, fy, 0, tx, ty, 0, false, 0, 0, null, lineX, 0,
			fromInstance, toInstance);
	}

	private static int[] find(List<int[]> routes, int object, int x, int y)
	{
		for (int[] r : routes)
		{
			if (r[0] == object && r[1] == x && r[2] == y)
			{
				return r;
			}
		}
		return null;
	}

	private static boolean any(TransportNetwork network, int object, int x, int y, boolean enters)
	{
		for (GolemTransport t : network.from(x, y))
		{
			if (t.getObjectId() == object && (enters ? t.entersInstance() : t.leavesInstance()))
			{
				return true;
			}
		}
		return false;
	}

	private static void set(Object target, String name, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static void check(String label, boolean ok)
	{
		failures += ok ? 0 : 1;
		System.out.println((ok ? "ok   " : "FAIL ") + label);
	}
}
