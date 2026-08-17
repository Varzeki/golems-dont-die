package com.golemsdontdie;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * How many golems the player has ever crafted, read from the game's own chat messages.
 *
 * <p>This exists because the plugin's roster is not the same thing as the player's
 * history. Golems made before the plugin was installed were never taken over, and ones
 * culled by the limit or dismissed by hand are gone — so the number wandering the
 * island can fall behind the number actually crafted. The gap is what the revive button
 * offers to close.
 *
 * <p>The total only ever goes up. A message reporting a lower figure than one already
 * seen is treated as not being the message we are looking for, rather than as the count
 * going backwards: golems crafted is a lifetime tally, and misreading some other line as
 * a smaller total would silently make the revive button offer too few golems.
 *
 * <p>The game reports the running total on every craft, so a stale count — from crafting
 * with the plugin disabled, or from a fresh install — corrects itself the next time a
 * golem is finished. Counting golem spawns instead was tried and dropped: on a shared
 * island it would tally other players' golems as the player's own.
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
	 * <p>Matched precisely rather than by keywords. A loose "a golem and a number" test
	 * would read "You need 5 stone chunks to build a golem" as a total of five, and the
	 * cost of a wrong match here is the revive button offering the wrong number of
	 * golems — so the pattern only accepts the sentence that actually reports the total.
	 * The trailing "on Wyrmscraig" is not required, in case the same line is reused
	 * elsewhere.
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
