package com.golemsdontdie;

/**
 * How a golem came to be somewhere, as the line in its journal reads.
 *
 * <p>Taken from the transport it used — the tables say what each obstacle is, and a ladder reads
 * differently from a gangplank — or from the crossing it was on. Walking is what is left, and is
 * much the commonest.
 */
enum GolemTravel
{
	WALKED("Walked to"),
	SAILED("Sailed to"),
	CLIMBED("Climbed to"),
	DESCENDED("Climbed down to"),
	SQUEEZED("Squeezed through to"),
	CROSSED("Crossed to"),
	JUMPED("Jumped across to"),
	BOARDED("Took a gangplank to"),
	/** Underground, arrived at on foot: a golem that walked into the dark went exploring. */
	EXPLORED("Explored");

	private final String verb;

	GolemTravel(String verb)
	{
		this.verb = verb;
	}

	/** The whole line, as the journal shows it. */
	String line(String place)
	{
		return verb + " " + place;
	}

	/**
	 * What an obstacle of this kind reads as. The plane says which way a climb went: the same
	 * ladder is climbed and climbed down.
	 */
	static GolemTravel of(int archetype, int fromPlane, int toPlane)
	{
		switch (archetype)
		{
			case GolemTransport.ARCHETYPE_LADDER:
			case GolemTransport.ARCHETYPE_CLIMB:
				return toPlane < fromPlane ? DESCENDED : CLIMBED;
			case GolemTransport.ARCHETYPE_SQUEEZE:
				return SQUEEZED;
			case GolemTransport.ARCHETYPE_JUMP:
			case GolemTransport.ARCHETYPE_DITCH:
				return JUMPED;
			case GolemTransport.ARCHETYPE_BALANCE:
			case GolemTransport.ARCHETYPE_TIGHTROPE:
			case GolemTransport.ARCHETYPE_STILE:
				return CROSSED;
			case GolemTransport.ARCHETYPE_GANGPLANK:
				return BOARDED;
			default:
				// Doors and everything unnamed: a golem went through and carried on walking.
				return WALKED;
		}
	}

	private static final GolemTravel[] ALL = values();

	static GolemTravel byOrdinal(int ordinal)
	{
		return ordinal >= 0 && ordinal < ALL.length ? ALL[ordinal] : WALKED;
	}
}
