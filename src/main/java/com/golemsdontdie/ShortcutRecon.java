package com.golemsdontdie;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.MenuOptionClicked;

/**
 * Watches the player use a shortcut and writes down what actually happened.
 *
 * <p>Which animation a shortcut plays, and how long it takes, is <b>not in the cache</b>.
 * Agility obstacles are resolved server-side: the server plays the animation and moves the
 * player, so the client holds no record of the pairing. Object definitions carry the
 * object's own animation — a swinging rope, a turning ring — but not the person's. Client
 * scripts are interface logic and do not decide it either.
 *
 * <p>That leaves measurement. This is the same method that harvested the golem's own
 * identifiers in the first place: use the thing in game with logging on, then read the log
 * and hardcode what it says.
 *
 * <p>Every line it prints is a fact rather than an estimate — the object clicked, the clip
 * the player played, how many ticks passed before they arrived, and where they started and
 * finished. Feed those to {@code dev-tools/BuildWyrmscraigTransports.java} and the guesses
 * go away.
 *
 * <p><b>Development only.</b> This is a measuring instrument, not a feature; it must come
 * out before release, along with its registration in the plugin.
 */
@Slf4j
@Singleton
class ShortcutRecon
{
	/**
	 * Ticks after a click within which an animation is taken to belong to it.
	 *
	 * <p>Generous, because a click on a shortcut is not the moment it is used: the player
	 * walks there first, and across a clearing that is easily ten seconds. An earlier
	 * three-tick window expired during the walk every single time and recorded nothing.
	 */
	private static final int CLAIM_WINDOW = 60;

	/** Ticks to keep watching before giving up on a shortcut ever finishing. */
	private static final int PATIENCE = 30;

	@Inject
	private Client client;

	private int clickedObject = -1;
	private String clickedOption = "";
	private String clickedTarget = "";
	private int clickedTick = -1;

	private WorldPoint startedAt;
	private int animation = -1;
	private int animationTick = -1;

	/** Remembers the object the player just clicked, so an animation can be attributed. */
	void onMenuOptionClicked(MenuOptionClicked event)
	{
		// Menu identifiers for a scene object carry its id; anything else is not a
		// shortcut and is ignored.
		String option = event.getMenuOption();
		if (option == null || option.isEmpty())
		{
			return;
		}

		clickedObject = event.getId();
		clickedOption = option;
		clickedTarget = event.getMenuTarget() == null ? "" : event.getMenuTarget();
		clickedTick = client.getTickCount();
		animation = -1;
		startedAt = null;
	}

	/** Notes the clip the player began playing, if it followed a click closely enough. */
	void onAnimationChanged(AnimationChanged event)
	{
		Player local = client.getLocalPlayer();
		if (local == null || event.getActor() != local)
		{
			return;
		}

		int playing = local.getAnimation();
		if (playing == -1)
		{
			return;
		}

		int tick = client.getTickCount();
		if (clickedTick < 0 || tick - clickedTick > CLAIM_WINDOW)
		{
			// Not attributable to anything the player clicked on — an emote, combat, a
			// skill. Nothing to learn from it.
			return;
		}

		// Every clip is logged as it starts, not just the first.
		//
		// Trying to decide when a shortcut was "finished" and print one tidy summary
		// missed the interesting cases entirely: a stepping-stone crossing is several
		// animations from a single click, and the tidy version waited for an ending that
		// never came in the shape it expected. A journal cannot miss anything — it says
		// what played, when, and where the player was, and the reading is done afterwards.
		WorldPoint at = local.getWorldLocation();
		log.info("SHORTCUT-ANIM object={} option=\"{} {}\" anim={} sinceClick={} sincePrev={} at={},{},{}",
			clickedObject, clickedOption, stripTags(clickedTarget), playing,
			tick - clickedTick, animationTick < 0 ? 0 : tick - animationTick,
			at == null ? -1 : at.getX(), at == null ? -1 : at.getY(),
			at == null ? -1 : at.getPlane());

		animation = playing;
		animationTick = tick;

		// Where the golem-equivalent actually left from, which is where the player is when
		// the clip starts — not where they were standing when they clicked, which may be
		// most of a region away.
		if (startedAt == null)
		{
			startedAt = at;
		}
	}

	/**
	 * Reports a completed shortcut once the player has stopped moving.
	 *
	 * <p>Called every tick. The measurement ends when the player has arrived somewhere
	 * other than where they started and is no longer animating, which is the same moment a
	 * watching human would call it done.
	 */
	void onGameTick()
	{
		if (animation < 0 || startedAt == null)
		{
			return;
		}

		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}

		int tick = client.getTickCount();
		int elapsed = tick - animationTick;
		WorldPoint at = local.getWorldLocation();
		boolean moved = at != null && !at.equals(startedAt);

		if (local.getAnimation() == -1 && moved)
		{
			// The summary line, which the journal above cannot give: how long from the
			// last clip starting to the player standing still somewhere new. That is the
			// number a looping animation hides — a rock climb reports one animation change
			// and then loops silently, so its length is only visible in the arrival.
			log.info("SHORTCUT-DONE object={} option=\"{} {}\" lastAnim={} ticks={} from={},{},{} to={},{},{}",
				clickedObject, clickedOption, stripTags(clickedTarget), animation, elapsed,
				startedAt.getX(), startedAt.getY(), startedAt.getPlane(),
				at.getX(), at.getY(), at.getPlane());
			reset();
			return;
		}

		if (elapsed > PATIENCE)
		{
			// Whatever it was, it was not a shortcut that finished.
			reset();
		}
	}

	private void reset()
	{
		animation = -1;
		clickedTick = -1;
		clickedObject = -1;
		startedAt = null;
	}

	private static String stripTags(String text)
	{
		return text.replaceAll("<[^>]*>", "");
	}
}
