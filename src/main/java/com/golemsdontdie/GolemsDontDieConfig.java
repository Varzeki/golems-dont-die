package com.golemsdontdie;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(GolemsDontDieConfig.GROUP)
public interface GolemsDontDieConfig extends Config
{
	String GROUP = "golemsdontdie";

	/** Key for the serialised golem roster. Plugin-written, so not a {@link ConfigItem}. */
	String SAVED_GOLEMS_KEY = "savedGolems";

	@ConfigSection(
		name = "Golems",
		description = "How many golems to keep",
		position = 0
	)
	String golemsSection = "golems";

	// Roaming and persistence are not settings. Golems always roam the whole island and
	// are always kept permanently — those were the interesting options and the answer
	// turned out to be the same in both cases, so offering the alternatives only
	// invited someone to pick the worse one.

	@ConfigItem(
		keyName = "limitGolems",
		name = "Limit golems",
		description = "Cap golems to improve performance if too many spawn",
		section = golemsSection,
		position = 0
	)
	default boolean limitGolems()
	{
		return false;
	}

	@ConfigItem(
		keyName = "maxGolems",
		name = "Maximum golems",
		description = "Maximum golems present on island. Has no effect if Limit golems is off.",
		section = golemsSection,
		position = 1
	)
	@Range(min = 1, max = 200)
	default int maxGolems()
	{
		return 25;
	}
}
