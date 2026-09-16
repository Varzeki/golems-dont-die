package com.golemsdontdie;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Renderable;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

/**
 * Keeps crafted golems alive by replacing each one with a client-side copy, so it
 * wanders Wyrmscraig indefinitely instead of crumbling after half a minute.
 *
 * <p>The handover happens as the golem finishes stepping off its plinth — the one
 * moment it is reliably standing still, so the copy inherits a position and heading
 * with no half-finished step to reconcile. The original is hidden from that point on
 * and crumbles unseen.
 *
 * <p>Copies are simulated in world coordinates, walking and pausing and turning under
 * their own steam, and are drawn only while inside the loaded scene. That separation
 * is what lets a golem wander off the edge of what is loaded and come back later:
 * leaving the scene costs it its renderer, not its existence.
 *
 * <p>Golems are not confined to Wyrmscraig. They use the game's transport network —
 * ladders, shortcuts, gangplanks — and sail between ports, so a golem made on the island
 * may turn up anywhere. What keeps that affordable is that cost scales with what the
 * player can see rather than with how many golems exist: a golem outside the scene is
 * stored as a route and a departure time and is not stepped at all. See {@link GolemTier}.
 *
 * <p>Purely cosmetic. Nothing here sends anything to the server, and the copies are
 * visible only to the player running the plugin.
 *
 * @see GolemTier for how much of a golem is simulated
 * @see WorldMesh for where a golem may walk
 * @see Golem for the simulation
 * @see FakeGolem for the drawing
 */
@Slf4j
@PluginDescriptor(
	name = "Golems Don't Die",
	description = "Makes Golems live and wander around forever instead of dying",
	tags = {"golem", "crafting", "wyrmscraig", "skilling", "cosmetic", "npc"}
)
public class GolemsDontDiePlugin extends Plugin
{
	/** Game ticks between position saves. Two minutes: cheap, and bounds what a crash costs. */
	private static final int SAVE_INTERVAL_TICKS = 200;

	/**
	 * Path searches allowed per frame across all golems. At 50fps this is 200 a
	 * second, far more than a roaming population actually asks for, while still
	 * capping the worst frame.
	 */
	private static final int PATH_SEARCHES_PER_FRAME = 4;

	/**
	 * Routes planned per frame for golems out of view.
	 *
	 * <p>A plan is about half a millisecond, and several times that where it has to flood a
	 * dungeon for somewhere to go. A thousand golems replan every fifteen ticks or so between
	 * them, which is plenty spread out on average and not at all when a crowd arrives
	 * somewhere together. A golem over budget stands where it arrived for another frame.
	 */
	private static final int FAR_PLANS_PER_FRAME = 6;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private RenderCallbackManager renderCallbacks;

	@Inject
	private ConfigManager configManager;

	@Inject
	private GolemsDontDieConfig config;

	@Inject
	private GolemDetector detector;

	@Inject
	private GolemModelFactory modelFactory;

	@Inject
	private GolemPathfinder pathfinder;

	@Inject
	private IslandMemory islandMemory;

	@Inject
	private TransportNetwork transports;

	@Inject
	private GolemAbilities abilities;

	@Inject
	private WorldMesh worldMesh;

	@Inject
	private RoamPlanner roamPlanner;

	@Inject
	private PropFactory propFactory;

	@Inject
	private SailingDocks sailingDocks;

	@Inject
	private RaftFactory raftFactory;

	/**
	 * Watches the player use obstacles and teaches the plugin what each one does.
	 *
	 * <p>Not a developer tool any more. Golems may only use obstacles whose animation is
	 * known, and this is what makes that set grow: see {@link ObstacleKnowledge}.
	 */
	@Inject
	private ObstacleObserver obstacleObserver;

	@Inject
	private ObstacleKnowledge obstacleKnowledge;

	@Inject
	private ObstacleTelemetry obstacleTelemetry;

	/** Built once at start-up and reused every frame. See {@link RoamContext}. */
	private RoamContext roamContext;

	@Inject
	private GolemStore store;

	@Inject
	private GolemMenu menu;

	@Inject
	private GolemTally tally;

	private final List<Golem> golems = new ArrayList<>();

	/** Real NPCs hidden because a copy has taken over from them. */
	private final Set<Integer> hiddenNpcs = new HashSet<>();

	/** Saved golems waiting for the cache to become readable. */
	private final List<GolemStore.SavedGolem> pendingRestore = new ArrayList<>();

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private GolemMinimapOverlay minimapOverlay;

	@Inject
	private ObstacleHighlightOverlay obstacleHighlightOverlay;

	@Inject
	private ObstacleIndex obstacleIndex;

	private GolemListPanel panel;
	private NavigationButton navButton;

	/**
	 * Hides the real golem once its copy has taken over.
	 *
	 * <p>Registered once at start-up and unregistered once at shutdown, never from an
	 * event handler — a registration that outlives the plugin because an event did not
	 * fire as expected would leave NPCs invisible with nothing left to un-hide them.
	 */
	private final RenderCallback drawCallback = new RenderCallback()
	{
		@Override
		public boolean addEntity(Renderable renderable, boolean ui)
		{
			return shouldDraw(renderable);
		}
	};

	private boolean callbackRegistered = false;

	private int lastGameCycle = -1;
	private int ticksSinceSave = 0;

	/** Set when a golem is added, removed or restored, so the panel redraws once. */
	private boolean rosterChanged = true;

	// ------------------------------------------------------------------ obstacles

	/**
	 * Takes one sighting from the observer and does something with it.
	 *
	 * <p>The player is told only when an obstacle actually unlocks, not on every sighting.
	 * A message per traversal would be chat spam for somebody running an agility course,
	 * and the interesting moment is the one where golems gained something.
	 */
	private void onObstacleSighting(ObstacleSighting sighting)
	{
		if (!config.learnObstacles())
		{
			return;
		}

		// Only obstacles. Chopping a tree, using a bank booth and picking sweetcorn were all
		// learned as shortcuts: an object clicked, an animation, and the player somewhere
		// else afterwards is all a watcher can see. Not learned, and not sent.
		if (!isObstacle(sighting.objectId, sighting.fromX, sighting.fromY, sighting.fromPlane,
			sighting.toX, sighting.toY, sighting.toPlane))
		{
			log.debug("Not an obstacle: {}", sighting);
			return;
		}

		// Deliberately silent. Learning happens constantly during ordinary play and
		// announcing it would be chat spam for anybody running an agility course — and
		// worse, it would make a background nicety feel like something the player is
		// supposed to be doing. Anyone who wants to see the state of it can turn on the
		// highlight overlay.
		if (obstacleKnowledge.record(sighting))
		{
			log.debug("Obstacle unlocked: {}", sighting);
		}

		// Routes go in as they are earned rather than at the next start-up. Somebody who
		// has just shown the plugin a staircase twice should see golems use it, not be
		// told to log out first.
		transports.setLearnedRoutes(obstacleKnowledge.learnedRoutes());
		// A new route can lead onto a floor nothing else reaches; it is ground from now on.
		worldMesh.admitTransportEnds(transports.all());
		saveLearnedObstacles();

		obstacleTelemetry.offer(sighting);
	}

	/** Coordinates this far east are an instance's, never the world's. */
	private static final int INSTANCE_X = 6400;

