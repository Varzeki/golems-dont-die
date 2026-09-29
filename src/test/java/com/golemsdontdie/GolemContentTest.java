package com.golemsdontdie;

import net.runelite.api.gameval.AnimationID;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** What a golem will copy off the player: the emote tab, and nothing that only sits beside it. */
public class GolemContentTest
{
	@Test
	public void theEmotesAreEmotes()
	{
		assertTrue(GolemContent.isEmote(AnimationID.EMOTE_YES));
		assertTrue(GolemContent.isEmote(AnimationID.EMOTE_DANCE));
		assertTrue(GolemContent.isEmote(AnimationID.EMOTE_RUN_ON_SPOT));
		assertTrue(GolemContent.isEmote(AnimationID.EMOTE_PANIC));
		assertTrue(GolemContent.isEmote(AnimationID.EMOTE_SHRUG));
	}

	/** The rune axe's chop is numbered among the emotes, and a golem chopping the air is no emote. */
	@Test
	public void choppingIsNotAnEmote()
	{
		assertFalse(GolemContent.isEmote(AnimationID.HUMAN_WOODCUTTING_RUNE_AXE));
		assertFalse(GolemContent.isEmote(AnimationID.HUMAN_WOODCUTTING_ADAMANT_AXE));
		assertFalse(GolemContent.isEmote(-1));
	}
}
