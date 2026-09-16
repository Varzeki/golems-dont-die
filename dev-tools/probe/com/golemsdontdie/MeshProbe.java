package com.golemsdontdie;

/**
 * The plugin's own walkability answers, for the validator.
 *
 * <p>The validator has to say whether a golem can stand on a tile, and the only honest
 * answer is the one the golems themselves get. Re-implementing the collision format would
 * give a second answer that could disagree with the first, so this loads the shipped mesh
 * and the saved island map with the plugin's own classes and asks them.
 *
 * <p>Public and static because the validator lives outside this package and calls it by
 * reflection, so it still runs without a built plugin — just without these checks.
 */
public final class MeshProbe
{
	private static WorldMesh mesh;
	private static IslandMemory memory;

	private MeshProbe()
	{
	}

	/** Loads the shipped mesh and island map, under the saved harvest if there is one. */
	public static void load(String islandMap)
	{
		mesh = new WorldMesh();
		mesh.load();
		memory = new IslandMemory();
		// Injected in the plugin. The memory falls back to the mesh for any region it has
		// not harvested, so without it every such tile threw — and the validator, reading
		// a failure as "no answer", called all of them walkable.
		try
		{
			java.lang.reflect.Field field = IslandMemory.class.getDeclaredField("worldMesh");
			field.setAccessible(true);
			field.set(memory, mesh);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException("IslandMemory no longer has a worldMesh field", e);
		}
		memory.deserialise(islandMap);
		memory.loadBundled();
		// Floors reached only by a transport are land to the plugin; see WorldMesh. The shipped
		// table only — learned routes are the validator's own business and it reads them itself.
		TransportNetwork network = new TransportNetwork();
		network.load();
		mesh.admitTransportEnds(network.all());

		obstacles = new ObstacleIndex();
		obstacles.load();
		pathfinder = new GolemPathfinder();
		try
		{
			java.lang.reflect.Field field = GolemPathfinder.class.getDeclaredField("memory");
			field.setAccessible(true);
			field.set(pathfinder, memory);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException("GolemPathfinder no longer has a memory field", e);
		}
	}

	/**
	 * What a golem's pathfinder may walk on: the island memory, which is the bounds every
	 * walk is planned inside and the test a transport's origin and landing are held to.
	 */
	public static boolean walkable(int x, int y, int plane)
	{
		return memory.isKnownWalkable(x, y, plane);
	}

	private static ObstacleIndex obstacles;
	private static GolemPathfinder pathfinder;

	/**
	 * The plugin's test for whether a route is a real obstacle, repeated with its own pieces:
	 * the obstacle index, and a short walk on the golems' map. {@code shipped} stands in for
	 * the transport table, which the validator reads for itself.
	 */
	public static boolean isObstacle(int objectId, boolean shipped, int fromX, int fromY, int fromPlane,
		int toX, int toY, int toPlane)
	{
		if (fromX >= 6400 || toX >= 6400)
		{
			return false;
		}
		if (shipped || obstacles.knows(objectId))
		{
			return true;
		}
		if (fromPlane != toPlane || !RouteGeometry.local(toX - fromX, toY - fromY))
		{
			return true;
		}
		int steps = Math.max(Math.abs(toX - fromX), Math.abs(toY - fromY));
		java.util.Deque<int[]> walk = pathfinder.findPath(fromX, fromY, fromPlane, toX, toY,
			new RoamBounds(memory, fromPlane, fromX, fromY));
		return walk.isEmpty() || walk.size() > steps * 2 + 4;
	}

	/** Whether a golem's own map lets it take one step, cardinal or diagonal. */
	public static boolean canStep(int x, int y, int plane, int dx, int dy)
	{
		return memory.canStep(x, y, plane, dx, dy);
	}

	/** What the roam planner will accept as a destination on the shipped land fill. */
	public static boolean landWalkable(int x, int y, int plane)
	{
		return mesh.isLandWalkable(x, y, plane);
	}

	/** Whether a tile is part of the open sea, which no golem may walk on whatever a harvest said. */
	public static boolean ocean(int x, int y, int plane)
	{
		return mesh.isOcean(x, y, plane);
	}

	/** The connected area a tile belongs to, 0 if the mesh has no answer. */
	public static int component(int x, int y, int plane)
	{
		return mesh.componentAt(x, y, plane);
	}
}
