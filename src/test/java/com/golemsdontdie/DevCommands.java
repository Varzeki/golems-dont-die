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
 * ::dance [seconds]     start a celebration now, whatever the settings say
 * ::levelup             as if a level went up, through the real path and its setting
 * ::collog              as if a collection log slot was filled
 * ::crafted             as if the golem count went up
 * ::bring [n]           teleport the nearest n golems to you (default 10)
 * ::traits              what the nearest golem is like, called, and has done
 * ::page                open the nearest golem's page
 * ::findme              point the arrow at the nearest golem
 * ::remove [n]          remove n golems, to leave some missing for the plinth to revive
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
	private GolemCelebration celebration;

	@Inject
	private GolemNames names;

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
			case "dance":
			case "levelup":
			case "collog":
			case "crafted":
			case "bring":
			case "traits":
			case "page":
			case "findme":
			case "remove":
			case "golemhelp":
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
		List<Golem> golems = roster(golemPlugin);
		int count = args.length > 0 ? number(args[0], 10) : 10;

		switch (command)
		{
			case "golemhelp":
				say("::golems  ::dance [s]  ::levelup  ::collog  ::crafted  ::bring [n]");
				say("::traits  ::page  ::findme  ::remove [n]");
				break;

			case "golems":
				say(golems.size() + " golems, " + named(golems) + " named, "
					+ inScene(golems) + " in the scene; the game says "
					+ client.getVarbitValue(GolemContent.GOLEM_COUNT_VARBIT) + " crafted.");
				break;

			case "dance":
			{
				// begin() adds its own ten seconds to whatever tick it is handed, so the tick it is
				// handed is worked back from the length wanted.
				int ticks = args.length > 0 ? number(args[0], 10) * 5 / 3 : 17;
				call(celebration, "begin", new Class<?>[]{int.class},
					new Object[]{client.getTickCount() + ticks - 17});
				say("Dancing for " + ticks + " ticks (" + (ticks * 3 / 5) + " seconds).");
				break;
			}

			case "levelup":
			{
				// Through the real path: seen once, then one higher, which is what a level-up is.
				Skill skill = Skill.MINING;
				int level = client.getRealSkillLevel(skill);
				celebration.statChanged(skill, level, client.getTickCount());
				celebration.statChanged(skill, level + 1, client.getTickCount());
				say("As if " + skill + " went from " + level + " to " + (level + 1) + ".");
				break;
			}

			case "collog":
				celebration.chatMessage("New item added to your collection log: Golem's heart",
					client.getTickCount());
				say("As if the collection log gained a slot.");
				break;

			case "crafted":
			{
				int crafted = client.getVarbitValue(GolemContent.GOLEM_COUNT_VARBIT);
				celebration.golemCount(crafted, client.getTickCount());
				celebration.golemCount(crafted + 1, client.getTickCount());
				say("As if golem " + (crafted + 1) + " was crafted.");
				break;
			}

			case "bring":
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

			case "traits":
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

			case "page":
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

			case "findme":
			{
				Golem golem = nearestOne(golems);
				if (golem != null)
				{
					call(golemPlugin, "findGolem", new Class<?>[]{Golem.class}, new Object[]{golem});
					say("Pointing at " + (golem.getNickname() == null ? "a golem" : golem.getNickname()) + ".");
				}
				break;
			}

			case "remove":
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
