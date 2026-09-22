package com.golemsdontdie;

import com.google.inject.Provider;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;

/**
 * Chat commands for testing the things that are hard to make happen on purpose: a level-up, a
 * collection log slot, a crowd of golems where you are standing.
 *
 * <p>Dev client only — this lives in the test source set and is never in the plugin jar. It reaches
 * into the plugin by reflection rather than by widening anything: a test harness should not leave
 * marks on what it tests.
 *
 * <pre>
 * ::golems              what the roster looks like
 * ::gdance [seconds]    start a celebration now, whatever the settings say
 * ::gguitar             put the nearest golems on the air guitar
 * ::glevel              as if a level went up, through the real path and its setting
 * ::gcollog             as if a collection log slot was filled
 * ::gcrafted            as if the golem count went up
 * ::gbring [n]          teleport the nearest n golems to you (default 10)
 * ::gtraits             what the nearest golem is like, called, and has done
 * ::gpage               open the nearest golem's page
 * ::gfind               point the arrow at the nearest golem
 * ::gremove [n]         remove n golems, to leave some missing for the plinth to revive
 * ::gmap                what the world map is looking at, with the map open
 * ::gwhere [name]       where a golem is, and whether the ground under it is walkable
 *
 * <p>All of them wear the g: ::dance belongs to somebody else's plugin, and the golems stood
 * there while the player danced.
 * </pre>
 */
@PluginDescriptor(name = "Golem Dev Commands (dev)", description = "Chat commands for testing golems",
	enabledByDefault = true)
