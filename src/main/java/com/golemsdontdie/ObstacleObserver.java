package com.golemsdontdie;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
 * <p>Which animation a shortcut plays, and how long it takes, is <b>not in the cache</b>.
 * Agility obstacles are resolved server-side: the server plays the animation and moves the
 * player, so the client holds no record of the pairing. Object definitions carry the
 * object's own animation — a swinging rope, a turning ring — but not the person's. Client
 * scripts are interface logic and do not decide it either, and three open reimplementations
 * of the server were checked and none is current enough to trust.
 *
 * <p>That leaves watching somebody do it. Which is fine, because somebody is playing the
 * game anyway.
 *
 * <p>This runs all the time and costs nothing when nothing is happening. Every obstacle the
 * player uses is correlated into an {@link ObstacleSighting} and handed to
 * {@link ObstacleKnowledge}; two consistent sightings unlock that obstacle for golems
 * permanently. A player who runs an agility course has taught the plugin a course's worth
 * of animations without being asked to do anything, and golems in that player's game become
 * able to use shortcuts that golems elsewhere still route around.
 *
 * <p>It always keeps a raw journal as well — every click, every animation, every tick, and
 * the player's exact position at 20ms resolution while a traversal is under way. The
 * correlation below has to decide in the moment what belongs to what; a journal can be
 * re-read as many times as it takes, so it catches what the live pass gets wrong and is the
 * only way to improve the live pass afterwards. A session cannot be repeated; a parse can.
 *
 * <p>Always on is affordable because the expensive rows are tied to the rare event. Per-tick
 * rows are around 360KB an hour; the 20ms rows run only while an obstacle is actually being
 * traversed, not after every click, which is what separates a few hundred rows per obstacle
 * from tens of megabytes an hour of somebody walking around a city. Old journals are pruned
 * so the folder cannot grow without bound.
 */
@Slf4j
@Singleton
class ObstacleObserver
{
	/** Bumped whenever a journal column changes, so an old file is never misparsed. */
	private static final int SCHEMA = 1;

	/**
	 * Ticks after a click within which an animation is taken to belong to it.
	 *
	 * <p>Generous, because a click on an obstacle is not the moment it is used: the player
	 * walks there first, and across a clearing that is easily ten seconds. An earlier
	 * three-tick window expired during the walk every single time and recorded nothing.
	 */
	private static final int CLAIM_WINDOW = 60;

	/** Ticks to keep watching before giving up on a traversal ever finishing. */
	private static final int PATIENCE = 40;

	/**
	 * Tiles the player can cover in one tick before it must have been a transport.
	 *
	 * <p>Two is already generous — running covers two tiles a tick and walking one — so
	 * anything past it is not locomotion. A staircase moves you several thousand tiles in a
	 * single tick, so the test is nowhere near the margin in the cases that matter.
	 */
	private static final int TELEPORT_TILES = 3;

	/** Client ticks of fine-grained journal recording after the last animation ends. */
	private static final int TAIL_CYCLES = 100;

	/**
	 * Client ticks of position kept in hand before a traversal is recognised.
	 *
	 * <p>Because the interesting part happens before the animation event arrives. An
	 * obstacle does not start moving you the moment it starts animating you — the player
	 * leans, gathers themselves, and only then goes — and recording from the animation
	 * onwards captures the jump while missing the wind-up entirely.
	 *
	 * <p>It cannot be recovered afterwards, so it is held continuously and written out only
	 * when a traversal turns out to have begun. A second and a bit of positions costs sixty
	 * entries of nothing and is the difference between knowing an obstacle's shape and
	 * guessing at it.
	 */
	private static final int LOOKBACK_CYCLES = 60;

	/** Journals kept on disk. Older ones are deleted when a new session starts. */
	private static final int JOURNALS_KEPT = 10;

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

	/**
	 * Whether to record what the golems are doing alongside what the player is doing.
	 *
	 * <p>Very loud — a row per visible golem per tick — and only worth it while something
	 * is being diagnosed. Its value is that the two halves land in the same file on the
	 * same clock, so a golem's animation and position can be held against the player's
	 * doing the same obstacle rather than described from memory.
	 */
	@Setter
	private boolean loggingGolems;

	boolean isLoggingGolems()
	{
		return loggingGolems && out != null;
	}

