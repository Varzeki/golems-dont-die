package com.golemsdontdie;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Golems placed on the world maps as the shipped table says each map draws the game. */
public class WorldMapLayersTest
{
	private final WorldMapLayers layers = new WorldMapLayers();
	private final int[] spot = new int[2];

	@Before
	public void load()
	{
		layers.load();
	}

	/** Taverley Underground draws its upper floor as an inset south of the floor below. */
	@Test
	public void anUpperFloorGoesToItsInset()
	{
		WorldMapLayers.Layer taverley = layers.named("Taverley Underground");
		assertTrue(taverley.place(2944, 9800, 1, spot));
		assertArrayEquals(new int[]{2944, 9608}, spot);
		assertTrue(taverley.place(2944, 9792, 0, spot));
		assertArrayEquals(new int[]{2944, 9792}, spot);
	}

	/** Cam Torum is a map of its own over Neypotzli: one tile, two maps, by floor. */
	@Test
	public void oneTileIsTwoMapsByFloor()
	{
		WorldMapLayers.Layer camTorum = layers.named("Cam Torum");
		WorldMapLayers.Layer neypotzli = layers.named("Neypotzli");
		assertTrue(camTorum.place(1384, 9536, 1, spot));
		assertFalse(neypotzli.place(1384, 9536, 1, spot));
		assertTrue(neypotzli.place(1344, 9536, 0, spot));
		assertFalse(camTorum.place(1344, 9536, 0, spot));
	}

	/** A map that draws none of the test tiles is the surface, which has no layer. */
	@Test
	public void theSurfaceHasNoLayer()
	{
		assertNull(layers.layerOf((x, y) -> false));
	}
}