public class DevCommands extends Plugin
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DevCommands.class);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private Provider<PluginManager> plugins;

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		String command = event.getCommand().toLowerCase();
		String[] args = event.getArguments();
		switch (command)
		{
			case "golems":
			case "gdance":
			case "gguitar":
			case "glevel":
			case "gcollog":
			case "gcrafted":
			case "gbring":
			case "gtraits":
			case "gpage":
			case "gfind":
			case "gremove":
			case "gmap":
			case "gwhere":
			case "gpath":
			case "gcutoff":
			case "ghelp":
				clientThread.invoke(() -> run(command, args));
				break;
			default:
				break;
		}
	}

	private void run(String command, String[] args)
	{
		GolemsDontDiePlugin golemPlugin = plugin();
		if (golemPlugin == null)
		{
			say("The golem plugin is not running.");
			return;
		}
		// Taken from the plugin rather than injected: every plugin gets an injector of its own, so
		// an injected copy would be a second GolemCelebration that nothing reads. It would also not
		// build at all — the config is only bound in the golem plugin's own injector.
		GolemCelebration celebration = (GolemCelebration) field(golemPlugin, "celebration");
		GolemNames names = (GolemNames) field(golemPlugin, "names");
		if (celebration == null || names == null)
		{
			say("Could not reach into the plugin.");
			return;
		}

		List<Golem> golems = roster(golemPlugin);
		int count = args.length > 0 ? number(args[0], 10) : 10;

		switch (command)
		{
			case "gmap":
			{
				// Which coordinates the map is drawing, so a dungeon view can be told from the
				// surface one: golems are put on the map by world coordinates, and a view working
				// in some other space is why they would not be on it.
				net.runelite.api.worldmap.WorldMap map = client.getWorldMap();
				if (map == null)
				{
					say("The map is not open.");
					break;
				}
				net.runelite.api.Point centre = map.getWorldMapPosition();
				Object points = field(golemPlugin, "mapPoints");
				Object under = points == null ? null : field(points, "underground");
				Object faces = points == null ? null : field(points, "used");
				say("map at " + (centre == null ? "?" : centre.getX() + "," + centre.getY())
					+ ", zoom " + map.getWorldMapZoom()
					+ "; the plugin says underground=" + under + ", faces=" + faces);
				say("the plugin measured the map's offset as " + field(points, "offsetX") + ","
					+ field(points, "offsetY") + " (measured=" + field(points, "measured") + ")");

				// What the client will and will not draw, which is the question behind every
				// "there are golems there and the map is empty".
				net.runelite.api.worldmap.WorldMapData data = map.getWorldMapData();
				Widget window = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
				int minX = 0;
				int maxX = 0;
				int minY = 0;
				int maxY = 0;
				if (window != null && centre != null)
				{
					float zoom = map.getWorldMapZoom();
					int halfWidth = (int) Math.ceil(window.getBounds().getWidth() / zoom / 2);
					int halfHeight = (int) Math.ceil(window.getBounds().getHeight() / zoom / 2);
					minX = centre.getX() - halfWidth;
					maxX = centre.getX() + halfWidth;
					minY = centre.getY() - halfHeight;
					maxY = centre.getY() + halfHeight;
					say("the map widget is " + (int) window.getBounds().getWidth() + "x"
						+ (int) window.getBounds().getHeight() + "px, showing x " + minX + ".." + maxX
						+ ", y " + minY + ".." + maxY);
					say("the map " + (data == null ? "has no data"
						: data.surfaceContainsPosition(centre.getX(), centre.getY())
							? "holds its own centre" : "does NOT hold its own centre"));
				}

				// A dungeon is drawn on the map in a space of its own, nothing like the coordinates
				// the dungeon really has. If that is so, this is the offset between them: the same
				// player, counted twice.
				Player me = client.getLocalPlayer();
				if (me != null && centre != null)
				{
					WorldPoint real = me.getWorldLocation();
					say("the player is really at " + real.getX() + "," + real.getY() + " plane "
						+ real.getPlane() + "; the map calls that " + centre.getX() + "," + centre.getY()
						+ " (offset " + (centre.getX() - real.getX()) + "," + (centre.getY() - real.getY())
						+ ")");
					say("the map " + (data == null || !data.surfaceContainsPosition(real.getX(), real.getY())
						? "does NOT hold" : "holds") + " the player's real tile.");

					// An instanced cave stands somewhere other than the map it was built from, and
					// the map draws the map. If that is what the offset is, this says so.
					net.runelite.api.WorldView wv = client.getTopLevelWorldView();
					boolean instance = wv != null && wv.isInstance();
					say("the player is " + (instance ? "in an instance" : "not in an instance")
						+ (instance ? ", built from " + WorldPoint.fromLocalInstance(client,
							me.getLocalLocation()) : ""));
				}

				// The map's own regions. Each one is somewhere on the map, and the icons inside it
				// carry the coordinates the region really has, which between them say where a real
				// tile is drawn on a map that does not use real coordinates.
				net.runelite.api.worldmap.WorldMapRenderer renderer = map.getWorldMapRenderer();
				if (renderer != null && renderer.isLoaded())
				{
					net.runelite.api.worldmap.WorldMapRegion[][] grid = renderer.getMapRegions();
					say("the map holds " + grid.length + "x" + (grid.length == 0 ? 0 : grid[0].length)
						+ " regions");
					int shown = 0;
					for (int i = 0; i < grid.length && shown < 8; i++)
					{
						for (int j = 0; j < grid[i].length && shown < 8; j++)
						{
							if (grid[i][j] == null || grid[i][j].getMapIcons() == null
								|| grid[i][j].getMapIcons().isEmpty())
							{
								continue;
							}
							WorldPoint icon = grid[i][j].getMapIcons().iterator().next().getCoordinate();
							say("  cell [" + i + "][" + j + "] has an icon really at "
								+ icon.getX() + "," + icon.getY() + " plane " + icon.getPlane());
							shown++;
						}
					}
				}

				int offX = points == null ? 0 : (int) field(points, "offsetX");
				int offY = points == null ? 0 : (int) field(points, "offsetY");
				int below = 0;
				int onMap = 0;
				int inView = 0;
				Golem deep = null;
				for (Golem one : golems)
				{
					WorldPoint at = one.currentTile();
					below += at.getY() >= 6400 ? 1 : 0;
					if (at.getY() >= 6400 && deep == null)
					{
						deep = one;
					}
					if (data == null)
					{
						continue;
					}
					int x = at.getX();
					int y = at.getY();
					if (!data.surfaceContainsPosition(x, y))
					{
						if ((offX != 0 || offY != 0) && data.surfaceContainsPosition(x + offX, y + offY))
						{
							x += offX;
							y += offY;
						}
						else if (y >= 6400 && data.surfaceContainsPosition(x, y - 6400))
						{
							y -= 6400;
						}
						else
						{
							continue;
						}
					}
					onMap++;
					inView += x >= minX && x <= maxX && y >= minY && y <= maxY ? 1 : 0;
				}
				say(below + " golems underground; " + onMap + " belong on this map, " + inView
					+ " of those are in view.");

				if (deep != null && data != null)
				{
					WorldPoint at = deep.currentTile();
					say("one underground at " + at.getX() + "," + at.getY() + ": the map "
						+ (data.surfaceContainsPosition(at.getX(), at.getY()) ? "holds" : "does not hold")
						+ " it there, and " + (data.surfaceContainsPosition(at.getX(), at.getY() - 6400)
							? "holds" : "does not hold") + " it folded to the surface.");
				}
				Golem golem = nearestOne(golems);
				if (golem != null)
				{
					WorldPoint at = golem.currentTile();
					say("nearest golem at " + at.getX() + "," + at.getY() + " plane " + at.getPlane());
				}
				break;
			}

			case "gwhere":
			{
				String wanted = args.length > 0 ? String.join(" ", args).toLowerCase() : "";
				int said = 0;
				for (Golem one : golems)
				{
					String called = one.getNickname() == null ? names.nameFor(one.getId()) : one.getNickname();
					if (wanted.isEmpty() || called != null && called.toLowerCase().contains(wanted))
					{
						WorldPoint at = one.currentTile();
						say(called + " at " + at.getX() + "," + at.getY() + " plane " + at.getPlane()
							+ ", " + (one.isSailing(client.getTickCount()) ? "sailing" : "ashore"));
						// How it came to be there, for a golem found somewhere a player cannot walk.
						Object history = field(one, "history");
						say("  " + field(history, "voyages") + " voyages, "
							+ field(history, "transports") + " shortcuts, "
							+ field(history, "walked") + " tiles walked");
						Object mesh = field(golemPlugin, "worldMesh");
						if (mesh != null)
						{
							say("  cut off from home: " + answer(mesh, "isCutOff",
								new Class<?>[]{int.class, int.class, int.class},
								new Object[]{at.getX(), at.getY(), at.getPlane()}));
						}
						if (++said >= 5)
						{
							break;
						}
					}
				}
				if (said == 0)
				{
					say("No golem called that.");
				}
				break;
			}

			case "gcutoff":
			{
				// What the plugin makes of the world right now: how many golems stand on ground
				// that does not join up with home, before any of them are moved for it.
				Object mesh = field(golemPlugin, "worldMesh");
				if (mesh == null)
				{
					say("No mesh.");
					break;
				}
				int cut = 0;
				int said = 0;
				for (Golem one : golems)
				{
					WorldPoint at = one.currentTile();
					if (!Boolean.TRUE.equals(answer(mesh, "isCutOff",
						new Class<?>[]{int.class, int.class, int.class},
						new Object[]{at.getX(), at.getY(), at.getPlane()})))
					{
						continue;
					}
					cut++;
					if (said++ < 5)
					{
						say("  cut off at " + at.getX() + "," + at.getY() + " plane " + at.getPlane());
					}
				}
				say(cut + " of " + golems.size() + " golems are cut off from home; the sweep's own"
					+ " share is " + field(golemPlugin, "cutOffShare")
					+ " (moves nobody above " + field(golemPlugin, "MOST_CUT_OFF") + ")");
				break;
			}

			case "gpath":
			{
				// Whether a golem could have walked from the plinth to a tile, over the map this
				// client has actually harvested rather than the one that shipped.
				if (args.length < 2)
				{
					say("::gpath x y");
					break;
				}
				int goalX = number(args[0], 0);
				int goalY = number(args[1], 0);
				Object memory = field(golemPlugin, "islandMemory");
				if (memory == null)
				{
					say("No island map.");
					break;
				}
				say(reachable(memory, goalX, goalY) + " from the plinth to " + goalX + "," + goalY);
				break;
			}

			case "ghelp":
				say("::golems  ::gdance [s]  ::gguitar  ::glevel  ::gcollog  ::gcrafted");
				say("::gbring [n]  ::gtraits  ::gpage  ::gfind  ::gremove [n]  ::gmap  ::gwhere [name]");
				say("::gpath x y  ::gcutoff");
				break;

			case "golems":
			{
				int tick = client.getTickCount();
				int sailing = 0;
				StringBuilder where = new StringBuilder();
				for (Golem golem : golems)
				{
					if (golem.isSailing(tick))
					{
						sailing++;
						WorldPoint at = golem.seaPosition(tick);
						if (sailing <= 3)
						{
							where.append(at == null ? " (nowhere?)"
								: " " + at.getX() + "," + at.getY() + " bound for "
								+ golem.saveTile().getX() + "," + golem.saveTile().getY());
						}
					}
				}
				say(golems.size() + " golems, " + named(golems) + " named, "
					+ inScene(golems) + " in the scene, " + sailing + " at sea; the game says "
					+ client.getVarbitValue(GolemContent.GOLEM_COUNT_VARBIT) + " crafted.");
				if (sailing > 0)
				{
					say("at sea:" + where);
				}
				break;
			}

			case "gguitar":
			{
				// The air guitar is one move in twelve, so it can go a whole celebration unseen.
				// This puts the golems nearby on it, and the guitar should appear in their hands.
				int given = 0;
				for (Golem golem : nearest(golems, where(), count))
				{
					set(golem, "danceMove", GolemDance.AIR_GUITAR);
					set(golem, "propPending", Boolean.TRUE);
					given++;
				}
				call(celebration, "begin", new Class<?>[]{int.class},
					new Object[]{client.getTickCount()});
				say(given + " golems on the air guitar.");
				break;
			}

			case "gdance":
			{
				// begin() adds its own ten seconds to whatever tick it is handed, so the tick it is
				// handed is worked back from the length wanted.
				int ticks = args.length > 0 ? number(args[0], 10) * 5 / 3 : 17;
				call(celebration, "begin", new Class<?>[]{int.class},
					new Object[]{client.getTickCount() + ticks - 17});
				say("Dancing for " + ticks + " ticks (" + (ticks * 3 / 5) + " seconds).");
				break;
			}

			case "glevel":
			{
				// Through the real path: seen once, then one higher, which is what a level-up is.
				Skill skill = Skill.MINING;
				int level = client.getRealSkillLevel(skill);
				celebration.statChanged(skill, level, client.getTickCount());
				celebration.statChanged(skill, level + 1, client.getTickCount());
				say("As if " + skill + " went from " + level + " to " + (level + 1) + ".");
				break;
			}

			case "gcollog":
				celebration.chatMessage("New item added to your collection log: Golem's heart",
					client.getTickCount());
				say("As if the collection log gained a slot.");
				break;

			case "gcrafted":
			{
				int crafted = client.getVarbitValue(GolemContent.GOLEM_COUNT_VARBIT);
				celebration.golemCount(crafted, client.getTickCount());
				celebration.golemCount(crafted + 1, client.getTickCount());
				say("As if golem " + (crafted + 1) + " was crafted.");
				break;
			}

			case "gbring":
			{
				Player player = client.getLocalPlayer();
				if (player == null)
				{
					return;
				}
				WorldPoint at = player.getWorldLocation();
				int moved = 0;
				for (Golem golem : nearest(golems, at, count))
				{
					golem.relocate(new WorldPoint(at.getX() + (moved % 5) - 2,
						at.getY() + (moved / 5) - 2, at.getPlane()));
					moved++;
				}
				say("Brought " + moved + " golems to you.");
				break;
			}

			case "gtraits":
			{
				Golem golem = nearestOne(golems);
				if (golem == null)
				{
					say("No golems.");
					return;
				}
				GolemHistory history = golem.getHistory();
				say((golem.getNickname() == null ? "Unnamed" : golem.getNickname())
					+ " (" + names.nameFor(golem.getId()) + "): " + GolemTrait.list(golem.getTraits()));
				say("Walked " + history.getWalked() + " tiles, " + history.getTransports()
					+ " shortcuts, " + history.getVoyages() + " voyages, furthest "
					+ history.getFurthest() + " tiles from home.");
				break;
			}

			case "gpage":
			{
				Golem golem = nearestOne(golems);
				if (golem != null)
				{
					Object page = field(golemPlugin, "page");
					if (page instanceof GolemPage)
					{
						GolemPage open = (GolemPage) page;
						javax.swing.SwingUtilities.invokeLater(() -> open.show(golem, null));
					}
				}
				break;
			}

			case "gfind":
			{
				Golem golem = nearestOne(golems);
				if (golem != null)
				{
					call(golemPlugin, "findGolem", new Class<?>[]{Golem.class}, new Object[]{golem});
					say("Pointing at " + (golem.getNickname() == null ? "a golem" : golem.getNickname()) + ".");
				}
				break;
			}

			case "gremove":
			{
				int removed = 0;
				for (Golem golem : new ArrayList<>(golems))
				{
					if (removed >= count)
					{
						break;
					}
					if (golem.getNickname() == null)
					{
						call(golemPlugin, "removeGolem", new Class<?>[]{Golem.class}, new Object[]{golem});
						removed++;
					}
				}
				say("Removed " + removed + " golems. Right-click a plinth to revive them.");
				break;
			}

			default:
				break;
		}
	}

	// ---------------------------------------------------------------- reaching in

	private GolemsDontDiePlugin plugin()
	{
		for (Plugin loaded : plugins.get().getPlugins())
		{
			if (loaded instanceof GolemsDontDiePlugin)
			{
				return (GolemsDontDiePlugin) loaded;
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private List<Golem> roster(GolemsDontDiePlugin golemPlugin)
	{
		Object golems = field(golemPlugin, "golems");
		return golems instanceof List ? new ArrayList<>((List<Golem>) golems) : new ArrayList<>();
	}

	/** Up the hierarchy, because an injected singleton may be a subclass of the class it names. */
	/**
	 * Walks the live island map out from the plinth, the way a golem does, and says whether it
	 * arrives. The shipped map can be read offline; this is the one this client has learned.
	 */
	private String reachable(Object memory, int goalX, int goalY)
	{
		final int[] dx = {1, -1, 0, 0, 1, -1, 1, -1};
		final int[] dy = {0, 0, 1, -1, 1, 1, -1, -1};
		java.util.Set<Long> seen = new java.util.HashSet<>();
		java.util.Deque<int[]> queue = new java.util.ArrayDeque<>();
		int startX = 2596;
		int startY = 2256;
		seen.add((long) startX << 20 | startY);
		queue.add(new int[]{startX, startY});

		while (!queue.isEmpty() && seen.size() < 40000)
		{
			int[] at = queue.poll();
			for (int d = 0; d < dx.length; d++)
			{
				int nx = at[0] + dx[d];
				int ny = at[1] + dy[d];
				if (nx < 2400 || nx > 2800 || ny < 2100 || ny > 2400
					|| !seen.add((long) nx << 20 | ny))
				{
					continue;
				}
				if (!walkable(memory, nx, ny) || !steppable(memory, at[0], at[1], dx[d], dy[d]))
				{
					continue;
				}
				if (nx == goalX && ny == goalY)
				{
					return "the golems' own map walks there, " + seen.size() + " tiles searched:";
				}
				queue.add(new int[]{nx, ny});
			}
		}
		return "no walk over the golems' own map reaches it, " + seen.size() + " tiles searched:";
	}

	private static boolean walkable(Object memory, int x, int y)
	{
		Object out = answer(memory, "isKnownWalkable", new Class<?>[]{int.class, int.class, int.class},
			new Object[]{x, y, 0});
		return Boolean.TRUE.equals(out);
	}

	private static boolean steppable(Object memory, int x, int y, int dx, int dy)
	{
		Object out = answer(memory, "canStep",
			new Class<?>[]{int.class, int.class, int.class, int.class, int.class},
			new Object[]{x, y, 0, dx, dy});
		return Boolean.TRUE.equals(out);
	}

	/** As call, but handing back what the method answered. */
	private static Object answer(Object target, String name, Class<?>[] types, Object[] args)
	{
		for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass())
		{
			try
			{
				Method method = type.getDeclaredMethod(name, types);
				method.setAccessible(true);
				return method.invoke(target, args);
			}
			catch (NoSuchMethodException ignored)
			{
				// Not on this one; try the class it came from.
			}
			catch (ReflectiveOperationException e)
			{
				return null;
			}
		}
		return null;
	}

	private static Object field(Object target, String name)
	{
		for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass())
		{
			try
			{
				Field field = type.getDeclaredField(name);
				field.setAccessible(true);
				return field.get(target);
			}
			catch (ReflectiveOperationException ignored)
			{
				// Not on this one; try the class it came from.
			}
		}
		return null;
	}

	private static void call(Object target, String name, Class<?>[] types, Object[] args)
	{
		for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass())
		{
			try
			{
				Method method = type.getDeclaredMethod(name, types);
				method.setAccessible(true);
				method.invoke(target, args);
				return;
			}
			catch (NoSuchMethodException ignored)
			{
				// Not on this one; try the class it came from.
			}
			catch (ReflectiveOperationException e)
			{
				e.printStackTrace();
				return;
			}
		}
	}

	// ---------------------------------------------------------------- odds and ends

	private List<Golem> nearest(List<Golem> golems, WorldPoint at, int count)
	{
		List<Golem> sorted = new ArrayList<>(golems);
		sorted.sort((one, other) -> Integer.compare(
			one.currentTile().distanceTo2D(at), other.currentTile().distanceTo2D(at)));
		return sorted.subList(0, Math.min(count, sorted.size()));
	}

	/** Where the player is, or the plinth if there is no player yet. */
	private WorldPoint where()
	{
		Player player = client.getLocalPlayer();
		return player != null ? player.getWorldLocation()
			: new WorldPoint(GolemContent.PLINTH_X, GolemContent.PLINTH_Y, 0);
	}

	private static void set(Object target, String name, Object value)
	{
		for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass())
		{
			try
			{
				Field field = type.getDeclaredField(name);
				field.setAccessible(true);
				field.set(target, value);
				return;
			}
			catch (ReflectiveOperationException ignored)
			{
				// Not on this one; try the class it came from.
			}
		}
	}

	private Golem nearestOne(List<Golem> golems)
	{
		Player player = client.getLocalPlayer();
		if (player == null || golems.isEmpty())
		{
			return null;
		}
		List<Golem> near = nearest(golems, player.getWorldLocation(), 1);
		return near.isEmpty() ? null : near.get(0);
	}

	private static int named(List<Golem> golems)
	{
		int named = 0;
		for (Golem golem : golems)
		{
			named += golem.getNickname() != null ? 1 : 0;
		}
		return named;
	}

	private static int inScene(List<Golem> golems)
	{
		int inScene = 0;
		for (Golem golem : golems)
		{
			inScene += golem.getRenderer() != null ? 1 : 0;
		}
		return inScene;
	}

	private static int number(String text, int fallback)
	{
		try
		{
			return Math.max(1, Integer.parseInt(text.trim()));
		}
		catch (NumberFormatException e)
		{
			return fallback;
		}
	}

	/**
	 * Says something in the chat box, and in the client log with it: a run of ::gmap read back
	 * over a screenshot is a slow way to answer a question about coordinates.
	 */
	private void say(String text)
	{
		client.addChatMessage(ChatMessageType.CONSOLE, "Golems", text, null);
		log.info("{}", text);
	}
}
