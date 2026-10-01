package com.golemsdontdie;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.*;
import net.runelite.client.callback.*;
import net.runelite.client.game.chatbox.*;

/**
 * Gives the copies the right-click menu the real golems have.
 *
 * <p>A {@link net.runelite.api.RuneLiteObjectController} is drawn by the client but is not
 * an entity it builds a menu from, so hovering a copy would otherwise produce nothing. The
 * hit test is done by hand each tick, as the game picks a model (FakeGolem.isUnder). Entries use
 * {@link MenuAction#RUNELITE} with their own handlers, so nothing reaches the server.
 */
@Singleton
class GolemMenu
{
	/** Target colour the client uses for an NPC with no combat level. */
	private static final String NPC_COLOUR = "<col=ffff00>";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	@Inject
	private GolemNames names;

	/** Told when a golem is named here, so the roster is saved and the sidebar shows it. */
	private Consumer<Golem> onRenamed = golem ->
	{
	};

	void setOnRenamed(Consumer<Golem> onRenamed)
	{
		this.onRenamed = onRenamed;
	}

	/** Opens a golem's page. Set by the plugin, which owns the window. */
	private Consumer<Golem> onInfo = golem ->
	{
	};

	void setOnInfo(Consumer<Golem> onInfo)
	{
		this.onInfo = onInfo;
	}

	/**
	 * Adds entries for every golem under the cursor, as the game lists every NPC under it: nearest
	 * the camera highest. Called once per client tick, after the client has built its menu.
	 */
	void addEntries(List<Golem> golems)
	{
		if (client.isMenuOpen() || golems.isEmpty())
		{
			return;
		}

		Point mouse = client.getMouseCanvasPosition();
		if (mouse == null || mouse.getX() < 0 || mouse.getY() < 0)
		{
			return;
		}

		// Over the scene, by the client's own word: it offers entries for the scene only there - Walk
		// here, Set heading at sea, or something in it. Over an interface, the chatbox or the minimap,
		// the game picks nothing in the scene behind, and nor do golems.
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		boolean overScene = false;
		for (MenuEntry entry : entries)
		{
			overScene |= SCENE_ENTRIES.contains(entry.getType());
		}
		if (!overScene)
		{
			return;
		}

		int room = MENU_LIMIT - entries.length;
		boolean shift = client.isKeyPressed(KeyCode.KC_SHIFT);
		for (Golem hovered : golemsAt(golems, mouse))
		{
			if (room < (shift ? 3 : 1))
			{
				break;
			}
			room -= shift ? 3 : 1;
			addEntries(hovered, shift);
		}
	}

	/** The entries the client offers only with the mouse over the scene. */
	private static final Set<MenuAction> SCENE_ENTRIES = EnumSet.of(MenuAction.WALK, MenuAction.SET_HEADING,
		MenuAction.GAME_OBJECT_FIRST_OPTION, MenuAction.GAME_OBJECT_SECOND_OPTION, MenuAction.GAME_OBJECT_THIRD_OPTION,
		MenuAction.GAME_OBJECT_FOURTH_OPTION, MenuAction.GAME_OBJECT_FIFTH_OPTION,
		MenuAction.WIDGET_TARGET_ON_GAME_OBJECT, MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_SECOND_OPTION,
		MenuAction.NPC_THIRD_OPTION, MenuAction.NPC_FOURTH_OPTION, MenuAction.NPC_FIFTH_OPTION,
		MenuAction.WIDGET_TARGET_ON_NPC, MenuAction.PLAYER_FIRST_OPTION, MenuAction.PLAYER_SECOND_OPTION,
		MenuAction.PLAYER_THIRD_OPTION, MenuAction.PLAYER_FOURTH_OPTION, MenuAction.PLAYER_FIFTH_OPTION,
		MenuAction.PLAYER_SIXTH_OPTION, MenuAction.PLAYER_SEVENTH_OPTION, MenuAction.PLAYER_EIGHTH_OPTION,
		MenuAction.WIDGET_TARGET_ON_PLAYER, MenuAction.GROUND_ITEM_FIRST_OPTION,
		MenuAction.GROUND_ITEM_SECOND_OPTION, MenuAction.GROUND_ITEM_THIRD_OPTION, MenuAction.GROUND_ITEM_FOURTH_OPTION,
		MenuAction.GROUND_ITEM_FIFTH_OPTION, MenuAction.WIDGET_TARGET_ON_GROUND_ITEM,
		MenuAction.WORLD_ENTITY_FIRST_OPTION, MenuAction.WORLD_ENTITY_SECOND_OPTION, MenuAction.WORLD_ENTITY_THIRD_OPTION,
		MenuAction.WORLD_ENTITY_FOURTH_OPTION, MenuAction.WORLD_ENTITY_FIFTH_OPTION, MenuAction.EXAMINE_OBJECT,
		MenuAction.EXAMINE_NPC, MenuAction.EXAMINE_ITEM_GROUND, MenuAction.EXAMINE_WORLD_ENTITY);

