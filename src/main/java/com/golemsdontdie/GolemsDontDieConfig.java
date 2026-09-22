package com.golemsdontdie;

import java.awt.Color;
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

	// Roaming and persistence are not settings: golems always roam the whole island and are
	// always kept, and offering the alternatives only invited picking the worse one.

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

	@ConfigItem(
		keyName = "showNameplates",
		name = "Show golem names",
		description = "Shows a named golem's name above its head. Name golems in the sidebar tab.",
		section = golemsSection,
		position = 2
	)
	default boolean showNameplates()
	{
		return true;
	}

	@ConfigItem(
		keyName = "nameplateColour",
		name = "Name colour",
		description = "Colour of golem names shown above their heads.",
		section = golemsSection,
		position = 3
	)
	default Color nameplateColour()
	{
		// The golems' own yellow, so a name reads as belonging to the golem under it.
		return new Color(0xFFE700);
	}

	/** Which golems are drawn on the world map. */
	enum MapGolems
	{
		NONE("None"),
		NAMED("Named golems"),
		ALL("All golems");

		private final String label;

		MapGolems(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@ConfigItem(
		keyName = "mapGolems",
		name = "On the world map",
		description = "Shows golems on the world map. A named golem wears its name; hover any of them "
			+ "to see where it is. Golems close together on the map are drawn as one face with a count.",
		section = golemsSection,
		position = 5
	)
	default MapGolems mapGolems()
	{
		return MapGolems.NAMED;
	}

	@ConfigItem(
		keyName = "restrictAmbition",
		name = "Restrict Golem ambition",
		description = "Keeps the Golems on Wyrmscraig",
		section = golemsSection,
		position = 4
	)
	default boolean restrictGolemAmbition()
	{
		return false;
	}

	@ConfigItem(
		keyName = "autoName",
		name = "Auto name golems",
		description = "Gives golems you have not named one anyway, in the sidebar and on a golem's "
			+ "page: a name from Gielinor and a surname off the rocks. A golem always gets the same "
			+ "name, and one you type yourself is kept whatever this is set to.",
		section = golemsSection,
		position = 6
	)
	default boolean autoName()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showSidebar",
		name = "Enable sidebar",
		description = "Shows the Golems tab. With it off, missing golems can still be revived by "
			+ "right-clicking a golem plinth on Wyrmscraig.",
		section = golemsSection,
		position = 7
	)
	default boolean showSidebar()
	{
		return true;
	}

	@ConfigSection(
		name = "Celebrations",
		description = "Golems stop what they are doing and dance when something goes well",
		position = 1
	)
	String celebrationsSection = "celebrations";

	@ConfigItem(
		keyName = "danceOnLevelUp",
		name = "Level up",
		description = "Golems dance when you gain a level.",
		section = celebrationsSection,
		position = 0
	)
	default boolean danceOnLevelUp()
	{
		return true;
	}

	@ConfigItem(
		keyName = "danceOnCollectionLog",
		name = "Collection log",
		description = "Golems dance when you fill a collection log slot. Needs the game's collection "
			+ "log chat message turned on.",
		section = celebrationsSection,
		position = 1
	)
	default boolean danceOnCollectionLog()
	{
		return true;
	}

	@ConfigItem(
		keyName = "danceOnGolemCrafted",
		name = "Golem crafted",
		description = "Golems dance each time you craft another golem.",
		section = celebrationsSection,
		position = 2
	)
	default boolean danceOnGolemCrafted()
	{
		return false;
	}

	// Developer settings, not shipped. Golems always learn from the player's play, so that is
	// not an option. Obstacle data is kept on disk for a later update to offer to send; see the
	// telemetry package. The two below are always off in a release: on a dev client, uncomment
	// the section and the item and read the setting in place of the matching DevOptions constant
	// (GolemsDontDiePlugin.applyObstacleSettings, ObstacleHighlightOverlay.render).
	// @ConfigSection(
	// 	name = "Obstacles",
	// 	description = "How golems learn to use shortcuts",
	// 	position = 1,
	// 	closedByDefault = true
	// )
	// String obstaclesSection = "obstacles";

	// @ConfigItem(
	// 	keyName = "highlightObstacles",
	// 	name = "Highlight nearby obstacles",
	// 	description = "Outlines shortcuts around you by how well golems know them. "
	// 		+ "Green: seen you use this one. Orange: golems will use it, inferred from "
	// 		+ "similar objects elsewhere — using it once confirms it. Red: no animation "
	// 		+ "data, so golems route around it.",
	// 	section = obstaclesSection,
	// 	position = 3
	// )
	// default boolean highlightObstacles()
	// {
	// 	return false;
	// }

	// @ConfigItem(
	// 	keyName = "logGolemState",
	// 	name = "Log golem state (developer)",
	// 	description = "Records what every visible golem is doing, every tick, into the same "
	// 		+ "journal as your own obstacle use — so the two can be compared directly. "
	// 		+ "Very verbose; for diagnosing a specific problem, not for ordinary play.",
	// 	section = obstaclesSection,
	// 	position = 4
	// )
	// default boolean logGolemState()
	// {
	// 	return false;
	// }

	/**
	 * Not a setting: a note at the bottom of the panel, so people know the sidebar tab exists.
	 * The panel draws an item it has no widget for as its name alone, and HTML in a label
	 * wraps. It stores nothing: no default value means unset to the config manager.
	 */
	@ConfigItem(
		keyName = "sidebarNote",
		name = "<html><body style='width: 170px; text-align: center'>Missing golems can be revived in the sidebar tab. "
			+ "Golems may also be managed or renamed in this tab.</body></html>",
		description = "",
		position = 100
	)
	default void sidebarNote()
	{
	}
}
