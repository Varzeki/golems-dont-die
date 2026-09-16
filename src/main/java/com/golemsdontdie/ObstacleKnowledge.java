package com.golemsdontdie;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * What is actually known about how each obstacle is traversed, and what golems may use.
 *
 * <p>The plugin ships a table of transports and a guess, per obstacle, at the animation a
 * player performs on it. The guess comes from the menu text and it is <b>often wrong</b>:
 * a basalt stepping stone is named "stepping stone" and there is an animation called
 * {@code HUMAN_STEPPINGSTONEJUMP}, but the game plays {@code HUMAN_SPOT_JUMP} instead. Every
 * obstacle measured so far has overturned its own guess at least once.
 *
 * <p>So guessing is no longer enough to act on, and this class answers two questions that
 * are easy to run together and should not be:
 *
 * <ul>
 *   <li><b>May a golem go through here?</b> Nearly always yes. No is reserved for obstacles
 *       where moving without the right animation would look broken rather than plain — a
 *       leap over open water, a walk along a tightrope.</li>
 *   <li><b>Do we know what it looks like?</b> Far less often. Where the answer is no, the
 *       golem traverses in its ordinary pose and plays nothing, which is exactly what it
 *       already does for a door.</li>
 * </ul>
 *
 * <p>Keeping them apart is what stops the gate causing worse bugs than it fixes. Locking
 * one direction of a two-way transport — a ladder that can be climbed but not descended —
 * would strand golems upstairs for the rest of the session.
 *
 * <p>That makes the plugin improve by being played. {@link ObstacleObserver} watches every
 * obstacle the player uses; two consistent sightings of the same obstacle unlock it for
 * golems permanently, and a player who does a lap of an agility course has taught the plugin
 * a course's worth of shortcuts without being asked to do anything.
 *
 * <p>Two sightings rather than one on purpose. A single animation following a click is not
 * proof of anything — the player may have been attacked, eaten, or teleported between the
 * click and the clip. Requiring the same answer twice costs nothing in practice, because
 * anybody who uses an obstacle once uses it again, and it removes essentially all of the
 * noise a one-sample rule would admit.
 */
@Slf4j
@Singleton
class ObstacleKnowledge
{
	/** Consistent sightings before an obstacle is trusted enough for a golem to use. */
	private static final int SIGHTINGS_TO_UNLOCK = 2;

	/** Where learned obstacles are kept between sessions. */
	static final String LEARNED_KEY = "learnedObstacles";

	/** Where the individual obstacles the player has personally used are kept. */
	static final String CONFIRMED_KEY = "confirmedObstacles";

	/** Where routes learned by watching the player are kept. */
	static final String ROUTES_KEY = "learnedRoutes";

	/** Where recorded motions are kept. */
	static final String CURVES_KEY = "learnedCurves";

	/** Where the line each obstacle moves the player along is kept. */
	static final String LINES_KEY = "learnedLines";

	/**
	 * Tiles either side of a transport's origin that still count as the same obstacle.
	 *
	 * <p>A sighting records where the player <i>stood</i>; the index records where the
	 * object <i>is</i>. Those are never the same tile — you stand beside a ladder to climb
	 * it — and on Wyrmscraig's rocks they are three tiles apart, because a climb starts
	 * from a distance.
	 *
	 * <p>Measured from the nearest tile of the object's footprint rather than its
	 * south-west corner, which is what the earlier version compared against. A cave three
	 * tiles wide was judged by one corner of itself, so entering it from the far end
	 * counted as being nowhere near it and the obstacle stayed orange no matter how many
	 * times it was used.
	 *
	 * <p>Three tiles is deliberately generous. Obstacles are sparse, and confirming one
	 * that was actually used is worth far more than the remote chance of crediting its
	 * neighbour.
	 */
	private static final int CONFIRM_TOLERANCE = 3;

	/**
	 * How sure we are about one particular obstacle in the world.
	 *
	 * <p>Three states because they call for three different responses, and lumping the
	 * middle one in either direction loses the thing worth knowing: an obstacle golems are
	 * already using on inferred data is exactly the obstacle worth walking over to and
	 * using once.
	 */
	enum Status
	{
		/** Seen being used, here, on this object. Golems copy what was observed. */
		CONFIRMED,

		/** Golems will use it, on data generalised from elsewhere. Worth confirming. */
		INFERRED,

		/** No usable animation, so golems route around it entirely. */
		UNUSABLE
	}

	/**
	 * What the player has been seen doing, by object id.
	 *
	 * <p>Outranks everything shipped. This is the live game on the player's own client, in
	 * the current revision, which is a better authority than anything measured months ago
	 * on a machine somewhere else.
	 */
	private final Map<Integer, Learned> learned = new LinkedHashMap<>();

	/**
	 * The individual obstacles the player has personally used, by object id.
	 *
	 * <p>Kept apart from {@link #learned} because they answer different questions. What a
	 * <i>kind</i> of object does is learned once and applies everywhere, which is what makes
	 * one lap of an agility course worth anything. Which <i>particular</i> ladder somebody
	 * has actually stood on is only ever used to colour it in.
	 */
	private final Map<Integer, List<int[]>> confirmed = new LinkedHashMap<>();

	/**
	 * Where an obstacle actually took the player, by the tile they used it from.
	 *
	 * <p>The other half of learning, and the half that was being thrown away. An animation
	 * alone cannot make an obstacle usable: something has to say where it comes out, and
	 * for most of the game nothing does. Wyrmscraig's stile and staircases have no row in
	 * any published table, so golems walked past them no matter how well the plugin had
	 * been taught what using one looks like.
	 *
	 * <p>Every sighting already carried this. A player who uses an obstacle demonstrates
	 * both what it looks like and where it goes, in the same moment, for free.
	 *
	 * <p>Keyed by object <i>and</i> origin because one kind of object is many obstacles: a
	 * ladder id appears two thousand times in the game and each one comes out somewhere
	 * different. That is the opposite of the animation, which is learned once per kind and
	 * applies everywhere.
	 */
	//
	// Insertion-ordered, as every map here is. A HashMap's order shifts as keys are added,
	// and routes are turned into transports in that order — so each new sighting quietly
	// renumbered every learned transport, and anything held against the old numbers was
	// now held against a different obstacle.
	private final Map<String, List<int[]>> routes = new LinkedHashMap<>();