	/**
	 * Whether something the player used is an obstacle — a way across — rather than
	 * anything else they clicked and then moved away from.
	 *
	 * <p>Yes if the cache lists it as one, or the transport tables do. Otherwise only if it
	 * took the player somewhere they could not simply have walked: another floor, a long
	 * way off, or a place their own map has no short walk to. A tree fails that — the player
	 * walked up to it and walked on — and so does a door, once open.
	 *
	 * <p>Routes still in an instance's own coordinates are refused whatever they are: they
	 * point at a room that no longer exists.
	 */
	private boolean isObstacle(int objectId, int fromX, int fromY, int fromPlane, int toX, int toY, int toPlane)
	{
		if (fromX >= INSTANCE_X || toX >= INSTANCE_X)
		{
			return false;
		}
		if (obstacleIndex.knows(objectId) || transports.archetypeFor(objectId) >= 0)
		{
			return true;
		}
		if (fromPlane != toPlane || !RouteGeometry.local(toX - fromX, toY - fromY))
		{
			return true;
		}
		int steps = Math.max(Math.abs(toX - fromX), Math.abs(toY - fromY));
		java.util.Deque<int[]> walk = pathfinder.findPath(fromX, fromY, fromPlane, toX, toY,
			new RoamBounds(islandMemory, fromPlane, fromX, fromY));
		return walk.isEmpty() || walk.size() > steps * 2 + 4;
	}

	private void saveLearnedObstacles()
	{
		configManager.setConfiguration(GolemsDontDieConfig.GROUP,
			ObstacleKnowledge.LEARNED_KEY, obstacleKnowledge.serialise());
		configManager.setConfiguration(GolemsDontDieConfig.GROUP,
			ObstacleKnowledge.CONFIRMED_KEY, obstacleKnowledge.serialiseConfirmed());
		configManager.setConfiguration(GolemsDontDieConfig.GROUP,
			ObstacleKnowledge.ROUTES_KEY, obstacleKnowledge.serialiseRoutes());
		configManager.setConfiguration(GolemsDontDieConfig.GROUP,
			ObstacleKnowledge.CURVES_KEY, obstacleKnowledge.serialiseCurves());
		configManager.setConfiguration(GolemsDontDieConfig.GROUP,
			ObstacleKnowledge.LINES_KEY, obstacleKnowledge.serialiseLines());
	}

	/**
	 * Writes what every golem near the player is doing, for reading against the journal.
	 *
	 * <p>Confined to golems in the scene, which is both the only place their behaviour can
	 * be watched and the only place the volume is bearable — there are four hundred and
	 * fifty of them and a row each per tick for all of them would be megabytes a minute.
	 *
	 * <p>The point is the comparison. The same file already holds the player's position at
	 * 20ms and every animation they start, so a golem crossing the same stepping stone can
	 * be held against the real thing on the same clock rather than described from memory.
	 */
	private void logGolemState()
	{
		if (!obstacleObserver.isLoggingGolems())
		{
			return;
		}

		for (Golem golem : golems)
		{
			if (golem.getTier() != GolemTier.SCENE)
			{
				continue;
			}
			FakeGolem drawn = golem.getRenderer();
			obstacleObserver.writeGolem(String.valueOf(golem.getId()), golem.debugState()
				+ (drawn == null ? " undrawn" : " drawnAgo=" + (client.getGameCycle() - drawn.getLastDrawnCycle())));
			checkStep(golem);
			checkStanding(golem);
		}
	}

	/** Pushes the obstacle settings into the pieces that act on them. */
	private void applyObstacleSettings()
	{
		obstacleTelemetry.configure(config.sendObstacleTelemetry(), config.telemetryEndpoint());
		obstacleObserver.setExplaining(config.highlightObstacles());
		obstacleObserver.setLoggingGolems(config.logGolemState());
	}

