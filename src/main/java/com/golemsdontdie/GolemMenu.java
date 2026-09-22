package com.golemsdontdie;

import java.awt.Shape;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.chatbox.ChatboxPanelManager;

/**
 * Gives the copies the right-click menu the real golems have.
 *
 * <p>A {@link net.runelite.api.RuneLiteObjectController} is drawn by the client but is not
 * an entity it builds a menu from, so hovering a copy would otherwise produce nothing. The
 * hit test is done by hand each tick, against the posed model's clickbox. Entries use
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

	/**
	 * Adds entries for whichever golem is under the cursor. Called once per client tick,
	 * when the client rebuilds its menu. Only the topmost golem gets entries.
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

		Golem hovered = topmostAt(golems, mouse);
		if (hovered == null)
		{
			return;
		}

		// Shown by its name. Every real golem is called "Golem", so this costs some of the
		// disguise, but a name is only there because the player put it there.
		String name = names.of(hovered);
		if (name == null || name.isEmpty())
		{
			name = hovered.getSnapshot().getName();
		}
		String target = NPC_COLOUR + name + "</col>";

		// Index 1, immediately above Cancel, which the client keeps at index 0. The menu
		// array is drawn bottom-up: inserting at 0 put Examine *below* Cancel.
		client.getMenu().createMenuEntry(1)
			.setOption("Examine")
			.setTarget(target)
			.setType(MenuAction.RUNELITE)
			.onClick(e -> message(GolemContent.GOLEM_EXAMINE));

		// Shift held, as NPC Indicators offers its tag. Added after Examine at the same
		// index, so it sits just above Cancel.
		if (client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			client.getMenu().createMenuEntry(1)
				.setOption("Set Name")
				.setTarget(target)
				.setType(MenuAction.RUNELITE)
				.onClick(e -> askForName(hovered));
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

	/** Opens the chatbox prompt for a golem's name. Left empty, the golem is "Golem" again. */
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

	/**
	 * How far from the cursor, in pixels, a golem's ground point can be and still be worth
	 * an exact hit test. Generous enough for a golem drawn tall, camera zoomed in.
	 */
	private static final int CANDIDATE_RADIUS = 180;

	/**
	 * Ceiling on exact hit tests per tick. Only reached when the cursor sits over golems
	 * near it but not under it, the one case the early exit cannot help with.
	 */
	private static final int MAX_CLICKBOX_TESTS = 10;

	/** Lower on screen first — that is nearer the camera. */
	private static final Comparator<Candidate> BY_DEPTH =
		(a, b) -> Integer.compare(b.screenY, a.screenY);

	/** Reused between ticks so hovering the scene does not allocate every frame. */
	private final List<Candidate> candidates = new ArrayList<>();

	/**
	 * The golem whose clickbox contains the mouse and is nearest the camera. Nearest is the
	 * largest canvas Y — in an isometric view a golem drawn lower is closer — the ordering
	 * the client's own entity picking produces. Clickboxes are only computed for golems
	 * already near the cursor: building one projects every face of the model, and doing
	 * that for a few hundred golems every client tick made the client stutter.
	 */
	private Golem topmostAt(List<Golem> golems, Point mouse)
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return null;
		}

		// Golems near enough the cursor to be worth an exact test.
		candidates.clear();
		for (Golem golem : golems)
		{
			FakeGolem renderer = golem.getRenderer();
			if (renderer == null)
			{
				continue;
			}

			Point ground = Perspective.localToCanvas(client,
				new LocalPoint(renderer.getX(), renderer.getY(), wv), golem.getPlane());
			if (ground == null
				|| Math.abs(ground.getX() - mouse.getX()) > CANDIDATE_RADIUS
				|| Math.abs(ground.getY() - mouse.getY()) > CANDIDATE_RADIUS)
			{
				continue;
			}
			candidates.add(new Candidate(golem, ground.getY()));
		}

		// Nearest the camera first, so the first golem whose box contains the cursor is the
		// answer and the rest are never projected — the early exit that keeps this cheap
		// when hundreds are piled under the mouse, where the radius filter cannot.
		candidates.sort(BY_DEPTH);

		int tested = 0;
		for (Candidate candidate : candidates)
		{
			if (tested++ >= MAX_CLICKBOX_TESTS)
			{
				// In a pile this deep, one more golem's
				// right-click is not worth a frame drop.
				break;
			}
			Shape clickbox = candidate.golem.getRenderer().clickbox();
			if (clickbox != null && clickbox.contains(mouse.getX(), mouse.getY()))
			{
				return candidate.golem;
			}
		}
		return null;
	}

	/** A golem near the cursor, with how far down the screen it is drawn. */
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

	private void message(String text)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", text, null);
	}
}
