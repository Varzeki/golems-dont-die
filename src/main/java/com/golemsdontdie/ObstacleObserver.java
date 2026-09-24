package com.golemsdontdie;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.MenuOptionClicked;

/**
 * Watches the player use obstacles, and teaches the plugin what it sees.
 *
 * <p>Which animation a shortcut plays, and how long it takes, is <b>not in the cache</b>:
 * agility obstacles are resolved server-side, object definitions carry the object's own
 * animation but not the person's, and of three open server reimplementations none is current
 * enough to trust. So it watches: every obstacle used becomes an {@link ObstacleSighting} for
 * {@link ObstacleKnowledge}, and two consistent sightings unlock it for golems permanently.
 */
@Slf4j
@Singleton
class ObstacleObserver
{
	/**
	 * Ticks after a click within which an animation is taken to belong to it. Generous because an
	 * earlier three-tick window expired during the walk to the obstacle, easily ten seconds.
	 */
	private static final int CLAIM_WINDOW = 60;

	/** Ticks to keep watching before giving up on a traversal ever finishing. */
	private static final int PATIENCE = 40;

	/**
	 * Tiles the player can cover in one tick before it must have been a transport. Running covers
	 * two a tick and a staircase several thousand, so the test is nowhere near the margin.
	 */
	private static final int TELEPORT_TILES = 3;

	@Inject
	private Client client;

	@Inject
	private ObstacleKnowledge knowledge;

	@Inject
	private ObstacleIndex index;

	@Inject
	private TransportNetwork transports;

	/** What golems can walk on, which decides where a traversal is learned to start and end. */
	@Inject
	private IslandMemory memory;

	/** Whether to report the plugin's verdict on each obstacle clicked. */
	@Setter
	private boolean explaining;

	/** Called when a sighting completes, so the plugin can persist and announce it. */
	@Setter
	private java.util.function.Consumer<ObstacleSighting> onSighting;

	// ------------------------------------------------------------- what was clicked

	private int clickedObject = -1;
	private String clickedName = "";
	private String clickedMenu = "";
	private int clickedTick = -1;

	/** An object clicked while another was still animating; see onMenuOptionClicked. */
	private int queuedObject = -1;
	private String queuedName = "";
	private String queuedMenu = "";
	private int queuedTick = -1;

	/** A golem walks a tile a tick, and so do the steps added on and off an obstacle. */
	private static final int WALK_CYCLES = 30;

	// --------------------------------------------------------- the traversal so far

	private final List<Integer> clips = new ArrayList<>();
	private WorldPoint startedAt;
	private int startedTick = -1;
	private int lastAnimTick = -1;

	/** Where the player was last tick, for spotting a move no walk could explain. */
	private WorldPoint previousTile;

	/**
	 * The client cycle the traversal's first clip began on, or -1. The movement window is measured
	 * from here: a golem's own transition starts there, so it is the only origin the two share.
	 */
	private int clipStartCycle = -1;

	/** Cycles from the first clip to the player first moving, and to them stopping. */
	private int moveDelay = -1;
	private int moveEnd = -1;

	/**
	 * When the last animation of the traversal ended, and where the player's tile was then. The
	 * traversal is over when the obstacle has finished with the player, not when the player next
	 * stands still: as one, that recorded the cathedral door plus the walk after it, two tiles off
	 * and three ticks instead of two.
	 */
	private int animEndCycle = -1;
	private WorldPoint animEndTile;

	/**
	 * Cycles of complete stillness on a tile centre that mark the end of one step: a crossing is
	 * one click, and basalt hops rest about fifteen cycles, where a climb's stalls last two.
	 */
	private static final int REST_CYCLES = 6;

	private static final int CYCLES_PER_TICK = 30;

	/**
	 * The traversal being recorded: {cycle, worldFineX, worldFineY} per client tick. World fine
	 * units, because a scene can shift underneath a recording and take local coordinates with it.
	 */
	private final List<int[]> samples = new ArrayList<>();

	/** {cycle, animationId} for each change during the traversal. */
	private final List<int[]> animTimeline = new ArrayList<>();

	/** Where the player was on the previous client tick, for spotting movement at all. */
	private LocalPoint previousFine;

	/**
	 * A move that looked like a silent traversal, held back one tick because the two events race:
	 * a ladder playing 828 can move first, so it was called animated sometimes and silent others,
	 * and every disagreement voided the count.
	 */
	private WorldPoint silentFrom;
	private WorldPoint silentTo;
	private int silentObject = -1;
	private String silentName = "";
	private String silentMenu = "";

	// ------------------------------------------------------------------ lifecycle

	void startUp()
	{
		resetSession();
	}

	void shutDown()
	{
		resetSession();
	}

	// --------------------------------------------------------------------- capture

