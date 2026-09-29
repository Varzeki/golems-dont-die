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
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.ClientShutdown;
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
 * <p>The handover happens as the golem finishes stepping off its plinth, the one moment it is
 * reliably standing still; the original is then hidden and crumbles unseen.
 *
 * <p>Copies are simulated in world coordinates and drawn only inside the loaded scene, so
 * leaving it costs a golem its renderer, not its existence. They are not confined to
 * Wyrmscraig: cost scales with what the player can see, and one out of view is a route and a
 * departure time, never stepped.
 *
 * <p>Purely cosmetic: nothing is sent to the server, and the copies are visible only to the
 * player running the plugin.
 *
 * @see GolemTier for how much of a golem is simulated
 * @see WorldMesh for where a golem may walk
 * @see Golem for the simulation, FakeGolem for the drawing
 */
@Slf4j
@PluginDescriptor(
	name = "Golems Don't Die",
	internalName = "golems-dont-die",
	description = "Golems should live forever.",
	tags = {"golem", "crafting", "wyrmscraig", "skilling", "cosmetic", "npc", "sailing", "exploration", "shortcuts"}
)
public class GolemsDontDiePlugin extends Plugin
{
	/** Game ticks between position saves. Two minutes bounds what a crash costs. */
	private static final int SAVE_INTERVAL_TICKS = 200;

	/**
	 * Path searches allowed per frame across all golems: 200 a second at 50fps, more than a
	 * roaming population asks for, while capping the worst frame.
	 */
	private static final int PATH_SEARCHES_PER_FRAME = 4;

	/**
	 * Routes planned per frame for golems out of view. A plan is about half a millisecond, more
	 * when it floods a dungeon, and a crowd arriving together replans at once. One over budget
	 * waits a frame.
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
	 * Watches the player use obstacles and teaches the plugin what each does. Golems may only
	 * use obstacles whose animation is known; this grows that set.
	 */
	@Inject
	private ObstacleObserver obstacleObserver;

	@Inject
	private ObstacleKnowledge obstacleKnowledge;

	/**
	 * The obstacle data file, for a later update to offer to send. Everything about it is in
	 * the {@code telemetry} package.
	 */
	@Inject
	private ObstacleDataBridge obstacleData;

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
	private GolemNameplateOverlay nameplateOverlay;

	@Inject
	private ObstacleIndex obstacleIndex;

	private GolemListPanel panel;
	private NavigationButton navButton;

	/**
	 * Hides the real golem once its copy has taken over. Registered at start-up and
	 * unregistered at shutdown, never from an event handler: one outliving the plugin would
	 * leave NPCs invisible for good.
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

	/** The client cycle this game tick began on, for how far through it a frame is. */
	private int tickStartCycle = -1;
	private int ticksSinceSave = 0;

	/**
	 * True from start-up until shutdown has saved. Anything arriving after, such as a name
	 * typed into a chatbox prompt left open, must not save: the roster is empty by then, and
	 * saving it replaced every golem the player had.
	 */
	private volatile boolean running;

	/** The tick an asked-for roster save is due, or -1. Renames ask; the save waits a moment for more. */
	private int saveGolemsAt = -1;

	/** Ticks a rename waits before saving, so typing a name is one save, not one a keystroke. */
	private static final int RENAME_SAVE_DELAY = 2;

	/** Set when a sighting was recorded this tick; routes rebuild once, at its end. */
	private boolean routesChanged;

	/**
	 * Whether a learned route is an obstacle. The answer can take a path search, and every
	 * route used to be re-asked on every sighting — one search per stone of a crossing.
	 */
	private final java.util.Map<String, Boolean> obstacleVerdicts = new java.util.HashMap<>();

	@Inject
	private Voyage voyage;

	@Inject
	private GolemCensus census;

	/** How often golems are counted by region, in game ticks. See GolemCensus. */
	private static final int CENSUS_TICKS = 10;

	private int ticksSinceCensus;

	/** Set when a golem is added, removed or restored, so the panel redraws once. */
	private boolean rosterChanged = true;

	// ------------------------------------------------------------------ obstacles

	/**
	 * Takes one sighting from the observer. The player is told only when an obstacle unlocks:
	 * a message per traversal would be chat spam on an agility course.
	 */
	private void onObstacleSighting(ObstacleSighting sighting)
	{
		// Only obstacles: chopping a tree, using a bank booth and picking sweetcorn were all
		// learned as shortcuts, since an object clicked, an animation and the player elsewhere
		// afterwards is all a watcher can see.
		//
		// In a rotated instance no route is usable, but what the obstacle plays is as true
		// there, so it is still recorded; the route filter refuses the route.
		boolean inInstance = sighting.fromX >= INSTANCE_X || sighting.toX >= INSTANCE_X;
		boolean obstacle = inInstance
			? obstacleIndex.knows(sighting.objectId) || transports.isShippedObstacle(sighting.objectId)
			: isObstacle(sighting.objectId, sighting.fromX, sighting.fromY, sighting.fromPlane,
				sighting.toX, sighting.toY, sighting.toPlane);
		if (!obstacle)
		{
			log.debug("Not an obstacle: {}", sighting);
			return;
		}

		// Deliberately silent: learning happens constantly during ordinary play, and
		// announcing it would make a background nicety feel like a chore.
		if (obstacleKnowledge.record(sighting))
		{
			log.debug("Obstacle unlocked: {}", sighting);
		}

		// Routes go in as they are earned, not at the next start-up: somebody who has shown the
		// plugin a staircase twice should see golems use it. Once per tick, however many hops.
		routesChanged = true;

		// And kept on disk, for a later update to offer to send.
		obstacleData.playerCrossed(sighting);
	}

	/** Puts what this tick's sightings taught into the network, and saves it. */
	private void applyLearnedRoutes()
	{
		routesChanged = false;
		transports.setLearnedRoutes(obstacleKnowledge.learnedRoutes());
		// A new route can lead onto a floor nothing else reaches: ground from now on.
		worldMesh.admitTransportEnds(transports.all());
		setHomeRegions(transports.homeRegions());
		// A new route into an instance can mean a new room golems may stand in.
		instanceRoomTiles = instanceRooms();
		saveLearnedObstacles();
	}

	/** {@link #isObstacle}, remembered per route for the session. */
	private boolean isObstacleRoute(int[] r)
	{
		String key = r[0] + "," + r[1] + "," + r[2] + "," + r[3] + ">" + r[4] + "," + r[5] + "," + r[6];
		Boolean known = obstacleVerdicts.get(key);
		if (known == null)
		{
			known = isObstacle(r[0], r[1], r[2], r[3], r[4], r[5], r[6]);
			obstacleVerdicts.put(key, known);
		}
		return known;
	}