	/**
	 * The recent recordings of each kind of obstacle.
	 *
	 * <p>By object rather than by place, like the animation and unlike the route: what a
	 * traversal looks like is a property of the obstacle, and is stored along its own axis
	 * so one recording serves every copy of it whichever way it faces.
	 *
	 * <p>Several are kept, not one. Keeping only the latest let a single bad traversal
	 * replace good ones: a player who went through the cathedral door and walked on
	 * diagonally before stopping overwrote three clean recordings with one that drifted a
	 * tile sideways, and every golem then performed the drift. With several in hand, the
	 * one performed is the one the others most agree with — see {@link #consensus}.
	 */
	private final Map<Integer, List<MotionCurve>> curves = new LinkedHashMap<>();

	/**
	 * The line each obstacle moves the player along, by object id, as counts of each
	 * direction seen — "0,1" for a stile crossed north or south, at any length.
	 *
	 * <p>Counted rather than taken from the first sighting so one odd traversal cannot fix
	 * an obstacle's line for good. Routes are held to the most common one; see
	 * {@link RouteGeometry}.
	 */
	private final Map<Integer, Map<String, Integer>> lines = new LinkedHashMap<>();

	/**
	 * Which saved routes may be offered at all, as {objectId, fromX, fromY, fromPlane, toX,
	 * toY, toPlane}. Set by the plugin, which has the map and the obstacle index.
	 *
	 * <p>So that what was learned before a rule existed is held to it without anybody
	 * finding and deleting it: trees and bank booths recorded as obstacles, routes stored in
	 * the coordinates of an instance that no longer exists.
	 */
	@lombok.Setter
	private java.util.function.Predicate<int[]> routeFilter;

	/** The recording performed for each object, chosen from {@link #curves}. */
	private final Map<Integer, MotionCurve> chosenCurves = new HashMap<>();

	/**
	 * Recent {ticks, delay, span} per object, for the same reason.
	 *
	 * <p>Only in memory. The saved value is itself the consensus of a previous session and
	 * seeds the history when it is next added to.
	 */
	private final Map<Integer, List<int[]>> timings = new HashMap<>();

	/** Recordings kept per object. Odd, so there is always a middle. */
	private static final int CURVES_KEPT = 5;

	/**
	 * How far two arrivals can differ and still count as the same route.
	 *
	 * <p>Small, because a stepping stone in the middle of a line leads two ways and those
	 * must stay apart. Two tiles separates "landed slightly differently" from "went
	 * somewhere else".
	 */
	private static final int DESTINATION_TOLERANCE = 2;

	/**
	 * Bumped whenever anything here changes.
	 *
	 * <p>Exists for the highlight overlay, which caches its tiles and would otherwise have
	 * no way to notice that an obstacle the player just used has changed colour. Using an
	 * obstacle leaves you standing next to it, so a cache keyed only on the player moving
	 * never refreshes at the one moment it matters.
	 */
	@lombok.Getter
	private int version;

	/** One obstacle as observed here, and how sure of it we are. */
	static final class Learned
	{
		final int[] clips;
		final int ticks;

		/** Cycles of stillness before the movement, and how long the movement lasts. */
		final int moveDelay;
		final int moveSpan;

		int sightings;

		Learned(int[] clips, int ticks, int sightings, int moveDelay, int moveSpan)
		{
			this.clips = clips;
			this.ticks = ticks;
			this.sightings = sightings;
			this.moveDelay = moveDelay;
			this.moveSpan = moveSpan;
		}

		boolean unlocked()
		{
			return sightings >= SIGHTINGS_TO_UNLOCK;
		}
	}

	// ------------------------------------------------------------------- the policy

	/**
	 * True if a golem may use this transport.
	 *
	 * <p>The shipped defaults below are the archetypes whose animation has either been
	 * measured or is corroborated well enough to act on. The rest are locked until somebody
	 * is seen using one — which is the whole point, and is why this list is deliberately
	 * short rather than optimistic.
	 */
	boolean isUnlocked(GolemTransport transport)
	{
		if (isLearned(transport.getObjectId()))
		{
			return true;
		}
		if (MeasuredShortcuts.animationFor(transport.getObjectId()) != -1)
		{
			// Somebody stood in front of this exact object and watched it.
			return true;
		}
		return shippedConfidence(transport);
	}

	/**
	 * Whether a golem may traverse this at all, on the shipped data alone.
	 *
	 * <p>Locked means a golem routes around the obstacle as though it were not there. That
	 * is reserved for the cases where using it without knowing the animation would look
	 * broken rather than merely plain — a leap across open water, a walk along a tightrope.
	 * A golem that slides across a chasm in its walking pose is worse than a golem that
	 * never went near the chasm.
	 *
	 * <ul>
	 *   <li><b>Jumps longer than two tiles.</b> Only the short hop has been measured; the
	 *       thresholds above it were invented, and there is reason to think the split does
	 *       not exist at all.</li>
	 *   <li><b>Balances, tightropes, squeezes and stiles.</b> Plausible names, no
	 *       measurements, and the one external source covering stiles disagrees with what
	 *       we ship.</li>
	 * </ul>
	 *
	 * <p>Ladders going down are deliberately <i>not</i> locked, despite the animation being
	 * disputed. Blocking one direction of a two-way transport is worse than a plain-looking
	 * one: a golem that can climb a ladder but not descend it is stranded upstairs, and the
	 * watchdog would spend the rest of the session hauling it out. It traverses silently
	 * instead — see {@link #clipsFor} — which is what a door already does and what eight
	 * thousand unclassified rows already do.
	 */
	private boolean shippedConfidence(GolemTransport transport)
	{
		switch (transport.getArchetype())
		{
			case GolemTransport.ARCHETYPE_JUMP:
			{
				int span = transport.travelDistance();
				return span >= 0 && span <= 2;
			}

			case GolemTransport.ARCHETYPE_BALANCE:
			case GolemTransport.ARCHETYPE_TIGHTROPE:
			case GolemTransport.ARCHETYPE_SQUEEZE:
			case GolemTransport.ARCHETYPE_STILE:
				return false;

			case GolemTransport.ARCHETYPE_DOOR:
				// A golem cannot open a door. Opening one changes a real object that every
				// other player in the world can see, and these golems exist on a single
				// client and must never touch anything shared. The transport tables carry
				// door rows because a *player* can open them, which is right for a
				// pathfinder and wrong here.
				//
				// A golem may still walk through a doorway that is already open: that is
				// ordinary walking on ground the collision map says is clear, and needs no
				// transport at all.
				return false;

			default:
				// Doors, gangplanks, ditches, climbs, ladders and the great mass of
				// unclassified rows. Either measured, corroborated, or animating nothing.
				return true;
		}
	}

