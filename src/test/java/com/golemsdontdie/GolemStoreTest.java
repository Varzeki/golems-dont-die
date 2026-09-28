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

	/**
	 * A save from a later version, with fields this one has never heard of, still loads: the
	 * format only grows at the end, and what is not understood is left rather than taken as damage.
	 */
	@Test
	public void aLaterSaveStillLoads()
	{
		GolemStore store = new GolemStore();
		StringBuilder later = new StringBuilder(store.serialise(
			java.util.Collections.singletonList(golem(77L, "Flint"))));
		for (int extra = 0; extra < 11; extra++)
		{
			later.append(",1");
		}
		List<GolemStore.SavedGolem> read = store.deserialise(later.toString());
		assertEquals(1, read.size());
		assertEquals(77L, read.get(0).seed);
		assertEquals("Flint", read.get(0).nickname);
	}

	/** A record too short to be a golem is dropped rather than half-read. */
	@Test
	public void aTruncatedRecordIsDropped()
	{
		assertEquals(0, new GolemStore().deserialise("1234,2596,2256").size());
	}

	/**
	 * Traits come back as they were dealt, not as the seed would deal them today: the list they are
	 * drawn from will change, and a golem's page must not change with it.
	 */
	@Test
	public void traitsSurviveTheSaveFile()
	{
		GolemStore store = new GolemStore();
		Golem golem = golem(5L, "Slate");
		int dealt = golem.getTraits();
		golem.restoreTraits(GolemTrait.LOYAL.mask() | GolemTrait.FOND_OF_GOATS.mask());

		GolemStore.SavedGolem saved = store.deserialise(store.serialise(
			java.util.Collections.singletonList(golem))).get(0);

		assertEquals(GolemTrait.LOYAL.mask() | GolemTrait.FOND_OF_GOATS.mask(), saved.traits);
		// And a save from before traits were kept says so, leaving the seed's hand in place.
		String older = "1234,2596,2256,0,0,2596,2256,-1,-1,-1,Pebble,0,0,5";
		assertEquals(0, store.deserialise(older).get(0).traits);
		assertEquals(dealt, GolemTrait.of(5L));
	}

	/** A golem's history goes with it, and comes back as it was. */
	@Test
	public void aHistorySurvivesTheSaveFile()
	{
		GolemStore store = new GolemStore();
		Golem golem = golem(99L, "Chip");
		golem.getHistory().tookTransport();
		golem.getHistory().tookTransport();
		golem.getHistory().sailed();
		// Two samples: the first only says where it is, the second is the ground between them.
		golem.getHistory().sample(2596, 2256, 0, PLINTH);
		golem.getHistory().sample(2600, 2256, 0, PLINTH);

		GolemStore.SavedGolem saved = store.deserialise(store.serialise(
			java.util.Collections.singletonList(golem))).get(0);

		assertEquals(2, saved.transports);
		assertEquals(1, saved.voyages);
		assertEquals(4, saved.walked);
		assertEquals(2600, saved.furthestX);
		assertEquals(golem.getHistory().getFirstSeen(), saved.firstSeen);
	}

	/** A star stays on the golem it was given to, and a golem saved before stars has none. */
	@Test
	public void aStarSurvivesTheSaveFile()
	{
		GolemStore store = new GolemStore();
		Golem starred = golem(8L, "Quartz");
		starred.setFavourite(true);
		List<GolemStore.SavedGolem> read = store.deserialise(store.serialise(
			Arrays.asList(starred, golem(9L, null))));
		assertEquals(true, read.get(0).favourite);
		assertEquals(false, read.get(1).favourite);
		assertEquals(false, store.deserialise("1234,2596,2256,0,0,2596,2256,-1,-1,-1,Pebble").get(0).favourite);
	}

	/** A craft number is kept, and a golem saved before they were kept has none until restored. */
	@Test
	public void aCraftNumberSurvivesTheSaveFile()
	{
		GolemStore store = new GolemStore();
		Golem numbered = golem(10L, null);
		numbered.setCraftNumber(2596);
		List<GolemStore.SavedGolem> read = store.deserialise(store.serialise(Arrays.asList(numbered)));
		assertEquals(2596, read.get(0).craftNumber);
		assertEquals(0, store.deserialise("1234,2596,2256,0,0,2596,2256,-1,-1,-1,Pebble").get(0).craftNumber);
	}
}
