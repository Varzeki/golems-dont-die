package com.golemsdontdie;

/**
 * Developer switches that are not in the settings panel.
 *
 * <p>Settings once, but nothing a player should meet. Flip one here, or restore its
 * commented-out item in {@link GolemsDontDieConfig}.
 */
final class DevOptions
{
	/** Outline shortcuts nearby by how well golems know them. See ObstacleHighlightOverlay. */
	static final boolean HIGHLIGHT_OBSTACLES = false;

	/**
	 * Log every visible golem's state each tick, and what it decides, at debug level. Very loud;
	 * only useful beside a bug. Nothing is written to a file: {@link ObstacleDataBridge} keeps what
	 * ordinary play is worth keeping, in the plugin's own folder.
	 */
	static final boolean LOG_GOLEM_STATE = false;

	private DevOptions()
	{
	}
}