	/** One golem, as it is this tick. */
	void writeGolem(String id, String state)
	{
		if (out == null)
		{
			return;
		}
		out.println(client.getGameCycle() + "	" + client.getTickCount() + "	GOLEM"
			+ "	-1	-1	-1	-1	-1	-1	" + id + " " + state);
	}

	/**
	 * One golem as it was drawn on one client frame, while it is mid-obstacle.
	 *
	 * <p>The per-tick row cannot see what goes wrong inside a traversal: a door crossed in
	 * one cycle and a door crossed in three look identical at 600ms, and so does an
	 * animation that finishes before the golem leaves the ground. These rows are written
	 * only during transitions, so they cost nothing while golems are walking.
	 */
	void writeGolemFrame(String id, String state)
	{
		if (out == null)
		{
			return;
		}
		out.println(client.getGameCycle() + "	" + client.getTickCount() + "	GOLEMFRAME"
			+ "	-1	-1	-1	-1	-1	-1	" + id + " " + state);
	}

	/** Something a golem decided, and why. */
	void writeGolemEvent(String id, String what)
	{
		if (out == null)
		{
			return;
		}
		out.println(client.getGameCycle() + "	" + client.getTickCount() + "	GOLEMACT"
			+ "	-1	-1	-1	-1	-1	-1	" + id + " " + what);
	}

	/** Called when a sighting completes, so the plugin can persist and announce it. */
	@Setter
	private java.util.function.Consumer<ObstacleSighting> onSighting;

	// ------------------------------------------------------------- what was clicked

	private int clickedObject = -1;
	private String clickedName = "";
	private String clickedMenu = "";
	private int clickedTick = -1;

	/**
	 * An object clicked while another was still animating the player, held until that one
	 * finishes. See onMenuOptionClicked.
	 */
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
	 * The client cycle the traversal's first clip began on, or -1.
	 *
	 * <p>Everything about the movement window is measured from here, because that is the
	 * moment a golem's own transition starts and so the only origin the two can share.
	 */
	private int clipStartCycle = -1;

	/** Cycles from the first clip to the player first moving, and to them stopping. */
	private int moveDelay = -1;
	private int moveEnd = -1;

	/**
	 * When the last animation of the traversal ended, and where the player's tile was then.
	 *
	 * <p>The traversal is over when the obstacle has finished with the player, not when
	 * the player next stands still. Those were treated as the same moment, and they are not
	 * whenever somebody clicks onward: a player who went through the cathedral door and kept
	 * walking produced a recording of the door followed by a diagonal walk, a destination two
	 * tiles off the door, and three ticks instead of two — and every golem performed all of
	 * it.
	 */
	private int animEndCycle = -1;
	private WorldPoint animEndTile;

	/**
	 * Cycles of complete stillness on a tile centre that mark the end of one step.
	 *
	 * <p>A crossing of several stones is one click in the current game, and the stones come
	 * back as one animation after another with a rest between. Measured between two basalt
	 * hops at about fifteen cycles; a climb's quantised stalls last one or two.
	 */
	private static final int REST_CYCLES = 6;

	private static final int CYCLES_PER_TICK = 30;

	/**
	 * The traversal being recorded: {cycle, worldFineX, worldFineY} per client tick.
	 *
	 * <p>World fine units rather than local, because a scene can shift underneath a
	 * recording and local coordinates would jump with it.
	 */
	private final List<int[]> samples = new ArrayList<>();

	/** {cycle, animationId} for each change during the traversal. */
	private final List<int[]> animTimeline = new ArrayList<>();

	/** Where the player was on the previous client tick, for spotting movement at all. */
	private LocalPoint previousFine;

	/**
	 * A move that looked like a silent traversal, held back one tick.
	 *
	 * <p>Because the two events race. A ladder that plays 828 can deliver the position
	 * change before the animation, and judging it on the tick of the move alone recorded
	 * the same ladder as animated sometimes and silent other times. Each disagreement
	 * threw away the count, so it never reached two and the obstacle stayed orange no
	 * matter how often it was climbed.
	 *
	 * <p>One tick of patience costs nothing and lets the animation arrive.
	 */
	private WorldPoint silentFrom;
	private WorldPoint silentTo;
	private int silentObject = -1;
	private String silentName = "";
	private String silentMenu = "";