	/**
	 * True if the shipped archetype's animation is trustworthy enough to play.
	 *
	 * <p>Separate from {@link #shippedConfidence} on purpose, and the distinction is the
	 * heart of this class: <i>may the golem go through here</i> is a different question
	 * from <i>do we know what it looks like</i>. Where the answer to the second is no, the
	 * golem goes through playing nothing rather than performing an invented animation.
	 */
	private boolean shippedAnimationKnown(GolemTransport transport)
	{
		switch (transport.getArchetype())
		{
			case GolemTransport.ARCHETYPE_NONE:
			case GolemTransport.ARCHETYPE_DOOR:
			case GolemTransport.ARCHETYPE_GANGPLANK:
				// Nothing is played on the golem, so nothing can be played wrongly.
				return true;

			case GolemTransport.ARCHETYPE_DITCH:
			case GolemTransport.ARCHETYPE_CLIMB_OVER:
			case GolemTransport.ARCHETYPE_CLIMB:
				return true;

			case GolemTransport.ARCHETYPE_LADDER:
				// Up is 828 and agreed everywhere. Down is 833 here, never measured, and
				// three independent reimplementations of the server say 827 instead — so
				// it is played silently until somebody is seen doing it.
				return transport.getToPlane() >= transport.getFromPlane();

			case GolemTransport.ARCHETYPE_JUMP:
			{
				int span = transport.travelDistance();
				return span >= 0 && span <= 2;
			}

			default:
				return false;
		}
	}

	/**
	 * How sure we are about one obstacle standing at one place in the world.
	 *
	 * <p>Drives the highlight overlay and nothing else. Golem behaviour is decided by
	 * {@link #isUnlocked} and {@link #clipsFor}, which do not care where the obstacle is.
	 */
	/**
	 * How sure we are about an obstacle known only by what and where it is.
	 *
	 * <p>This is the overlay's question. It works from the obstacle index, which holds
	 * every obstacle in the game and nothing about where any of them lead, so there may be
	 * no transport row for this thing at all — and that is itself the answer: an obstacle
	 * the plugin has never heard of is one golems cannot use.
	 */
	Status statusAt(int objectId, int x, int y, int plane, int sizeX, int sizeY,
		int archetype)
	{
		// Knowing the animation is not enough to use an obstacle. Something also has to
		// say where it comes out, and for a great many obstacles nothing does: the stile
		// and the staircases on Wyrmscraig have no transport row anywhere, so a golem
		// cannot take them however well it has been taught what they look like.
		//
		// This check comes first because the colour has to mean "will a golem use this".
		// It did not, and an obstacle that had been used repeatedly showed green while
		// golems walked past it all day.
		boolean routed = archetype >= 0 || hasRoute(objectId, x, y, plane, sizeX, sizeY);
		if (!routed)
		{
			return Status.UNUSABLE;
		}

		if (!usableArchetype(archetype) && !isLearned(objectId)
			&& MeasuredShortcuts.animationFor(objectId) == -1)
		{
			return Status.UNUSABLE;
		}

		// Confirmed means the whole thing is known here: what it looks like, and where it
		// goes. Either half missing is "will use it, on generalised data".
		if (isLearned(objectId)
			&& isConfirmedAt(objectId, x, y, plane, sizeX, sizeY)
			&& hasRoute(objectId, x, y, plane, sizeX, sizeY))
		{
			return Status.CONFIRMED;
		}
		return Status.INFERRED;
	}

	/**
	 * Everything known about one obstacle, in one line, for reading in game.
	 *
	 * <p>Exists because "it is orange and I do not know why" was costing whole testing
	 * sessions. Four things decide the colour and any one of them can be the missing piece:
	 * the animation, the confirmation that it was this obstacle, the route, and whether
	 * anything in the table describes it at all. Guessing which from the colour alone is
	 * what turned several fixes into the next bug.
	 */
	String explain(int objectId, int x, int y, int plane, int sizeX, int sizeY, int archetype)
	{
		Learned known = learned.get(objectId);
		StringBuilder sb = new StringBuilder();
		sb.append(statusAt(objectId, x, y, plane, sizeX, sizeY, archetype)).append(" | ");

		if (known == null)
		{
			sb.append("anim: never seen");
		}
		else
		{
			sb.append("anim: ");
			if (known.clips.length == 0)
			{
				sb.append("none(silent)");
			}
			else
			{
				for (int i = 0; i < known.clips.length; i++)
				{
					sb.append(i == 0 ? "" : ",").append(known.clips[i]);
				}
			}
			sb.append(" ").append(known.ticks).append("t x").append(known.sightings)
				.append(known.unlocked() ? "" : " (needs another)");
		}

		sb.append(" | route: ")
			.append(hasRoute(objectId, x, y, plane, sizeX, sizeY) ? "known" : "unknown");
		sb.append(" | here: ")
			.append(isConfirmedAt(objectId, x, y, plane, sizeX, sizeY) ? "yes" : "no");
		sb.append(" | table: ").append(archetype < 0 ? "no rows" : "arch " + archetype);
		return sb.toString();
	}

	/**
	 * Whether golems will take this kind of obstacle on shipped data alone.
	 *
	 * <p>The same set {@link #shippedConfidence} locks, minus the parts that need a
	 * transport row to decide. A ladder is usable in both directions here even though only
	 * the upward clip is trusted, because it is used either way — it simply plays nothing
	 * going down until somebody is watched doing it.
	 */
	private boolean usableArchetype(int archetype)
	{
		switch (archetype)
		{
			case GolemTransport.ARCHETYPE_BALANCE:
			case GolemTransport.ARCHETYPE_TIGHTROPE:
			case GolemTransport.ARCHETYPE_SQUEEZE:
			case GolemTransport.ARCHETYPE_STILE:
			case GolemTransport.ARCHETYPE_DOOR:
				return false;
			default:
				return true;
		}
	}

