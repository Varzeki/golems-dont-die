package com.golemsdontdie;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * How many golems the player has ever crafted, read from the game's own count.
 *
 * <p>The plugin's roster is not the player's history: golems made before it was installed were
 * never taken over, and ones culled or dismissed are gone. The gap is what the revive button
 * offers to close. The total only ever goes up, a lower figure being treated as a misread line,
 * because reading some other line as a smaller total would silently offer too few golems.
 *
 * <p>{@link GolemContent#GOLEM_COUNT_VARBIT} arrives with the player's variables at login, so
 * golems crafted on mobile or before the plugin was installed are counted by the next login
 * rather than staying wrong until the next craft. The chat line on every craft reports the same
 * number and is a backstop: it does not depend on the varbit meaning what it appears to, and is
 * not capped at 65,535. Counting golem spawns was dropped — on a shared island it would tally
 * other players' golems.
 */
@Slf4j
@Singleton
class GolemTally
{
	/** Config key for the running total. Plugin-written, so not a config item. */
	static final String TOTAL_KEY = "totalGolemsCrafted";

	/**
	 * The game's own tally line, sent each time a golem is finished:
	 * <pre>You have crafted 374 golems on Wyrmscraig.</pre>
	 *
	 * <p>Matched precisely, not by keywords: a loose test would read "You need 5 stone chunks to
	 * build a golem" as a total of five. The trailing "on Wyrmscraig" is not required.
	 */
	private static final Pattern TOTAL_MESSAGE =
		Pattern.compile("(?i)\\byou have crafted\\s+([\\d,]+)\\s+golems?\\b");

	@Inject
	private ConfigManager configManager;

	/** Golems crafted, ever. Zero until a message says otherwise. */
	@Getter
	private int total;

	void load()
	{
		total = 0;
		String stored = configManager.getConfiguration(GolemsDontDieConfig.GROUP, TOTAL_KEY);
		if (stored == null || stored.trim().isEmpty())
		{
			return;
		}
		try
		{
			total = Math.max(0, Integer.parseInt(stored.trim()));
		}
		catch (NumberFormatException e)
		{
			log.debug("Stored golem total '{}' unreadable", stored);
		}
	}

	/**
	 * Offers a chat line to the tally.
	 *
	 * @return true if the total changed, so the panel can be redrawn
	 */
	boolean observe(String message)
	{
		if (message == null || message.isEmpty())
		{
			return false;
		}

		Matcher matcher = TOTAL_MESSAGE.matcher(message);
		if (!matcher.find())
		{
			return false;
		}

		int found;
		try
		{
			found = Integer.parseInt(matcher.group(1).replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			// A number too large for an int is not a golem count.
			return false;
		}

		return setTotal(found);
	}

	/**
	 * Offers the game's own count, read from {@link GolemContent#GOLEM_COUNT_VARBIT}. Refusing
	 * to go backwards covers every awkward case: a varbit read before the server sends it is 0,
	 * and a count past sixteen bits pins at 65,535 or wraps small, by which point the chat line
	 * carries the real figure.
	 *
	 * @return true if the total changed, so the panel can be redrawn
	 */
	boolean observeCount(int count)
	{
		return setTotal(count);
	}

	/**
	 * Raises the total. Ignores anything that would lower it.
	 *
	 * @return true if the total changed
	 */
	boolean setTotal(int candidate)
	{
		if (candidate <= total)
		{
			return false;
		}
		total = candidate;
		configManager.setConfiguration(GolemsDontDieConfig.GROUP, TOTAL_KEY, total);
		log.debug("Golems crafted total is now {}", total);
		return true;
	}

}