	/** Remembers the object the player just clicked, so an animation can be attributed. */
	void onMenuOptionClicked(MenuOptionClicked event)
	{
		String option = event.getMenuOption();
		if (option == null || option.isEmpty())
		{
			return;
		}


		if (!isObjectAction(event.getMenuAction()))
		{
			// Only scene objects are obstacles: a definition looked up for a walk target returns
			// whatever object shares that number. A click on anything else drops the object being
			// walked to, as the game does — a door clicked and then a teleport cast was learned as a
			// door to the teleport's destination — but a traversal already started is left to finish.
			if (startedAt == null && clips.isEmpty() && posing == -1)
			{
				clickedObject = -1;
				clickedTick = -1;
				clearSilent();
			}
			return;
		}

		Player local = client.getLocalPlayer();
		WorldPoint at = local == null ? null : local.getWorldLocation();

		// A click while an obstacle is still animating is held, not acted on: players click ahead as
		// a matter of course, and ending the recording there cut it off mid-animation.
		if (startedAt != null && !clips.isEmpty() && local != null && local.getAnimation() != -1)
		{
			queuedObject = event.getId();
			queuedMenu = (option + " " + stripTags(event.getMenuTarget())).trim();
			queuedName = objectName(queuedObject);
			queuedTick = client.getTickCount();
			return;
		}

		// A traversal already under way is finished by the next click, not thrown away: chained
		// obstacles are one action per step, and discarding the one in flight merged both hops into
		// a four-tile route a golem cleared in a single jump.
		if (startedAt != null && !clips.isEmpty() && at != null && !at.equals(startedAt))
		{
			// The next stone of the same crossing ends the hop on the stone it landed on, and the
			// next starts there. Any other click is somewhere new, and the hop ends as usual.
			boolean sameObstacle = event.getId() == clickedObject
				|| objectName(event.getId()).equals(clickedName) && !clickedName.isEmpty();
			complete(at, client.getTickCount(), sameObstacle);
			if (sameObstacle)
			{
				chainTile = at;
			}
		}

		clickedObject = event.getId();
		clickedMenu = (option + " " + stripTags(event.getMenuTarget())).trim();
		clickedName = objectName(clickedObject);
		clickedTick = client.getTickCount();
		hurt = false;
		ordinaryPoses = local == null ? null : ordinaryPosesOf(local);

		clips.clear();
		startedAt = null;


		if (explaining)
		{
			explain(clickedObject);
		}
	}