	/**
	 * Whether an archetype alone is enough, where no transport row is to hand.
	 *
	 * <p>Coarser than {@link #shippedAnimationKnown}, which can also see the plane change
	 * and the distance. Ladders come out unknown here rather than "known going up", because
	 * without a row there is no direction to ask about.
	 */
	private boolean shippedAnimationForArchetype(int archetype)
	{
		switch (archetype)
		{
			case GolemTransport.ARCHETYPE_GANGPLANK:
			case GolemTransport.ARCHETYPE_DITCH:
			case GolemTransport.ARCHETYPE_CLIMB_OVER:
			case GolemTransport.ARCHETYPE_CLIMB:
				return true;
			default:
				return false;
		}
	}

	Status statusAt(GolemTransport transport)
	{
		if (!isUnlocked(transport))
		{
			return Status.UNUSABLE;
		}
		// Confirmed means two things at once: this kind of object has been watched enough
		// to be trusted, and this particular one is the one that was watched. A single
		// sighting is neither — it has not cleared the noise bar, so calling it confirmed
		// would put a green outline on an obstacle whose animation is not yet being used.
		Learned known = learned.get(transport.getObjectId());
		if (known != null && known.unlocked()
			&& isConfirmedAt(transport.getObjectId(), transport.getFromX(),
				transport.getFromY(), transport.getFromPlane(), 1, 1))
		{
			return Status.CONFIRMED;
		}
		return Status.INFERRED;
	}

