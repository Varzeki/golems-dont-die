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

	@ConfigSection(
		name = "Obstacles",
		description = "How golems learn to use shortcuts",
		position = 1,
		closedByDefault = true
	)
	String obstaclesSection = "obstacles";

	@ConfigItem(
		keyName = "learnObstacles",
		name = "Learn from your play",
		description = "Golems only use shortcuts whose animation is known. Watching you use "
			+ "one teaches them how, and unlocks it permanently.",
		section = obstaclesSection,
		position = 0
	)
	default boolean learnObstacles()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendObstacleTelemetry",
		name = "Send obstacle telemetry",
		description = "Share which animation each shortcut plays, so a future update can "
			+ "ship them for everyone. Sends only the object, its animation and how long it "
			+ "took — never your name, world, location or anything identifying. Needs a "
			+ "collection URL below; nothing is sent without one.",
		section = obstaclesSection,
		position = 1
	)
	default boolean sendObstacleTelemetry()
	{
		return false;
	}

	@ConfigItem(
		keyName = "telemetryEndpoint",
		name = "Collection URL",
		description = "Where to send obstacle telemetry. Blank means nothing is ever sent.",
		section = obstaclesSection,
		position = 2
	)
	default String telemetryEndpoint()
	{
		return "";
	}

	@ConfigItem(
		keyName = "highlightObstacles",
		name = "Highlight nearby obstacles",
		description = "Outlines shortcuts around you by how well golems know them. "
			+ "Green: seen you use this one. Orange: golems will use it, inferred from "
			+ "similar objects elsewhere — using it once confirms it. Red: no animation "
			+ "data, so golems route around it.",
		section = obstaclesSection,
		position = 3
	)
	default boolean highlightObstacles()
	{
		return false;
	}

	@ConfigItem(
		keyName = "logGolemState",
		name = "Log golem state (developer)",
		description = "Records what every visible golem is doing, every tick, into the same "
			+ "journal as your own obstacle use — so the two can be compared directly. "
			+ "Very verbose; for diagnosing a specific problem, not for ordinary play.",
		section = obstaclesSection,
		position = 4
	)
	default boolean logGolemState()
	{
		return false;
	}
}