	/** The most entries the game's menu holds. */
	private static final int MENU_LIMIT = 500;

	/**
	 * One golem's entries, just above Cancel and below any golem nearer the camera: Examine, and with
	 * shift held, as NPC Indicators offers its tag, naming and its page.
	 */
	private void addEntries(Golem hovered, boolean shift)
	{
		// Shown by its name, typed or given by auto naming. Every real golem is called "Golem", so
		// this costs some of the disguise; with auto naming off, only a typed name shows.
		String name = names.of(hovered);
		if (name == null || name.isEmpty())
		{
			name = hovered.getSnapshot().getName();
		}
		String target = NPC_COLOUR + name + "</col>";

		// Index 1, immediately above Cancel, which the client keeps at index 0. The menu array is
		// drawn bottom-up, so each entry put here goes beneath the ones before it.
		client.getMenu().createMenuEntry(1)
			.setOption("Examine")
			.setTarget(target)
			.setType(MenuAction.RUNELITE)
			.onClick(e -> message(GolemContent.GOLEM_EXAMINE));
		if (shift)
		{
			client.getMenu().createMenuEntry(1)
				.setOption("Set Name")
				.setTarget(target)
				.setType(MenuAction.RUNELITE)
				.onClick(e -> askForName(hovered));

			client.getMenu().createMenuEntry(1)
				.setOption("Show Info")
				.setTarget(target)
				.setType(MenuAction.RUNELITE)
				.onClick(e -> onInfo.accept(hovered));
		}
	}

	/**
	 * Adds the plinth's revive entry, above Cancel as the golems' own entries are.
	 *
	 * <p>Shown on a golem plinth while golems are missing and not otherwise, which is why it needs
	 * no shift to reach. The count is in the option, so a player knows what they are asking for
	 * before they ask.
	 */
	void addReviveEntry(int missing, String target, Runnable revive)
	{
		client.getMenu().createMenuEntry(1)
			.setOption("Revive missing golems (" + missing + ")")
			.setTarget(target == null ? "" : target)
			.setType(MenuAction.RUNELITE)
			.onClick(e -> revive.run());
	}

	/** Opens the chatbox prompt for a golem's name. Left empty, it goes back to its auto name, or "Golem". */
	private void askForName(Golem golem)
	{
		String current = golem.getNickname();
		chatboxPanelManager.openTextInput("Name this golem")
			.value(current == null ? "" : current)
			// Enter arrives on the AWT thread; golems are only touched on the client's.
			.onDone((String text) -> clientThread.invoke(() ->
			{
				String name = text.trim();
				golem.setNickname(name.isEmpty() ? null : name);
				onRenamed.accept(golem);
			}))
			.build();
	}

	/** Lower on screen first - that is nearer the camera, the camera never rolling. */
	private static final Comparator<Candidate> BY_DEPTH =
		(a, b) -> Integer.compare(b.screenY, a.screenY);

	/** Reused between ticks so hovering the scene does not allocate every frame. */
	private final List<Candidate> candidates = new ArrayList<>();
	private final List<Golem> under = new ArrayList<>();

	/** Every golem drawn under the mouse, nearest the camera first. See FakeGolem.isUnder. */
	private List<Golem> golemsAt(List<Golem> golems, Point mouse)
	{
		under.clear();
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return under;
		}
		candidates.clear();
		for (Golem golem : golems)
		{
			FakeGolem renderer = golem.getRenderer();
			if (renderer == null || !renderer.isUnder(mouse.getX(), mouse.getY()))
			{
				continue;
			}
			// In the world the golem is drawn in, which aboard the player's ship is the ship's.
			LocalPoint drawnAt = golem.isAboard() ? golem.drawnPoint(client)
				: new LocalPoint(renderer.getX(), renderer.getY(), wv);
			Point ground = drawnAt == null ? null
				: Perspective.localToCanvas(client, drawnAt, golem.isAboard() ? golem.drawnLevel() : golem.getPlane());
			candidates.add(new Candidate(golem, ground == null ? Integer.MIN_VALUE : ground.getY()));
		}
		candidates.sort(BY_DEPTH);
		for (Candidate candidate : candidates)
		{
			under.add(candidate.golem);
		}
		return under;
	}

	/** A golem under the cursor, with how far down the screen it stands. */
	private static final class Candidate
	{
		final Golem golem;
		final int screenY;

		Candidate(Golem golem, int screenY)
		{
			this.golem = golem;
			this.screenY = screenY;
		}
	}

	/** Sent as the game sends a real NPC's examine, so chat filters and colours treat it as one. */
	private void message(String text)
	{
		client.addChatMessage(ChatMessageType.NPC_EXAMINE, "", text, null);
	}
}