	/**
	 * Says what the plugin currently believes about the obstacle just clicked. Tied to the
	 * highlight setting: it answers which of the four things the colour depends on is missing.
	 */
	private void explain(int objectId)
	{
		int sizeX = 1;
		int sizeY = 1;
		int x = -1;
		int y = -1;
		int plane = 0;

		Player local = client.getLocalPlayer();
		WorldPoint at = local == null ? null : local.getWorldLocation();
		if (at != null)
		{
			// The index entry nearest the player, so the footprint and position match the overlay's.
			for (ObstacleIndex.Obstacle o : index.near(at.getX(), at.getY(), at.getPlane(), 12))
			{
				if (o.objectId == objectId)
				{
					x = o.x;
					y = o.y;
					plane = o.plane;
					sizeX = o.sizeX;
					sizeY = o.sizeY;
					break;
				}
			}
		}

		if (x < 0)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"[golem] " + objectId + " is not in the obstacle index", null);
			return;
		}

		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[golem] " + objectId + " "
			+ knowledge.explain(objectId, x, y, plane, sizeX, sizeY,
				transports.archetypeFor(objectId)), null);
	}

	/**
	 * Notes each clip the player begins, and the moment the traversal starts. Every clip is kept:
	 * OSRS builds a traversal in parts, and only the first would be a mount and then stillness.
	 */
	void onAnimationChanged(AnimationChanged event)
	{
		Player local = client.getLocalPlayer();
		if (local == null || event.getActor() != local)
		{
			return;
		}

		int playing = local.getAnimation();
		int tick = client.getTickCount();


		if (playing == -1 && startedAt != null)
		{
			animEndCycle = client.getGameCycle();
			animEndTile = local.getWorldLocation();
			animEndTileTemplate = templateOf(animEndTile);
			// Kept so a golem stops animating when the player did; without it the golem stepped
			// off a stile frozen in the last frame of the climb.
			animTimeline.add(new int[]{client.getGameCycle(), -1});
		}

		if (playing == -1 || clickedTick < 0 || tick - clickedTick > CLAIM_WINDOW)
		{
			// An emote, combat, a skill: not attributable to a click, so nothing to learn.
			return;
		}

		// An animation turned up after all, so the move was not silent.
		clearSilent();

		// The next step of a chained crossing, not more of the same one: one click crosses a whole
		// line of stones, and storing that as one traversal let golems cross without touching the
		// middle stone. The same clip again, while the player rests on a tile they did not start
		// from, is a new step; a multi-part obstacle pauses between *different* clips.
		WorldPoint splitAt = null;
		List<int[]> restRun = null;
		if (startedAt != null && !clips.isEmpty() && clips.get(clips.size() - 1) == playing)
		{
			restRun = restingRun();
			WorldPoint resting = restRun == null ? null : tileOf(restRun.get(restRun.size() - 1));
			if (resting != null && !resting.equals(startedAt))
			{
				int object = clickedObject;
				String name = clickedName;
				String menu = clickedMenu;
				int clicked = clickedTick;

				complete(resting, tick, true);
				chainTile = resting;

				clickedObject = object;
				clickedName = name;
				clickedMenu = menu;
				clickedTick = clicked;
				splitAt = resting;
			}
			else
			{
				restRun = null;
			}
		}

		beginClip(playing, splitAt, restRun, local, tick);
	}

	/**
	 * Notes a clip starting: the first of a traversal, which anchors it, or another part of the one
	 * under way. Played animations come here, and so does a pose that moves the player silently.
	 */
	private void beginClip(int playing, WorldPoint splitAt, List<int[]> restRun, Player local, int tick)
	{
		if (startedAt == null)
		{
			// Where the traversal began is where the player was a tick ago: movement and animation
			// arrive in either order, and a rock climb moves you first, so anchoring on the current
			// position recorded a traversal that never left its own tile.
			startedAt = splitAt != null ? splitAt
				: previousTile != null ? previousTile : local.getWorldLocation();
			startedAtTemplate = splitAt != null ? templateOf(splitAt)
				: previousTile != null ? previousTileTemplate : templateOf(local.getWorldLocation());
			startedTick = tick;
			clipStartCycle = client.getGameCycle();
			moveDelay = -1;
			moveEnd = -1;
			samples.clear();
			if (restRun != null)
			{
				// The rest before this step belongs to it: it is where the step starts from.
				samples.addAll(restRun);
			}
			animTimeline.clear();
			animEndCycle = -1;
			animEndTile = null;
		}


		clips.add(playing);
		animTimeline.add(new int[]{client.getGameCycle(), playing});
		lastAnimTick = tick;
	}

	/**
	 * Finishes a traversal once the player has stopped moving somewhere new. A looping clip hides
	 * its own length — a rock climb loops silently — so only the arrival gives it.
	 */
	void onGameTick()
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}

		int tick = client.getTickCount();
		WorldPoint at = local.getWorldLocation();

		WorldPoint was = previousTile;
		previousTile = at;
		WorldPoint wasTemplate = previousTileTemplate;
		previousTileTemplate = templateOf(at);

		// A pose the obstacle put the player in, playing nothing. See posedTraversal.
		if (posedTraversal(local, at, was, wasTemplate, tick))
		{
			return;
		}

		if (startedAt == null && clips.isEmpty())
		{
			// Nothing is animating, so watch for the traversal that plays no animation at all.
			// Staircases are the large case and were invisible here, capture having only ever begun
			// on an animation; "plays nothing" is itself a fact worth knowing.
			silentTraversal(was, at, tick, wasTemplate, previousTileTemplate);
			return;
		}

		if (startedAt == null || clips.isEmpty())
		{
			return;
		}

		// Finished means stopped, not merely between animations: a looping clip reports -1 between
		// repeats, and a rock climb was declared over on its first tile, recording 2550,2209 ->
		// 2550,2208. Or two ticks after the animation ended, moving or not, since a player who
		// clicks away the moment an obstacle lets go never stands still.
		boolean stillMoving = was == null || !was.equals(at);
		boolean released = animEndCycle >= 0
			&& client.getGameCycle() - animEndCycle >= 2 * CYCLES_PER_TICK;
		if (local.getAnimation() == -1 && at != null && (!stillMoving || released)
			&& !at.equals(startedAt))
		{
			complete(at, tick);
			return;
		}

		if (tick - lastAnimTick > PATIENCE)
		{
			// Whatever that was, it was not a traversal that finished.
			reset();
		}
	}

	private void complete(WorldPoint at, int tick)
	{
		complete(at, tick, false);
	}

	/**
	 * Turns the watched traversal into a sighting and hands it on.
	 *
	 * @param continues true if the same obstacle carries straight on, as the next hop of a line of
	 *                  stepping stones does, so the part ends on the stone it landed on.
	 */
	private void complete(WorldPoint at, int tick, boolean continues)
	{
		// A fall is not the way across: washed downstream off a stepping stone the player still
		// moves, and learned, that teaches golems to cross by swimming. So hurt means failed.
		if (hurt)
		{
			hurt = false;
			chainTile = null;
			reset();
			return;
		}

		// Started on the stone a previous hop of the same crossing landed on.
		boolean fromChain = chainTile != null && chainTile.equals(startedAt);
		chainTile = null;

		int[] played = new int[clips.size()];
		for (int i = 0; i < played.length; i++)
		{
			played[i] = clips.get(i);
		}

		// Where the obstacle put the player, not always where they came to rest; see animEndTile.
		// A tile equal to the start is a clip that ended before its own teleport — a ladder.
		WorldPoint to = at;
		if (animEndTile != null && !animEndTile.equals(startedAt) && !animEndTile.equals(at))
		{
			to = animEndTile;
		}

		// Where a golem gets on and off, which for some obstacles is not where the obstacle begins
		// and ends: the stile puts the player on its own blocked tile first. See wayIn, wayOut.
		WorldPoint landing = to;
		// The direction and line in lasting coordinates. Across an instance edge the raw tiles are
		// thousands apart — the pew to the Mad Angel's room recorded a line of 11486,5792.
		WorldPoint landingTemplate = landing.equals(animEndTile) ? animEndTileTemplate : templateOf(landing);
		WorldPoint lineFrom = startedAtTemplate != null ? startedAtTemplate : startedAt;
		WorldPoint lineTo = landingTemplate != null ? landingTemplate : landing;
		int dirX = Integer.signum(lineTo.getX() - lineFrom.getX());
		int dirY = Integer.signum(lineTo.getY() - lineFrom.getY());
		boolean level = landing.getPlane() == startedAt.getPlane();
		// Not in the middle of a crossing. Stepping back to walkable ground is right for a stile but
		// wrong for a stone mid-river, which was learned as a jump over the stone beside it.
		WorldPoint origin = level && !fromChain ? wayIn(startedAt, dirX, dirY) : startedAt;
		to = level && !continues ? wayOut(landing, dirX, dirY) : landing;

		// The recording is the obstacle and nothing else: from the player standing on the tile it
		// starts them from to the moment it puts them down. Everything either side is the player,
		// and was being replayed by golems. Where the route's ends lie a tile outside, the steps
		// between are added as ordinary walking.
		List<int[]> track = flattenTeleports(cutAtArrival(trimToStart(samples, startedAt), landing));
		if (level && !track.isEmpty())
		{
			track = withWalk(track, origin, startedAt, landing, to);
		}
		MotionCurve curve = MotionCurve.record(track, animTimeline,
			origin.getX() * Golem.TILE + Golem.TILE / 2,
			origin.getY() * Golem.TILE + Golem.TILE / 2,
			to.getX() * Golem.TILE + Golem.TILE / 2,
			to.getY() * Golem.TILE + Golem.TILE / 2);

		// Timings read off the recording, which is exactly one step long. Counted live they came back
		// as one cycle for nearly every obstacle, the position stalling every other cycle.
		int ticks = tick - startedTick;
		int delay = Math.max(0, moveDelay);
		int span = 0;
		if (curve != null)
		{
			ticks = Math.max(1, Math.round(curve.cycles() / (float) CYCLES_PER_TICK));
			if (curve.firstMoveCycle() >= 0)
			{
				delay = Math.max(0, curve.firstMoveCycle() - curve.firstAnimationCycle());
				span = Math.max(0, curve.lastMoveCycle() - curve.firstMoveCycle());
			}
		}

		// The route's ends in lasting coordinates: inside an instance that is its template, captured
		// when seen, since the instance may be gone by now. The same offset carries over.
		WorldPoint fromTemplate = shift(startedAtTemplate, origin, startedAt);
		WorldPoint toTemplate = shift(landingTemplate, to, landing);
		boolean placed = fromTemplate != null && toTemplate != null;
		WorldPoint from = placed ? fromTemplate : origin;
		WorldPoint end = placed ? toTemplate : to;

		ObstacleSighting sighting = new ObstacleSighting(
			clickedObject, clickedName, clickedMenu, played, ticks,
			from.getX(), from.getY(), from.getPlane(),
			end.getX(), end.getY(), end.getPlane(), !placed,
			delay, span, curve,
			level ? lineTo.getX() - lineFrom.getX() : 0,
			level ? lineTo.getY() - lineFrom.getY() : 0,
			// A tile inside an instance is not its own template; outside one it is.
			startedAtTemplate != null && !startedAtTemplate.equals(startedAt),
			landingTemplate != null && !landingTemplate.equals(landing));


		reset();

		// A traversal that did not move the player is not a traversal: the main guard against being
		// attacked between clicking a ladder and reaching it.
		if (!sighting.moved())
		{
			return;
		}

		if (onSighting != null)
		{
			onSighting.accept(sighting);
		}
	}

	/**
	 * Records a traversal that moved the player without animating them, judged by the move rather
	 * than the click: a plane change, or a jump no running could cover in one tick.
	 */
	private void silentTraversal(WorldPoint was, WorldPoint now, int tick, WorldPoint wasTemplate,
		WorldPoint nowTemplate)
	{
		// Last tick's candidate, now that an animation has had its chance to turn up.
		if (silentFrom != null)
		{
			WorldPoint from = silentFrom;
			WorldPoint to = silentTo;
			WorldPoint fromTemplate = silentFromTemplate;
			WorldPoint toTemplate = silentToTemplate;
			boolean placed = fromTemplate != null && toTemplate != null;
			int object = silentObject;
			String name = silentName;
			String menu = silentMenu;
			clearSilent();

			// A silent traversal has no clip to measure a window against but still has a shape:
			// without one a staircase appeared at the far end the tick the golem arrived. A silent
			// exit is reported a tick after the overworld loads, so its template is a tick older.
			MotionCurve curve = silentCurve(from, to);
			WorldPoint start = placed ? fromTemplate : from;
			WorldPoint end = placed ? toTemplate : to;
			ObstacleSighting sighting = new ObstacleSighting(object, name, menu,
				new int[0], 1, start.getX(), start.getY(), start.getPlane(),
				end.getX(), end.getY(), end.getPlane(), !placed, 0, 0,
				curve, 0, 0,
				fromTemplate != null && !fromTemplate.equals(from),
				toTemplate != null && !toTemplate.equals(to));

			reset();

			if (onSighting != null)
			{
				onSighting.accept(sighting);
			}
			return;
		}

		if (clickedObject < 0 || was == null || now == null
			|| clickedTick < 0 || tick - clickedTick > CLAIM_WINDOW)
		{
			return;
		}

		boolean jumped = was.getPlane() != now.getPlane()
			|| Math.abs(was.getX() - now.getX()) > TELEPORT_TILES
			|| Math.abs(was.getY() - now.getY()) > TELEPORT_TILES;
		if (!jumped)
		{
			return;
		}

		// Boarding or leaving a boat is not an obstacle: aboard, the player stands in the boat's own
		// world thousands of tiles off the map, and a gangplank was learned as a way there.
		boolean aboard = onBoat();
		if (aboard || boatChanged)
		{
			return;
		}

		// Held, not emitted. Next tick decides.
		silentFrom = was;
		silentTo = now;
		silentFromTemplate = wasTemplate;
		silentToTemplate = nowTemplate;
		silentObject = clickedObject;
		silentName = clickedName;
		silentMenu = clickedMenu;
	}

	/**
	 * Drops the walk up to the obstacle, keeping the traversal itself, which starts at the last
	 * moment the player stood on its origin tile. Recording begins at the click, well before.
	 */
	private List<int[]> trimToStart(List<int[]> all, WorldPoint from)
	{
		int start = startOfTraversal(all, from);
		if (start >= 0)
		{
			return all.subList(start, all.size());
		}
		// Never seen standing there: start at the animation rather than keep the approach,
		// which is the player's walk and not the obstacle.
		for (int i = 0; i < all.size(); i++)
		{
			if (all.get(i)[0] >= clipStartCycle)
			{
				return all.subList(i, all.size());
			}
		}
		return all;
	}

	/**
	 * The walk onto and off an obstacle, added where a route starts or ends a tile outside it:
	 * straight lines at walking pace playing no animation, so the golem's own walk shows.
	 */
	private static List<int[]> withWalk(List<int[]> track, WorldPoint origin, WorldPoint start,
		WorldPoint landing, WorldPoint dest)
	{
		List<int[]> out = new ArrayList<>();
		int[] first = track.get(0);
		if (!origin.equals(start))
		{
			for (int i = 0; i < WALK_CYCLES; i++)
			{
				out.add(walkSample(first[0] - WALK_CYCLES + i, origin, start, i / (float) WALK_CYCLES, first[3]));
			}
		}
		out.addAll(track);
		if (!dest.equals(landing))
		{
			int[] last = track.get(track.size() - 1);
			for (int i = 0; i < WALK_CYCLES; i++)
			{
				out.add(walkSample(last[0] + 1 + i, landing, dest, (i + 1) / (float) WALK_CYCLES, last[3]));
			}
		}
		return out;
	}

	private static int[] walkSample(int cycle, WorldPoint from, WorldPoint to, float t, int plane)
	{
		float fromX = from.getX() * Golem.TILE + Golem.TILE / 2f;
		float fromY = from.getY() * Golem.TILE + Golem.TILE / 2f;
		float toX = to.getX() * Golem.TILE + Golem.TILE / 2f;
		float toY = to.getY() * Golem.TILE + Golem.TILE / 2f;
		return new int[]{cycle, Math.round(fromX + (toX - fromX) * t), Math.round(fromY + (toY - fromY) * t),
			plane, -1, -1};
	}

	/** The index of the traversal's first sample, or -1 if the player never stood on its origin. */
	private static int startOfTraversal(List<int[]> all, WorldPoint from)
	{
		if (from == null || all.isEmpty())
		{
			return -1;
		}

		// Tile centres, because the samples are rendered and a player is drawn at a tile's middle.
		int originX = from.getX() * Golem.TILE + Golem.TILE / 2;
		int originY = from.getY() * Golem.TILE + Golem.TILE / 2;

		// The moment the player arrived on the origin tile, not the moment they left it: a door
		// stands still for thirty cycles and then teleports, all of it "at the origin", so taking the
		// last sample there began the recording at the teleport. Hence the walk back to the run.
		int last = -1;
		for (int i = 0; i < all.size(); i++)
		{
			int[] sample = all.get(i);
			if (Math.abs(sample[1] - originX) <= Golem.TILE / 4
				&& Math.abs(sample[2] - originY) <= Golem.TILE / 4)
			{
				last = i;
			}
		}

		if (last < 0)
		{
			return -1;
		}

		int start = last;
		while (start > 0)
		{
			int[] before = all.get(start - 1);
			if (Math.abs(before[1] - originX) > Golem.TILE / 4
				|| Math.abs(before[2] - originY) > Golem.TILE / 4)
			{
				break;
			}
			start--;
		}

		return start;
	}

	/**
	 * Drops whatever the player did after the obstacle finished: the first moment, at or after the
	 * last animation ended, that they are drawn on the destination. A door keeps its whole clip.
	 */
	private List<int[]> cutAtArrival(List<int[]> track, WorldPoint to)
	{
		// Without an end to the animation, the first arrival after it began will do.
		int after = animEndCycle >= 0 ? animEndCycle : clipStartCycle;
		if (after < 0)
		{
			return track;
		}
		int centreX = to.getX() * Golem.TILE + Golem.TILE / 2;
		int centreY = to.getY() * Golem.TILE + Golem.TILE / 2;
		for (int i = 0; i < track.size(); i++)
		{
			int[] sample = track.get(i);
			if (sample[0] >= after && sample[3] == to.getPlane()
				&& Math.abs(sample[1] - centreX) <= Golem.TILE / 4
				&& Math.abs(sample[2] - centreY) <= Golem.TILE / 4)
			{
				return track.subList(0, i + 1);
			}
		}
		return track;
	}

	/**
	 * Holds the recording still from the first teleport onward. A cave mouth puts the player six
	 * thousand tiles away while still animating, which a golem would glide across the world.
	 */
	private static List<int[]> flattenTeleports(List<int[]> track)
	{
		for (int i = 1; i < track.size(); i++)
		{
			int[] before = track.get(i - 1);
			int[] after = track.get(i);
			if (before[3] != after[3]
				|| Math.abs(after[1] - before[1]) > TELEPORT_TILES * Golem.TILE
				|| Math.abs(after[2] - before[2]) > TELEPORT_TILES * Golem.TILE)
			{
				List<int[]> held = new ArrayList<>(track.subList(0, i));
				for (int j = i; j < track.size(); j++)
				{
					int[] sample = track.get(j);
					held.add(new int[]{sample[0], before[1], before[2], before[3],
						sample.length > 4 ? sample[4] : -1, sample.length > 5 ? sample[5] : -1});
				}
				return held;
			}
		}
		return track;
	}

	/**
	 * The stillness on a silent obstacle before the player vanishes, as a recording. Only the part
	 * on the origin tile: the approach is not the obstacle, and the far side is elsewhere.
	 */
	private MotionCurve silentCurve(WorldPoint from, WorldPoint to)
	{
		int start = startOfTraversal(samples, from);
		if (start < 0)
		{
			return null;
		}
		int originX = from.getX() * Golem.TILE + Golem.TILE / 2;
		int originY = from.getY() * Golem.TILE + Golem.TILE / 2;
		int end = samples.size();
		for (int i = start; i < samples.size(); i++)
		{
			int[] sample = samples.get(i);
			if (sample[3] != from.getPlane()
				|| Math.abs(sample[1] - originX) > Golem.TILE / 4
				|| Math.abs(sample[2] - originY) > Golem.TILE / 4)
			{
				end = i;
				break;
			}
		}
		return MotionCurve.record(samples.subList(start, end), new ArrayList<>(),
			originX, originY,
			to.getX() * Golem.TILE + Golem.TILE / 2, to.getY() * Golem.TILE + Golem.TILE / 2);
	}

	/** The rest ending the recording, if long enough on a tile centre to end a step; else null. */
	private List<int[]> restingRun()
	{
		if (samples.isEmpty())
		{
			return null;
		}
		int[] last = samples.get(samples.size() - 1);
		int first = samples.size() - 1;
		while (first > 0)
		{
			int[] before = samples.get(first - 1);
			if (before[1] != last[1] || before[2] != last[2] || before[3] != last[3])
			{
				break;
			}
			first--;
		}
		if (last[0] - samples.get(first)[0] < REST_CYCLES)
		{
			return null;
		}
		int offX = Math.floorMod(last[1], Golem.TILE) - Golem.TILE / 2;
		int offY = Math.floorMod(last[2], Golem.TILE) - Golem.TILE / 2;
		if (Math.abs(offX) > Golem.TILE / 4 || Math.abs(offY) > Golem.TILE / 4)
		{
			return null;
		}
		return new ArrayList<>(samples.subList(first, samples.size()));
	}

	/**
	 * Where a traversal really starts, for a golem.
	 *
	 * <p>Some obstacles start the player on a tile no golem can walk onto: clicking the stile walks
	 * you onto its blocked tile, so a route learned from there started where no golem could stand
	 * and the stile went unused. The way in is the walkable tile behind the obstacle, along its
	 * line — from the obstacle alone, never from where the player was, since players come at a
	 * stile from the side. A stepping stone is blocked too, but behind it is water.
	 */
	private WorldPoint wayIn(WorldPoint origin, int dirX, int dirY)
	{
		if (origin == null || walkable(origin) || (dirX == 0 && dirY == 0))
		{
			return origin;
		}
		WorldPoint behind = new WorldPoint(origin.getX() - dirX, origin.getY() - dirY, origin.getPlane());
		return walkable(behind) ? behind : origin;
	}

	/** Where a traversal really ends: the landing, else the walkable tile past it, else itself. */
	private WorldPoint wayOut(WorldPoint landing, int dirX, int dirY)
	{
		if (walkable(landing) || (dirX == 0 && dirY == 0))
		{
			return landing;
		}
		WorldPoint beyond = new WorldPoint(landing.getX() + dirX, landing.getY() + dirY, landing.getPlane());
		return walkable(beyond) ? beyond : landing;
	}

	private boolean walkable(WorldPoint tile)
	{
		return tile != null && memory.isKnownWalkable(tile.getX(), tile.getY(), tile.getPlane());
	}

	private static boolean adjacent(WorldPoint a, WorldPoint b)
	{
		return a != null && b != null && a.getPlane() == b.getPlane() && !a.equals(b)
			&& Math.abs(a.getX() - b.getX()) <= 1 && Math.abs(a.getY() - b.getY()) <= 1;
	}

	private static WorldPoint tileOf(int[] sample)
	{
		return new WorldPoint(Math.floorDiv(sample[1], Golem.TILE),
			Math.floorDiv(sample[2], Golem.TILE), sample[3]);
	}

	/** The stone a hop of a crossing just landed on, where the crossing's next hop starts. */
	private WorldPoint chainTile;

	/** True if the player took damage since clicking the obstacle: they failed it. */
	private boolean hurt;

	/** The player's own standing, walking, running and turning poses, as they were at the click. */
	private java.util.Set<Integer> ordinaryPoses;

	/** A pose being recorded as the traversal's clip, or -1. */
	private int posing = -1;

	/** True if the player was aboard a boat last tick. */
	private boolean wasOnBoat;

	/**
	 * True if the player got on or off a boat this tick, kept apart from {@link #wasOnBoat} because
	 * {@link #posedTraversal} has moved that on before {@link #silentTraversal} looks: read there,
	 * stepping off a boat could be learned as a route.
	 */
	private boolean boatChanged;

	/**
	 * Forgets the player's last position and click for a new session — the plugin starting, a
	 * logout, a world hop — since otherwise a jump that never happened could be learned.
	 */
	void resetSession()
	{
		queuedObject = -1;
		reset();
		previousTile = null;
		previousTileTemplate = null;
		wasOnBoat = false;
		boatChanged = false;
		hurt = false;
		ordinaryPoses = null;
		clickedName = "";
		clickedMenu = "";
	}

	/** Marks the obstacle under way as failed. Called when the player takes a hit. */
	void onPlayerHurt()
	{
		if (clickedTick >= 0 || startedAt != null)
		{
			hurt = true;
		}
	}

	private boolean onBoat()
	{
		Player local = client.getLocalPlayer();
		return local != null && local.getWorldView() != null && !local.getWorldView().isTopLevel();
	}

	private static java.util.Set<Integer> ordinaryPosesOf(Player local)
	{
		java.util.Set<Integer> poses = new java.util.HashSet<>();
		poses.add(-1);
		poses.add(local.getIdlePoseAnimation());
		poses.add(local.getIdleRotateLeft());
		poses.add(local.getIdleRotateRight());
		poses.add(local.getWalkAnimation());
		poses.add(local.getWalkRotateLeft());
		poses.add(local.getWalkRotateRight());
		poses.add(local.getWalkRotate180());
		poses.add(local.getRunAnimation());
		return poses;
	}

	/**
	 * Watches for an obstacle that moves the player in a pose of its own, playing nothing.
	 *
	 * <p>Climbing down a vine is one: the game swaps the player's walk for a climbing pose and
	 * moves them a tile a tick with no animation, so this class, recording only on an animation or
	 * a jump, saw nothing and the vine could only be learned going up. A pose none of the player's
	 * own, soon after a click, is that obstacle's clip.
	 *
	 * @return true if this tick belonged to such a traversal
	 */
	private boolean posedTraversal(Player local, WorldPoint at, WorldPoint was, WorldPoint wasTemplate, int tick)
	{
		boolean aboard = onBoat();
		boatChanged = aboard != wasOnBoat;
		wasOnBoat = aboard;
		if (boatChanged || aboard)
		{
			return false;
		}

		int pose = local.getPoseAnimation();
		boolean special = ordinaryPoses != null && !ordinaryPoses.contains(pose) && local.getAnimation() == -1;

		if (posing != -1)
		{
			if (special && pose == posing)
			{
				return false;
			}
			// Back to the player's own poses: the obstacle has let go.
			posing = -1;
			if (startedAt != null)
			{
				animEndCycle = client.getGameCycle();
				animEndTile = at;
				animEndTileTemplate = templateOf(at);
				animTimeline.add(new int[]{client.getGameCycle(), -1});
			}
			return false;
		}

		if (!special || startedAt != null || !clips.isEmpty()
			|| clickedObject < 0 || clickedTick < 0 || tick - clickedTick > CLAIM_WINDOW)
		{
			return false;
		}

		clearSilent();
		posing = pose;
		beginClip(pose, null, null, local, tick);
		// From where the player was when the pose took them, a tick ago: by now they have
		// already been moved a tile.
		if (was != null)
		{
			startedAt = was;
			startedAtTemplate = wasTemplate;
		}
		return true;
	}

	private void clearSilent()
	{
		silentFrom = null;
		silentTo = null;
		silentFromTemplate = null;
		silentToTemplate = null;
		silentObject = -1;
	}

	private void reset()
	{
		clearSilent();
		posing = -1;
		chainTile = null;
		clipStartCycle = -1;
		moveDelay = -1;
		moveEnd = -1;
		animEndCycle = -1;
		animEndTile = null;
		animEndTileTemplate = null;
		startedAtTemplate = null;
		samples.clear();
		animTimeline.clear();
		clips.clear();
		startedAt = null;
		startedTick = -1;
		clickedTick = -1;
		clickedObject = -1;

		// A click held back while the obstacle finished is the one the next traversal belongs to.
		if (queuedObject >= 0)
		{
			clickedObject = queuedObject;
			clickedName = queuedName;
			clickedMenu = queuedMenu;
			clickedTick = queuedTick;
			queuedObject = -1;
		}
	}

	// ------------------------------------------------------------------- motion

	/**
	 * Samples the player's exact position while a traversal is in progress. A game tick is 600ms and
	 * movement is interpolated thirty times inside it, so tick resolution cannot tell a walk from a
	 * glide from a teleport. Local coordinates: 128ths of a tile, the golems' unit.
	 */
	void onClientTick()
	{
		// The samples below are the motion curves golems learn from, and a curve is what splits a
		// line of stepping stones into its hops.
		Player local = client.getLocalPlayer();
		LocalPoint fine = local == null ? null : local.getLocalLocation();

		// The motion itself, sample by sample: what a golem performs, the window below being a summary
		// for obstacles no curve was captured for. Sampled from the click onward, not the animation,
		// because the movement can happen *before* the clip — a rock climb puts you at the far side
		// first — so the approach is trimmed at the end. A rolling window; a cap left it unsampled.
		if (clickedTick >= 0 && local != null)
		{
			net.runelite.api.WorldView view = client.getTopLevelWorldView();
			LocalPoint fineNow = local.getLocalLocation();
			if (view != null && fineNow != null)
			{
				// The *rendered* position, and only that. A traversal moves the logical tile to the
				// destination at once and then slides the drawn position across, which is why
				// getWorldLocation() already reads the top of a rock climb; combining the two gave
				// the destination tile with the departure's sub-tile offset.
				if (samples.size() >= MotionCurve.MAX_CYCLES)
				{
					samples.remove(0);
				}
				samples.add(new int[]{
					client.getGameCycle(),
					view.getBaseX() * Golem.TILE + fineNow.getX(),
					view.getBaseY() * Golem.TILE + fineNow.getY(),
					local.getWorldLocation().getPlane(),
					local.getOrientation(),
					local.getAnimation() == -1 ? -1 : local.getAnimationFrame(),
				});
			}
		}

		// The movement window, measured against the clip rather than the tick. A traversal holds the
		// player still for part of its animation and then moves them quickly — 33 cycles of stillness
		// then 12 of movement, on the stones — and only the total made golems drift.
		if (clipStartCycle >= 0 && fine != null && previousFine != null)
		{
			boolean moving = fine.getX() != previousFine.getX()
				|| fine.getY() != previousFine.getY();
			if (moving)
			{
				int since = client.getGameCycle() - clipStartCycle;
				if (moveDelay < 0)
				{
					moveDelay = Math.max(0, since);
				}
			}
		}
		previousFine = fine;

	}

	private String objectName(int id)
	{
		try
		{
			ObjectComposition def = client.getObjectDefinition(id);
			return def == null || def.getName() == null ? "" : def.getName();
		}
		catch (RuntimeException e)
		{
			return "";
		}
	}

	private static boolean isObjectAction(MenuAction action)
	{
		switch (action)
		{
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
				return true;
			default:
				return false;
		}
	}

	/**
	 * The sampled positions' templates, captured while that instance is still the loaded scene.
	 */
	private WorldPoint previousTileTemplate;
	private WorldPoint startedAtTemplate;
	private WorldPoint animEndTileTemplate;
	private WorldPoint silentFromTemplate;
	private WorldPoint silentToTemplate;

	private WorldPoint templateOf(WorldPoint tile)
	{
		return InstanceMap.templateOf(client.getTopLevelWorldView(), tile);
	}

	/** {@code base} moved by however far {@code moved} is from {@code reference}; null if base is. */
	private static WorldPoint shift(WorldPoint base, WorldPoint moved, WorldPoint reference)
	{
		if (base == null)
		{
			return null;
		}
		return new WorldPoint(base.getX() + moved.getX() - reference.getX(),
			base.getY() + moved.getY() - reference.getY(),
			base.getPlane() + moved.getPlane() - reference.getPlane());
	}

	private static String stripTags(String text)
	{
		return text == null ? "" : text.replaceAll("<[^>]*>", "");
	}
}
