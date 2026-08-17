package com.golemsdontdie;

import java.awt.Shape;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

/**
 * Gives the copies the right-click menu the real golems have.
 *
 * <p>Without this a fake golem is inert under the cursor: a
 * {@link net.runelite.api.RuneLiteObjectController} is drawn by the client but is not
 * an entity it will build a menu from, so hovering one produces nothing where the real
 * golem offered an option and an examine. That reads as wrong immediately — the
 * illusion survives the swap and then dies to a mouse hover.
 *
 * <p>So the hit test is done by hand each tick: take the posed model that was drawn,
 * ask {@link net.runelite.api.Perspective} for its clickbox, and see whether the mouse
 * is inside it. Entries are added with {@link MenuAction#RUNELITE} and their own click
 * handlers, so nothing is sent to the server — a fake golem is a local fiction and
 * clicking one must stay local.
 */
@Singleton
class GolemMenu
{
	/** Target colour the client uses for an NPC with no combat level. */
	private static final String NPC_COLOUR = "<col=ffff00>";

	@Inject
	private Client client;

	/**
	 * Adds entries for whichever golem is under the cursor.
	 *
	 * <p>Called once per client tick, which is when the client rebuilds its menu. Only
	 * the topmost golem gets entries — stacking three sets because three golems overlap
	 * is not what the real game does.
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

		// A named golem is shown by its name.
		//
		// This does cost some of the disguise — every real golem is called "Golem", so
		// a named one is visibly not one of them. That is the right trade: naming a
		// golem you then cannot pick out of a crowd is pointless, and a name is only
		// ever there because the player deliberately put it there. Leave a golem
		// unnamed and it stays indistinguishable.
		String name = hovered.getNickname();
		if (name == null || name.isEmpty())
		{
			name = hovered.getSnapshot().getName();
		}
		String target = NPC_COLOUR + name + "</col>";

		// Index 1, immediately above Cancel, which the client always keeps at index 0.
		// The menu array is drawn bottom-up, so inserting at 0 would put Examine
		// *below* Cancel — which is exactly what the first attempt did, giving
		// "Walk here, Cancel, Examine" against the real "Walk here, Examine, Cancel".
		client.getMenu().createMenuEntry(1)
			.setOption("Examine")
			.setTarget(target)
			.setType(MenuAction.RUNELITE)
			.onClick(e -> message(GolemContent.GOLEM_EXAMINE));
	}

	/**
	 * How far from the cursor, in pixels, a golem's ground point can be and still be
	 * worth an exact hit test. Generous enough to cover a golem drawn tall on screen
	 * with the camera zoomed right in.
	 */
	private static final int CANDIDATE_RADIUS = 180;

	/**
	 * Ceiling on exact hit tests per tick. Only reached when the cursor sits over a
	 * heap of golems that are near it but not under it, which is the one case the
	 * early exit cannot help with.
	 */
	private static final int MAX_CLICKBOX_TESTS = 10;

	/** Lower on screen first — that is nearer the camera. */
	private static final Comparator<Candidate> BY_DEPTH =
		(a, b) -> Integer.compare(b.screenY, a.screenY);

	/** Reused between ticks so hovering the scene does not allocate every frame. */
	private final List<Candidate> candidates = new ArrayList<>();

	/**
	 * The golem whose clickbox contains the mouse and which is nearest the camera.
	 *
	 * <p>Nearest is approximated by the largest canvas Y of the clickbox — a golem
	 * drawn lower on the screen is closer to the camera in an isometric view, which is
	 * the same ordering the client's own entity picking produces.
	 *
	 * <p>Clickboxes are only computed for golems already near the cursor. Building one
	 * means projecting every face of the model and merging the results, and this runs
	 * on every client tick; doing it for a few hundred golems made the client stutter
	 * whenever the mouse was over the scene. Projecting a golem's ground point instead
	 * is a single matrix transform, so the expensive test is reserved for the handful
	 * of golems that could plausibly be under the cursor.
	 */
	private Golem topmostAt(List<Golem> golems, Point mouse)
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return null;
		}

		// Gather the golems near enough the cursor to be worth an exact test, keeping
		// how far down the screen each is drawn.
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

		// Nearest the camera first. In an isometric view a golem drawn lower on the
		// screen is in front, which is the order the client picks entities in — so the
		// first golem whose box contains the cursor is the answer and the rest need
		// never be projected at all. That early exit is what keeps this cheap when a
		// few hundred golems are piled up under the mouse; the radius filter alone
		// does nothing when they are all in the same place.
		candidates.sort(BY_DEPTH);

		int tested = 0;
		for (Candidate candidate : candidates)
		{
			if (tested++ >= MAX_CLICKBOX_TESTS)
			{
				// A pile deep enough to hit this is a pile where one more golem's
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