	/** Coordinates this far east are an instance's, never the world's. */
	private static final int INSTANCE_X = 6400;

	/**
	 * Whether something the player used is a way across, rather than anything else they clicked
	 * and walked away from. Yes if the cache or the transport tables say so; otherwise only if
	 * it took them somewhere they could not have walked — another floor, a long way off, or
	 * somewhere their map has no short walk to. A tree fails that, as does an open door. Routes
	 * in an instance's own coordinates point at a room that no longer exists.
	 */
	private boolean isObstacle(int objectId, int fromX, int fromY, int fromPlane, int toX, int toY, int toPlane)
	{
		if (fromX >= INSTANCE_X || toX >= INSTANCE_X)
		{
			return false;
		}
		if (obstacleIndex.knows(objectId) || transports.isShippedObstacle(objectId))
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
	 * Logs what every golem near the player is doing, at debug level, for chasing a bug. Confined
	 * to the scene: a line per tick for four hundred and fifty golems would be megabytes a minute.
	 */
	private void logGolemState()
	{
		if (!DevOptions.LOG_GOLEM_STATE)
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
			log.debug("Golem {} {}", golem.getId(), golem.debugState()
				+ (drawn == null ? " undrawn" : " drawnAgo=" + (client.getGameCycle() - drawn.getLastDrawnCycle())));
			checkStep(golem);
			checkStanding(golem);
		}
	}

	/** Pushes the obstacle settings into the pieces that act on them. */
	private void applyObstacleSettings()
	{
		obstacleObserver.setExplaining(DevOptions.HIGHLIGHT_OBSTACLES);
	}

