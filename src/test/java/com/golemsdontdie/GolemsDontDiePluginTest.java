package com.golemsdontdie;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Launches a developer-mode client with this plugin loaded, for testing in game.
 *
 * <p>Not a test despite the name and the location — {@code Launch-Plugin.ps1} derives
 * this class by appending "Test" to the plugin class from
 * {@code runelite-plugin.properties}, and runs its {@code main}. It lives in the test
 * source set so it is compiled but never shipped in the plugin jar.
 */
public class GolemsDontDiePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(GolemsDontDiePlugin.class, BoatMotionRecon.class, BrandingExport.class,
			PortraitExport.class, DevCommands.class);
		RuneLite.main(args);
	}
}