	private boolean isConfirmedAt(int objectId, int x, int y, int plane,
		int sizeX, int sizeY)
	{
		List<int[]> places = confirmed.get(objectId);
		if (places == null)
		{
			return false;
		}
		for (int[] at : places)
		{
			if (at[2] != plane)
			{
				continue;
			}
			// Distance to the nearest tile the object occupies, not to its corner.
			int dx = Math.max(Math.max(x - at[0], at[0] - (x + sizeX - 1)), 0);
			int dy = Math.max(Math.max(y - at[1], at[1] - (y + sizeY - 1)), 0);
			if (dx <= CONFIRM_TOLERANCE && dy <= CONFIRM_TOLERANCE)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Records where an obstacle led, when that is worth anything.
	 *
	 * <p>Instances are refused. They are rebuilt with fresh coordinates every time, so a
	 * destination recorded inside one describes a room that no longer exists — the church
	 * pew gave three different exits on three consecutive uses.
	 */
	private void noteRoute(ObstacleSighting sighting)
	{
		if (sighting.instance || !sighting.moved())
		{
			return;
		}

		String key = routeKey(sighting.objectId, sighting.fromX, sighting.fromY,
			sighting.fromPlane);
		List<int[]> here = routes.computeIfAbsent(key, k -> new ArrayList<>());

		int flags = sighting.toInstance && !sighting.fromInstance ? GolemTransport.INTO_INSTANCE
			: sighting.fromInstance && !sighting.toInstance ? GolemTransport.OUT_OF_INSTANCE : 0;

		for (int[] seen : here)
		{
			// Near enough is the same route. Where a traversal puts you down is not exact
			// — a rock climb landed on 2555 one time and 2556 the next — and demanding the
			// same tile every time meant the count went back to one after every use, so
			// the obstacle could never reach two however many times it was climbed.
			if (seen[2] == sighting.toPlane
				&& Math.abs(seen[0] - sighting.toX) <= DESTINATION_TOLERANCE
				&& Math.abs(seen[1] - sighting.toY) <= DESTINATION_TOLERANCE)
			{
				// The same route, and the newest sighting's end is the one kept.
				//
				// Keeping the first end let a route be taught once under old rules and never
				// corrected: the way out of the stile's pen was saved ending on the stile
				// itself, every later crossing that ended correctly on the far side only added
				// to its count, and golems could not use a route that set them down on a tile
				// with nowhere to go. Hundreds went in and none came out. A route's ends are
				// decided by the obstacle, so the latest decision is the best one.
				seen[0] = sighting.toX;
				seen[1] = sighting.toY;
				seen[3]++;
				seen[4] = flags;
				return;
			}
		}

		// Genuinely somewhere else, so it is a second route from this tile rather than a
		// contradiction of the first. The middle of a line of stepping stones really does
		// lead two ways, and both are true.
		here.add(new int[]{sighting.toX, sighting.toY, sighting.toPlane, 1, flags});
	}

	/** How far apart two origins of the same move can be and still be one obstacle used from two spots. */
	private static final int NEIGHBOUR_ORIGIN = 2;

	/**
	 * Sightings behind a route, counting its neighbours.
	 *
	 * <p>A church pew is two tiles long and a player climbs it from whichever end is nearer,
	 * so its routes — the same hop, a tile apart — were each seen once and none ever reached
	 * the two sightings a route needs. Golems never used the pew however often the player did.
	 * Routes of one object that make the same move from origins a tile or two apart are the
	 * same obstacle used from a different spot, and vouch for each other.
	 *
	 * @param to {toX, toY, toPlane, count, flags}
	 */
	private int sightingsFor(int objectId, int fromX, int fromY, int fromPlane, int[] to)
	{
		int total = 0;
		for (Map.Entry<String, List<int[]>> e : routes.entrySet())
		{
			String[] key = e.getKey().split(",");
			int otherX = Integer.parseInt(key[1]);
			int otherY = Integer.parseInt(key[2]);
			if (Integer.parseInt(key[0]) != objectId || Integer.parseInt(key[3]) != fromPlane
				|| Math.abs(otherX - fromX) > NEIGHBOUR_ORIGIN || Math.abs(otherY - fromY) > NEIGHBOUR_ORIGIN)
			{
				continue;
			}
			for (int[] other : e.getValue())
			{
				if (other[2] == to[2] && other[0] - otherX == to[0] - fromX && other[1] - otherY == to[1] - fromY)
				{
					total += other[3];
				}
			}
		}
		return total;
	}

	/** Every obstacle the player has used and where, as {objectId, x, y, plane}. */
	List<int[]> confirmedPlaces()
	{
		List<int[]> out = new ArrayList<>();
		for (Map.Entry<Integer, List<int[]>> e : confirmed.entrySet())
		{
			for (int[] at : e.getValue())
			{
				out.add(new int[]{e.getKey(), at[0], at[1], at[2]});
			}
		}
		return out;
	}

	private static String routeKey(int objectId, int x, int y, int plane)
	{
		return objectId + "," + x + "," + y + "," + plane;
	}

	/**
	 * Every route seen often enough to act on, as {objectId, fromX, fromY, fromPlane,
	 * toX, toY, toPlane, ticks}.
	 *
	 * <p>The tick count matters as much as the coordinates. A learned transport used to be
	 * created with a duration of one, which is how watching a player climb a rockslide made
	 * golems stop climbing it: the shipped row said four ticks and was walked, the learned
	 * row said one and was compressed into a single tick of gliding. Being taught an
	 * obstacle made the golems worse at it.
	 */
	List<int[]> learnedRoutes()
	{
		List<int[]> out = new ArrayList<>();
		for (Map.Entry<String, List<int[]>> e : routes.entrySet())
		{
			String[] from = e.getKey().split(",");
			for (int[] to : e.getValue())
			{
				if (to[3] < SIGHTINGS_TO_UNLOCK && sightingsFor(Integer.parseInt(from[0]), Integer.parseInt(from[1]),
					Integer.parseInt(from[2]), Integer.parseInt(from[3]), to) < SIGHTINGS_TO_UNLOCK)
				{
					continue;
				}
				// And a route saved before its obstacle's line was known is held to it now,
				// so the diagonal stile routes already in a profile stop being used as soon
				// as the stile is crossed once — nobody has to find and delete them.
				if (!followsObjectLine(Integer.parseInt(from[0]), Integer.parseInt(from[1]),
					Integer.parseInt(from[2]), Integer.parseInt(from[3]), to))
				{
					continue;
				}
				Learned known = learned.get(Integer.parseInt(from[0]));
				int[] route = {
					Integer.parseInt(from[0]), Integer.parseInt(from[1]),
					Integer.parseInt(from[2]), Integer.parseInt(from[3]),
					to[0], to[1], to[2],
					known == null ? 1 : Math.max(1, known.ticks),
					to[4],
				};
				if (routeFilter == null || routeFilter.test(route))
				{
					out.add(route);
				}
			}
		}
		return out;
	}

	/** True if the player has shown us where this obstacle goes from near this tile. */
	boolean hasRoute(int objectId, int x, int y, int plane, int sizeX, int sizeY)
	{
		for (Map.Entry<String, List<int[]>> e : routes.entrySet())
		{
			String[] from = e.getKey().split(",");
			// Which obstacle first, because it is cheap and nearly always no: the overlay asks
			// this of every obstacle in range, and counting a route's sightings is not free.
			if (Integer.parseInt(from[0]) != objectId
				|| Integer.parseInt(from[3]) != plane)
			{
				continue;
			}
			int fx = Integer.parseInt(from[1]);
			int fy = Integer.parseInt(from[2]);
			int dx = Math.max(Math.max(x - fx, fx - (x + sizeX - 1)), 0);
			int dy = Math.max(Math.max(y - fy, fy - (y + sizeY - 1)), 0);
			if (dx > CONFIRM_TOLERANCE || dy > CONFIRM_TOLERANCE)
			{
				continue;
			}
			for (int[] to : e.getValue())
			{
				if ((to[3] >= SIGHTINGS_TO_UNLOCK
						|| sightingsFor(objectId, fx, fy, plane, to) >= SIGHTINGS_TO_UNLOCK)
					&& followsObjectLine(objectId, fx, fy, plane, to)
					&& (routeFilter == null || routeFilter.test(new int[]{
						objectId, fx, fy, plane, to[0], to[1], to[2]})))
				{
					return true;
				}
			}
		}
		return false;
	}

	private void noteLine(ObstacleSighting sighting)
	{
		if (sighting.fromPlane != sighting.toPlane || !RouteGeometry.local(sighting.lineX, sighting.lineY))
		{
			return;
		}
		int[] direction = RouteGeometry.canonical(sighting.lineX, sighting.lineY);
		lines.computeIfAbsent(sighting.objectId, k -> new LinkedHashMap<>())
			.merge(direction[0] + "," + direction[1], 1, Integer::sum);
	}

	/** The line this obstacle most often moves players along, or null if none is known. */
	int[] lineOf(int objectId)
	{
		Map<String, Integer> seen = lines.get(objectId);
		if (seen == null || seen.isEmpty())
		{
			return null;
		}
		String best = null;
		for (Map.Entry<String, Integer> e : seen.entrySet())
		{
			if (best == null || e.getValue() > seen.get(best))
			{
				best = e.getKey();
			}
		}
		String[] parts = best.split(",");
		return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
	}

	/** Whether this sighting's route lies along its own movement and its obstacle's line. */
	private boolean followsLine(ObstacleSighting sighting)
	{
		if (sighting.fromPlane != sighting.toPlane)
		{
			return true;
		}
		int routeX = sighting.toX - sighting.fromX;
		int routeY = sighting.toY - sighting.fromY;
		int[] line = lineOf(sighting.objectId);
		return RouteGeometry.follows(routeX, routeY, sighting.lineX, sighting.lineY)
			&& (line == null || RouteGeometry.follows(routeX, routeY, line[0], line[1]));
	}

	/** Whether a stored route, {toX, toY, toPlane, count}, lies along its obstacle's line. */
	private boolean followsObjectLine(int objectId, int fromX, int fromY, int fromPlane, int[] to)
	{
		int[] line = lineOf(objectId);
		return line == null || to[2] != fromPlane
			|| RouteGeometry.follows(to[0] - fromX, to[1] - fromY, line[0], line[1]);
	}

	/** {@code objectId=x,y,count|x,y,count;...} */
	String serialiseLines()
	{
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, Map<String, Integer>> e : lines.entrySet())
		{
			if (sb.length() > 0)
			{
				sb.append(';');
			}
			sb.append(e.getKey()).append('=');
			boolean first = true;
			for (Map.Entry<String, Integer> direction : e.getValue().entrySet())
			{
				sb.append(first ? "" : "|").append(direction.getKey()).append(',')
					.append(direction.getValue());
				first = false;
			}
		}
		return sb.toString();
	}

	void deserialiseLines(String saved)
	{
		lines.clear();
		version++;
		if (saved == null || saved.isEmpty())
		{
			return;
		}
		for (String entry : saved.split(";"))
		{
			try
			{
				int split = entry.indexOf('=');
				Map<String, Integer> seen = new LinkedHashMap<>();
				for (String direction : entry.substring(split + 1).split("\\|"))
				{
					String[] parts = direction.split(",");
					seen.put(parts[0] + "," + parts[1], Integer.parseInt(parts[2]));
				}
				lines.put(Integer.parseInt(entry.substring(0, split)), seen);
			}
			catch (RuntimeException e)
			{
				log.debug("Skipping unreadable line: {}", entry);
			}
		}
	}

	String serialiseCurves()
	{
		// One entry per recording, oldest first, so an object with several simply repeats its
		// id. Files written when only one was kept read back unchanged.
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, List<MotionCurve>> e : curves.entrySet())
		{
			for (MotionCurve curve : e.getValue())
			{
				if (sb.length() > 0)
				{
					sb.append(';');
				}
				sb.append(e.getKey()).append('=').append(curve.serialise());
			}
		}
		return sb.toString();
	}