	@Provides
	GolemsDontDieConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GolemsDontDieConfig.class);
	}

	@Override
	protected void startUp()
	{
		running = true;
		// Before anything below writes a key, or every install looks like an update.
		checkForUpdate();

		// Obstacles already taught, and the settings governing learning. Before the transport
		// network, whose usability gate consults it.
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
		// In the plugin's own folder, which RuneLite hands out; a plugin writes nowhere else.
		net.runelite.client.util.Filepath folder = null;
		try
		{
			folder = getPluginDirectory();
		}
		catch (java.io.IOException | RuntimeException e)
		{
			log.warn("No folder for the obstacle data; it will not be kept this session", e);
		}
		obstacleData.startUp(folder);

		// Saved map first, shipped baseline underneath: what the player has walked beats a
		// static export of the same ground.
		islandMemory.deserialise(configManager.getConfiguration(GolemsDontDieConfig.GROUP, IslandMemory.MAP_KEY));
		islandMemory.loadBundled();
		// Anywhere a transport from the island leads is ground golems will walk; resolved
		// lazily, after the network has loaded.
		islandMemory.setAlsoIsland(transports::leadsToRegion);

		// Read-only and shared, so loaded once here: a first-use load would land mid-frame,
		// the one place a few milliseconds shows.
		worldMesh.load();
		propFactory.load();
		transports.load();
		// The index before the routes, because the routes are filtered by it.
		obstacleIndex.load();
		obstacleVerdicts.clear();
		obstacleKnowledge.setRouteFilter(this::isObstacleRoute);
		transports.setLearnedRoutes(obstacleKnowledge.learnedRoutes());
		// Floors reached only by transports the land fill never had. See WorldMesh.
		worldMesh.admitTransportEnds(transports.all());
		setHomeRegions(transports.homeRegions());
		roamContext = new RoamContext(islandMemory, pathfinder, transports, abilities);
		roamContext.setKnowledge(obstacleKnowledge);
		roamContext.setObstacles(obstacleIndex);
		roamContext.setDecisions((golem, what) ->
		{
			if (DevOptions.LOG_GOLEM_STATE)
			{
				log.debug("Golem {} {}", golem.getId(), what);
			}
		});
		roamContext.setPlanner(roamPlanner);
		roamContext.setCensus(census);
		roamContext.setTraversals(obstacleData::golemCrossed);
		roamContext.setModels(modelFactory);

		pendingRestore.addAll(store.deserialise(
			configManager.getConfiguration(GolemsDontDieConfig.GROUP, GolemsDontDieConfig.SAVED_GOLEMS_KEY)));

		tally.load();

		// Enabling mid-session skips the login the count would have arrived at.
		clientThread.invokeLater(this::syncTally);

		// All three panel callbacks arrive on the Swing thread and touch the roster, which the
		// client thread owns, so each hops across.
		panel = new GolemListPanel(
			golem -> clientThread.invoke(() -> removeGolem(golem)),
			(golem, name) -> clientThread.invoke(() ->
			{
				golem.setNickname(name);
				saveGolemsSoon();
			}),
			() -> clientThread.invoke(this::reviveMissing));
		menu.setOnRenamed(golem ->
		{
			if (!running || panel == null)
			{
				return;
			}
			saveGolemsSoon();
			List<Golem> living = livingGolems();
			panel.refresh(living, tally.getTotal() - living.size(), true);
		});
		navButton = NavigationButton.builder()
			.tooltip("Golems")
			.icon(ImageUtil.loadImageResource(GolemsDontDiePlugin.class, "/golem-icon.png"))
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		overlayManager.add(minimapOverlay);
		overlayManager.add(obstacleHighlightOverlay);
		overlayManager.add(nameplateOverlay);

		renderCallbacks.register(drawCallback);
		callbackRegistered = true;

		lastGameCycle = -1;
		clientThread.invokeLater(this::restorePending);
	}

	@Override
	protected void shutDown()
	{
		// Nothing drawn from here on, so nothing reads what is cleared below.
		if (callbackRegistered)
		{
			renderCallbacks.unregister(drawCallback);
			callbackRegistered = false;
		}

		// Golems and their maps belong to the client thread and this runs on Swing's: saving
		// here iterated the roster while a frame could be moving it.
		onClientThread(() ->
		{
			// Each step on its own, so one failing cannot stop the rest: the old shutdown's
			// first line threw, the roster stayed in memory, and re-enabling doubled every
			// golem. Save before tearing down.
			safely("stopping the obstacle observer", obstacleObserver::shutDown);
			safely("saving obstacle data", obstacleData::save);
			safely("saving learned routes", () ->
			{
				if (routesChanged)
				{
					applyLearnedRoutes();
				}
				saveLearnedObstacles();
			});

			// Or shutdown is indistinguishable from deleting every golem the player has.
			safely("saving golems", this::saveGolems);
			safely("saving the island map", this::saveIslandMemory);
			running = false;

			safely("removing golems", () ->
			{
				for (Golem golem : golems)
				{
					detachRenderer(golem);
				}
			});
			golems.clear();
			pendingRestore.clear();
			safely("removing props", this::clearProps);
			safely("removing rafts", this::clearRafts);
			propFactory.clear();
			raftFactory.clear();
			census.clear();
			hiddenNpcs.clear();
			detector.reset();
			modelFactory.clear();
			saveGolemsAt = -1;
		});
		voyage.shutDown();

		overlayManager.remove(minimapOverlay);
		overlayManager.remove(obstacleHighlightOverlay);
		overlayManager.remove(nameplateOverlay);

		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		panel = null;
	}

	/** Runs one step of shutdown, logging rather than throwing if it fails. */
	private void safely(String what, Runnable step)
	{
		try
		{
			step.run();
		}
		catch (RuntimeException | AssertionError e)
		{
			log.warn("Failed {} while shutting down; carrying on", what, e);
		}
	}

	/**
	 * Runs a task on the client thread: here if this is already it, queued otherwise.
	 *
	 * <p>Golems, their maps and everything drawn belong to that thread, and the plugin is stopped
	 * from Swing's. Nothing waits for the task: the client runs while the plugin is being stopped,
	 * so it is picked up on the next cycle, and a closing client has its own moment to save — see
	 * {@link #onClientShutdown}.
	 */
	private void onClientThread(Runnable task)
	{
		if (client.isClientThread())
		{
			task.run();
			return;
		}
		clientThread.invoke(task);
	}

	/**
	 * Saves before the client closes.
	 *
	 * <p>Stopping a plugin and closing the client are different moments, and only this one ends with
	 * the client thread going away. The save is handed to that thread and the client is asked to wait
	 * for it, which is how a plugin keeps work alive across shutdown without holding anything up.
	 */
	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		if (!running)
		{
			return;
		}
		java.util.concurrent.CompletableFuture<Void> saved = new java.util.concurrent.CompletableFuture<>();
		event.waitFor(saved);
		onClientThread(() ->
		{
			safely("saving golems", this::saveGolems);
			safely("saving the island map", this::saveIslandMemory);
			safely("saving learned routes", this::saveLearnedObstacles);
			safely("saving obstacle data", obstacleData::save);
			saved.complete(null);
		});
	}

	/** Saves the roster shortly, folding in any other change asked for before then. */
	private void saveGolemsSoon()
	{
		if (saveGolemsAt < 0)
		{
			saveGolemsAt = client.getTickCount() + RENAME_SAVE_DELAY;
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
	 * Keeps the tally level with the game's own count of golems crafted: the half that works
	 * without the plugin having run, since the count arrives at login.
	 */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		// A change can be reported against the varbit or the varp holding it, so both wake this.
		// Neither value is read: the varp's is the count over sixteen bits of carving state.
		if (event.getVarbitId() == GolemContent.GOLEM_COUNT_VARBIT
			|| event.getVarpId() == GolemContent.GOLEM_COUNT_VARP)
		{
			syncTally();
		}
	}

	/**
	 * Reads the game's count of golems crafted into the tally. Safe before the server has sent
	 * it: an unsent variable reads 0, which the tally ignores.
	 */
	private void syncTally()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		int count = client.getVarbitValue(GolemContent.GOLEM_COUNT_VARBIT);
		if (tally.observeCount(count))
		{
			rosterChanged = true;
		}
		if (count > 0)
		{
			gameCount = count;
			trimToGameCount();
		}
	}

	/** Golems the game says have been crafted, as read this session, or -1 if unread. */
	private int gameCount = -1;

	/**
	 * Removes unnamed golems beyond the number the game says were ever crafted. Any beyond it
	 * are copies, and there have been: a failed shutdown left the roster in memory, and
	 * re-enabling loaded the save on top, making one player's 451 golems 902. Only the game's
	 * own count is trusted. Unnamed golems go first, out of view before in view, then duplicate
	 * names; a golem with a name of its own is never removed.
	 */
	private void trimToGameCount()
	{
		if (gameCount <= 0 || !pendingRestore.isEmpty())
		{
			return;
		}
		int excess = countLiving() - gameCount;
		if (excess <= 0)
		{
			return;
		}
		int removed = 0;

		// Unnamed first, newest first: a doubled roster's copies are the later half.
		for (int pass = 0; pass < 2 && removed < excess; pass++)
		{
			for (int i = golems.size() - 1; i >= 0 && removed < excess; i--)
			{
				Golem golem = golems.get(i);
				if (golem.isDying() || isNamed(golem) || pass == 0 && golem.getTier() != GolemTier.FAR)
				{
					continue;
				}
				golem.startDying();
				removed++;
			}
		}

		// Then, if still over, every golem after the first with each name.
		java.util.Set<String> names = new java.util.HashSet<>();
		for (Golem golem : golems)
		{
			if (removed >= excess)
			{
				break;
			}
			if (golem.isDying() || !isNamed(golem))
			{
				continue;
			}
			if (!names.add(golem.getNickname().trim().toLowerCase()))
			{
				golem.startDying();
				removed++;
			}
		}

		rosterChanged = true;
		saveGolems();
		log.debug("Removed {} golems beyond the {} the game says were crafted{}", removed, gameCount,
			removed < excess ? "; " + (excess - removed) + " more are named and kept" : "");
	}

	/**
	 * Brings the roster back up to the number of golems ever crafted: new golems on the plinth,
	 * dispersing as freshly made ones would, since nothing of a culled golem survives.
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

			// Seeded off a counter as well as the tile, so a batch revived onto one square
			// does not walk away in lockstep.
			long seed = uniqueSeed(((long) plinth.getX() << 32) ^ ((long) plinth.getY() << 8)
				^ ((golems.size() + i) * 0x9E3779B9L));
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

		// As the golem finishes stepping off its plinth, not when it crumbles: waiting meant
		// taking over mid-stride, with a position, heading and half-step to reconcile.
		if (animation == -1 && detector.hasLeftPlinth(npc))
		{
			replace(npc);
			return;
		}

		if (animation == GolemContent.GOLEM_SPAWN_ANIMATION)
		{
			detector.noteLeftPlinth(npc);
		}

		// Fallback: if a golem is never seen stepping off, a jump at the crumble beats losing
		// it.
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
	 * Takes over from a live golem: builds the copy and hides the original. The copy goes at the
	 * NPC's <i>local</i> location, not its world tile, which is the tile it is moving onto — one
	 * caught mid-step would appear to teleport.
	 */
	private void replace(NPC npc)
	{
		if (!hasRoomForAnother())
		{
			// Every slot is spoken for by a named golem; leave this one to the game.
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
		long seed = uniqueSeed(((long) home.getX() << 32) ^ ((long) home.getY() << 8) ^ npc.getIndex());

		// Taken live, not from the snapshot, so the copy starts facing where the original
		// faced this frame.
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
	 * Retires the oldest unnamed golems until the roster is inside the limit. Unlimited by
	 * default: a golem costs an animated model every frame, so a cap is worth offering, but
	 * choosing one would quietly delete golems collected on purpose.
	 *
	 * <p><b>Named golems are never culled</b>, even over the limit: a name is the one
	 * unambiguous signal the player cares. Retired golems crumble, and leave the roster when
	 * the animation finishes.
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
	 * The roster as anything outside the client thread should see it: a copy, because the panel
	 * reads it on the Swing thread while this one changes it, with mid-crumble golems left out
	 * so pressing ✕ removes the entry at once.
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

	/** Golems not already crumbling: a dying one is on its way out and does not count. */
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
	 * Whether a new golem can be taken over. False when the roster is full of named golems:
	 * nothing can make room, so the plugin stands aside rather than killing one to pay.
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
	 * Retires one golem, from the side panel's X button. Client thread only. It crumbles rather
	 * than vanishing, but the panel drops it at once so the button feels immediate.
	 */
	private void removeGolem(Golem golem)
	{
		golem.startDying();
		rosterChanged = true;
		saveGolems();
	}

	/**
	 * A seed, and so an id, that no golem has. Seeding from the plinth and the game's NPC slot
	 * gave golems crafted minutes apart the same seed, because the game reuses slots; a seed is
	 * everything a golem decides, so twins walked in lockstep and shared one raft, and four
	 * hundred ids were in two places at once in one session. Now mixed with a counter and the
	 * clock, and checked against the roster.
	 */
	private long uniqueSeed(long base)
	{
		long seed = base ^ (++seedsIssued * 0x9E3779B97F4A7C15L) ^ System.nanoTime();
		while (seedInUse(seed))
		{
			seed += 0x9E3779B97F4A7C15L;
		}
		return seed;
	}

	private long seedsIssued;

	private boolean seedInUse(long seed)
	{
		for (Golem golem : golems)
		{
			if (golem.getId() == seed)
			{
				return true;
			}
		}
		return false;
	}

	// ---- simulation ----

	/**
	 * Ticks between looks at a golem out of view. Three seconds: the near ring a golem must
	 * cross to reach the scene is over a hundred tiles wide, and a teleport rechecks everything
	 * anyway.
	 */
	private static final int FAR_CHECK_TICKS = 5;

	/** Tiles the player can move in one frame before it counts as a teleport. */
	private static final int TELEPORT_TILES = 8;

	/** What the view was last frame, to notice it changing at a stroke. */
	private int lastViewX = Integer.MIN_VALUE;
	private int lastViewY;
	private int lastViewPlane;
	private int lastBaseX;
	private int lastBaseY;
	private boolean lastInstance;
	private boolean lastRestrict;

	/**
	 * True if something since last frame could have brought golems into view, or sent them home,
	 * all at once, so every golem is looked at now rather than in turn.
	 */
	private boolean farViewChanged(WorldView wv, WorldPoint playerAt, boolean restrict)
	{
		int x = playerAt == null ? Integer.MIN_VALUE : playerAt.getX();
		int y = playerAt == null ? 0 : playerAt.getY();
		int plane = playerAt == null ? -1 : playerAt.getPlane();
		int baseX = wv == null ? Integer.MIN_VALUE : wv.getBaseX();
		int baseY = wv == null ? Integer.MIN_VALUE : wv.getBaseY();
		boolean instance = wv != null && wv.isInstance();

		boolean changed = lastViewX == Integer.MIN_VALUE || x == Integer.MIN_VALUE
			|| Math.abs(x - lastViewX) > TELEPORT_TILES || Math.abs(y - lastViewY) > TELEPORT_TILES
			|| plane != lastViewPlane || baseX != lastBaseX || baseY != lastBaseY
			|| instance != lastInstance || restrict != lastRestrict;

		lastViewX = x;
		lastViewY = y;
		lastViewPlane = plane;
		lastBaseX = baseX;
		lastBaseY = baseY;
		lastInstance = instance;
		lastRestrict = restrict;
		return changed;
	}

	/** Golems standing on, and drawn on, each tile; refilled every frame. */
	private final TileMap occupancy = new TileMap(256);
	private final TileMap drawnPerTile = new TileMap(256);

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		if (golems.isEmpty())
		{
			drawnGolems.clear();
			return;
		}

		int cycle = client.getGameCycle();
		int elapsed = lastGameCycle < 0 ? 0 : cycle - lastGameCycle;
		lastGameCycle = cycle;

		// A negative or absurd delta means a world hop or reconnect reset the cycle counter;
		// skip the frame rather than teleporting every golem.
		if (elapsed < 0 || elapsed > 200)
		{
			elapsed = 0;
		}

		// Rationed per frame: a golem only searches when it finishes a walk, but with hundreds
		// the arrivals bunch up and fifty breadth-first searches in a frame stutters. One
		// denied waits, indistinguishable from its usual pause.
		int searchBudget = PATH_SEARCHES_PER_FRAME;
		int farPlanBudget = FAR_PLANS_PER_FRAME;

		// Resolved once rather than per golem: both were built five hundred times a frame for
		// the same answer.
		WorldView wv = client.getTopLevelWorldView();

		// One context for the whole frame; only the two per-golem knobs change as the
		// roster is walked.
		roamContext.setTick(client.getTickCount());
		// Thirty client cycles to a tick.
		roamContext.setTickFraction(tickStartCycle < 0 ? 0f
			: Math.min(0.999f, Math.max(0f, (client.getGameCycle() - tickStartCycle) / 30f)));
		roamContext.setMaySearchSea(true);
		// Read once for the frame rather than through the config proxy for every golem.
		boolean restrictAmbition = config.restrictGolemAmbition();
		roamContext.setAmbitionRestricted(restrictAmbition);

		// In the main world even aboard a boat, whose coordinates would put golems out of range.
		WorldPoint playerAt = PlayerPosition.of(client);

		// Where golems are standing, to keep them off one tile. The same map every frame,
		// emptied and refilled, so a pen of golems does not box a key each.
		occupancy.clear();
		for (Golem golem : golems)
		{
			if (golem.getTier() != GolemTier.FAR)
			{
				occupancy.addTo(RoamContext.tileKey(golem.getFineX() / Golem.TILE,
					golem.getFineY() / Golem.TILE, golem.getPlane()), 1);
			}
		}
		roamContext.setOccupancy(occupancy);

		// Golems drawn per tile this frame. See updateRenderer.
		drawnPerTile.clear();

		// Inside an instance: which template chunks it is built from, and where the player is in
		// template terms. Golems live in the template; see tierFor.
		Map<Long, int[]> sceneChunks = wv != null && wv.isInstance() ? InstanceMap.sceneChunks(wv) : null;
		WorldPoint playerTemplate = sceneChunks == null ? playerAt : InstanceMap.templateOf(wv, playerAt);

		// Golems out of view are looked at every few ticks; see Golem.scheduleFarCheck. All at
		// once after a teleport, a new scene, a change of floor or the ambition setting.
		int tick = client.getTickCount();
		boolean lookAtAll = farViewChanged(wv, playerAt, restrictAmbition);

		for (Golem golem : golems)
		{
			if (!lookAtAll && golem.getTier() == GolemTier.FAR && golem.getRenderer() == null
				&& !golem.farCheckDue(tick))
			{
				continue;
			}
			if (golem.getTier() == GolemTier.FAR)
			{
				golem.catchUpFar(tick);
			}

			// A question about this golem, not the player's proximity to Wyrmscraig: the old
			// gate switched every golem off when the player left the island.
			GolemTier tier = tierFor(golem, wv, playerAt, playerTemplate, sceneChunks);

			golem.setTier(tier, roamContext, roamPlanner);

			if (tier == GolemTier.FAR)
			{
				if (golem.advanceFar(roamContext, roamPlanner, farPlanBudget > 0))
				{
					farPlanBudget--;
				}
				golem.scheduleFarCheck(tick, FAR_CHECK_TICKS);
			}
			else if (elapsed > 0)
			{
				roamContext.setMayPath(searchBudget > 0);
				if (golem.advance(elapsed, roamContext))
				{
					searchBudget--;
				}
			}

			if (restrictAmbition)
			{
				sendHomeIfAway(golem);
			}
			rescueIfStuck(golem, tier);

			updateRenderer(golem, wv, tier == GolemTier.SCENE, drawnPerTile);
			updateRaft(golem, tier == GolemTier.SCENE);

			// Scenery is only animated for a golem the player can see; a request from three
			// regions away is dropped, not queued, as nobody watched the plank lower.
			GolemTransport prop = golem.claimPendingProp();
			if (prop != null && tier == GolemTier.SCENE)
			{
				spawnProp(prop, wv);
			}
		}

		advanceProps();
		reapCrumbled();

		// Golems with a model in the scene, in roster order, for the overlays and the menu. A
		// renderer is only attached above, so nothing outside this list is drawn before it is
		// rebuilt; one detached since is skipped by their own checks.
		drawnGolems.clear();
		for (Golem golem : golems)
		{
			if (golem.getRenderer() != null)
			{
				drawnGolems.add(golem);
			}
		}
	}

	/**
	 * Moves a golem that has stopped getting anywhere. The watchdog on {@link Golem} decides
	 * <em>whether</em>; this decides <em>where</em>, because only the plugin has the mesh. The
	 * golem goes to the nearest tile it could walk out of, or home to the plinth, which always
	 * exists and is always walkable. Moved, never replaced: same name, id, seed and gait.
	 * Allowed even on screen, since a golem frozen in scenery is worse than one that slides.
	 */
	private void rescueIfStuck(Golem golem, GolemTier tier)
	{
		int tick = roamContext.getTick();

		// A visible golem somewhere it could not have walked to is wrong now, not in five
		// minutes, so it is put right rather than waiting for the watchdog — a net under
		// everything else. A stepping stone is the legitimate exception: blocked ground the
		// network says you may stand on.
		//
		// A golem mid-obstacle is exempt, because half of them legitimately stand on nothing.
		// Without this the watchdog fired every tick of every crossing and called relocate,
		// dropping the path, the step and the itinerary: golems were pulled off the stones by
		// the thing meant to rescue them, 8,855 times on one tile in one session. isStuck()
		// below exempts transitions; this check simply ran before it.
		//
		// Nor a golem at sea, which is on water because it is in a boat: without this every
		// golem that sailed in view was pulled back onto the nearest beach.
		if (tier != GolemTier.FAR && !golem.isDying() && !golem.inTransition() && !golem.isSailing(tick))
		{
			// The tile as numbers first: this runs for every golem in view every frame, and the
			// point is only needed once one turns out to be somewhere it should not be.
			int x = golem.getFineX() / Golem.TILE;
			int y = golem.getFineY() / Golem.TILE;
			if (!islandMemory.isKnownWalkable(x, y, golem.getPlane())
				&& !transports.hasOrigin(x, y))
			{
				WorldPoint on = golem.currentTile();
				WorldPoint safe = roamPlanner.snapToMesh(on);
				if (!safe.equals(on))
				{
					log.debug("Golem {} was on unwalkable ground at {}; moved to {}",
						golem.getId(), on, safe);
					noteRescue(golem, on, safe, "unwalkable");
					golem.relocate(safe);
					// Snapped out of a room is out of its instance: left flagged, it was out of view
					// for good.
					golem.setInInstance(false);
					golem.noteUnstuck(tick);
					return;
				}
			}
		}

		if (!golem.isStuck(tick))
		{
			return;
		}

		// The cheap, invisible things first — spin, back out, re-plan — before the golem is
		// picked up and put somewhere.
		if (golem.workFree(roamContext, roamPlanner))
		{
			return;
		}

		WorldPoint at = golem.currentTile();
		WorldPoint safe = roamPlanner.snapToMesh(at);

		if (safe.equals(at))
		{
			// The mesh says this tile is fine, so the golem is stuck for a reason the map
			// cannot see; home is the fallback that cannot fail.
			safe = new WorldPoint(GolemContent.PLINTH_X, GolemContent.PLINTH_Y, 0);
		}

		log.debug("Rescuing stuck golem {} from {} to {} (tier {})",
			golem.getId(), at, safe, tier);
		noteRescue(golem, at, safe, "stuck");
		golem.relocate(safe);
		golem.setInInstance(false);
		golem.noteUnstuck(tick);
	}

	/** The plane last harvested for, so moving to another floor harvests that one. */
	private int harvestedPlane = -1;

	/** A door opened: the shut door's wall object went and the open door's came. */
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
	 * Logs a golem that has just walked across an edge the live game says is blocked, judged by
	 * the game's own collision at the moment of the step rather than the island memory the golem
	 * planned with, which is the thing that might be stale. A golem through a shut door is
	 * invisible to every other check: its path was valid when planned.
	 */
	private void checkStep(Golem golem)
	{
		// The step the golem actually began, not the tile it was logged on a tick ago:
		// comparing logged tiles called stepping-stone landings and half-finished diagonals
		// steps through walls, which was most of the first 169 reports.
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
		// Not near the scene edge, where the client marks a border blocked whatever is really
		// there; steps along it were reported as walls.
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
		log.debug("Golem {} {}", golem.getId(), "crossed blocked edge from "
			+ before.getX() + "," + before.getY() + "," + before.getPlane() + " to "
			+ now.getX() + "," + now.getY() + "," + now.getPlane()
			+ " flags=" + Integer.toHexString(flags[sx][sy]) + "," + Integer.toHexString(flags[sx + dx][sy + dy]));
	}

	/**
	 * Logs a golem standing on a tile the live game says nothing can stand on — water, or inside
	 * scenery — outside a traversal and off any transport's starting tile. Judged by the game's
	 * own collision, because the golems' map is the thing that can be wrong: false walls and
	 * holes both look fine from inside the plugin.
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
		log.debug("Golem {} {}", golem.getId(), "standing on blocked tile "
			+ at.getX() + "," + at.getY() + "," + at.getPlane() + " flags=" + Integer.toHexString(flags)
			+ (golem.debugState().contains(" itinerary") ? " route" : ""));
	}

	private static final int LIVE_UNWALKABLE = net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FULL
		| net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_OBJECT
		| net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FLOOR
		| net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION;

	/** The game's rule for one step, on raw scene flags: both ways round a corner clear. */
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

	/**
	 * Wyrmscraig, its caves and floors, and everywhere its own transports lead; recomputed
	 * whenever a route is learned. See {@link TransportNetwork#homeRegions}.
	 */
	private java.util.Set<Integer> homeRegions = java.util.Collections.emptySet();

	/** {@link #homeRegions} by region id, for the check made of every golem every frame. */
	private boolean[] homeRegionBits = new boolean[1 << 16];

	private void setHomeRegions(java.util.Set<Integer> regions)
	{
		boolean[] bits = new boolean[1 << 16];
		for (int regionId : regions)
		{
			if (regionId >= 0 && regionId < bits.length)
			{
				bits[regionId] = true;
			}
		}
		// Bits before the set: a check between the two sees the old set's emptiness with the new
		// bits, never a non-empty set with no bits, which would send every golem home.
		homeRegionBits = bits;
		homeRegions = regions;
	}

	/**
	 * Puts a golem back on the plinth if it is anywhere but home, for "Restrict Golem ambition".
	 * By region, which is all home is: the island, its caves and its upper floors are regions,
	 * and an instance is simulated at its template.
	 */
	private void sendHomeIfAway(Golem golem)
	{
		// Every golem every frame while the setting is on, so the region comes from the
		// golem's own numbers, exactly as WorldPoint.getRegionID does, rather than a new one.
		int regionId = ((golem.getFineX() / Golem.TILE >> 6) << 8) | (golem.getFineY() / Golem.TILE >> 6);
		if (homeRegions.isEmpty() || (regionId >= 0 && regionId < homeRegionBits.length
			? homeRegionBits[regionId] : homeRegions.contains(regionId)))
		{
			return;
		}
		WorldPoint at = golem.currentTile();
		WorldPoint plinth = new WorldPoint(GolemContent.PLINTH_X, GolemContent.PLINTH_Y, 0);
		noteRescue(golem, at, plinth, "ambition");
		golem.relocate(plinth);
		golem.setInInstance(false);
		golem.noteUnstuck(roamContext.getTick());
	}

	/**
	 * This release, as the plugin's own files record it. It sits beside
	 * {@code runelite-plugin.properties} and {@code build.gradle}, which the Plugin Hub reads;
	 * this one is what the plugin writes into the data it keeps.
	 */
	static final String VERSION = "2.0";

	/** Set once an update has been announced, or on a new install with nothing to say. */
	private static final String ANNOUNCED_KEY = "announcedSailing";

	/** Keys the version before sailing wrote; any of them means this is an update, not an install. */
	private static final String[] OLD_VERSION_KEYS = {
		GolemsDontDieConfig.SAVED_GOLEMS_KEY, GolemTally.TOTAL_KEY, IslandMemory.MAP_KEY,
	};

	/** True if this launch is an update from the version before sailing, not yet announced. */
	private boolean announcementPending;

	/**
	 * Decides, once, whether this is an update worth announcing. A new install has none of the
	 * old version's keys and is marked announced straight away; an update has some, and hears
	 * about it at its next login.
	 */
	private void checkForUpdate()
	{
		if (configManager.getConfiguration(GolemsDontDieConfig.GROUP, ANNOUNCED_KEY) != null)
		{
			return;
		}
		for (String key : OLD_VERSION_KEYS)
		{
			if (configManager.getConfiguration(GolemsDontDieConfig.GROUP, key) != null)
			{
				announcementPending = true;
				return;
			}
		}
		configManager.setConfiguration(GolemsDontDieConfig.GROUP, ANNOUNCED_KEY, true);
	}

	/** Says it once, on the first tick logged in, and marks it only once said. */
	private void announceUpdate()
	{
		if (!announcementPending || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		announcementPending = false;
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=ff0000>The Golems have learned to sail...</col>", null);
		configManager.setConfiguration(GolemsDontDieConfig.GROUP, ANNOUNCED_KEY, true);
	}

	/**
	 * Logs a rescue, which is otherwise invisible: the golem simply reappears at the plinth. Ninety
	 * came off one ladder in a single session.
	 */
	private void noteRescue(Golem golem, WorldPoint from, WorldPoint to, String reason)
	{
		if (DevOptions.LOG_GOLEM_STATE)
		{
			log.debug("Golem {} {}", golem.getId(), "rescued " + reason
				+ " from " + from.getX() + "," + from.getY() + "," + from.getPlane()
				+ " to " + to.getX() + "," + to.getY() + "," + to.getPlane()
				+ " tier=" + golem.getTier());
		}
	}

	/** Animated scenery currently on screen. Short-lived: each plays once and goes. */
	private final List<FakeProp> props = new ArrayList<>();

	/**
	 * Hulls drawn under golems at sea, keyed by the golem itself. Not by id: ids come from the
	 * crafting site and the game's NPC slot, which the game reuses, so two golems could share
	 * one raft — whichever was updated last had it, and if the other was ashore it took the
	 * raft away again, leaving a golem at the helm of nothing.
	 */
	private final Map<Golem, FakeRaft> rafts = new java.util.IdentityHashMap<>();

	/**
	 * Gives a golem a boat if it is standing on open water, and takes it away otherwise. About
	 * position, not intent: an "is sailing" flag would leave every unanticipated case standing
	 * on the sea.
	 */
	private void updateRaft(Golem golem, boolean visible)
	{
		if (!visible && rafts.isEmpty())
		{
			// Not afloat and no hull to take away: the usual case, no id boxed.
			return;
		}
		FakeRaft raft = rafts.get(golem);

		// On the sea, or anywhere else a crossing goes: the cave's lake is not the sea.
		boolean afloat = visible && (golem.isAfloat() || worldMesh.isOcean(
			golem.getFineX() / Golem.TILE, golem.getFineY() / Golem.TILE, golem.getPlane()));

		if (!afloat)
		{
			if (raft != null)
			{
				client.removeRuneLiteObject(raft);
				rafts.remove(golem);
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
			rafts.put(golem, raft);
		}

		// The hull follows the golem, which is at the helm at the stern, so the raft's middle is
		// a tile ahead — the way it faces: 0 south, 512 west, 1024 north, 1536 east.
		double facing = golem.getOrientation() * Math.PI / 1024;
		int aheadX = (int) Math.round(-Math.sin(facing) * GolemContent.RAFT_HELM_OFFSET);
		int aheadY = (int) Math.round(-Math.cos(facing) * GolemContent.RAFT_HELM_OFFSET);
		// At the golem's ground height, always in the scene when the golem is drawn.
		WorldView view = client.getTopLevelWorldView();
		int localX = golem.getFineX() - (view == null ? 0 : view.getBaseX() * Golem.TILE);
		int localY = golem.getFineY() - (view == null ? 0 : view.getBaseY() * Golem.TILE);
		if (view != null && Golem.isInScene(view, localX, localY))
		{
			int ground = Perspective.getTileHeight(client, new LocalPoint(localX, localY, view), 0);
			raft.steer(golem.getFineX() + aheadX, golem.getFineY() + aheadY, golem.getOrientation(), ground);
		}
		else
		{
			raft.steer(golem.getFineX() + aheadX, golem.getFineY() + aheadY, golem.getOrientation());
		}
	}

	/** Drops every hull: scene coordinates are changing, or the plugin is stopping. */
	private void clearRafts()
	{
		for (FakeRaft raft : rafts.values())
		{
			client.removeRuneLiteObject(raft);
		}
		rafts.clear();
	}

	/**
	 * Draws an animated copy of the object a golem is using. It sits on top of the real object
	 * rather than replacing it, which is why doors are excluded: over one already open, a
	 * second copy reads as a ghost.
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

	/** Drops every scenery copy, finished or not. */
	private void clearProps()
	{
		for (FakeProp prop : props)
		{
			client.removeRuneLiteObject(prop);
		}
		props.clear();
	}

	/**
	 * Drops golems whose crumble has finished. Separate from the loop above so the roster is
	 * not modified while being walked.
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
	 * Attaches or detaches a golem's drawn object as it crosses the scene boundary, which is
	 * what lets one wander off the edge of what is loaded and come back: leaving the scene
	 * costs it its renderer, not its existence.
	 */
	/**
	 * Most golems drawn on one tile. The game draws only a few objects per tile, and with more
	 * than that on one it chose a different few each frame and they flashed; the roster is
	 * walked in a fixed order instead, so the first few keep their place and the rest wait.
	 */
	private static final int MAX_DRAWN_PER_TILE = 4;

	/**
	 * Which tier a golem is in, and where it is drawn. Inside an instance, a golem standing in a
	 * chunk it was built from is in the scene, drawn at the matching place on the instance's
	 * plane; every other golem is judged by its distance from the player in template terms,
	 * which puts the overworld out of range.
	 */
	private GolemTier tierFor(Golem golem, WorldView wv, WorldPoint playerAt, WorldPoint playerTemplate,
		Map<Long, int[]> sceneChunks)
	{
		int tileX = golem.getFineX() / Golem.TILE;
		int tileY = golem.getFineY() / Golem.TILE;

		// Where a golem stands says whether it is in an instance, whatever it was told on the
		// way: the room exists only as a copy walked in template coordinates. Told wrongly, it
		// was drawn in the wrong world — golems that had climbed out of the Mad Angel's room
		// went on walking the instance's copy of the island. The flag was only set by the
		// crossing, and one performed as a walk, or cut short by going out of view, never set
		// it.
		if (!instanceRoomTiles.isEmpty())
		{
			boolean inRoom = instanceRoomTiles.contains(RoamContext.tileKey(tileX, tileY, golem.getPlane()));
			if (golem.isInInstance() != inRoom && !golem.inTransition())
			{
				golem.setInInstance(inRoom);
			}
		}
		if (sceneChunks == null)
		{
			golem.setDrawOffset(0, 0, -1);
			// A golem in an instance is somewhere the overworld cannot see, even though it
			// is simulated in the template room — a real, sealed room on the island.
			if (golem.isInInstance())
			{
				return GolemTier.FAR;
			}
			return playerAt == null ? GolemTier.FAR
				: GolemTier.of(wv, tileX, tileY, golem.getPlane(), playerAt.getX(), playerAt.getY(), playerAt.getPlane());
		}

		// The instance is built from the chunks around the room as well as the room, and golems
		// on the island in those chunks were drawn inside it; the rest wait out of range.
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

	private void updateRenderer(Golem golem, WorldView wv, boolean inSceneTier, TileMap drawnPerTile)
	{
		boolean visible = inSceneTier
			&& wv != null
			&& wv.getPlane() == golem.getDrawPlane()
			&& inScene(wv, golem)
			&& drawnPerTile.addTo(RoamContext.tileKey(golem.getFineX() / Golem.TILE,
				golem.getFineY() / Golem.TILE, golem.getPlane()), 1) <= MAX_DRAWN_PER_TILE;

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
			long traceId = golem.getId();
			renderer.setTrace(line -> log.debug("Golem {} frame {}", traceId, line), () -> DevOptions.LOG_GOLEM_STATE);
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
		// Its raft goes with it: a golem removed or crumbled at sea is never updated again,
		// so nothing else would take the raft away.
		FakeRaft raft = rafts.remove(golem);
		if (raft != null)
		{
			client.removeRuneLiteObject(raft);
		}
	}

	// ---- per-tick upkeep ----

	/**
	 * Development only: records what a shortcut actually does when the player uses one. Which
	 * clip it plays, and how long it takes, is server-side and in no cache, so the only way to
	 * know is to use one and watch. Remove this and its two siblings before release.
	 */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		obstacleObserver.onMenuOptionClicked(event);
	}

	@Subscribe
	public void onHitsplatApplied(net.runelite.api.events.HitsplatApplied event)
	{
		// A hit while using an obstacle means it failed; a fall is not the way across.
		if (event.getActor() == client.getLocalPlayer())
		{
			obstacleObserver.onPlayerHurt();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		tickStartCycle = client.getGameCycle();
		announceUpdate();
		obstacleObserver.onGameTick();
		logGolemState();
		// Refreshed continuously rather than read once at spawn: a golem steps off its
		// plinth and starts walking, and its pose animations need not be the ones it had
		// while standing on it.
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

		// A new floor is ground to learn even though no scene was loaded: the harvest reads the
		// player's plane and only ran on a scene load, which a ladder does not trigger, so
		// golems that climbed arrived on unknown ground and were lifted back to the plinth —
		// 217 times in one session.
		WorldPoint me = PlayerPosition.of(client);
		int plane = me == null ? -1 : me.getPlane();
		if (plane != harvestedPlane)
		{
			harvestedPlane = plane;
			islandMemory.sceneChanged();
		}
		islandMemory.harvestLoadedRegions();

		// Only when the roster has actually changed: copying five hundred golems every tick
		// to hand the panel an identical list is work for nothing.
		if (panel != null && rosterChanged)
		{
			rosterChanged = false;
			List<Golem> living = livingGolems();
			panel.refresh(living, tally.getTotal() - living.size());
		}

		if (routesChanged)
		{
			applyLearnedRoutes();
			obstacleData.save();
		}

		if (++ticksSinceCensus >= CENSUS_TICKS)
		{
			ticksSinceCensus = 0;
			census.recount(golems);
		}

		if (saveGolemsAt >= 0 && client.getTickCount() >= saveGolemsAt)
		{
			saveGolemsAt = -1;
			saveGolems();
		}

		if (++ticksSinceSave >= SAVE_INTERVAL_TICKS)
		{
			ticksSinceSave = 0;
			saveGolems();
			saveIslandMemory();
			// Golems crossing obstacles add to the record constantly; written with the rest.
			obstacleData.save();
		}
	}

	/**
	 * Offers the hovered golem's menu entries.
	 *
	 * <p>On client tick rather than game tick: this is when the client rebuilds its menu, and
	 * a golem waiting up to 600ms to become hoverable would feel broken beside a real NPC.
	 */
	@Subscribe
	public void onClientTick(ClientTick event)
	{
		obstacleObserver.onClientTick();

		if (!golems.isEmpty())
		{
			menu.addEntries(drawnGolems);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();

		if (state == GameState.LOADING)
		{
			// The scene is being rebuilt, so every registered object is about to refer to
			// coordinates that no longer mean what they did. Drop the renderers; the golems
			// themselves are in world space.
			for (Golem golem : golems)
			{
				detachRenderer(golem);
			}
			// Scenery copies are registered objects too, holding scene-local coordinates
			// about to mean something else. A prop is short-lived, so drop them all.
			clearProps();
			clearRafts();
			lastGameCycle = -1;
			islandMemory.sceneChanged();
			return;
		}

		if (state == GameState.LOGGED_IN)
		{
			// The dock table is game data, not a bundled resource, so it needs a logged-in
			// client. Here rather than in startUp is what lets the plugin be enabled at the
			// login screen.
			sailingDocks.load();
			// Belt and braces next to onVarbitChanged: whichever of the two sees the
			// count first, the other is a no-op.
			syncTally();
			restorePending();
			return;
		}

		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			// Golems are permanent, so nothing is released here — only the renderers,
			// which belong to a scene that is going away.
			saveGolems();
			saveIslandMemory();
			// Quest progress is the account's, and the next login may be another account.
			// Not on every LOGGED_IN, which also follows each loading screen.
			sailingDocks.forgetQuests();
			abilities.forgetQuests();
			obstacleObserver.resetSession();
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

		// The obstacle settings are developer options for now, not in the panel: see
		// DevOptions. They must take effect the moment they change, so this returns with them.
		// if ("highlightObstacles".equals(event.getKey()) || "logGolemState".equals(event.getKey()))
		// {
		// 	applyObstacleSettings();
		// }
	}

	// ---- persistence ----

	/** Every tile of every instance room, from the last time routes changed. */
	private java.util.Set<Long> instanceRoomTiles = java.util.Collections.emptySet();

	/** Tiles flooded from an instance route's landing before it counts as open ground. */
	private static final int ROOM_FLOOD = 4000;

	/**
	 * Tiles only a transport into an instance reaches: the template rooms golems walk while
	 * inside one, as {@link RoamContext#tileKey} keys. For golems saved before the plugin
	 * tracked which were in an instance, and ones that walked into the Mad Angel's sealed room
	 * before the planner learned not to.
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
			TileMap reached = pathfinder.flood(t.getToX(), t.getToY(), t.getToPlane(), ROOM_FLOOD);
			// A landing the golem could walk back from, or that opens onto a whole island,
			// is not a room.
			if (reached.size() >= ROOM_FLOOD || (t.getFromPlane() == t.getToPlane()
				&& reached.containsKey(GolemPathfinder.pack(t.getFromX(), t.getFromY()))))
			{
				continue;
			}
			for (int i = 0; i < reached.size(); i++)
			{
				long tile = reached.keyAt(i);
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

		// The saved roster replaces whatever is in memory, never adding on top: golems already
		// here are left from a shutdown that did not finish, and are the same golems again.
		if (!golems.isEmpty())
		{
			log.warn("Restoring over {} golems left from before; replacing them", golems.size());
			for (Golem golem : golems)
			{
				detachRenderer(golem);
			}
			golems.clear();
		}

		List<GolemStore.SavedGolem> saved = new ArrayList<>(pendingRestore);
		java.util.Set<Long> rooms = instanceRooms();
		instanceRoomTiles = rooms;
		for (int i = 0; i < saved.size(); i++)
		{
			Golem golem = store.revive(saved.get(i), i);
			if (golem != null)
			{
				// A saved tile can have become unstandable, and a golem restored inside a new
				// wall would never path anywhere again. Moved while nothing is looking, and
				// moved rather than replaced: same name, same id.
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
		// Now there is a whole roster to hold against the game's count.
		trimToGameCount();
		log.debug("Restored {} saved golems", golems.size());
	}

	/**
	 * Writes the roster out. Golems mid-crumble are excluded: they have been retired, and
	 * saving them would bring them back alive on the next login.
	 */
	private void saveGolems()
	{
		if (!running)
		{
			log.debug("Not saving golems: the plugin is not running");
			return;
		}
		// Nothing to write until the saved roster has been loaded: golems are restored at login,
		// and saving the empty roster before that replaced every saved golem with nothing —
		// launching the client and closing it without logging in was enough.
		if (!pendingRestore.isEmpty())
		{
			log.debug("Not saving golems: {} saved golems are not restored yet", pendingRestore.size());
			return;
		}
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
	 * Hides a real golem once its copy has taken over: both exist for the rest of the
	 * original's short life, so without this there would visibly be two.
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
	 * The current roster, for the overlays. The live list, not a copy: the minimap overlay reads
	 * it once per frame and copying would allocate for nothing. Both are touched only on the
	 * client thread.
	 */
	List<Golem> activeGolems()
	{
		return golems;
	}

	/** Golems with a model in the scene this frame. See the end of onBeforeRender. */
	private final List<Golem> drawnGolems = new ArrayList<>();

	/**
	 * The golems being drawn, for the overlays and the menu: everything else is off the scene
	 * and would only be looked at to be skipped, thousands of times a frame.
	 */
	List<Golem> drawnGolems()
	{
		return drawnGolems;
	}
}
