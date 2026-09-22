package com.golemsdontdie;

import java.util.Arrays;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * What the save file has to keep. A golem's seed above all: its gait and its traits come out of
 * that one number, so a golem that comes back with a different seed comes back a different golem.
 */
public class GolemStoreTest
{
	private static final WorldPoint PLINTH = new WorldPoint(2596, 2256, 0);

	private static Golem golem(long seed, String nickname)
	{
		GolemSnapshot snapshot = new GolemSnapshot(1234, "Golem", new int[0], new short[0], new short[0],
			128, 128, 1, -1, -1, -1, PLINTH, 0);
		Golem golem = Golem.onTile(snapshot, PLINTH, seed, PLINTH);
		golem.setNickname(nickname);
		return golem;
	}

	@Test
	public void aSeedSurvivesTheSaveFile()
	{
		GolemStore store = new GolemStore();
		List<Golem> roster = Arrays.asList(golem(1234567890123L, "Pebble"), golem(-42L, null));

		List<GolemStore.SavedGolem> read = store.deserialise(store.serialise(roster));

		assertEquals(2, read.size());
		assertEquals(1234567890123L, read.get(0).seed);
		assertEquals("Pebble", read.get(0).nickname);
		assertEquals(-42L, read.get(1).seed);
		assertNull(read.get(1).nickname);
	}

	/** A save from before seeds were kept still loads, and says it has none. */
	@Test
	public void anOlderSaveStillLoads()
	{
		GolemStore store = new GolemStore();
		String eleven = "1234,2596,2256,0,0,2596,2256,-1,-1,-1,Pebble";
		String thirteen = eleven + ",1,600";

		for (String encoded : new String[]{eleven, thirteen})
		{
			List<GolemStore.SavedGolem> read = store.deserialise(encoded);
			assertEquals(encoded, 1, read.size());
			assertEquals(encoded, 0L, read.get(0).seed);
			assertEquals(encoded, "Pebble", read.get(0).nickname);
			assertEquals(encoded, 2596, read.get(0).worldX);
		}
	}

	/** And a record with more fields than the format has is not half-read. */
	@Test
	public void aGarbledRecordIsDropped()
	{
		GolemStore store = new GolemStore();
		assertEquals(0, store.deserialise("1234,2596,2256,0,0,2596,2256,-1,-1,-1,Pebble,1,600,7,8,9").size());
		assertEquals(0, store.deserialise("1234,2596,2256").size());
	}
}