	void deserialiseCurves(String saved)
	{
		curves.clear();
		chosenCurves.clear();
		version++;
		if (saved == null || saved.isEmpty())
		{
			return;
		}
		for (String entry : saved.split(";"))
		{
			int split = entry.indexOf('=');
			if (split <= 0)
			{
				continue;
			}
			try
			{
				MotionCurve curve = MotionCurve.parse(entry.substring(split + 1));
				if (curve != null)
				{
					addCurve(Integer.parseInt(entry.substring(0, split)), curve);
				}
			}
			catch (RuntimeException e)
			{
				log.debug("Skipping unreadable curve: {}", entry.substring(0, split));
			}
		}
		log.debug("Restored recorded motions for {} obstacles", curves.size());
	}

	private void addCurve(int objectId, MotionCurve curve)
	{
		List<MotionCurve> kept = curves.computeIfAbsent(objectId, k -> new ArrayList<>());
		// A recording that knows which way the player faced supersedes every one that does
		// not. Otherwise the older recordings outvote it and the golem keeps facing forwards
		// down the rockslide for as long as they are kept.
		if (curve.hasFacing())
		{
			kept.removeIf(old -> !old.hasFacing());
		}
		// Likewise the player's keyframes: an older recording without them would play the
		// clip at its authored speed and hop before the jump.
		if (curve.hasKeyframes())
		{
			kept.removeIf(old -> !old.hasKeyframes());
		}
		kept.add(curve);
		while (kept.size() > CURVES_KEPT)
		{
			kept.remove(0);
		}
		chosenCurves.put(objectId, consensus(kept));
	}

	/**
	 * The recording the others most agree with.
	 *
	 * <p>A medoid rather than an average: averaging two recordings of a door produces a
	 * glide neither of them contains. Recordings are compared on what a person watching
	 * would notice — how long, how far, how far off the line, and how many separate
	 * movements.
	 *
	 * <p>Two recordings cannot outvote each other, so there the more ordinary one wins: the
	 * one that stays on its axis and moves once. Ties go to the newer.
	 */
	static MotionCurve consensus(List<MotionCurve> kept)
	{
		MotionCurve best = null;
		double bestScore = Double.MAX_VALUE;
		for (int i = kept.size() - 1; i >= 0; i--)
		{
			MotionCurve candidate = kept.get(i);
			double score = kept.size() < 3 ? oddness(candidate) : 0;
			for (MotionCurve other : kept)
			{
				if (other != candidate)
				{
					score += difference(candidate, other);
				}
			}
			if (score < bestScore - 1e-6)
			{
				best = candidate;
				bestScore = score;
			}
		}
		return best;
	}

	private static double difference(MotionCurve a, MotionCurve b)
	{
		return Math.abs(a.cycles() - b.cycles()) / (double) Math.max(1, Math.max(a.cycles(), b.cycles()))
			+ Math.abs(a.reach() - b.reach()) / (double) Golem.TILE
			+ Math.abs(a.maxLateral() - b.maxLateral()) / (double) Golem.TILE
			+ Math.abs(a.bursts() - b.bursts());
	}

	private static double oddness(MotionCurve curve)
	{
		return curve.maxLateral() / (double) Golem.TILE + Math.max(0, curve.bursts() - 1);
	}

	/** The middle value of one column of {@link #timings}. */
	private static int median(List<int[]> rows, int column)
	{
		int[] values = new int[rows.size()];
		for (int i = 0; i < values.length; i++)
		{
			values[i] = rows.get(i)[column];
		}
		java.util.Arrays.sort(values);
		return values[values.length / 2];
	}

