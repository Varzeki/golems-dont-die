package com.golemsdontdie;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Where a crew sails: somewhere every one of them would go, or nowhere. A crew that took the best
 * of a bad lot sailed a golem that loves the snow off to the desert.
 */
public class VoyageCrewTest
{
	private static final SailingDocks.Dock HOME = dock(1, 2803, 3421);
	private static final SailingDocks.Dock SNOW = dock(2, 2878, 3940);
	private static final SailingDocks.Dock DESERT = dock(3, 3293, 3184);

	/** Every roll comes up short: only a port somebody wants for certain is taken. */
	private static final Random UNLUCKY = new Random()
	{
		@Override
		public float nextFloat()
		{
			return 0.99f;
		}
	};

	private Voyage voyage;
	private RoamContext context;

	private static SailingDocks.Dock dock(int row, int x, int y)
	{
		WorldPoint at = new WorldPoint(x, y, 0);
		return new SailingDocks.Dock(row, "Dock " + row, 0, 0, at, true, at);
	}

	private static void inject(Object into, String name, Object value) throws Exception
	{
		Field field = into.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(into, value);
	}

	@Before
	public void setUp() throws Exception
	{
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) -> "getRealSkillLevel".equals(method.getName()) ? 99 : null);
		SailingDocks docks = new SailingDocks();
		inject(docks, "client", client);
		docks.getDocks().addAll(Arrays.asList(HOME, SNOW, DESERT));
		voyage = new Voyage();
		inject(voyage, "docks", docks);

		PlaceNames places = new PlaceNames();
		places.load();
		GolemClimate climate = new GolemClimate();
		climate.learn(places);
		context = new RoamContext(null, null, null, null);
		context.setClimates(climate);
	}

	private static TransportMemory golem(GolemTrait... traits)
	{
		TransportMemory memory = new TransportMemory();
		int mask = 0;
		for (GolemTrait trait : traits)
		{
			mask |= trait.mask();
		}
		memory.setTraits(mask);
		return memory;
	}

	@Test
	public void aCrewThatLovesTheSnowSailsToIt()
	{
		List<TransportMemory> crew = Arrays.asList(golem(GolemTrait.LIKES_THE_COLD), golem(GolemTrait.LIKES_THE_COLD),
			golem());
		assertSame(SNOW, voyage.crewPort(HOME, crew, UNLUCKY, context));
	}

	/** One wants the snow and one the desert: neither port is one both would go to, so neither. */
	@Test
	public void aCrewThatCannotAgreeStaysAshore()
	{
		List<TransportMemory> crew = Arrays.asList(golem(GolemTrait.LIKES_THE_COLD), golem(GolemTrait.LIKES_THE_HEAT),
			golem());
		assertNull(voyage.crewPort(HOME, crew, UNLUCKY, context));
	}

	/** Golems that do not mind where they go always go somewhere. */
	@Test
	public void aCrewThatDoesNotMindGoesAnywhere()
	{
		List<TransportMemory> crew = Arrays.asList(golem(), golem(), golem());
		assertNotNull(voyage.crewPort(HOME, crew, UNLUCKY, context));
	}

	/** A port one of them has just come from is not offered, even to a crew that would like it. */
	@Test
	public void noneOfThemSailsStraightBack()
	{
		TransportMemory back = golem();
		back.setBlockedPort(SNOW.getRowId());
		List<TransportMemory> crew = Arrays.asList(golem(GolemTrait.LIKES_THE_COLD), back, golem());
		assertNull(voyage.crewPort(HOME, crew, UNLUCKY, context));
	}
}
