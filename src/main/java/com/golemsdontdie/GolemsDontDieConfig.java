package com.golemsdontdie;

import java.awt.*;
import net.runelite.client.config.*;

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
		description = "Cap golems to improve performance. Golems over the cap crumble, oldest first; named and starred golems are kept.",
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

	@Alpha
	@ConfigItem(
		keyName = "nameplateColour",
		name = "Name colour",
		description = "Colour of golem names shown above their heads, and how see-through they are.",
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
		// Short, because the settings panel sizes the box to its longest choice and takes the room
		// out of the setting's name: "Named and starred golems" left the name reading "On t...".
		NONE("Off"),
		NAMED("Named"),
		ALL("All");

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
		name = "World map",
		description = "Shows golems on the world map. Named: golems you have named or starred. All: "
			+ "every golem. A named golem wears its name; hover any of them to see where it is. Golems "
			+ "close together on the map are drawn as one face with a count.",
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
		description = "Gives golems you have not named one anyway, wherever a golem's name is shown, in "
			+ "the name style below. A golem always gets the same name, and one you type yourself is kept "
			+ "whatever this is set to. Only names you give are written on the world map.",
		section = golemsSection,
		position = 6
	)
	default boolean autoName()
	{
		return false;
	}

	/** What auto naming calls a golem. */
	enum NameStyle
	{
		DEFAULT("Default"),
		ORDINAL("Ordinal");

		private final String label;

		NameStyle(String label)
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
		keyName = "nameStyle",
		name = "Name style",
		description = "The names auto naming gives. Default: a name from Gielinor and a surname off the "
			+ "rocks. Ordinal: the order the golem was crafted in, in Latin: Primus, Secundus, Tertius. "
			+ "Golems from before this were numbered oldest first.",
		section = golemsSection,
		position = 7
	)
	default NameStyle nameStyle()
	{
		return NameStyle.DEFAULT;
	}

	@ConfigItem(
		keyName = "showSidebar",
		name = "Enable sidebar",
		description = "Shows the Golems tab. With it off, missing golems can still be revived by "
			+ "right-clicking a golem plinth on Wyrmscraig.",
		section = golemsSection,
		position = 8
	)
	default boolean showSidebar()
	{
		return true;
	}

	@ConfigItem(
		keyName = "findPath",
		name = "Path to a golem being found",
		description = "While you are finding a golem, the Shortest Path plugin draws the way to it. "
			+ "Needs Shortest Path installed; without it this does nothing.",
		section = golemsSection,
		position = 9
	)
	default boolean findPath()
	{
		return true;
	}

	@ConfigItem(
		keyName = "golemsJoinShip",
		name = "Golems join your ship",
		description = "Golems standing near you when you step aboard come too: they stand at "
			+ "the rail while you sail and step off where you do. Not while golem ambition is restricted.",
		section = golemsSection,
		position = 10
	)
	default boolean golemsJoinShip()
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
		description = "Golems dance when you fill a collection log slot.",
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

	@ConfigItem(
		keyName = "danceOnQuest",
		name = "Quest complete",
		description = "Golems dance when you finish a quest or miniquest.",
		section = celebrationsSection,
		position = 3
	)
	default boolean danceOnQuest()
	{
		return true;
	}

	@ConfigItem(
		keyName = "danceOnDiary",
		name = "Achievement diary",
		description = "Golems dance when you finish a tier of an achievement diary.",
		section = celebrationsSection,
		position = 4
	)
	default boolean danceOnDiary()
	{
		return true;
	}

	@ConfigItem(
		keyName = "danceOnCombatTask",
		name = "Combat achievement",
		description = "Golems dance when you complete a combat task.",
		section = celebrationsSection,
		position = 5
	)
	default boolean danceOnCombatTask()
	{
		return true;
	}

	@ConfigItem(
		keyName = "danceOnPet",
		name = "Pet",
		description = "Golems dance when a pet finds you.",
		section = celebrationsSection,
		position = 6
	)
	default boolean danceOnPet()
	{
		return true;
	}

	@ConfigItem(
		keyName = "danceOnPersonalBest",
		name = "Personal best",
		description = "Golems dance when you beat your best time at a boss, a raid or a course.",
		section = celebrationsSection,
		position = 7
	)
	default boolean danceOnPersonalBest()
	{
		return true;
	}

	@ConfigItem(
		keyName = "danceOnClue",
		name = "Clue scroll",
		description = "Golems dance when you finish a clue scroll. Off by default: for a player doing "
			+ "clues back to back it would be every few minutes.",
		section = celebrationsSection,
		position = 8
	)
	default boolean danceOnClue()
	{
		return false;
	}

	@ConfigSection(
		name = "Obstacle data",
		description = "Helping golems learn the game's obstacles",
		position = 2
	)
	String obstacleDataSection = "obstacleData";

	@ConfigItem(
		keyName = "shareObstacleData",
		name = "Share obstacle data",
		description = "Sends the obstacle data the plugin keeps (the shortcuts and obstacles you have crossed, "
			+ "and how golems crossed them) to the plugin's author, so every player's golems can use them in a "
			+ "later update. No account, name, world or location of yours is sent: only the obstacles themselves.",
		// The Plugin Hub's own wording for a plugin that adds networking.
		warning = "This feature submits your IP address and various account data to a 3rd-party server not "
			+ "controlled or verified by Runelite developers.",
		section = obstacleDataSection,
		position = 0
	)
	default boolean shareObstacleData()
	{
		return false;
	}

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