	// ------------------------------------------------------------------- journal

	private PrintWriter out;
	private File file;
	private int fineRemaining;

	/** The rolling lookback: cycle, localX, localY per entry, oldest overwritten. */
	private final int[][] lookback = new int[LOOKBACK_CYCLES][3];
	private int lookbackAt;
	private int lookbackHeld;
	private int lastPose = -2;
	private int lastPlane = -2;

	// ------------------------------------------------------------------ lifecycle

	void startUp()
	{
		try
		{
			String stamp = LocalDateTime.now()
				.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
			file = new File(net.runelite.client.RuneLite.RUNELITE_DIR,
				"golem-obstacles-" + stamp + ".tsv");
			out = new PrintWriter(new BufferedWriter(new FileWriter(file)));

			out.println("#schema\t" + SCHEMA);
			out.println("#started\t" + LocalDateTime.now());
			out.println("#cycle\ttick\ttype\tx\ty\tplane\tanim\tpose\torient\tdetail");
			out.flush();

			log.info("Obstacle journal: {}", file.getAbsolutePath());
			pruneOldJournals();
		}
		catch (IOException e)
		{
			// Disabled rather than throwing every tick for the rest of the session.
			out = null;
			log.error("Could not open the obstacle journal; journalling is off", e);
		}
	}

	/**
	 * Deletes all but the most recent journals.
	 *
	 * <p>Only files this class wrote, matched on its own prefix and suffix, and only in
	 * RuneLite's own folder. The names carry a sortable timestamp, so the newest are simply
	 * the last ones alphabetically.
	 */
	private void pruneOldJournals()
	{
		try
		{
			File[] existing = net.runelite.client.RuneLite.RUNELITE_DIR
				.listFiles((d, n) -> n.startsWith("golem-obstacles-") && n.endsWith(".tsv"));
			if (existing == null || existing.length <= JOURNALS_KEPT)
			{
				return;
			}

			java.util.Arrays.sort(existing, java.util.Comparator.comparing(File::getName));
			for (int i = 0; i < existing.length - JOURNALS_KEPT; i++)
			{
				if (!existing[i].delete())
				{
					log.debug("Could not delete old journal {}", existing[i]);
				}
			}
		}
		catch (RuntimeException e)
		{
			// Tidying is not worth failing a session over.
			log.debug("Could not prune old journals", e);
		}
	}

	void shutDown()
	{
		if (out != null)
		{
			write("SESSION", "end");
			out.flush();
			out.close();
			out = null;
			log.info("Obstacle journal closed: {}", file);
		}
	}

	/** Where the journal is being written, or null if it is not. */
	String journalPath()
	{
		return file == null ? null : file.getAbsolutePath();
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

		if (out != null)
		{
			write("CLICK", "id=" + event.getId()
				+ " opcode=" + event.getMenuAction()
				+ " param0=" + event.getParam0()
				+ " param1=" + event.getParam1()
				+ " option=\"" + option + "\""
				+ " target=\"" + stripTags(event.getMenuTarget()) + "\"");
		}

		if (!isObjectAction(event.getMenuAction()))
		{
			// Only scene objects are obstacles. Looking up a definition for a walk target
			// returns whatever object happens to share that number, which would attach a
			// plausible and wrong name to a great many rows.
			return;
		}

		Player local = client.getLocalPlayer();
		WorldPoint at = local == null ? null : local.getWorldLocation();

		// A click while an obstacle is still animating the player is held, not acted on.
		//
		// Players click ahead as a matter of course — onto the far side of a stile while
		// still climbing it, onto the next thing mid-hop — and the game finishes the obstacle
		// before it does anything with the click. Finishing the recording on that click
		// instead cut it off partway through the animation. So it waits until the obstacle is
		// done, and then becomes the click the next traversal belongs to.
		if (startedAt != null && !clips.isEmpty() && local != null && local.getAnimation() != -1)
		{
			queuedObject = event.getId();
			queuedMenu = (option + " " + stripTags(event.getMenuTarget())).trim();
			queuedName = objectName(queuedObject);
			queuedTick = client.getTickCount();
			if (out != null)
			{
				describeObject(queuedObject);
			}
			return;
		}

		// A traversal already under way is finished by the next click, not thrown away.
		//
		// Chained obstacles are one action per step: a line of stepping stones takes a
		// fresh click at every stone, and the click for the second arrives before the
		// first has settled. Discarding the one in flight merged both hops into a single
		// sighting and taught a four-tile route across three stones — a golem following it
		// would clear the whole crossing in one jump and skip the middle stone entirely.
		if (startedAt != null && !clips.isEmpty() && at != null && !at.equals(startedAt))
		{
			complete(at, client.getTickCount());
		}

		clickedObject = event.getId();
		clickedMenu = (option + " " + stripTags(event.getMenuTarget())).trim();
		clickedName = objectName(clickedObject);
		clickedTick = client.getTickCount();

		clips.clear();
		startedAt = null;

		if (out != null)
		{
			describeObject(clickedObject);
		}

		if (explaining)
		{
			explain(clickedObject);
		}
	}

