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

	/** Record every visible golem's state each tick into the obstacle journal. */
	static final boolean LOG_GOLEM_STATE = false;

	/**
	 * Write the tick-by-tick obstacle journal to the RuneLite folder. Off in a release; only
	 * useful beside a bug. {@link ObstacleDataBridge} keeps what ordinary play is worth
	 * keeping, and is always on.
	 */
	static final boolean JOURNAL = false;

	private DevOptions()
	{
	}
}