	@Provides
	GolemsDontDieConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GolemsDontDieConfig.class);
	}

	@Override
	protected void startUp()
	{
		// Obstacles the player has already taught us, and the settings that govern
		// learning. Loaded before the transport network, because the network's usability
		// gate consults it the first time a golem considers a shortcut.
		obstacleKnowledge.deserialise(
			configManager.getConfiguration(GolemsDontDieConfig.GROUP, ObstacleKnowledge.LEARNED_KEY));
		obstacleKnowledge.deserialiseConfirmed(
			configManager.getConfiguration(GolemsDontDieConfig.GROUP, ObstacleKnowledge.CONFIRMED_KEY));
		obstacleKnowledge.deserialiseRoutes(
			configManager.getConfiguration(GolemsDontDieConfig.GROUP, ObstacleKnowledge.ROUTES_KEY));
		obstacleKnowledge.deserialiseCurves(
			configManager.getConfiguration(GolemsDontDieConfig.GROUP, ObstacleKnowledge.CURVES_KEY));
		obstacleKnowledge.deserialiseLines(
			configManager.getConfiguration(GolemsDontDieConfig.GROUP, ObstacleKnowledge.LINES_KEY));
		applyObstacleSettings();

		obstacleObserver.setOnSighting(this::onObstacleSighting);
		obstacleObserver.startUp();

		// Saved map first, then the shipped baseline underneath it — anything the
		// player has actually walked beats a static export of the same ground.
		islandMemory.deserialise(configManager.getConfiguration(GolemsDontDieConfig.GROUP, IslandMemory.MAP_KEY));
		islandMemory.loadBundled();
		// Anywhere a transport from the island leads is ground golems will walk, so it is
		// mapped too — a basement, a cave. Resolved lazily, after the network has loaded.
		islandMemory.setAlsoIsland(transports::leadsToRegion);

		// The transport table is read-only and shared by every golem, so it is loaded
		// once here rather than lazily on first use — a first-use load would land in the
		// middle of a frame, which is the one place a few milliseconds is noticeable.
		worldMesh.load();
		propFactory.load();
		transports.load();
		// The index before the routes, because the routes are filtered by it.
		obstacleIndex.load();
		obstacleKnowledge.setRouteFilter(r -> isObstacle(r[0], r[1], r[2], r[3], r[4], r[5], r[6]));
		transports.setLearnedRoutes(obstacleKnowledge.learnedRoutes());
		// Floors reached only by transports the land fill was never given. See WorldMesh.
		worldMesh.admitTransportEnds(transports.all());
		roamContext = new RoamContext(islandMemory, pathfinder, transports, abilities);
		roamContext.setKnowledge(obstacleKnowledge);
		roamContext.setObstacles(obstacleIndex);
		roamContext.setJournal((golem, what) ->
		{
			if (obstacleObserver.isLoggingGolems())
			{
				obstacleObserver.writeGolemEvent(String.valueOf(golem.getId()), what);
			}
		});
		roamContext.setPlanner(roamPlanner);
		roamContext.setModels(modelFactory);

		pendingRestore.addAll(store.deserialise(
			configManager.getConfiguration(GolemsDontDieConfig.GROUP, GolemsDontDieConfig.SAVED_GOLEMS_KEY)));

		tally.load();

		// All three panel callbacks arrive on the Swing thread and touch the roster, so
		// each hops to the client thread — the list is owned there.
		panel = new GolemListPanel(
			golem -> clientThread.invoke(() -> removeGolem(golem)),
			() -> clientThread.invoke(this::saveGolems),
			() -> clientThread.invoke(this::reviveMissing));
		navButton = NavigationButton.builder()
			.tooltip("Golems")
			.icon(ImageUtil.loadImageResource(GolemsDontDiePlugin.class, "/golem-icon.png"))
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		overlayManager.add(minimapOverlay);
		overlayManager.add(obstacleHighlightOverlay);

		renderCallbacks.register(drawCallback);
		callbackRegistered = true;

		lastGameCycle = -1;
		clientThread.invokeLater(this::restorePending);
	}

	@Override
	protected void shutDown()
	{
		// Closed first, because a measuring session that is never repeated must not lose
		// its last rows to something going wrong further down this method.
		obstacleObserver.shutDown();
		obstacleTelemetry.flush();
		saveLearnedObstacles();

		// Save before tearing down, or a shutdown would be indistinguishable from
		// deleting every golem the player has.
		saveGolems();
		saveIslandMemory();

		clientThread.invoke(() ->
		{
			for (Golem golem : golems)
			{
				detachRenderer(golem);
			}
			golems.clear();
			clearProps();
			clearRafts();
			propFactory.clear();
			raftFactory.clear();
		});

		overlayManager.remove(minimapOverlay);
		overlayManager.remove(obstacleHighlightOverlay);

		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		panel = null;

		pendingRestore.clear();
		hiddenNpcs.clear();
		detector.reset();
		modelFactory.clear();

		if (callbackRegistered)
		{
			renderCallbacks.unregister(drawCallback);
			callbackRegistered = false;
		}
	}

	// ---- watching the real golems ----

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		NPC npc = event.getNpc();
		if (!detector.isGolem(npc))
		{
			return;
		}

		detector.track(npc);
		islandMemory.anchorAt(npc.getWorldLocation());
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		if (tally.observe(event.getMessage()))
		{
			rosterChanged = true;
		}
	}

	/**
	 * Brings the roster back up to the number of golems ever crafted.
	 *
	 * <p>Revived golems are placed on the plinth and disperse on their own, exactly as
	 * a freshly made one would. They are new golems standing in for lost ones rather
	 * than the originals restored — nothing about a golem that was culled or never
	 * tracked survives to be brought back.
	 */
	private void reviveMissing()
	{
		int missing = tally.getTotal() - livingGolems().size();
		if (missing <= 0)
		{
			return;
		}

		WorldPoint plinth = new WorldPoint(GolemContent.PLINTH_X, GolemContent.PLINTH_Y, 0);
		int revived = 0;

		for (int i = 0; i < missing; i++)
		{
			GolemSnapshot snapshot = GolemSnapshot.restore(
				client, GolemContent.GOLEM_NPC_ID,
				GolemContent.GOLEM_IDLE_ANIMATION, GolemContent.GOLEM_WALK_ANIMATION, -1,
				plinth, 0);
			if (snapshot == null)
			{
				log.debug("Cannot revive golems: npc {} not in cache", GolemContent.GOLEM_NPC_ID);
				break;
			}

			// Seeded off a counter as well as the tile so a batch revived onto one
			// square does not walk away in lockstep.
			long seed = ((long) plinth.getX() << 32) ^ ((long) plinth.getY() << 8)
				^ ((golems.size() + i) * 0x9E3779B9L);
			golems.add(Golem.onTile(snapshot, plinth, seed, plinth));
			revived++;
		}

		if (revived > 0)
		{
			rosterChanged = true;
			saveGolems();
			log.debug("Revived {} golems", revived);
		}
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		obstacleObserver.onAnimationChanged(event);

		if (!(event.getActor() instanceof NPC))
		{
			return;
		}
		NPC npc = (NPC) event.getActor();
		if (!detector.isGolem(npc))
		{
			return;
		}

		int animation = npc.getAnimation();
		if (detector.wasReplaced(npc))
		{
			return;
		}

		// The handover happens as the golem finishes stepping off its plinth, not when
		// it crumbles.
		//
		// Waiting for the crumble meant taking over from a golem that was usually
		// mid-stride, and a copy handed a walk it did not start has to reconcile a
		// position, a heading and a half-finished step all at once. Taking over at the
		// one moment the golem is reliably standing still — the tick the plinth
		// animation ends — means there is nothing to reconcile. The original is hidden
		// for the rest of its short life and crumbles unseen.
		if (animation == -1 && detector.hasLeftPlinth(npc))
		{
			replace(npc);
			return;
		}

		if (animation == GolemContent.GOLEM_SPAWN_ANIMATION)
		{
			detector.noteLeftPlinth(npc);
		}

		// Fallback. If a golem is somehow never seen stepping off — the player arrived
		// mid-life, or the spawn animation was missed — the crumble is still a valid
		// moment to take over, and a jump is better than losing the golem.
		if (detector.isDeathAnimation(animation))
		{
			replace(npc);
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		NPC npc = event.getNpc();
		if (!detector.isGolem(npc))
		{
			return;
		}

		hiddenNpcs.remove(npc.getIndex());
		detector.forget(npc);
	}

	/**
	 * Takes over from a live golem: builds the copy, and hides the original from this
	 * moment on.
	 *
	 * <p>The copy is placed at the NPC's <i>local</i> location, not its world tile.
	 * That distinction is the whole difference between a seamless handover and a
	 * visible jump: {@link NPC#getWorldLocation()} returns the tile the golem is
	 * moving onto, so a golem caught mid-step hands over a destination it has not
	 * reached yet and the copy appears to teleport there. {@link NPC#getLocalLocation()}
	 * is the interpolated position actually being drawn.
	 */
	private void replace(NPC npc)
	{
		if (!hasRoomForAnother())
		{
			// Every slot is spoken for by a named golem. Leave this one alone and let
			// the game play out its own death.
			detector.markReplaced(npc);
			return;
		}

		GolemSnapshot snapshot = detector.snapshotOf(npc);
		if (snapshot == null)
		{
			snapshot = GolemSnapshot.of(npc);
		}
		if (snapshot == null)
		{
			log.debug("No usable snapshot for golem {}, letting it die", npc.getId());
			return;
		}

		WorldView wv = client.getTopLevelWorldView();
		LocalPoint local = npc.getLocalLocation();
		if (wv == null || local == null)
		{
			return;
		}

		// Local coordinates are scene-relative; the simulation is world-absolute.
		int fineX = local.getX() + wv.getBaseX() * Golem.TILE;
		int fineY = local.getY() + wv.getBaseY() * Golem.TILE;

		WorldPoint home = npc.getWorldLocation();
		long seed = ((long) home.getX() << 32) ^ ((long) home.getY() << 8) ^ npc.getIndex();

		// Orientation is taken live rather than from the snapshot, so the copy starts
		// facing exactly where the original was facing on this frame.
		GolemSnapshot posed = snapshot.facing(npc.getCurrentOrientation());
		Golem golem = new Golem(posed, home, seed, fineX, fineY);

		golems.add(golem);
		rosterChanged = true;
		enforceGolemLimit();

		detector.markReplaced(npc);
		hiddenNpcs.add(npc.getIndex());
		saveGolems();
	}

	/**
	 * Retires the oldest unnamed golems until the roster is inside the limit.
	 *
	 * <p>Unlimited by default. A golem costs an animated model drawn each frame, so a
	 * cap is worth having available, but choosing it for the player would quietly
	 * delete golems they had collected on purpose.
	 *
	 * <p><b>Named golems are never culled</b>, even when that leaves the roster over
	 * the limit. A name is the one unambiguous signal that the player cares about a
	 * particular golem, and a performance setting silently deleting the one they called
	 * Dave would be indefensible. Better to sit above the cap.
	 *
	 * <p>Retired golems crumble rather than vanishing; the roster is trimmed when the
	 * animation finishes.
	 */
	private void enforceGolemLimit()
	{
		if (!config.limitGolems())
		{
			return;
		}

		int limit = Math.max(1, config.maxGolems());
		int alive = countLiving();

		for (Golem golem : golems)
		{
			if (alive <= limit)
			{
				break;
			}
			if (golem.isDying() || isNamed(golem))
			{
				continue;
			}
			golem.startDying();
			alive--;
		}
	}

	/**
	 * The roster as anything outside the client thread should see it: a copy, with
	 * golems that are mid-crumble already gone.
	 *
	 * <p>A copy because the panel reads it on the Swing thread while this one keeps
	 * changing it. Crumbling golems are excluded so that pressing ✕ removes the entry
	 * at once rather than leaving it listed for the two seconds it takes to fall apart.
	 */
	private List<Golem> livingGolems()
	{
		List<Golem> living = new ArrayList<>(golems.size());
		for (Golem golem : golems)
		{
			if (!golem.isDying())
			{
				living.add(golem);
			}
		}
		return living;
	}

	/** Golems not already crumbling. A dying golem is on its way out and does not count. */
	private int countLiving()
	{
		int alive = 0;
		for (Golem golem : golems)
		{
			if (!golem.isDying())
			{
				alive++;
			}
		}
		return alive;
	}

	private static boolean isNamed(Golem golem)
	{
		String name = golem.getNickname();
		return name != null && !name.isEmpty();
	}

	/**
	 * Whether a new golem can be taken over.
	 *
	 * <p>False when the roster is full of golems the player has named: there is nothing
	 * to make room with, and culling a named golem is not on the table. In that case the
	 * plugin stands aside entirely and lets the real golem live out its twenty seconds
	 * and crumble, which is the honest outcome — better than taking one over and
	 * immediately killing something else to pay for it.
	 */
	private boolean hasRoomForAnother()
	{
		if (!config.limitGolems())
		{
			return true;
		}
		if (countLiving() < Math.max(1, config.maxGolems()))
		{
			return true;
		}
		for (Golem golem : golems)
		{
			if (!golem.isDying() && !isNamed(golem))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Retires one golem, from the side panel's X button. Client thread only.
	 *
	 * <p>It crumbles rather than vanishing, and leaves the roster when the animation
	 * finishes. The panel drops it from the list straight away so the button feels
	 * immediate — the golem the player is watching is on its way out either way.
	 */
	private void removeGolem(Golem golem)
	{
		golem.startDying();
		rosterChanged = true;
		saveGolems();
	}

	// ---- simulation ----

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		if (golems.isEmpty())
		{
			return;
		}

		int cycle = client.getGameCycle();
		int elapsed = lastGameCycle < 0 ? 0 : cycle - lastGameCycle;
		lastGameCycle = cycle;

		// A negative or absurd delta means the cycle counter was reset by a world hop
		// or a reconnect. Skip that frame rather than teleporting every golem.
		if (elapsed < 0 || elapsed > 200)
		{
			elapsed = 0;
		}

		// Path searches are rationed per frame. A golem only searches when it finishes
		// a walk, which is rare individually — but with hundreds of them the arrivals
		// bunch up, and a frame that runs fifty breadth-first searches is a visible
		// stutter. Golems denied a search simply stand still for a frame and ask again,
		// which is indistinguishable from the pause they take between walks anyway.
		int searchBudget = PATH_SEARCHES_PER_FRAME;
		int farPlanBudget = FAR_PLANS_PER_FRAME;

		// Resolved once rather than per golem. Both of these were being fetched or
		// built five hundred times a frame to produce the same answer.
		WorldView wv = client.getTopLevelWorldView();

		// One context for the whole frame. Only the two per-golem knobs change as the
		// roster is walked; everything else in it is the same for every golem.
		roamContext.setTick(client.getTickCount());
		roamContext.setMaySearchSea(true);

		Player local = client.getLocalPlayer();
		WorldPoint playerAt = local == null ? null : local.getWorldLocation();

		// Where golems are standing, for keeping them from piling onto one tile.
		Map<Long, Integer> occupancy = new HashMap<>();
		for (Golem golem : golems)
		{
			if (golem.getTier() != GolemTier.FAR)
			{
				occupancy.merge(RoamContext.tileKey(golem.getFineX() / Golem.TILE,
					golem.getFineY() / Golem.TILE, golem.getPlane()), 1, Integer::sum);
			}
		}
		roamContext.setOccupancy(occupancy);

		// Golems drawn per tile this frame. See updateRenderer.
		Map<Long, Integer> drawnPerTile = new HashMap<>();

		// Inside an instance, which template chunks it is built from and where the player is
		// in template terms. Golems live in the template; see tierFor.
		Map<Long, int[]> sceneChunks = wv != null && wv.isInstance() ? InstanceMap.sceneChunks(wv) : null;
		WorldPoint playerTemplate = sceneChunks == null ? playerAt : InstanceMap.templateOf(wv, playerAt);

		for (Golem golem : golems)
		{
			// Which tier this golem is in is a question about this golem, not about the
			// player's proximity to Wyrmscraig. That distinction is the whole change: the
			// old gate switched every golem off at once whenever the player walked away
			// from the island, which was correct only while the island was all there was.
			GolemTier tier = tierFor(golem, wv, playerAt, playerTemplate, sceneChunks);

			golem.setTier(tier, roamContext, roamPlanner);

			if (tier == GolemTier.FAR)
			{
				if (golem.advanceFar(roamContext, roamPlanner, farPlanBudget > 0))
				{
					farPlanBudget--;
				}
			}
			else if (elapsed > 0)
			{
				roamContext.setMayPath(searchBudget > 0);
				if (golem.advance(elapsed, roamContext))
				{
					searchBudget--;
				}
			}

			rescueIfStuck(golem, tier);

			updateRenderer(golem, wv, tier == GolemTier.SCENE, drawnPerTile);
			updateRaft(golem, tier == GolemTier.SCENE);

			// Scenery is only animated for a golem the player can actually see. A prop
			// request from a golem three regions away is dropped rather than queued —
			// nobody watched the plank lower, so there is nothing to catch up on.
			GolemTransport prop = golem.claimPendingProp();
			if (prop != null && tier == GolemTier.SCENE)
			{
				spawnProp(prop, wv);
			}
		}

		advanceProps();
		reapCrumbled();
	}

	/**
	 * Moves a golem that has stopped getting anywhere.
	 *
	 * <p>The watchdog on {@link Golem} decides <em>whether</em>; this decides <em>where</em>,
	 * because only the plugin has the mesh. The golem is put on the nearest tile it could
	 * walk out of, and if the mesh offers nothing within range it goes home to the plinth —
	 * which always exists and is always walkable, and is where golems come from anyway.
	 *
	 * <p>It is moved, never replaced. Same name, same id, same seed, same gait. Deleting a
	 * golem to resolve a navigation problem would be a death, and the plugin is called
	 * Golems Don't Die.
	 *
	 * <p>Relocation while the golem is on screen is deliberately still allowed. Sliding a
	 * visible golem a few tiles is odd; leaving it frozen in scenery forever is worse, and
	 * a stuck golem is nearly always somewhere nobody is looking anyway.
	 */
	private void rescueIfStuck(Golem golem, GolemTier tier)
	{
		int tick = roamContext.getTick();

		// A golem the player can see, standing somewhere it could not have walked to, is
		// wrong now rather than in five minutes' time — so it is put right immediately
		// instead of waiting for the watchdog's patience to run out.
		//
		// This is a net under everything else: however a golem came to be on water or
		// inside scenery, it does not stay there while being looked at. A stepping stone
		// is the one legitimate exception, being blocked ground the network says you may
		// stand on.
		// A golem mid-obstacle is exempt, because half of them are legitimately standing on
		// nothing. A stepping-stone hop passes over open water, a vault crosses a ditch, and
		// a climb hangs off a cliff face — all of them unwalkable ground the golem is
		// supposed to be over.
		//
		// Without this the watchdog fired every tick of every crossing and called relocate,
		// which drops the path, the step and the itinerary. That is what was hauling golems
		// out of hops and leaving them sliding: they were not escaping the stones, they were
		// being pulled off them by the thing meant to rescue them. The journal shows one
		// golem triggering it 8,855 times on a single tile between two basalt stones.
		//
		// isStuck() below already exempts transitions. This check simply ran before it.
		// Nor is a golem at sea: it is on water because it is in a boat. Without this every
		// golem that sailed in view was pulled back onto the nearest beach the frame it left.
		if (tier != GolemTier.FAR && !golem.isDying() && !golem.inTransition() && !golem.isSailing(tick))
		{
			WorldPoint on = golem.currentTile();
			if (!islandMemory.isKnownWalkable(on.getX(), on.getY(), on.getPlane())
				&& !transports.hasOrigin(on.getX(), on.getY()))
			{
				WorldPoint safe = roamPlanner.snapToMesh(on);
				if (!safe.equals(on))
				{
					log.debug("Golem {} was on unwalkable ground at {}; moved to {}",
						golem.getId(), on, safe);
					noteRescue(golem, on, safe, "unwalkable");
					golem.relocate(safe);
					golem.noteUnstuck(tick);
					return;
				}
			}
		}

		if (!golem.isStuck(tick))
		{
			return;
		}

		// Try the cheap, invisible things first — spin, back out, re-plan. Only when all
		// of those have been exhausted does the golem get picked up and put somewhere.
		if (golem.workFree(roamContext, roamPlanner))
		{
			return;
		}

		WorldPoint at = golem.currentTile();
		WorldPoint safe = roamPlanner.snapToMesh(at);

		if (safe.equals(at))
		{
			// The mesh says this tile is fine, so the golem is stuck for some reason the
			// map cannot see. Home is the fallback that cannot fail.
			safe = new WorldPoint(GolemContent.PLINTH_X, GolemContent.PLINTH_Y, 0);
		}

		log.debug("Rescuing stuck golem {} from {} to {} (tier {})",
			golem.getId(), at, safe, tier);
		noteRescue(golem, at, safe, "stuck");
		golem.relocate(safe);
		golem.noteUnstuck(tick);
	}

	/**
	 * Puts a rescue in the journal.
	 *
	 * <p>A rescue is the plugin admitting a golem got somewhere it could not get out of,
	 * and that is exactly what cannot be seen from the journal otherwise: the golem simply
	 * reappears at the plinth. Ninety golems were lifted off the top of one ladder in a
	 * single session with nothing but a debug line to show for it.
	 */
	/** The plane last harvested for, so moving to another floor harvests that one. */
	private int harvestedPlane = -1;

	/** A door opened: the wall object for the shut door went and one for the open door came. */
	@Subscribe
	public void onWallObjectSpawned(net.runelite.api.events.WallObjectSpawned event)
	{
		islandMemory.passabilityChanged(event.getWallObject().getWorldLocation());
	}

	@Subscribe
	public void onWallObjectDespawned(net.runelite.api.events.WallObjectDespawned event)
	{
		islandMemory.passabilityChanged(event.getWallObject().getWorldLocation());
	}

	/** The last step of each golem already checked, by its step count. */
	private final Map<Long, Integer> loggedSteps = new HashMap<>();

	/**
	 * Logs a golem that has just walked across an edge the live game says is blocked.
	 *
	 * <p>The game's own collision, read at the moment of the step — not the island memory
	 * the golem planned with, which is exactly the thing that might be stale. A golem
	 * walking through a shut door is invisible to every other check: its path was valid
	 * when it was planned, and it arrives on walkable ground.
	 */
	private void checkStep(Golem golem)
	{
		// The step the golem actually began, checked once — not the tile it was logged on a
		// tick ago. Comparing logged tiles called a landing from a stepping-stone hop, and
		// the corner of a diagonal step caught halfway, steps through walls: most of the
		// first 169 reports were exactly that.
		int serial = golem.getStepSerial();
		Integer checked = loggedSteps.put(golem.getId(), serial);
		if (checked == null || checked == serial || golem.lastStepClimbing())
		{
			return;
		}
		int[] step = golem.lastStep();
		WorldPoint before = new WorldPoint(step[0], step[1], golem.getPlane());
		WorldPoint now = new WorldPoint(step[2], step[3], golem.getPlane());
		int dx = now.getX() - before.getX();
		int dy = now.getY() - before.getY();
		if ((dx == 0 && dy == 0) || Math.abs(dx) > 1 || Math.abs(dy) > 1)
		{
			return;
		}
		WorldView wv = client.getTopLevelWorldView();
		// Not inside an instance: golems there are in template coordinates, which the loaded
		// scene's collision does not describe.
		if (wv == null || wv.isInstance() || wv.getCollisionMaps() == null
			|| now.getPlane() >= wv.getCollisionMaps().length
			|| wv.getCollisionMaps()[now.getPlane()] == null)
		{
			return;
		}
		int[][] flags = wv.getCollisionMaps()[now.getPlane()].getFlags();
		int sx = before.getX() - wv.getBaseX();
		int sy = before.getY() - wv.getBaseY();
		// Not near the edge of the loaded scene, where the client marks a border of tiles as
		// blocked whatever is really there. Steps along it were reported as walls.
		int margin = 6;
		if (sx < margin || sy < margin || sx >= wv.getSizeX() - margin || sy >= wv.getSizeY() - margin
			|| sx + dx < margin || sy + dy < margin
			|| sx + dx >= wv.getSizeX() - margin || sy + dy >= wv.getSizeY() - margin)
		{
			return;
		}
		if (!liveStepBlocked(flags, sx, sy, dx, dy))
		{
			return;
		}
		obstacleObserver.writeGolemEvent(String.valueOf(golem.getId()), "crossed blocked edge from "
			+ before.getX() + "," + before.getY() + "," + before.getPlane() + " to "
			+ now.getX() + "," + now.getY() + "," + now.getPlane()
			+ " flags=" + Integer.toHexString(flags[sx][sy]) + "," + Integer.toHexString(flags[sx + dx][sy + dy]));
	}

	/**
	 * Logs a golem standing on a tile the live game says nothing can stand on — the water,
	 * or inside scenery — outside a traversal and off any transport's starting tile.
	 *
	 * <p>Judged by the game's own collision at that moment, because the golems' map is the
	 * very thing that can be wrong: a map with false walls in it and a map with holes in it
	 * both look fine from inside the plugin. Marked when the golem is following a route,
	 * which is how a golem out of view gets placed without walking.
	 */
	private void checkStanding(Golem golem)
	{
		if (golem.inTransition())
		{
			return;
		}
		WorldView wv = client.getTopLevelWorldView();
		WorldPoint at = golem.currentTile();
		if (wv == null || wv.isInstance() || wv.getCollisionMaps() == null
			|| at.getPlane() >= wv.getCollisionMaps().length || wv.getCollisionMaps()[at.getPlane()] == null)
		{
			return;
		}
		int sx = at.getX() - wv.getBaseX();
		int sy = at.getY() - wv.getBaseY();
		int margin = 6;
		if (sx < margin || sy < margin || sx >= wv.getSizeX() - margin || sy >= wv.getSizeY() - margin)
		{
			return;
		}
		int flags = wv.getCollisionMaps()[at.getPlane()].getFlags()[sx][sy];
		if ((flags & LIVE_UNWALKABLE) == 0 || transports.hasOrigin(at.getX(), at.getY()))
		{
			return;
		}
		obstacleObserver.writeGolemEvent(String.valueOf(golem.getId()), "standing on blocked tile "
			+ at.getX() + "," + at.getY() + "," + at.getPlane() + " flags=" + Integer.toHexString(flags)
			+ (golem.debugState().contains(" itinerary") ? " route" : ""));
	}

	private static final int LIVE_UNWALKABLE = net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FULL
		| net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_OBJECT
		| net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FLOOR
		| net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION;

	/** The game's rule for one step, on raw scene flags: both ways round a corner must be clear. */
	private static boolean liveStepBlocked(int[][] flags, int x, int y, int dx, int dy)
	{
		if (dx != 0 && dy != 0)
		{
			return liveStepBlocked(flags, x, y, dx, 0) || liveStepBlocked(flags, x, y, 0, dy)
				|| liveStepBlocked(flags, x + dx, y, 0, dy) || liveStepBlocked(flags, x, y + dy, dx, 0);
		}
		int nx = x + dx;
		int ny = y + dy;
		if (x < 0 || y < 0 || nx < 0 || ny < 0 || x >= flags.length || nx >= flags.length
			|| y >= flags[x].length || ny >= flags[nx].length)
		{
			return false;
		}
		int out;
		int in;
		if (dy == 1)
		{
			out = net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
			in = net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
		}
		else if (dy == -1)
		{
			out = net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
			in = net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
		}
		else if (dx == 1)
		{
			out = net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_EAST;
			in = net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_WEST;
		}
		else
		{
			out = net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_WEST;
			in = net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_EAST;
		}
		return (flags[x][y] & out) != 0 || (flags[nx][ny] & (in | LIVE_UNWALKABLE)) != 0;
	}

	private void noteRescue(Golem golem, WorldPoint from, WorldPoint to, String reason)
	{
		if (obstacleObserver.isLoggingGolems())
		{
			obstacleObserver.writeGolemEvent(String.valueOf(golem.getId()), "rescued " + reason
				+ " from " + from.getX() + "," + from.getY() + "," + from.getPlane()
				+ " to " + to.getX() + "," + to.getY() + "," + to.getPlane()
				+ " tier=" + golem.getTier());
		}
	}

	/** Animated scenery currently on screen. Short-lived: each plays once and goes. */
	private final List<FakeProp> props = new ArrayList<>();

	/** Hulls drawn under golems that are at sea, keyed by golem id. */
	private final Map<Long, FakeRaft> rafts = new HashMap<>();

	/**
	 * Gives a golem a boat if it is standing on open water, and takes it away otherwise.
	 *
	 * <p>The rule is deliberately about position rather than about intent. A golem is
	 * drawn on a raft when it is on an ocean tile, full stop — which covers a crossing, a
	 * golem the player has sailed out to meet, and a golem that ended up on water by some
	 * route nobody anticipated. Tying it to a "is sailing" flag instead would leave every
	 * unanticipated case as a golem standing on the sea.
	 */
	private void updateRaft(Golem golem, boolean visible)
	{
		FakeRaft raft = rafts.get(golem.getId());

		boolean afloat = visible && worldMesh.isOcean(
			golem.getFineX() / Golem.TILE, golem.getFineY() / Golem.TILE, golem.getPlane());

		if (!afloat)
		{
			if (raft != null)
			{
				client.removeRuneLiteObject(raft);
				rafts.remove(golem.getId());
			}
			return;
		}

		if (raft == null)
		{
			Model hull = raftFactory.raftModel();
			if (hull == null)
			{
				return;
			}
			raft = new FakeRaft(client, hull, modelFactory,
				golem.getFineX(), golem.getFineY(), golem.getOrientation());
			client.registerRuneLiteObject(raft);
			rafts.put(golem.getId(), raft);
		}

		// The hull follows the golem rather than the other way round. The golem is the
		// thing being simulated; the boat is scenery that keeps up with it.
		raft.steer(golem.getFineX(), golem.getFineY(), golem.getOrientation());
	}

	/** Drops every hull. Scene coordinates are about to change, or the plugin is stopping. */
	private void clearRafts()
	{
		for (FakeRaft raft : rafts.values())
		{
			client.removeRuneLiteObject(raft);
		}
		rafts.clear();
	}

	/**
	 * Draws an animated copy of the object a golem is using.
	 *
	 * <p>The copy sits on top of the real object rather than replacing it. That is
	 * deliberate and it is why doors are excluded: for something that is already open, or
	 * that another player is operating, a second copy would read as a ghost. For a plank
	 * being crossed or a ring being stepped into, the motion is the whole point.
	 */
	private void spawnProp(GolemTransport transport, WorldView wv)
	{
		if (wv == null)
		{
			return;
		}

		PropFactory.Prop prop = propFactory.propFor(transport.getObjectId());
		if (prop == null)
		{
			return;
		}
		Model model = propFactory.modelFor(prop);
		if (model == null)
		{
			return;
		}

		int fineX = transport.getFromX() * Golem.TILE + Golem.TILE / 2;
		int fineY = transport.getFromY() * Golem.TILE + Golem.TILE / 2;
		int localX = fineX - wv.getBaseX() * Golem.TILE;
		int localY = fineY - wv.getBaseY() * Golem.TILE;
		if (!Golem.isInScene(wv, localX, localY))
		{
			return;
		}

		int height = Perspective.getTileHeight(client,
			new LocalPoint(localX, localY, wv), transport.getFromPlane());

		FakeProp drawn = new FakeProp(client, model,
			modelFactory.animationFor(prop.getAnimation()), prop.getAnimation(),
			fineX, fineY, transport.getFromPlane(), height, GolemContent.PROP_CYCLES);

		client.registerRuneLiteObject(drawn);
		props.add(drawn);
	}

	/** Unregisters scenery whose animation has run out. */
	private void advanceProps()
	{
		for (Iterator<FakeProp> it = props.iterator(); it.hasNext(); )
		{
			FakeProp prop = it.next();
			if (prop.isFinished())
			{
				client.removeRuneLiteObject(prop);
				it.remove();
			}
		}
	}

	/** Drops every scenery copy, whether or not its animation has finished. */
	private void clearProps()
	{
		for (FakeProp prop : props)
		{
			client.removeRuneLiteObject(prop);
		}
		props.clear();
	}

	/**
	 * Drops golems whose crumble has finished.
	 *
	 * <p>Separate from the loop above so the roster is not modified while it is being
	 * walked.
	 */
	private void reapCrumbled()
	{
		for (Iterator<Golem> it = golems.iterator(); it.hasNext(); )
		{
			Golem golem = it.next();
			if (golem.isCrumbled())
			{
				detachRenderer(golem);
				it.remove();
				rosterChanged = true;
			}
		}
	}

	/**
	 * Attaches or detaches a golem's drawn object as it crosses the scene boundary.
	 *
	 * <p>This is what lets a golem wander off the edge of what is loaded and come back
	 * later: leaving the scene costs it its renderer, not its existence.
	 */
	/**
	 * Most golems drawn on one tile.
	 *
	 * <p>The game draws only a few objects per tile. With more golems than that standing on
	 * one, it chose a different few each frame and they flashed in and out. The same golems
	 * are drawn every frame instead — the roster is walked in a fixed order, so the first
	 * few on a tile keep their place — and any beyond simply are not shown until there is room.
	 */
	private static final int MAX_DRAWN_PER_TILE = 4;

	/**
	 * Which tier a golem is in, and where it is drawn.
	 *
	 * <p>Outside an instance, as it always was. Inside one, a golem standing in a chunk the
	 * instance was built from is in the scene — drawn at the matching place in the instance,
	 * on the instance's plane — and every other golem is judged by its distance from where
	 * the player is in template terms, which puts the whole overworld out of range.
	 */
	private GolemTier tierFor(Golem golem, WorldView wv, WorldPoint playerAt, WorldPoint playerTemplate,
		Map<Long, int[]> sceneChunks)
	{
		int tileX = golem.getFineX() / Golem.TILE;
		int tileY = golem.getFineY() / Golem.TILE;
		if (sceneChunks == null)
		{
			golem.setDrawOffset(0, 0, -1);
			// A golem in an instance is somewhere the overworld cannot see, even though it is
			// simulated in the template room — a real, sealed room on the island.
			if (golem.isInInstance())
			{
				return GolemTier.FAR;
			}
			return playerAt == null ? GolemTier.FAR
				: GolemTier.of(wv, tileX, tileY, golem.getPlane(), playerAt.getX(), playerAt.getY(), playerAt.getPlane());
		}

		// Only golems in the instance are in it. The instance is built from the chunks around
		// the room as well as the room, and golems on the island in those chunks were drawn
		// inside it; every other golem waits out of range until the player leaves.
		int[] scene = golem.isInInstance()
			? sceneChunks.get(InstanceMap.chunkKey(tileX >> 3, tileY >> 3, golem.getPlane()))
			: null;
		if (scene != null)
		{
			int drawX = wv.getBaseX() + (scene[0] << 3) + (tileX & 7);
			int drawY = wv.getBaseY() + (scene[1] << 3) + (tileY & 7);
			golem.setDrawOffset((drawX - tileX) * Golem.TILE, (drawY - tileY) * Golem.TILE, scene[2]);
			return playerAt == null ? GolemTier.FAR
				: GolemTier.of(wv, drawX, drawY, scene[2], playerAt.getX(), playerAt.getY(), playerAt.getPlane());
		}

		golem.setDrawOffset(0, 0, -1);
		return GolemTier.FAR;
	}

	private void updateRenderer(Golem golem, WorldView wv, boolean inSceneTier,
		Map<Long, Integer> drawnPerTile)
	{
		boolean visible = inSceneTier
			&& wv != null
			&& wv.getPlane() == golem.getDrawPlane()
			&& inScene(wv, golem)
			&& drawnPerTile.merge(RoamContext.tileKey(golem.getFineX() / Golem.TILE,
				golem.getFineY() / Golem.TILE, golem.getPlane()), 1, Integer::sum) <= MAX_DRAWN_PER_TILE;

		if (!visible)
		{
			detachRenderer(golem);
			return;
		}

		if (golem.getRenderer() == null)
		{
			Model model = modelFactory.modelFor(golem.getSnapshot());
			if (model == null)
			{
				return;
			}
			FakeGolem renderer = new FakeGolem(client, golem, model, modelFactory);
			String traceId = String.valueOf(golem.getId());
			renderer.setTrace(line ->
			{
				if (obstacleObserver.isLoggingGolems())
				{
					obstacleObserver.writeGolemFrame(traceId, line);
				}
			});
			golem.setRenderer(renderer);
			client.registerRuneLiteObject(renderer);
		}
	}

	private boolean inScene(WorldView wv, Golem golem)
	{
		return Golem.isInScene(wv,
			golem.getDrawFineX() - wv.getBaseX() * Golem.TILE,
			golem.getDrawFineY() - wv.getBaseY() * Golem.TILE);
	}

	private void detachRenderer(Golem golem)
	{
		FakeGolem renderer = golem.getRenderer();
		if (renderer != null)
		{
			client.removeRuneLiteObject(renderer);
			golem.setRenderer(null);
		}
	}

	// ---- per-tick upkeep ----

	/**
	 * Development only: records what a shortcut actually does when the player uses one.
	 *
	 * <p>Which clip an obstacle plays, and how long it takes, is decided server-side and
	 * is in no cache anywhere — so the only way to know is to use one and watch. Remove
	 * this and its two siblings before release.
	 */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		obstacleObserver.onMenuOptionClicked(event);
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		obstacleObserver.onGameTick();
		logGolemState();
		// Snapshots are refreshed continuously rather than read once at spawn: a golem
		// steps off its plinth and starts walking, and the pose animations it is given
		// are not necessarily the ones it had while standing on it.
		WorldView topLevel = client.getTopLevelWorldView();
		if (topLevel != null)
		{
			for (NPC npc : topLevel.npcs())
			{
				if (detector.isGolem(npc) && !detector.wasReplaced(npc))
				{
					detector.track(npc);
				}
			}
		}

		// A new floor is ground to learn even though no scene was loaded.
		//
		// The harvest reads the plane the player is on, and only ran when a scene loaded —
		// climbing a ladder does not load one, so the floor at the top was never recorded.
		// Every golem that climbed it arrived on ground it knew nothing about, could find
		// nowhere to walk, and was lifted back to the plinth: 217 times in one session.
		net.runelite.api.Player me = client.getLocalPlayer();
		int plane = me == null ? -1 : me.getWorldLocation().getPlane();
		if (plane != harvestedPlane)
		{
			harvestedPlane = plane;
			islandMemory.sceneChanged();
		}
		islandMemory.harvestLoadedRegions();

		// Only when the roster has actually changed. Copying five hundred golems every
		// tick to hand the panel a list it would find identical is work for nothing,
		// and the roster changes when a golem is made or removed — not on a timer.
		if (panel != null && rosterChanged)
		{
			rosterChanged = false;
			List<Golem> living = livingGolems();
			panel.refresh(living, tally.getTotal() - living.size());
		}

		if (++ticksSinceSave >= SAVE_INTERVAL_TICKS)
		{
			ticksSinceSave = 0;
			saveGolems();
			saveIslandMemory();
		}
	}

	/**
	 * Offers the hovered golem's menu entries.
	 *
	 * <p>On client tick rather than game tick: this is when the client rebuilds its
	 * menu, and a golem that had to wait up to 600ms to become hoverable would feel
	 * broken next to a real NPC.
	 */
	@Subscribe
	public void onClientTick(ClientTick event)
	{
		obstacleObserver.onClientTick();

		if (!golems.isEmpty())
		{
			menu.addEntries(golems);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();

		if (state == GameState.LOADING)
		{
			// The scene is being rebuilt, so every registered object is about to refer
			// to coordinates that no longer mean what they did. Drop the renderers; the
			// golems themselves are in world space and are unaffected.
			for (Golem golem : golems)
			{
				detachRenderer(golem);
			}
			// Scenery copies are registered objects too, and they hold scene-local
			// coordinates that are about to mean something else entirely. A prop is
			// short-lived, so there is nothing to preserve — drop them all.
			clearProps();
			clearRafts();
			lastGameCycle = -1;
			islandMemory.sceneChanged();
			return;
		}

		if (state == GameState.LOGGED_IN)
		{
			// The dock table is game data, not a bundled resource, so it cannot be read
			// until there is a logged-in client to read it from. Doing it here rather than
			// in startUp is what lets the plugin be enabled at the login screen.
			sailingDocks.load();
			restorePending();
			return;
		}

		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			// Golems are always permanent, so nothing is released here — only the
			// renderers, which belong to a scene that is going away.
			saveGolems();
			saveIslandMemory();
			detector.reset();
			hiddenNpcs.clear();
			lastGameCycle = -1;

			for (Golem golem : golems)
			{
				detachRenderer(golem);
			}
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!GolemsDontDieConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		if ("maxGolems".equals(event.getKey()) || "limitGolems".equals(event.getKey()))
		{
			clientThread.invoke(() ->
			{
				enforceGolemLimit();
				saveGolems();
			});
		}

		// Telemetry in particular must take effect the moment it is switched off, not at
		// the next restart. Somebody turning it off has decided they do not want the next
		// request made, and configure() drops whatever was queued.
		if ("sendObstacleTelemetry".equals(event.getKey())
			|| "telemetryEndpoint".equals(event.getKey())
			|| "highlightObstacles".equals(event.getKey())
			|| "logGolemState".equals(event.getKey()))
		{
			applyObstacleSettings();
		}
	}

	// ---- persistence ----

	/** Tiles flooded from an instance route's landing before it counts as open ground rather than a room. */
	private static final int ROOM_FLOOD = 4000;

	/**
	 * Tiles only a transport into an instance reaches: the template rooms golems walk while
	 * inside one, as {@link RoamContext#tileKey} keys.
	 *
	 * <p>For golems saved before the plugin knew which golems were in an instance, and for
	 * the ones that got into the Mad Angel's sealed room by walking before the planner learned
	 * not to. They are standing in the instance's room, so that is where they are.
	 */
	private java.util.Set<Long> instanceRooms()
	{
		java.util.Set<Long> rooms = new java.util.HashSet<>();
		for (GolemTransport t : transports.all())
		{
			if (!t.entersInstance())
			{
				continue;
			}
			Map<Long, Long> reached = pathfinder.flood(t.getToX(), t.getToY(), t.getToPlane(), ROOM_FLOOD);
			// A landing the golem could walk back from, or one that opens onto a whole island, is not a room.
			if (reached.size() >= ROOM_FLOOD || (t.getFromPlane() == t.getToPlane()
				&& reached.containsKey(GolemPathfinder.pack(t.getFromX(), t.getFromY()))))
			{
				continue;
			}
			for (long tile : reached.keySet())
			{
				rooms.add(RoamContext.tileKey(GolemPathfinder.unpackX(tile), GolemPathfinder.unpackY(tile), t.getToPlane()));
			}
		}
		return rooms;
	}

	private void restorePending()
	{
		if (pendingRestore.isEmpty() || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		List<GolemStore.SavedGolem> saved = new ArrayList<>(pendingRestore);
		java.util.Set<Long> rooms = instanceRooms();
		for (int i = 0; i < saved.size(); i++)
		{
			Golem golem = store.revive(saved.get(i), i);
			if (golem != null)
			{
				// A saved tile can have become unstandable since it was written — the
				// world changes between updates, and a golem restored inside a new wall
				// would never path anywhere again. Moving it now, while nothing is
				// looking, costs a lookup and avoids a golem that is silently stuck for
				// the rest of its life. It is moved, never replaced: same name, same id.
				WorldPoint at = golem.currentTile();
				WorldPoint safe = roamPlanner.snapToMesh(at);
				if (!safe.equals(at))
				{
					log.debug("Relocated restored golem from {} to {}", at, safe);
					golem.relocate(safe);
				}
				WorldPoint restored = golem.currentTile();
				if (!golem.isInInstance()
					&& rooms.contains(RoamContext.tileKey(restored.getX(), restored.getY(), restored.getPlane())))
				{
					golem.setInInstance(true);
				}

				golems.add(golem);
				rosterChanged = true;
			}
		}
		pendingRestore.clear();
		enforceGolemLimit();
		log.debug("Restored {} saved golems", golems.size());
	}

	/**
	 * Writes the roster out.
	 *
	 * <p>Golems mid-crumble are excluded — they have been retired, and saving them
	 * would bring them back alive on the next login.
	 */
	private void saveGolems()
	{
		configManager.setConfiguration(
			GolemsDontDieConfig.GROUP, GolemsDontDieConfig.SAVED_GOLEMS_KEY, store.serialise(livingGolems()));
	}

	private void saveIslandMemory()
	{
		if (!islandMemory.isDirty())
		{
			return;
		}
		configManager.setConfiguration(
			GolemsDontDieConfig.GROUP, IslandMemory.MAP_KEY, islandMemory.serialise());
	}

	// ---- hiding the original ----

	/**
	 * Hides a real golem once its copy has taken over.
	 *
	 * <p>Both exist for the rest of the original's short life — it steps off the
	 * plinth, the copy takes over, and the original keeps walking and then crumbles
	 * where the player cannot see it. Without this there would visibly be two.
	 */
	private boolean shouldDraw(Renderable renderable)
	{
		if (hiddenNpcs.isEmpty() || !(renderable instanceof NPC))
		{
			return true;
		}
		return !hiddenNpcs.contains(((NPC) renderable).getIndex());
	}

	/**
	 * The current roster, for the overlays.
	 *
	 * <p>The live list, not a copy: this is read once per frame by the minimap overlay
	 * and copying it every time would allocate for nothing. Both the overlay and the
	 * list are touched only on the client thread.
	 */
	List<Golem> activeGolems()
	{
		return golems;
	}
}
