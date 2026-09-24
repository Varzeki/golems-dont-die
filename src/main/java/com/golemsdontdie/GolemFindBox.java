package com.golemsdontdie;

import java.awt.Color;
import java.awt.image.BufferedImage;
import net.runelite.api.MenuAction;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.infobox.InfoBox;

/**
 * The golem being looked for, as an infobox: how far off it is, and who and where on hover.
 *
 * <p>The arrow and the map face both need the player to be looking somewhere in particular. This
 * sits with the rest of the infoboxes, so the answer to "how far now?" is always in the corner of
 * the eye, and a right-click on it is the way to stop looking without opening the sidebar.
 *
 * <p>Client thread: it is told what to say by the plugin once a tick, and only reads it back.
 */
class GolemFindBox extends InfoBox
{
	/** The right-click entry that stops the looking. */
	static final String STOP = "Stop";

	/** Whose infobox a click on {@link #STOP} is about: this one's target line. */
	static final String TARGET = "finding golem";

	/** The distance, or blank while it cannot be said. */
	private String text = "";

	GolemFindBox(BufferedImage icon, Plugin plugin)
	{
		super(icon, plugin);
		getMenuEntries().add(new OverlayMenuEntry(MenuAction.RUNELITE_INFOBOX, STOP, TARGET));
	}

	/**
	 * Says where the golem is.
	 *
	 * @param name  what the golem is called
	 * @param where where it is, in words
	 * @param tiles how far off it is, or -1 where that cannot be said, such as a golem on another
	 *              floor or the player not yet placed
	 */
	void show(String name, String where, int tiles)
	{
		text = tiles < 0 ? "" : tiles < 1000 ? Integer.toString(tiles) : tiles / 1000 + "k";
		String away = tiles < 0 ? "" : "</br>" + String.format("%,d", tiles) + " tiles away";
		setTooltip("Finding " + name + "</br>" + where + away);
	}

	@Override
	public String getText()
	{
		return text;
	}

	@Override
	public Color getTextColor()
	{
		return Color.WHITE;
	}
}
