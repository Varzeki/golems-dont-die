package com.golemsdontdie;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;

/**
 * Crossings from Wyrmscraig's dock, planned as the plugin plans them, and checked.
 *
 * <ul>
 *   <li>Every leg stays on the open sea.</li>
 *   <li>Golems crossing to the same port take different lines.</li>
 *   <li>Some crossings rest.</li>
 *   <li>A raft sails as a boat does: it turns at most a compass point a tick, moves only the
 *       way it faces, and never faster than full sail.</li>
 *   <li>A crossing starts on the water at the mooring and ends ashore.</li>
 *   <li>Position moves smoothly: sampled every tenth of a tick, it never jumps.</li>
 * </ul>
 *
 *   java com.golemsdontdie.VoyageProbe
 */
public class VoyageProbe
{
	private static int failures;

	public static void main(String[] args) throws Exception
	{
		WorldMesh mesh = new WorldMesh();
		mesh.load();
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, arguments) -> method.getName().equals("getRealSkillLevel") ? 99
				: method.getName().equals("getIntStack") ? new int[]{2}
				: method.getReturnType() == int.class ? 0 : method.getReturnType() == boolean.class ? false : null);

		SailingDocks docks = new SailingDocks();
		set(docks, "mesh", mesh);
		set(docks, "client", client);
		Method loadBuoys = SailingDocks.class.getDeclaredMethod("loadBuoys");
		Method snapToWater = SailingDocks.class.getDeclaredMethod("snapToWater", WorldPoint.class);
		Method quayside = SailingDocks.class.getDeclaredMethod("quayside", int.class, WorldPoint.class);
		loadBuoys.setAccessible(true);
		snapToWater.setAccessible(true);
		quayside.setAccessible(true);
		loadBuoys.invoke(docks);
		@SuppressWarnings("unchecked")
		List<WorldPoint> buoys = (List<WorldPoint>) get(docks, "buoys");
		Method landQuayside = SailingDocks.class.getDeclaredMethod("quayside", int.class, WorldPoint.class, boolean.class);
		Method snapToInlandWater = SailingDocks.class.getDeclaredMethod("snapToInlandWater", WorldPoint.class);
		landQuayside.setAccessible(true);
		snapToInlandWater.setAccessible(true);
		for (int i = 0; i < buoys.size(); i++)
		{
			WorldPoint water = (WorldPoint) snapToWater.invoke(docks, buoys.get(i));
			// As SailingDocks.load: a dock off the open sea stands on land and berths on its own water.
			WorldPoint shore = (WorldPoint) landQuayside.invoke(docks, i, buoys.get(i), water != null);
			WorldPoint berth = water != null ? (WorldPoint) snapToWater.invoke(docks, shore)
				: (WorldPoint) snapToInlandWater.invoke(docks, shore);
			docks.getDocks().add(new SailingDocks.Dock(i, "dock " + i, 1, -1, water != null ? water : buoys.get(i),
				water != null, shore, berth != null ? berth : (water != null ? water : buoys.get(i))));
		}
		SeaMesh sea = new SeaMesh();
		set(sea, "mesh", mesh);
		Voyage voyage = new Voyage();
		set(voyage, "docks", docks);
		set(voyage, "sea", sea);

		SailingDocks.Dock home = docks.byRow(59);
		int rests = 0;
		int crossings = 0;
		boolean allAtSea = true;
		boolean allSmooth = true;
		boolean startsOnWater = true;
		boolean endsAshore = true;
		int worstJump = 0;
		int worstTurn = 0;
		int worstSpeed = 0;
		int sideways = 0;
		int ticksSailed = 0;
		int cornerTouches = 0;
		int bowAground = 0;
		boolean allArrive = true;
		double worstStretch = 0;
		String worstCase = "";
		for (int port : new int[]{0, 13, 31, 47})
		{
			SailingDocks.Dock to = docks.byRow(port);
			Set<String> lines = new HashSet<>();
			for (int golem = 0; golem < 5; golem++)
			{
				
				long started = System.nanoTime();
				Itinerary crossing = voyage.crossTo(home, to, home.getShore(), new TransportMemory(), 1000,
					new Random(golem * 31L + port), null);
				long planned = (System.nanoTime() - started) / 1_000_000;
				if (crossing == null)
				{
					System.out.println("  no crossing to dock " + port + " drawn");
					failures++;
					continue;
				}
				crossings++;
				int[] xs = (int[]) get(crossing, "xs");
				int[] ys = (int[]) get(crossing, "ys");
				int[] fx = (int[]) get(crossing, "fineXs");
				int[] fy = (int[]) get(crossing, "fineYs");
				int[] facing = (int[]) get(crossing, "facings");
				int still = 0;
				boolean rested = false;
				for (int i = 1; i < xs.length; i++)
				{
					if (!sea.clearLine(xs[i - 1], ys[i - 1], xs[i], ys[i]))
					{
						// The raft's own path, sampled finely, rather than tile middle to tile middle.
						boolean fineClear = true;
						for (int s = 0; s <= 16; s++)
						{
							int px = fx[i - 1] + (fx[i] - fx[i - 1]) * s / 16;
							int py = fy[i - 1] + (fy[i] - fy[i - 1]) * s / 16;
							fineClear &= mesh.isOcean(Math.floorDiv(px, 128), Math.floorDiv(py, 128), 0);
						}
						if (!fineClear)
						{
							allAtSea = false;
							System.out.printf("  aground: tick %d (%d,%d)->(%d,%d) fine (%d,%d)->(%d,%d)%n", i, xs[i - 1], ys[i - 1],
								xs[i], ys[i], fx[i - 1], fy[i - 1], fx[i], fy[i]);
						}
						else
						{
							cornerTouches++;
						}
					}
					int dx = fx[i] - fx[i - 1];
					int dy = fy[i] - fy[i - 1];
					still = dx == 0 && dy == 0 ? still + 1 : 0;
					rested |= still >= 8;
					worstTurn = Math.max(worstTurn, Math.abs(((facing[i] - facing[i - 1] + 1024) & 2047) - 1024));
					worstSpeed = Math.max(worstSpeed, Math.max(Math.abs(dx), Math.abs(dy)));
					if (i < xs.length - 1)
					{
						ticksSailed++;
						// Moving the way it faces: ahead, and not more than a compass point off the bow.
						double angle = (facing[i] - 1024) * Math.PI / 1024;
						double along = dx * Math.sin(angle) + dy * Math.cos(angle);
						if ((dx != 0 || dy != 0) && along < Math.hypot(dx, dy) * Math.cos(Math.PI / 8) - 1)
						{
							sideways++;
						}
					}
				}
				rests += rested ? 1 : 0;
				lines.add(xs.length + ":" + (xs.length > 2 ? xs[1] + "," + ys[1] + "," + xs[xs.length / 2] : ""));
				// In to within ten tiles of the far gangplank, or all the way to the water beside it.
				allArrive &= Math.max(Math.abs(xs[xs.length - 1] - to.getShore().getX()),
					Math.abs(ys[ys.length - 1] - to.getShore().getY())) <= 10
					|| xs[xs.length - 1] == to.getBerth().getX() && ys[ys.length - 1] == to.getBerth().getY();
				for (int i = 0; i < xs.length; i++)
				{
					// The raft's bow, about two and a half tiles ahead of the golem at its helm.
					double angle = (facing[i] - 1024) * Math.PI / 1024;
					int bowX = Math.floorDiv(fx[i] + (int) Math.round(Math.sin(angle) * 340), 128);
					int bowY = Math.floorDiv(fy[i] + (int) Math.round(Math.cos(angle) * 340), 128);
					bowAground += mesh.isOcean(bowX, bowY, 0) ? 0 : 1;
				}
				double sailed = 0;
				for (int i = 1; i < fx.length; i++)
				{
					sailed += Math.hypot(fx[i] - fx[i - 1], fy[i] - fy[i - 1]) / 128.0;
				}
				List<int[]> shortest = sea.route(home.getMooring(), to.getMooring());
				double direct = 0;
				for (int i = 1; i < shortest.size(); i++)
				{
					direct += Math.hypot(shortest.get(i)[0] - shortest.get(i - 1)[0], shortest.get(i)[1] - shortest.get(i - 1)[1]);
				}
				if (sailed / Math.max(1, direct) > worstStretch)
				{
					worstStretch = sailed / Math.max(1, direct);
					worstCase = String.format("dock %d golem %d: shortest %.0f, sailed %.0f, %d waypoints", port, golem, direct, sailed, xs.length);
				}
				if (golem == 1)
				{
					System.out.printf("  to dock %d: a later crossing, field already built, planned in %d ms%n", port, planned);
				}
				if (golem == 0)
				{
					System.out.printf("  to dock %d: shortest %.0f tiles, this golem sailed %.0f over %d waypoints%n",
						port, direct, sailed, xs.length);
					System.out.printf("  to dock %d: first crossing planned in %d ms (distance field built)%n", port, planned);
				}
				startsOnWater &= mesh.isOcean(xs[0], ys[0], 0) && xs[0] == home.getBerth().getX()
					&& ys[0] == home.getBerth().getY();
				endsAshore &= crossing.positionAt(1000 + crossing.getDuration()).equals(to.getShore());

				int[] last = crossing.fineAt(1000, 0f);
				for (int t = 0; t < crossing.getDuration() * 10; t++)
				{
					int[] now = crossing.fineAt(1000 + t / 10, (t % 10) / 10f);
					int jump = Math.max(Math.abs(now[0] - last[0]), Math.abs(now[1] - last[1]));
					worstJump = Math.max(worstJump, jump);
					last = now;
				}
			}
			System.out.printf("  to dock %d: %d different lines among 5 golems%n", port, lines.size());
			check("golems to dock " + port + " do not all take one line", lines.size() > 1);
		}
		// Full sail is 1.5 tiles a tick, 192 fine units: a tenth of a tick is at most 20 of them.
		allSmooth = worstJump <= 20;
		check("every tick's move stays on the open sea (" + cornerTouches
			+ " pass a headland's corner closer than tile middles would)", allAtSea);
		check("every crossing comes in within ten tiles of the far gangplank", allArrive);
		System.out.println("  longest by ratio: " + worstCase);
		check(String.format("no crossing much longer than the shortest route (worst %.2fx)", worstStretch), worstStretch < 2.5);
		check("crossings start on the water beside the gangplank", startsOnWater);
		check(String.format("the bow stays at sea (%d of %d ticks over land)", bowAground, ticksSailed),
			bowAground <= ticksSailed / 100);
		check("crossings end ashore at the far quayside", endsAshore);
		check("some crossings rest (" + rests + " rests in " + crossings + ")", rests > 0);
		check("position glides: largest move in a tenth of a tick " + worstJump + " fine units", allSmooth);
		check("turns at most a compass point a tick (worst " + worstTurn + ")", worstTurn <= 128);
		check("never faster than full sail (worst " + worstSpeed + " fine units a tick)", worstSpeed <= 192);
		check(String.format("moves the way it faces (%d of %d ticks otherwise)", sideways, ticksSailed),
			sideways <= ticksSailed / 50);
		caveCrossings(docks, voyage, mesh, home);

		System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
	}

	/**
	 * Crossings to and from Wyrmscraig's cave dock: across the lake, through the cave mouth in one
	 * tick, and across the sea, and the same the other way.
	 */
	private static void caveCrossings(SailingDocks docks, Voyage voyage, WorldMesh mesh, SailingDocks.Dock home)
		throws Exception
	{
		SailingDocks.Dock cave = null;
		for (SailingDocks.Dock dock : docks.getDocks())
		{
			if (!dock.isOnOpenSea())
			{
				cave = dock;
			}
		}
		check("the cave dock is found", cave != null);
		if (cave == null)
		{
			return;
		}
		int lake = mesh.componentAt(GolemContent.CAVE_LAKE_MOUTH_X, GolemContent.CAVE_LAKE_MOUTH_Y, 0);
		System.out.println("  cave dock: shore " + cave.getShore() + ", berth " + cave.getBerth()
			+ ", lake component " + lake + ", berth component " + mesh.componentAt(cave.getBerth().getX(), cave.getBerth().getY(), 0));

		SailingDocks.Dock far = docks.byRow(13);
		for (boolean outward : new boolean[]{true, false})
		{
			for (int seed = 0; seed < 3; seed++)
			{
				SailingDocks.Dock from = outward ? cave : (seed == 0 ? home : far);
				SailingDocks.Dock to = outward ? (seed == 0 ? home : far) : cave;
				String label = (outward ? "out of the cave to dock " : "into the cave from dock ") + (outward ? to.getRowId() : from.getRowId());
				Itinerary crossing = voyage.crossTo(from, to, from.getShore(), new TransportMemory(), 1000, new Random(seed), null);
				if (crossing == null || !crossing.isSailed())
				{
					check(label + ": sailed", false);
					continue;
				}
				int[] fx = (int[]) get(crossing, "fineXs");
				int[] fy = (int[]) get(crossing, "fineYs");
				int jumps = 0;
				boolean lakeLegOnLake = true;
				boolean seaLegAtSea = true;
				boolean passedMouth = false;
				boolean atMouth = true;
				int worstTurn = 0;
				int[] facing = (int[]) get(crossing, "facings");
				for (int i = 0; i < fx.length; i++)
				{
					int tx = Math.floorDiv(fx[i], 128);
					int ty = Math.floorDiv(fy[i], 128);
					if (i > 0 && (Math.abs(fx[i] - fx[i - 1]) > 16 * 128 || Math.abs(fy[i] - fy[i - 1]) > 16 * 128))
					{
						jumps++;
						passedMouth = true;
						// Through the mouth from beside it, to beside the other.
						int[] leftFrom = outward ? new int[]{GolemContent.CAVE_LAKE_MOUTH_X, GolemContent.CAVE_LAKE_MOUTH_Y}
							: new int[]{GolemContent.CAVE_SEA_MOUTH_X, GolemContent.CAVE_SEA_MOUTH_Y};
						int bx = Math.floorDiv(fx[i - 1], 128);
						int by = Math.floorDiv(fy[i - 1], 128);
						atMouth &= Math.max(Math.abs(bx - leftFrom[0]), Math.abs(by - leftFrom[1])) <= 3;
					}
					boolean onLakeSide = outward != passedMouth;
					if (onLakeSide)
					{
						lakeLegOnLake &= mesh.componentAt(tx, ty, 0) == lake;
					}
					else
					{
						seaLegAtSea &= mesh.isOcean(tx, ty, 0);
					}
					if (i > 0 && !(jumps > 0 && Math.abs(fx[i] - fx[i - 1]) > 16 * 128))
					{
						worstTurn = Math.max(worstTurn, Math.abs(((facing[i] - facing[i - 1] + 1024) & 2047) - 1024));
					}
				}
				int endX = Math.floorDiv(fx[fx.length - 1], 128);
				int endY = Math.floorDiv(fy[fy.length - 1], 128);
				boolean arrives = Math.max(Math.abs(endX - to.getShore().getX()), Math.abs(endY - to.getShore().getY())) <= 10;
				System.out.printf("  %s: %d ticks, %d jump(s), ends %d,%d%n", label, fx.length - 1, jumps, endX, endY);
				check(label + ": through the cave mouth once", jumps == 1);
				check(label + ": goes through from the mouth itself", atMouth);
				check(label + ": the lake leg stays on the lake", lakeLegOnLake);
				check(label + ": the sea leg stays at sea", seaLegAtSea);
				check(label + ": comes in within ten tiles of the far gangplank", arrives);
				check(label + ": turns at most a compass point a tick, apart from the mouth", worstTurn <= 128 || jumps > 0);
			}
		}
	}

	private static void check(String label, boolean ok)
	{
		failures += ok ? 0 : 1;
		System.out.println((ok ? "ok   " : "FAIL ") + label);
	}

	private static void set(Object target, String name, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static Object get(Object target, String name) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}
}