	String serialiseRoutes()
	{
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, List<int[]>> e : routes.entrySet())
		{
			for (int[] to : e.getValue())
			{
				if (sb.length() > 0)
				{
					sb.append(';');
				}
				sb.append(e.getKey()).append('>').append(to[0]).append(',').append(to[1])
					.append(',').append(to[2]).append(',').append(to[3]);
				// Only where there is one, so a profile without instances saves as it always did.
				if (to[4] != 0)
				{
					sb.append(',').append(to[4]);
				}
			}
		}
		return sb.toString();
	}

	void deserialiseRoutes(String saved)
	{
		routes.clear();
		version++;
		if (saved == null || saved.isEmpty())
		{
			return;
		}
		for (String entry : saved.split(";"))
		{
			try
			{
				String[] halves = entry.split(">");
				String[] to = halves[1].split(",");
				routes.computeIfAbsent(halves[0], k -> new ArrayList<>())
					.add(new int[]{Integer.parseInt(to[0]), Integer.parseInt(to[1]),
						Integer.parseInt(to[2]), Integer.parseInt(to[3]),
						to.length > 4 ? Integer.parseInt(to[4]) : 0});
			}
			catch (RuntimeException e)
			{
				log.debug("Skipping unreadable route: {}", entry);
			}
		}
	}

	private void noteConfirmed(ObstacleSighting sighting)
	{
		List<int[]> places = confirmed.computeIfAbsent(sighting.objectId, k -> new ArrayList<>());
		if (isConfirmedAt(sighting.objectId, sighting.fromX, sighting.fromY,
			sighting.fromPlane, 1, 1))
		{
			return;
		}
		places.add(new int[]{sighting.fromX, sighting.fromY, sighting.fromPlane});
	}

	// ------------------------------------------------------------- what to play

	/**
	 * The clips a golem should play for this transport, best source first.
	 *
	 * <p>What the player was seen doing beats what was shipped, which beats the archetype's
	 * guess. A sighting counts here as soon as it exists, even before it has unlocked the
	 * obstacle — if the golem is allowed to use it at all, it may as well use the best
	 * animation available for it.
	 */
	/**
	 * True if the player has been watched using this obstacle, whatever the outcome.
	 *
	 * <p>Distinguishes "seen, and it plays nothing" from "never seen", which an empty clip
	 * set cannot do on its own.
	 */
	boolean isLearned(int objectId)
	{
		Learned known = learned.get(objectId);
		return known != null && known.unlocked();
	}

	int[] clipsFor(GolemTransport transport)
	{
		// Only once it has cleared the same bar everything else clears. A single sighting
		// can be a player who was attacked between clicking a ladder and reaching it, and
		// handing that animation straight to every golem in the game is exactly the kind
		// of confident wrongness this class exists to stop.
		Learned known = learned.get(transport.getObjectId());
		if (known != null && known.unlocked())
		{
			// Including when it is empty: that is a measured "plays nothing", and playing
			// nothing is the correct thing to do with it.
			return known.clips;
		}
		if (MeasuredShortcuts.animationFor(transport.getObjectId()) != -1
			|| shippedAnimationKnown(transport))
		{
			return transport.animations();
		}

		// Not known. The golem goes through in its ordinary pose rather than performing
		// something invented — which is the same thing it already does for a door, and is
		// the failure worth choosing: plain, not wrong.
		return NOTHING;
	}

	private static final int[] NOTHING = new int[0];

	/** The recorded motion for this obstacle, or null if nobody has been watched doing it. */
	MotionCurve curveFor(GolemTransport transport)
	{
		return chosenCurves.get(transport.getObjectId());
	}

	/** Cycles a golem should stand still before moving, or 0 if unknown. */
	int moveDelayFor(GolemTransport transport)
	{
		Learned known = learned.get(transport.getObjectId());
		return known != null && known.unlocked() ? known.moveDelay : 0;
	}

	/** Cycles the movement itself should take, or 0 if unknown. */
	int moveSpanFor(GolemTransport transport)
	{
		Learned known = learned.get(transport.getObjectId());
		return known != null && known.unlocked() ? known.moveSpan : 0;
	}


	/**
	 * How long the traversal should take, or 0 to fall back to the clip and the table.
	 *
	 * <p>A learned duration is the time from the first clip starting to the player standing
	 * still, and for a single-clip motion that includes the pause afterwards. Applying it
	 * to a hop stretched a 38-cycle jump across 60 cycles: the clip finished early and
	 * started again, so golems hopped twice while gliding over at two thirds speed.
	 *
	 * <p>So a single clip is left to run at its own length. Only a motion built in parts
	 * takes the observed duration, because there the middle clip is a loop and stretching
	 * it is what it is for.
	 */
	int ticksFor(GolemTransport transport)
	{
		Learned known = learned.get(transport.getObjectId());
		if (known != null && known.unlocked() && known.ticks > 0 && known.clips.length != 1)
		{
			return known.ticks;
		}
		return MeasuredShortcuts.ticksFor(transport.getObjectId());
	}

	// ---------------------------------------------------------------- learning

	/**
	 * Records one sighting, and reports whether it has just unlocked the obstacle.
	 *
	 * <p>A sighting that disagrees with what was already learned resets the count rather
	 * than averaging. Two different answers mean one of them was noise, and starting again
	 * is the honest response — averaging two animation ids produces a third that is not an
	 * animation at all.
	 */
	boolean record(ObstacleSighting sighting)
	{
		// An empty clip set is not a failed observation. It is the observation that this
		// obstacle animates nobody — which is what a staircase does, and what more than
		// fifteen hundred rows of the network do. Discarding it made every one of them
		// permanently unlearnable, because the only evidence they will ever produce is the
		// absence of an animation.
		version++;
		noteConfirmed(sighting);
		noteLine(sighting);

		// Only a route that lies along the obstacle is learned — along this traversal's own
		// movement, and along the line this obstacle is known to move players. A route off
		// the line is performed off the line: that was the stile crossed on a slant. Its
		// recording goes with it, having been captured along the same wrong axis.
		//
		// The animation is still learned. What the player played is not in question, only
		// where the route's ends were taken from.
		if (followsLine(sighting))
		{
			noteRoute(sighting);
			if (sighting.curve != null && !sighting.curve.isEmpty())
			{
				addCurve(sighting.objectId, sighting.curve);
			}
		}
		else
		{
			log.debug("Refused route off the line of {}: {}", sighting.objectId, sighting);
		}

		// One crossing is not one use.
		//
		// A sighting covers everything between the click and the arrival, and a stepping
		// stone crossing is several hops from a single click — three stones came back as
		// clips 741,741,741 over six ticks. But the transport table stores a stone
		// crossing as one row per hop, so that whole crossing was then replayed at every
		// individual stone: three jumps and six ticks to cross one two-tile gap, which is
		// a golem hopping on the spot and sliding through the air at a third of the speed.
		//
		// A run of the same clip is one motion performed repeatedly, so it collapses to
		// that motion and its share of the time. Genuinely multi-part traversals — take
		// hold, haul, step off — have different ids and are left alone.
		int[] clips = collapseRepeats(sighting.clips);
		int ticks = clips.length == sighting.clips.length || clips.length == 0
			? sighting.ticks
			: Math.max(1, Math.round(sighting.ticks / (float) sighting.clips.length));

		Learned existing = learned.get(sighting.objectId);
		if (existing == null || !sameClips(existing.clips, clips))
		{
			List<int[]> fresh = new ArrayList<>();
			fresh.add(new int[]{ticks, sighting.moveDelay, sighting.moveSpan});
			timings.put(sighting.objectId, fresh);
			learned.put(sighting.objectId,
				new Learned(clips, ticks, 1, sighting.moveDelay, sighting.moveSpan));
			return false;
		}

		boolean wasUnlocked = existing.unlocked();
		existing.sightings++;

		// The middle of the recent sightings, not the latest one.
		//
		// Taking the latest let one unusual traversal rewrite what every golem does: a
		// door that takes two ticks was stored as three because the last player through it
		// kept walking. What is already saved seeds the history, so a restart does not
		// hand the next sighting the same power.
		List<int[]> history = timings.computeIfAbsent(sighting.objectId, k ->
		{
			List<int[]> seeded = new ArrayList<>();
			seeded.add(new int[]{existing.ticks, existing.moveDelay, existing.moveSpan});
			return seeded;
		});
		history.add(new int[]{ticks, sighting.moveDelay, sighting.moveSpan});
		while (history.size() > CURVES_KEPT)
		{
			history.remove(0);
		}

		Learned updated = new Learned(existing.clips, median(history, 0), existing.sightings,
			median(history, 1), median(history, 2));
		learned.put(sighting.objectId, updated);

		return !wasUnlocked && updated.unlocked();
	}

	/**
	 * Reduces a run of one repeated clip to a single instance of it.
	 *
	 * <p>Only when every clip is the same. A mixed set is a motion with parts, and losing
	 * the parts would leave a golem playing a mount and then standing still.
	 */
	private static int[] collapseRepeats(int[] clips)
	{
		if (clips.length < 2)
		{
			return clips;
		}
		for (int clip : clips)
		{
			if (clip != clips[0])
			{
				return clips;
			}
		}
		return new int[]{clips[0]};
	}

	private static boolean sameClips(int[] a, int[] b)
	{
		if (a.length != b.length)
		{
			return false;
		}
		for (int i = 0; i < a.length; i++)
		{
			if (a[i] != b[i])
			{
				return false;
			}
		}
		return true;
	}

	/** How many obstacles the player has taught the plugin so far. */
	int unlockedCount()
	{
		int n = 0;
		for (Learned l : learned.values())
		{
			if (l.unlocked())
			{
				n++;
			}
		}
		return n;
	}

	int sightingCount()
	{
		return learned.size();
	}

	// -------------------------------------------------------------- persistence

	/**
	 * Everything learned, as one line.
	 *
	 * <p>{@code objectId:clip|clip|clip:ticks:sightings}, semicolon separated. Compact
	 * because it lives in the RuneLite config alongside everything else, and a config value
	 * is not a database.
	 */
	String serialise()
	{
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, Learned> e : learned.entrySet())
		{
			Learned l = e.getValue();
			if (sb.length() > 0)
			{
				sb.append(';');
			}
			sb.append(e.getKey()).append(':');
			for (int i = 0; i < l.clips.length; i++)
			{
				sb.append(i == 0 ? "" : "|").append(l.clips[i]);
			}
			sb.append(':').append(l.ticks).append(':').append(l.sightings)
				.append(':').append(l.moveDelay).append(':').append(l.moveSpan);
		}
		return sb.toString();
	}

	/**
	 * The obstacles the player has personally used, as one line.
	 *
	 * <p>{@code objectId,x,y,plane} groups separated by semicolons. Purely cosmetic data —
	 * losing it costs the player some green outlines, not any golem behaviour.
	 */
	String serialiseConfirmed()
	{
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, List<int[]>> e : confirmed.entrySet())
		{
			for (int[] at : e.getValue())
			{
				if (sb.length() > 0)
				{
					sb.append(';');
				}
				sb.append(e.getKey()).append(',').append(at[0]).append(',')
					.append(at[1]).append(',').append(at[2]);
			}
		}
		return sb.toString();
	}

	void deserialiseConfirmed(String saved)
	{
		confirmed.clear();
		if (saved == null || saved.isEmpty())
		{
			return;
		}

		for (String entry : saved.split(";"))
		{
			try
			{
				String[] parts = entry.split(",");
				if (parts.length != 4)
				{
					continue;
				}
				confirmed.computeIfAbsent(Integer.parseInt(parts[0]), k -> new ArrayList<>())
					.add(new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
						Integer.parseInt(parts[3])});
			}
			catch (RuntimeException e)
			{
				log.debug("Skipping unreadable confirmed obstacle: {}", entry);
			}
		}
	}

	/** Restores what was learned in previous sessions. Bad rows are skipped, not fatal. */
	void deserialise(String saved)
	{
		version++;
		learned.clear();
		if (saved == null || saved.isEmpty())
		{
			return;
		}

		for (String entry : saved.split(";"))
		{
			try
			{
				String[] parts = entry.split(":");
				if (parts.length < 4)
				{
					continue;
				}

				// An empty clip field is a learned "plays nothing", not a broken row.
				// Parsing it as a number throws, which sent the whole entry to the catch
				// below as unreadable — so every silent obstacle a player taught the plugin
				// would have been quietly forgotten the next time they logged in.
				int[] clips;
				if (parts[1].isEmpty())
				{
					clips = new int[0];
				}
				else
				{
					String[] clipText = parts[1].split("\\|");
					clips = new int[clipText.length];
					for (int i = 0; i < clips.length; i++)
					{
						clips[i] = Integer.parseInt(clipText[i]);
					}
				}

				// Repaired on the way in as well as on the way out. Entries written before
				// the repeat collapse existed hold a whole crossing where they should hold
				// one hop, and a player who already taught the plugin a stepping stone
				// should not have to unlearn it by hand.
				int ticks = Integer.parseInt(parts[2]);
				int[] collapsed = collapseRepeats(clips);
				if (collapsed.length != clips.length && clips.length > 0)
				{
					ticks = Math.max(1, Math.round(ticks / (float) clips.length));
				}

				// Rows written before the movement window was recorded have four fields;
				// they load with zeroes and are filled in the next time anybody uses the
				// obstacle, rather than being discarded.
				int delay = parts.length > 4 ? Integer.parseInt(parts[4]) : 0;
				int span = parts.length > 5 ? Integer.parseInt(parts[5]) : 0;
				learned.put(Integer.parseInt(parts[0]),
					new Learned(collapsed, ticks, Integer.parseInt(parts[3]), delay, span));
			}
			catch (RuntimeException e)
			{
				// One malformed entry must not cost the player everything else they have
				// taught the plugin.
				log.debug("Skipping unreadable learned obstacle: {}", entry);
			}
		}

		log.debug("Restored {} learned obstacles, {} unlocked", learned.size(), unlockedCount());
	}
}
