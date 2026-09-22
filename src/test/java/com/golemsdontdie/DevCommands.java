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
 *
 * <p>All of them wear the g: ::dance belongs to somebody else's plugin, and the golems stood
 * there while the player danced.
 * </pre>
 */
@PluginDescriptor(name = "Golem Dev Commands (dev)", description = "Chat commands for testing golems",
	enabledByDefault = true)
public class DevCommands extends Plugin
{
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
				say("map at " + (centre == null ? "?" : centre.getX() + "," + centre.getY())
					+ ", zoom " + map.getWorldMapZoom());
				int under = 0;
				int named = 0;
				for (Golem one : golems)
				{
					if (one.currentTile().getY() >= 6400)
					{
						under++;
						named += one.getNickname() != null ? 1 : 0;
					}
				}
				say(under + " golems underground, " + named + " of them named.");
				Golem golem = nearestOne(golems);
				if (golem != null)
				{
					WorldPoint at = golem.currentTile();
					say("nearest golem at " + at.getX() + "," + at.getY() + " plane " + at.getPlane());
				}
				break;
			}

			case "ghelp":
				say("::golems  ::gdance [s]  ::gguitar  ::glevel  ::gcollog  ::gcrafted");
				say("::gbring [n]  ::gtraits  ::gpage  ::gfind  ::gremove [n]  ::gmap");
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

	private void say(String text)
	{
		client.addChatMessage(ChatMessageType.CONSOLE, "Golems", text, null);
	}
}