	/**
	 * Says what the plugin currently believes about the obstacle just clicked.
	 *
	 * <p>Tied to the highlight setting, because it answers the question the highlight
	 * raises and cannot: not what colour this is, but which of the four things the colour
	 * depends on is missing.
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
			// The index entry nearest the player, so the footprint and position used are
			// the same ones the overlay colours.
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
	 * Notes each clip the player begins, and the moment the traversal starts.
	 *
	 * <p>Every clip is kept, not just the first. OSRS builds a traversal in parts — take
	 * hold, haul, step off — and a stepping-stone crossing is several animations from a
	 * single click. Recording only the first would have golems playing a mount and then
	 * standing still for the rest of the obstacle.
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

		if (out != null)
		{
			write("ANIM", "anim=" + playing);
		}

		if (playing == -1 && startedAt != null)
		{
			animEndCycle = client.getGameCycle();
			animEndTile = local.getWorldLocation();
			animEndTileTemplate = templateOf(animEndTile);
			// Kept in the recording, so a golem stops animating when the player did. The
			// stile ends its clip and then the player steps off; without this the golem
			// stepped off frozen in the last frame of the climb.
			animTimeline.add(new int[]{client.getGameCycle(), -1});
		}

		if (playing == -1 || clickedTick < 0 || tick - clickedTick > CLAIM_WINDOW)
		{
			// Not attributable to anything the player clicked on — an emote, combat, a
			// skill. Nothing to learn from it.
			return;
		}

		// An animation turned up after all, so the move was not silent.
		clearSilent();

		// The next step of a chained crossing, rather than more of the same one.
		//
		// One click crosses a whole line of stones, so the recording ran from the first bank
		// to the last and was stored as a single traversal: two hops, four tiles, one curve.
		// The table has a row per hop, so every hop then performed both — and a learned
		// route from bank to bank let golems clear the crossing without touching the middle
		// stone.
		//
		// The same clip starting again while the player rests on a tile they did not start
		// from is a new step. The same clip only, because a multi-part obstacle — mount,
		// cross, dismount — legitimately pauses between different clips and is one thing.
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

				complete(resting, tick);

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

		if (startedAt == null)
		{
			// Where the traversal began is where the player was a tick ago, not where
			// they are now.
			//
			// Movement and animation are separate things and arrive in either order. A
			// rock climb moves you first: by the time the clip fires you are already at
			// the far end, so anchoring on the current position recorded a traversal that
			// started and finished on the same tile and therefore never finished at all —
			// which is why one side of that climb learned nothing however many times it
			// was used.
			//
			// A tick earlier is the last place the player certainly was before whatever
			// the obstacle did to them. It is also close enough when they walked here
			// instead, being one tile back along the approach.
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

		// Everything held from before this moment is now known to matter.
		flushLookback();

		// Cycle-level recording starts here rather than at the click, and this is the
		// difference between a journal that can be left on forever and one that cannot.
		// Keyed to the click it followed every walk across every town: a click is common
		// and a traversal is rare, so tying the expensive rows to the rare event costs
		// almost nothing across a session and loses none of the interesting part.
		fineRemaining = TAIL_CYCLES;

		clips.add(playing);
		animTimeline.add(new int[]{client.getGameCycle(), playing});
		lastAnimTick = tick;
	}

	/**
	 * Finishes a traversal once the player has stopped moving somewhere new.
	 *
	 * <p>The end is as informative as the start. A looping clip hides its own length — a
	 * rock climb reports one animation change and then loops silently — so the only way to
	 * know how long it ran is the moment the player arrives.
	 */
	void onGameTick()
	{
		journalTick();

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

		if (startedAt == null && clips.isEmpty())
		{
			// Nothing is animating, so watch for the other kind of traversal: the kind
			// that plays no animation at all.
			//
			// Staircases are the large case and they were invisible to this class
			// entirely. A player clicks one, and a few ticks later they are several
			// thousand tiles away underground, having played nothing. Because capture only
			// ever began on an animation, no staircase in the game could be learned — and
			// "plays nothing" is a fact worth knowing, not an absence of one. A golem that
			// walks up a staircase in its ordinary pose is doing exactly the right thing.
			silentTraversal(was, at, tick, wasTemplate, previousTileTemplate);
			return;
		}

		if (startedAt == null || clips.isEmpty())
		{
			return;
		}

		// Finished means stopped, not merely between animations.
		//
		// A looping clip reports -1 between repeats, and a multi-tile traversal was being
		// declared over on its first tile because of it: a rock climb recorded a route of
		// 2550,2209 -> 2550,2208, one tile, which is not a climb at all. Requiring the
		// player to have actually settled — no animation and no movement since last tick —
		// waits for the whole obstacle.
		//
		// Or two ticks after the animation ended, moving or not. A player who clicks away the
		// moment an obstacle lets go of them never stands still, and waiting for them to kept
		// the traversal open until patience ran out and threw it away. What they do after
		// that point is not the obstacle, and the recording is cut where the obstacle ended.
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

	/** Turns the watched traversal into a sighting and hands it on. */
	private void complete(WorldPoint at, int tick)
	{
		int[] played = new int[clips.size()];
		for (int i = 0; i < played.length; i++)
		{
			played[i] = clips.get(i);
		}

		// Where the obstacle put the player, which is not always where they came to rest.
		// See animEndTile. A tile equal to the start is a clip that ended before its own
		// teleport — a ladder — and says nothing about the destination.
		WorldPoint to = at;
		if (animEndTile != null && !animEndTile.equals(startedAt) && !animEndTile.equals(at))
		{
			to = animEndTile;
		}

		// Where a golem gets on and off, which for some obstacles is not where the obstacle
		// itself begins and ends. The stile walks the player onto its own blocked tile before
		// climbing them and puts them down on another; routes learned between those two
		// tiles started and ended where no golem can stand. See wayIn and wayOut.
		WorldPoint landing = to;
		// The direction and the line in lasting coordinates. Across the edge of an instance the
		// raw tiles are thousands apart — the pew into the Mad Angel's room recorded a line of
		// 11486,5792 — while their templates are the tiles either side of the pew.
		WorldPoint landingTemplate = landing.equals(animEndTile) ? animEndTileTemplate : templateOf(landing);
		WorldPoint lineFrom = startedAtTemplate != null ? startedAtTemplate : startedAt;
		WorldPoint lineTo = landingTemplate != null ? landingTemplate : landing;
		int dirX = Integer.signum(lineTo.getX() - lineFrom.getX());
		int dirY = Integer.signum(lineTo.getY() - lineFrom.getY());
		boolean level = landing.getPlane() == startedAt.getPlane();
		WorldPoint origin = level ? wayIn(startedAt, dirX, dirY) : startedAt;
		to = level ? wayOut(landing, dirX, dirY) : landing;

		// The recording is the obstacle and nothing else: from the moment the player stands
		// on the tile it starts them from to the moment it puts them down.
		//
		// Everything either side is the player, not the obstacle — the walk up to it, the
		// click that sent them off the far side, the way a player spam-clicks their way
		// across an agility course. Those were being recorded, and a golem replayed them.
		// Where the route's ends lie a tile outside the obstacle, the steps between are
		// added as ordinary walking, the same for every recording of it: a stile climbed
		// from outside and one climbed from on top come out the same shape.
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

		// Timings read off the recording, which is exactly one step long, rather than
		// counted live. Counting live measured "longest unbroken run of movement" on a
		// position that stalls every other cycle, and came back as one cycle for nearly
		// every obstacle ever recorded.
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

		// The route's ends in lasting coordinates. Inside an instance that is its template —
		// captured when each position was seen, since the instance may be gone by now. The
		// ends are a tile either side of the obstacle's own start and landing, so the same
		// offset is carried over. Only if an end could not be placed is the sighting marked
		// as an instance, whose route is not kept.
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

		if (out != null)
		{
			write("SIGHTING", sighting.toString());
		}

		reset();

		// A traversal that did not move the player is not a traversal. This is the main
		// thing standing between an honest sighting and the player having been attacked
		// between clicking a ladder and reaching it.
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
	 * Records a traversal that moved the player without animating them.
	 *
	 * <p>Judged by the move itself rather than by the click: a plane change, or a jump no
	 * amount of running could cover in one tick. Walking to the obstacle looks nothing like
	 * either, which is what keeps the ordinary business of getting there out of the record.
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

			// A silent traversal has no clip to measure a window against, but it still has a
			// shape. Without one a staircase could never be performed from a recording, and
			// fell back to appearing at the far end on the tick the golem arrived.
			// The curve in the coordinates it was sampled in; the route's ends in lasting ones.
			// Leaving an instance by a silent exit is reported a tick after the overworld has
			// loaded, so the exit's own tile was placed in its template on the tick before.
			MotionCurve curve = silentCurve(from, to);
			WorldPoint start = placed ? fromTemplate : from;
			WorldPoint end = placed ? toTemplate : to;
			ObstacleSighting sighting = new ObstacleSighting(object, name, menu,
				new int[0], 1, start.getX(), start.getY(), start.getPlane(),
				end.getX(), end.getY(), end.getPlane(), !placed, 0, 0,
				curve, 0, 0,
				fromTemplate != null && !fromTemplate.equals(from),
				toTemplate != null && !toTemplate.equals(to));

			if (out != null)
			{
				write("SIGHTING", "silent " + sighting);
			}
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
	 * Drops the walk up to the obstacle, keeping the traversal itself.
	 *
	 * <p>Recording begins at the click, which is usually several seconds and several tiles
	 * before anything interesting. The traversal starts at the last moment the player was
	 * still standing on its origin tile, so everything before that is the approach and is
	 * thrown away.
	 */
	private List<int[]> trimToStart(List<int[]> all, WorldPoint from)
	{
		int start = startOfTraversal(all, from);
		if (start >= 0)
		{
			return all.subList(start, all.size());
		}
		// Never seen standing there: start at the animation rather than keep the whole
		// approach, which would be the player's walk and not the obstacle.
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
	 * The walk onto and off an obstacle, added where a route starts or ends a tile outside it.
	 *
	 * <p>Straight lines at a golem's walking pace, playing no animation — the golem's own
	 * walk is shown for them. Only the obstacle between is the player's.
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

		// Tile centres, because the samples are rendered positions and a player standing
		// on a tile is drawn at its middle.
		int originX = from.getX() * Golem.TILE + Golem.TILE / 2;
		int originY = from.getY() * Golem.TILE + Golem.TILE / 2;

		// The moment the player arrived on the origin tile, not the moment they left it.
		//
		// Taking the last sample at the origin cut the wind-up off every obstacle that has
		// one. A door stands still for thirty cycles and then teleports, and all of that
		// stillness is "at the origin" — so the recording began at the teleport and the
		// golem crossed instantly at the start of its animation instead of the end.
		//
		// So: find the last sample at the origin, then walk back to the beginning of the
		// unbroken run it belongs to. For a door that recovers the whole pause; for a climb,
		// which leaves immediately and never returns, it is the single sample before
		// departure. Both are the instant the traversal began.
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
	 * Drops whatever the player did after the obstacle finished with them.
	 *
	 * <p>The first moment, at or after the last animation ended, that the player is drawn
	 * on the destination. A door keeps its whole animation — the player arrives halfway
	 * through and stands there until it ends — and loses the walk that followed.
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
	 * Holds the recording still from the first teleport onward.
	 *
	 * <p>A cave mouth plays its animation and puts the player six thousand tiles away while
	 * it is still playing. Recorded as it is, that is a curve travelling six thousand tiles
	 * along the ground, which a golem would glide across the world performing. What a golem
	 * should do is stand there for the rest of the animation and then be put down at the far
	 * end, which is what holding the last position before the jump produces.
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
	 * The stillness on a silent obstacle before the player vanishes, as a recording.
	 *
	 * <p>Only the part spent on the origin tile: the approach is not the obstacle, and the
	 * far side is on another floor or across the map.
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

	/**
	 * The unbroken run of identical samples at the end of the recording, if it is a rest on
	 * a tile's centre long enough to end a step. Null otherwise.
	 */
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
	 * <p>Some obstacles start the player on a tile no golem can walk onto: clicking the stile
	 * walks you onto its blocked tile, and only then does the climb begin. Learned from
	 * there, the route started where no golem could ever stand, and the stile went unused
	 * however many golems passed it. The way in is the walkable tile behind the obstacle,
	 * along the line it moves the player.
	 *
	 * <p>Decided from the obstacle alone — its line and what can be walked on — and never
	 * from where the player happened to be. Players come at a stile from the side and step
	 * off it at an angle, and routes taken from their positions ran diagonally across it.
	 *
	 * <p>A stepping stone is blocked ground too, but behind it is water, so a crossing
	 * keeps starting on the stone it starts on.
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

	/**
	 * Where a traversal really ends, for a golem: the landing if it can be walked on, else the
	 * walkable tile past it along the same line, else the landing itself.
	 */
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

		// A click held back while the obstacle finished is the one the next traversal
		// belongs to.
		if (queuedObject >= 0)
		{
			clickedObject = queuedObject;
			clickedName = queuedName;
			clickedMenu = queuedMenu;
			clickedTick = queuedTick;
			queuedObject = -1;
		}
	}

	// ------------------------------------------------------------------- journal

	/** Writes the per-tick position row, and notes pose and plane changes. */
	private void journalTick()
	{
		if (out == null)
		{
			return;
		}

		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}

		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		boolean instanced = view != null && view.isInstance();
		WorldPoint template = instanced ? templateOf(local.getWorldLocation()) : null;
		write("TICK", "instance=" + instanced + (template == null ? ""
			: " template=" + template.getX() + "," + template.getY() + "," + template.getPlane()));

		int pose = local.getPoseAnimation();
		if (pose != lastPose)
		{
			write("POSE", "pose=" + pose + " idle=" + local.getIdlePoseAnimation());
			lastPose = pose;
		}

		WorldPoint at = local.getWorldLocation();
		if (at != null && at.getPlane() != lastPlane)
		{
			write("PLANE", "from=" + lastPlane + " to=" + at.getPlane());
			lastPlane = at.getPlane();
		}

		out.flush();
	}

	/**
	 * Writes the player's exact position while a traversal is in progress.
	 *
	 * <p>A game tick is 600ms and movement is interpolated thirty times inside it, so tick
	 * resolution cannot tell a walk from a glide from a teleport. That distinction is the
	 * whole question for the golems, which have been accused of all three, so these rows
	 * are in local coordinates — 128ths of a tile, the same unit the client animates in and
	 * the same unit the golems are simulated in.
	 */
	void onClientTick()
	{
		if (out == null)
		{
			return;
		}

		Player local = client.getLocalPlayer();
		LocalPoint fine = local == null ? null : local.getLocalLocation();

		if (fine != null)
		{
			// Always held, whether or not anything is being recorded. This is the only
			// copy of what happened just before a traversal was noticed.
			lookback[lookbackAt][0] = client.getGameCycle();
			lookback[lookbackAt][1] = fine.getX();
			lookback[lookbackAt][2] = fine.getY();
			lookbackAt = (lookbackAt + 1) % LOOKBACK_CYCLES;
			lookbackHeld = Math.min(lookbackHeld + 1, LOOKBACK_CYCLES);
		}

		// The motion itself, kept sample by sample. This is what a golem performs; the
		// window below is a summary of it, useful for reading and for obstacles no curve
		// was ever captured for.
		// Sampled from the click onward, not from the animation.
		//
		// On some obstacles the movement happens *before* the clip — a rock climb puts you
		// at the far side and animates you afterwards. Starting the recording at the
		// animation meant every sample was already at the destination, so the golem jumped
		// there on the first cycle and then wandered along the leftover noise. The leading
		// approach is trimmed off at the end instead, once the traversal's true start tile
		// is known.
		//
		// A rolling window rather than a cap. Capped, a long walk to the obstacle filled it
		// before the player arrived, and the traversal itself was never sampled.
		if (clickedTick >= 0 && local != null)
		{
			net.runelite.api.WorldView view = client.getTopLevelWorldView();
			LocalPoint fineNow = local.getLocalLocation();
			if (view != null && fineNow != null)
			{
				// The *rendered* position, and only that.
				//
				// A traversal moves the player's logical tile to the destination
				// immediately and then slides the drawn position across — which is what
				// makes a rock climb look like a climb while getWorldLocation() already
				// reads the top. Combining the two, as this did, produced the destination
				// tile with the departure's sub-tile offset: a recording that began at the
				// far side and then wandered. The golem jumped and flew around, which is
				// precisely what was drawn.
				//
				// The local position is the animation's own truth, so the scene base is
				// added to it rather than the logical tile being consulted at all.
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

		// The movement window, measured against the clip rather than the tick.
		//
		// A traversal holds the player still for part of its animation and then moves them
		// quickly — 33 cycles of stillness then 12 of movement, on the stones. Recording
		// only the total made every golem start drifting on the first frame.
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

		if (fineRemaining <= 0)
		{
			return;
		}
		fineRemaining--;

		// The keyframe too. Movement and animation are two clocks, and whether a golem's
		// hop plays at the right moment in its glide can only be judged against the frame
		// the player's own hop was on when they left the ground.
		write("FINE", fine == null ? "local=none"
			: "localX=" + fine.getX() + " localY=" + fine.getY()
				+ " frame=" + (local == null ? -1 : local.getAnimationFrame()));
	}

	/** Writes the held positions, oldest first, as the run-up to a traversal. */
	private void flushLookback()
	{
		if (out == null || lookbackHeld == 0)
		{
			return;
		}

		int start = (lookbackAt - lookbackHeld + LOOKBACK_CYCLES) % LOOKBACK_CYCLES;
		for (int i = 0; i < lookbackHeld; i++)
		{
			int[] entry = lookback[(start + i) % LOOKBACK_CYCLES];
			out.println(entry[0] + "	-1	PRE	-1	-1	-1	-1	-1	-1	localX="
				+ entry[1] + " localY=" + entry[2]);
		}
		lookbackHeld = 0;
	}

	/** One journal row: when, what, where, and the detail for this kind of event. */
	private void write(String type, String detail)
	{
		if (out == null)
		{
			return;
		}

		Player local = client.getLocalPlayer();
		WorldPoint at = local == null ? null : local.getWorldLocation();

		out.println(client.getGameCycle()
			+ "\t" + client.getTickCount()
			+ "\t" + type
			+ "\t" + (at == null ? -1 : at.getX())
			+ "\t" + (at == null ? -1 : at.getY())
			+ "\t" + (at == null ? -1 : at.getPlane())
			+ "\t" + (local == null ? -1 : local.getAnimation())
			+ "\t" + (local == null ? -1 : local.getPoseAnimation())
			+ "\t" + (local == null ? -1 : local.getOrientation())
			+ "\t" + detail);
	}

	/** Writes what the cache knows about an object, once per click. */
	private void describeObject(int id)
	{
		try
		{
			ObjectComposition def = client.getObjectDefinition(id);
			if (def == null)
			{
				return;
			}

			StringBuilder actions = new StringBuilder();
			String[] ops = def.getActions();
			if (ops != null)
			{
				for (String op : ops)
				{
					if (op != null && !op.isEmpty())
					{
						actions.append(actions.length() == 0 ? "" : ",").append(op);
					}
				}
			}

			// An impostor is the object the varbits actually resolve this one to. Where
			// there is one, its id is the id the game is really using.
			int impostor = -1;
			try
			{
				ObjectComposition real = def.getImpostor();
				if (real != null)
				{
					impostor = real.getId();
				}
			}
			catch (RuntimeException e)
			{
				// No impostor ids on this definition. Normal for most objects.
			}

			write("OBJ", "id=" + id + " name=\"" + def.getName() + "\""
				+ " actions=\"" + actions + "\" impostor=" + impostor);
		}
		catch (RuntimeException e)
		{
			write("OBJ", "id=" + id + " lookup-failed");
		}
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
	 * Positions kept in lasting coordinates alongside the ones sampled: the template of an
	 * instance tile, captured while that instance is still the loaded scene. See InstanceMap.
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
