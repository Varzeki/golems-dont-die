package com.golemsdontdie;

import com.google.inject.*;
import java.awt.image.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import javax.inject.Inject;
import javax.swing.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.*;
import net.runelite.client.callback.*;
import net.runelite.client.config.*;
import net.runelite.client.eventbus.*;
import net.runelite.client.events.*;
import net.runelite.client.plugins.*;
import net.runelite.client.ui.*;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.ui.overlay.infobox.*;
import net.runelite.client.util.*;
import static com.golemsdontdie.RouteGeometry.span;

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

	@Inject
	private GolemCrews crews;

	/** Golems that come aboard the player's own ship. See GolemShipmates. */
	@Inject
	private GolemShipmates shipmates;

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
	private InfoBoxManager infoBoxManager;

	/** For asking the Shortest Path plugin for a route, if it is installed. See pathTo. */
	@Inject
	private EventBus eventBus;

	/** The infobox saying how far off the golem being looked for is; null while none is. */
	private GolemFindBox findBox;

	@Inject
	private GolemMinimapOverlay minimapOverlay;

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
	private final Map<String, Boolean> obstacleVerdicts = new HashMap<>();

	@Inject
	private Voyage voyage;

	@Inject
	private GolemCensus census;

	@Inject
	private PlaceNames placeNames;

	@Inject
	private GolemClimate climates;

	@Inject
	private GolemNames names;

	/** One golem's own page, opened from the list. Swing thread only. */
	private GolemPage page;

	@Inject
	private GolemPortrait portraits;

	@Inject
	private Whereabouts whereabouts;

	@Inject
	private GolemMapPoints mapPoints;


	/** Reads what golems have been taught from the profile. */
	private void loadKnowledge()
	{
		obstacleKnowledge.deserialise(setting(ObstacleKnowledge.LEARNED_KEY));
		obstacleKnowledge.deserialiseConfirmed(setting(ObstacleKnowledge.CONFIRMED_KEY));
		obstacleKnowledge.deserialiseRoutes(setting(ObstacleKnowledge.ROUTES_KEY));
		obstacleKnowledge.deserialiseCurves(setting(ObstacleKnowledge.CURVES_KEY));
		obstacleKnowledge.deserialiseLines(setting(ObstacleKnowledge.LINES_KEY));
	}

	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		// RuneLite leaves a plugin running across a profile switch when both profiles have it on.
		// Everything held here came from the profile just left, and the next save would have
		// written it into the new one: one profile's golems, map and lessons over another's.
		clientThread.invoke(this::reloadProfile);
	}

	/**
	 * Drops everything read from the last profile, unsaved, and reads the new one's. The golems
	 * are the account's rather than the profile's, so they are saved first and read back.
	 */
	private void reloadProfile()
	{
		if (!running)
		{
			return;
		}
		saveGolems();
		dropRoster();
		rosterAccount = null;

		loadKnowledge();
		islandMemory.deserialise(setting(IslandMemory.MAP_KEY));
		islandMemory.loadBundled();
		applyLearnedRoutes();
		// Logged in, the golems come out now rather than at the next login.
		loadAccount();
	}

	/** Takes every golem out of memory, unsaved, with everything drawn for them. */
	private void dropRoster()
	{
		saveGolemsAt = -1;
		shipmates.abandon(client.getTickCount());
		for (Golem golem : golems)
		{
			detachRenderer(golem);
		}
		golems.clear();
		pendingRestore.clear();
		crews.clear();
		census.clear();
		clearProps();
		clearRafts();
		findGolem(null);
		mapPoints.clear();
		gameCount = -1;
		rosterChanged = true;
	}

	/**
	 * The account whose golems are in memory, as its RuneScape profile key, or null before one is
	 * loaded. Golems are kept per account and per kind of world, as the game keeps its count of
	 * them: one roster for every account on a profile had an alt with five golems crafted cull a
	 * main's four hundred down to five, and save that.
	 */
	private String rosterAccount;

	/**
	 * Names the account the golems saved before rosters were kept per account went to. Kept in
	 * the profile, beside the old save, so they are handed over once.
	 */
	private static final String ADOPTED_KEY = "savedGolemsAdoptedBy";

	/** How many golems the old, profile-wide save holds; -1 until counted. */
	private int legacyGolems = -1;

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		// Another account, or the same one on a seasonal world: another roster.
		clientThread.invoke(this::loadAccount);
	}

	/**
	 * Reads the logged-in account's golems, if they are not the ones in memory: at login, on a
	 * hop to a different kind of world, and once when the plugin starts logged in.
	 */
	private void loadAccount()
	{
		if (!running || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		String account = configManager.getRSProfileKey();
		if (account == null || account.equals(rosterAccount))
		{
			return;
		}
		String saved = configManager.getConfiguration(GolemsDontDieConfig.GROUP, account,
			GolemsDontDieConfig.SAVED_GOLEMS_KEY);
		String total = configManager.getConfiguration(GolemsDontDieConfig.GROUP, account, GolemTally.TOTAL_KEY);
		if (saved == null && setting(ADOPTED_KEY) == null)
		{
			// The roster saved before golems were kept per account goes to the first account that
			// could have crafted every golem in it. An alt, or a seasonal world, has crafted fewer.
			// The count is waited for: 0 is also what it reads before the server has sent it.
			String legacy = setting(GolemsDontDieConfig.SAVED_GOLEMS_KEY);
			if (legacyGolems < 0)
			{
				legacyGolems = store.deserialise(legacy).size();
			}
			if (legacyGolems > 0)
			{
				int count = client.getVarbitValue(GolemContent.GOLEM_COUNT_VARBIT);
				if (count <= 0)
				{
					return;
				}
				if (count >= legacyGolems)
				{
					saved = legacy;
					total = setting(GolemTally.TOTAL_KEY);
					setSetting(ADOPTED_KEY, account);
					log.debug("Handed the {} golems saved before accounts were told apart to {}", legacyGolems, account);
				}
			}
		}

		if (rosterAccount != null)
		{
			saveGolems();
			dropRoster();
		}
		rosterAccount = account;
		pendingRestore.addAll(store.deserialise(saved));
		tally.load(account, total);
		restorePending();
		syncTally();
	}

	/** Whether the golems in memory are the logged-in account's, so its count may judge them. */
	private boolean rosterIsLoggedIn()
	{
		return rosterAccount != null && rosterAccount.equals(configManager.getRSProfileKey());
	}

	/**
	 * Brings the list's "where is it" lines up to date, and now and then its order. Only the golems
	 * on the page: naming a place for every golem of a roster in the thousands, five times a second,
	 * would be the most expensive thing the plugin does.
	 */
	private void placeListed(GolemListPanel list)
	{
		List<Golem> listed = list.onScreenGolems();
		List<String> places = new ArrayList<>(listed.size());
		List<Integer> away = new ArrayList<>(listed.size());
		WorldPoint standing = PlayerPosition.of(client);
		for (Golem golem : listed)
		{
			places.add(whereabouts.of(golem, roamContext.getTick()));
			away.add(standing == null ? -1 : standing.distanceTo2D(golem.currentTile()));
		}
		list.showPlaces(listed, places, away);

		// And the order, now and then: nearest first, but not so often that the list shuffles
		// under a name being typed.
		if (++placesSinceOrder >= ORDERS_EVERY)
		{
			placesSinceOrder = 0;
			list.reorder(nearestFirst(), missingGolems(countLiving()));
		}
	}

	/** How often the sidebar's "where is it" lines are brought up to date, in game ticks. */
	private static final int PLACES_TICKS = 5;

	/** How many of those updates pass before the sidebar is put back in order of distance. */
	private static final int ORDERS_EVERY = 4;

	private int ticksSincePlaces;

	/** Set from the Swing thread when the list has built rows with no place yet. */
	private volatile boolean placesWanted;

	private int placesSinceOrder;

	/**
	 * The roster nearest first, which is the order the sidebar lists golems in.
	 *
	 * <p>Sorted here rather than in the panel because where a golem is belongs to the client thread.
	 * Distance is measured flat, so a golem upstairs or underfoot sorts by how far away it is on the
	 * map rather than being pushed to the end.
	 */
	private List<Golem> nearestFirst()
	{
		List<Golem> living = livingGolems();
		WorldPoint me = PlayerPosition.of(client);
		// Starred golems first, wherever they are: that is what starring one is for. Nearest first
		// within each, so the favourites keep the same order as everyone else.
		Comparator<Golem> order = Comparator.comparing(golem -> !golem.isFavourite());
		if (me != null)
		{
			order = order.thenComparingInt(golem -> golem.currentTile().distanceTo2D(me));
		}
		living.sort(order);
		return living;
	}

	/** How often golems are counted by region, in game ticks. See GolemCensus. */
	private static final int CENSUS_TICKS = 10;

	private int ticksSinceCensus;

	@Inject
	private GolemCelebration celebration;

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
		// Learned already, many times over: nothing more to write down.
		if (obstacleKnowledge.wellKnown(sighting))
		{
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
		worldMesh.linkSpaces(GolemContent.PLINTH_X, GolemContent.PLINTH_Y, 0);
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
		int steps = span(toX - fromX, toY - fromY);
		java.util.Deque<int[]> walk = pathfinder.findPath(fromX, fromY, fromPlane, toX, toY,
			new RoamBounds(islandMemory, fromPlane, fromX, fromY));
		return walk.isEmpty() || walk.size() > steps * 2 + 4;
	}

	private void saveLearnedObstacles()
	{
		setSetting(ObstacleKnowledge.LEARNED_KEY, obstacleKnowledge.serialise());
		setSetting(ObstacleKnowledge.CONFIRMED_KEY, obstacleKnowledge.serialiseConfirmed());
		setSetting(ObstacleKnowledge.ROUTES_KEY, obstacleKnowledge.serialiseRoutes());
		setSetting(ObstacleKnowledge.CURVES_KEY, obstacleKnowledge.serialiseCurves());
		setSetting(ObstacleKnowledge.LINES_KEY, obstacleKnowledge.serialiseLines());
	}

	@Provides
	GolemsDontDieConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GolemsDontDieConfig.class);
	}

	/** One of the plugin's own stored values, in its config group, or null if never set. */
	private String setting(String key)
	{
		return configManager.getConfiguration(GolemsDontDieConfig.GROUP, key);
	}

	private void setSetting(String key, Object value)
	{
		configManager.setConfiguration(GolemsDontDieConfig.GROUP, key, value);
	}

	@Override
	protected void startUp()
	{
		running = true;
		// Before anything below writes a key, or every install looks like an update.
		checkForUpdate();

		// Obstacles already taught, and the settings governing learning. Before the transport
		// network, whose usability gate consults it.
		loadKnowledge();

		obstacleObserver.setOnSighting(this::onObstacleSighting);
		obstacleObserver.startUp();
		// In the plugin's own folder, which RuneLite hands out; a plugin writes nowhere else.
		Filepath folder = null;
		try
		{
			folder = getPluginDirectory();
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("No folder for the obstacle data; it will not be kept this session", e);
		}
		obstacleData.startUp(folder);
		mapPoints.startUp();
		// Drawn in the world it stands in, which it is registered with: moving aboard or ashore
		// is a new drawn object, made on the next frame.
		shipmates.setOnMoved(this::detachRenderer);

		// Saved map first, shipped baseline underneath: what the player has walked beats a
		// static export of the same ground.
		islandMemory.deserialise(setting(IslandMemory.MAP_KEY));
		islandMemory.loadBundled();
		// Anywhere a transport from the island leads is ground golems will walk; resolved
		// lazily, after the network has loaded.
		islandMemory.setAlsoIsland(transports::leadsToRegion);

		// Read-only and shared, so loaded once here: a first-use load would land mid-frame,
		// the one place a few milliseconds shows.
		worldMesh.load();
		placeNames.load();
		names.load();
		// The climate is named places resolved to regions, so it waits for them.
		climates.learn(placeNames);
		propFactory.load();
		transports.load();
		// The index before the routes, because the routes are filtered by it.
		obstacleIndex.load();
		obstacleVerdicts.clear();
		obstacleKnowledge.setRouteFilter(this::isObstacleRoute);
		transports.setLearnedRoutes(obstacleKnowledge.learnedRoutes());
		// Floors reached only by transports the land fill never had. See WorldMesh.
		worldMesh.admitTransportEnds(transports.all());
		worldMesh.linkSpaces(GolemContent.PLINTH_X, GolemContent.PLINTH_Y, 0);
		setHomeRegions(transports.homeRegions());
		roamContext = new RoamContext(islandMemory, pathfinder, transports, abilities);
		roamContext.setKnowledge(obstacleKnowledge);
		roamContext.setObstacles(obstacleIndex);
		roamContext.setPlanner(roamPlanner);
		roamContext.setCensus(census);
		roamContext.setClimates(climates);
		roamContext.setTraversals(obstacleData::golemCrossed);
		roamContext.setMuster(crews::offer);
		roamContext.setModels(modelFactory);

		// Golems are the account's, so they are read at login; enabling mid-session skips the login.
		clientThread.invokeLater(this::loadAccount);

		// All three panel callbacks arrive on the Swing thread and touch the roster, which the
		// client thread owns, so each hops across.
		panel = new GolemListPanel(
			names,
			golem -> clientThread.invoke(() -> removeGolem(golem)),
			(golem, name) -> clientThread.invoke(() ->
			{
				golem.setNickname(name);
				saveGolemsSoon();
				pageRenamed(golem);
				// A golem named gives up the name it went by, and one unnamed may want another's.
				rosterChanged = true;
			}),
			() -> clientThread.invoke(this::reviveMissing),
			golem -> clientThread.invoke(() -> findGolem(golem)),
			golem -> clientThread.invoke(() ->
			{
				golem.setFavourite(!golem.isFavourite());
				saveGolemsSoon();
				// Straight to the top, or back among the rest, rather than at the next reorder.
				rosterChanged = true;
			}),
			golem ->
			{
				// Gone if the plugin stopped between the click and this: see shutDown.
				GolemPage open = page;
				if (open != null)
				{
					open.show(golem, panel);
					// The picture and the name of the furthest place it has been both need the
					// client thread, so the page goes up first and they arrive a frame later.
					clientThread.invoke(() -> dressPage(open, golem));
				}
			});
		panel.setOnPlacesWanted(() -> placesWanted = true);
		// Swing throughout, and it only reads the golem it is given: see GolemPage.
		page = new GolemPage(golem -> clientThread.invoke(() -> findGolem(golem)), names, placeNames);
		menu.setOnInfo(golem ->
		{
			GolemPage open = page;
			if (open != null)
			{
				// Swing's, from the client thread: the page is a window, not part of the game.
				SwingUtilities.invokeLater(() ->
				{
					open.show(golem, panel);
					clientThread.invoke(() -> dressPage(open, golem));
				});
			}
		});
		menu.setOnRenamed(golem ->
		{
			if (!running || panel == null)
			{
				return;
			}
			saveGolemsSoon();
			names.assign(golems);
			pageRenamed(golem);
			List<Golem> living = nearestFirst();
			panel.refresh(living, missingGolems(living.size()), true);
		});
		navButton = NavigationButton.builder()
			.tooltip("Golems")
			.icon(ImageUtil.loadImageResource(GolemsDontDiePlugin.class, "/golem-icon.png"))
			.priority(7)
			.panel(panel)
			.build();
		showSidebar(config.showSidebar());
		overlayManager.add(minimapOverlay);
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

			// Ashore first, at the quays they boarded from, so none is saved at sea.
			safely("bringing golems ashore", () -> shipmates.abandon(client.getTickCount()));
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
			safely("taking golems off the map", mapPoints::clear);
			safely("taking the arrow off a golem", () ->
			{
				finding = null;
				clearOurArrow();
				showFindBox(false);
				clearPath();
			});
			propFactory.clear();
			raftFactory.clear();
			crews.clear();
			census.clear();
			hiddenNpcs.clear();
			detector.reset();
			modelFactory.clear();
			saveGolemsAt = -1;
		});
		voyage.shutDown();

		overlayManager.remove(minimapOverlay);
		overlayManager.remove(nameplateOverlay);

		if (navButton != null)
		{
			showSidebar(false);
			navButton = null;
		}
		if (page != null)
		{
			GolemPage closing = page;
			page = null;
			SwingUtilities.invokeLater(closing::close);
		}
		panel = null;
	}

	/** Tells an open golem page that a golem has a new name, in case it is that golem's page. */
	private void pageRenamed(Golem golem)
	{
		GolemPage open = page;
		if (open != null)
		{
			open.renamed(golem);
		}
	}

	/**
	 * Draws the golem's portrait and names the furthest place it has been, then hands both to the
	 * page. Client thread: models, animation frames and the place names all live here.
	 */
	private void dressPage(GolemPage open, Golem golem)
	{
		BufferedImage drawn = portraits.of(golem);
		GolemHistory history = golem.getHistory();
		String far = history.getFurthest() <= 0 ? null
			: placeNames.nameFor(history.getFurthestX(), history.getFurthestY(), 0);
		SwingUtilities.invokeLater(() ->
		{
			open.setFurthest(golem, far);
			open.showPicture(golem, drawn);
		});
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
		CompletableFuture<Void> saved = new CompletableFuture<>();
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
		// One already copied, back in view before it crumbled: still hidden, and not copied again.
		if (detector.returned(npc, client.getTickCount()))
		{
			hiddenNpcs.add(npc.getIndex());
		}
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
		celebration.chatMessage(event.getMessage(), client.getTickCount());
	}

	/** How often the achievement diary flags are read, in ticks. See GolemCelebration.diaries. */
	private static final int DIARY_TICKS = 5;

	private int ticksSinceDiaries;

	/**
	 * Reads every diary tier's "complete" flag, now and then. Read rather than listened for: a
	 * tier nobody has finished sends nothing at login, so its first change is the finish itself.
	 */
	private void readDiaries()
	{
		if (client.getGameState() != GameState.LOGGED_IN || ++ticksSinceDiaries < DIARY_TICKS)
		{
			return;
		}
		ticksSinceDiaries = 0;
		int[] done = new int[GolemCelebration.DIARY_TIERS.length];
		for (int i = 0; i < done.length; i++)
		{
			done[i] = client.getVarbitValue(GolemCelebration.DIARY_TIERS[i]);
		}
		celebration.diaries(done, client.getTickCount());
	}

	/** The quest reward scroll: a quest or miniquest has just been finished. */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == net.runelite.api.gameval.InterfaceID.QUESTSCROLL)
		{
			celebration.questCompleted(client.getTickCount());
		}
	}

	/** Set between a pop-up notification starting and its text being filled in. */
	private boolean popupStarting;

	/**
	 * The game's pop-up notifications, which say "Collection log" and "Combat Task Completed!"
	 * whether or not the player has the matching chat lines turned on. Read as the Screenshot
	 * plugin reads them: the title is set once the pop-up's second script runs.
	 */
	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() == ScriptID.NOTIFICATION_START)
		{
			popupStarting = true;
		}
		else if (event.getScriptId() == ScriptID.NOTIFICATION_DELAY && popupStarting)
		{
			popupStarting = false;
			celebration.popup(client.getVarcStrValue(VarClientID.NOTIFICATION_TITLE),
				client.getTickCount());
		}
	}

	/** A level going up is one of the things the golems dance about. See GolemCelebration. */
	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		celebration.statChanged(event.getSkill(), event.getLevel(), client.getTickCount());
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
		// Another account's count says nothing about the golems in memory.
		if (client.getGameState() != GameState.LOGGED_IN || !rosterIsLoggedIn())
		{
			return;
		}

		int count = client.getVarbitValue(GolemContent.GOLEM_COUNT_VARBIT);
		if (tally.observeCount(count))
		{
			rosterChanged = true;
		}
		// The count itself says a golem was crafted, message or no message.
		if (count > 0)
		{
			celebration.golemCount(count, client.getTickCount());
		}
		if (count > 0)
		{
			gameCount = count;
			trimToGameCount();
		}
	}

	/** The golem being pointed at, or null. See findGolem. */
	private Golem finding;

	/** The golem being pointed at, for the overlay that draws the arrow over it. */
	Golem getFinding()
	{
		return finding;
	}

	/**
	 * The Shortest Path plugin's own name for itself, and its two messages: draw a route to a
	 * target, and take it away again. See its ShortestPathPlugin.onPluginMessage.
	 */
	private static final String SHORTEST_PATH = "shortestpath";
	private static final String SHORTEST_PATH_ROUTE = "path";
	private static final String SHORTEST_PATH_CLEAR = "clear";

	/**
	 * How far a golem must have moved, and how long since the last route, before it is routed to
	 * again. A route is a pathfinding search in the other plugin, and a golem walking is a target
	 * moving every tick; asked every tick it would search without end for a route it had not
	 * finished drawing.
	 */
	private static final int REROUTE_TILES = 12;
	private static final int REROUTE_TICKS = 10;

	/** Where Shortest Path was last asked to go, or null if this plugin has not asked. */
	private WorldPoint routedTo;
	private int routedAt;

	/**
	 * Asks Shortest Path for the way to a golem. Nobody listens if it is not installed, so this is
	 * free when it is not; when it is, the route follows the golem at a walking pace.
	 */
	private void pathTo(WorldPoint at)
	{
		if (!config.findPath())
		{
			clearPath();
			return;
		}
		int tick = client.getTickCount();
		boolean moved = routedTo == null || routedTo.getPlane() != at.getPlane()
			|| routedTo.distanceTo2D(at) >= REROUTE_TILES;
		if (!moved || routedTo != null && tick - routedAt < REROUTE_TICKS && tick >= routedAt)
		{
			return;
		}
		routedTo = at;
		routedAt = tick;
		Map<String, Object> data = new HashMap<>();
		// Onto ground: a golem a tile out on the water, or on the rocks at its edge, is a target
		// Shortest Path can only reach by way of the sea.
		data.put("target", roamPlanner.snapToMesh(at));
		eventBus.post(new PluginMessage(SHORTEST_PATH, SHORTEST_PATH_ROUTE, data));
	}

	/** Takes away a route this plugin asked for. One the player set themselves is not ours. */
	private void clearPath()
	{
		if (routedTo != null)
		{
			routedTo = null;
			eventBus.post(new PluginMessage(SHORTEST_PATH, SHORTEST_PATH_CLEAR));
		}
	}

	/** Puts the find infobox up, or takes it down. */
	private void showFindBox(boolean shown)
	{
		if (shown && findBox == null)
		{
			findBox = new GolemFindBox(ImageUtil.loadImageResource(GolemsDontDiePlugin.class, "/golem-icon.png"), this);
			infoBoxManager.addInfoBox(findBox);
		}
		else if (!shown && findBox != null)
		{
			infoBoxManager.removeInfoBox(findBox);
			findBox = null;
		}
	}

	/** "Stop" on the find infobox's right-click menu. */
	@Subscribe
	public void onInfoBoxMenuClicked(InfoBoxMenuClicked event)
	{
		if (event.getInfoBox() == findBox && findBox != null
			&& GolemFindBox.STOP.equals(event.getEntry().getOption()))
		{
			findGolem(null);
		}
	}

	/** How near a golem being looked for counts as found, in tiles. */
	private static final int FOUND_TILES = 8;

	/**
	 * Points at a golem until it is reached, or at nothing when given null.
	 *
	 * <p>A hint arrow over it while it is in view, an arrow at the edge of the screen while it is
	 * not, and the golem alone on the world map with its face stuck to the map's edge. Asking again
	 * for the golem already being pointed at stops the pointing, so one button does both.
	 */
	private void findGolem(Golem golem)
	{
		finding = golem == finding ? null : golem;
		if (finding == null)
		{
			clearOurArrow();
			clearPath();
		}
		showFindBox(finding != null);
		if (panel != null)
		{
			panel.setFinding(finding == null ? null
				: names.of(finding) != null ? names.of(finding) : "an unnamed golem");
		}
		GolemPage open = page;
		Golem target = finding;
		if (open != null)
		{
			SwingUtilities.invokeLater(() -> open.setFinding(target));
		}
		pointAtGolem();
	}

	/** Moves the arrow to where the golem is now, and stops once the player has reached it. */
	private void pointAtGolem()
	{
		if (finding == null)
		{
			return;
		}
		if (finding.isDying() || !golems.contains(finding))
		{
			findGolem(null);
			return;
		}

		WorldPoint at = finding.isSailing(roamContext.getTick()) ? finding.saveTile() : finding.currentTile();
		WorldPoint me = PlayerPosition.of(client);
		if (findBox != null)
		{
			// No distance across floors or between a cave and the ground above it: the tiles
			// between them are not a walk, and a number would say they were.
			boolean comparable = me != null && me.getPlane() == at.getPlane()
				&& WorldLayout.sameLayer(me.getY(), at.getY());
			String name = names.of(finding);
			findBox.show(name == null ? "an unnamed golem" : name, whereabouts.of(finding, roamContext.getTick()),
				comparable ? me.distanceTo2D(at) : -1);
		}
		if (me != null && me.getPlane() == at.getPlane() && me.distanceTo2D(at) <= FOUND_TILES)
		{
			// Found. An arrow over a golem standing beside you is noise.
			findGolem(null);
			return;
		}
		pathTo(at);

		// The game's own arrow points at a tile and moves when the tick says so, which beside a
		// walking golem reads as an arrow trailing it. So it is used only where the plugin cannot
		// draw its own: a golem out of the scene, where the arrow's other half — the marker on the
		// minimap — is the whole of what a player can go on. In the scene the two were drawn one
		// on top of the other, and GolemNameplateOverlay's is the better of them.
		if (finding.getRenderer() != null)
		{
			clearOurArrow();
		}
		else if (!client.hasHintArrow() || isOurArrow())
		{
			// Never over the game's own: a quest, a clue or a Slayer task pointing somewhere is
			// worth more than this, and the infobox, the map and the route still say where the
			// golem is.
			client.setHintArrow(at);
			arrowAt = at;
		}
		else
		{
			// The game has put up an arrow of its own since: it keeps it.
			arrowAt = null;
		}
	}

	/** True if the hint arrow showing is the one this plugin last put up. */
	private boolean isOurArrow()
	{
		return arrowAt != null && client.hasHintArrow()
			&& client.getHintArrowType() == HintArrowType.COORDINATE
			&& arrowAt.equals(client.getHintArrowPoint());
	}

	/** Where this plugin last put the game's hint arrow, or null if the arrow is not ours. */
	private WorldPoint arrowAt;

	/**
	 * Takes the hint arrow away if it is still the one this plugin put up. One the game has put up
	 * since — a quest step, a clue — is left where it is.
	 */
	private void clearOurArrow()
	{
		if (isOurArrow())
		{
			client.clearHintArrow();
		}
		arrowAt = null;
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
		if (gameCount <= 0 || !pendingRestore.isEmpty() || !rosterIsLoggedIn())
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
		Set<String> names = new HashSet<>();
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
		int missing = missingGolems(countLiving());
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
			numberNewGolems();
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
		if (animation == GolemContent.GOLEM_SPAWN_ANIMATION && detector.wasReplaced(npc))
		{
			// Not a golem come back after all: a new one, crafted onto an index a copied golem
			// had, stepping off the plinth. It is taken over like any other.
			detector.crafted(npc);
			hiddenNpcs.remove(npc.getIndex());
		}
		if (detector.wasReplaced(npc))
		{
			if (detector.isDeathAnimation(animation))
			{
				detector.noteCrumbling(npc);
			}
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
		detector.forget(npc, client.getTickCount());
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
		numberNewGolems();
		rosterChanged = true;
		enforceGolemLimit();

		detector.markReplaced(npc);
		hiddenNpcs.add(npc.getIndex());
		saveGolems();
	}

	/**
	 * How many golems reviving would bring back: the gap between golems crafted and golems here,
	 * but never past the limit when there is one. Offering the whole gap under a limit of 25
	 * revived four hundred golems, and the next craft culled the oldest of them to pay.
	 */
	private int missingGolems(int living)
	{
		int missing = tally.getTotal() - living;
		if (config.limitGolems())
		{
			missing = Math.min(missing, Math.max(1, config.maxGolems()) - living);
		}
		return Math.max(0, missing);
	}

	/**
	 * Retires the oldest unnamed golems until the roster is inside the limit. Unlimited by
	 * default: a golem costs an animated model every frame, so a cap is worth offering, but
	 * choosing one would quietly delete golems collected on purpose.
	 *
	 * <p><b>Named and starred golems are never culled</b>, even over the limit: a name or a star
	 * is the player saying they care about that golem. Retired golems crumble, and leave the
	 * roster when the animation finishes.
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
			if (golem.isDying() || isKept(golem))
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

	/** Whether the limit leaves a golem alone: one the player has named or starred. */
	private static boolean isKept(Golem golem)
	{
		return isNamed(golem) || golem.isFavourite();
	}

	/**
	 * Whether a new golem can be taken over. False when the roster is full of named or starred
	 * golems: nothing can make room, so the plugin stands aside rather than killing one to pay.
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
			if (!golem.isDying() && !isKept(golem))
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

		// What the golems are doing because of the player, once a tick rather than once a frame.
		// Fireworks especially: fifty rolls a tick would fill the sky in a second, and even once
		// a tick is too often for a clip about three ticks long, so they go every few.
		boolean social = tick != lastSocialTick;
		lastSocialTick = tick;
		if (social)
		{
			noteEmote();
			greetEachOther(tick);
		}
		boolean celebrating = celebration.isDancing(tick);
		int since = celebration.startedAt(tick);
		boolean fireworksDue = celebrating && tick != lastFireworkTick && since % FIREWORK_EVERY == 0;
		lastFireworkTick = celebrating ? tick : lastFireworkTick;

		for (Golem golem : golems)
		{
			// Aboard the player's ship: nowhere to walk and nothing to plan. It goes where the ship
			// goes, is drawn on its deck, and dances there if there is something to dance about.
			if (golem.isAboard())
			{
				shipmates.carry(golem);
				golem.setTickNow(tick);
				golem.setDancing(celebrating);
				updateRenderer(golem, wv, true, drawnPerTile);
				continue;
			}
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

			// Only the golems the player can see dance: one three regions away would be standing
			// still for nobody, and it has walking to be getting on with.
			golem.setTickNow(tick);
			golem.setDancing(celebrating && tier == GolemTier.SCENE || golem.isPartying(tick));
			if (social && tier == GolemTier.SCENE)
			{
				beSociable(golem, tick, playerAt);
			}

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

			// What the dance needs: the guitar an air guitar is played on, and the fireworks.
			if (golem.takeDanceProp())
			{
				spawnDanceProp(golem, wv);
			}
			if (fireworksDue && tier == GolemTier.SCENE)
			{
				spawnFirework(golem, wv);
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
		golem.relocate(safe);
		golem.noteUnstuck(tick);
	}

	/** The plane last harvested for, so moving to another floor harvests that one. */
	private int harvestedPlane = -1;

	/** A door opened: the shut door's wall object went and the open door's came. */
	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned event)
	{
		islandMemory.passabilityChanged(event.getWallObject().getWorldLocation());
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned event)
	{
		islandMemory.passabilityChanged(event.getWallObject().getWorldLocation());
	}

	/**
	 * Wyrmscraig, its caves and floors, and everywhere its own transports lead; recomputed
	 * whenever a route is learned. See {@link TransportNetwork#homeRegions}.
	 */
	private Set<Integer> homeRegions = Collections.emptySet();

	/** {@link #homeRegions} by region id, for the check made of every golem every frame. */
	private boolean[] homeRegionBits = new boolean[1 << 16];

	private void setHomeRegions(Set<Integer> regions)
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

	/** Golems asked whether they are cut off this tick. See sendHomeIfCutOff. */
	private static final int SWEEP_PER_TICK = 32;

	/** Where the sweep has got to in the roster. */
	private int sweptTo;

	/** Golems seen and golems cut off, over the pass being made now. */
	private int sweepSeen;
	private int sweepCutOff;

	/**
	 * What share of the roster the last full pass found cut off, or -1 before the first pass has
	 * finished. Nothing is moved until a pass has been made, and nothing is moved at all while
	 * that share is absurd.
	 */
	private float cutOffShare = -1;

	/**
	 * The share of golems that may be cut off before the answer is disbelieved rather than acted
	 * on. Whether a space joins up with home rests on the transport tables and the docks, and a
	 * missing piece can strand whole countries: measured against the shipped tables alone, with
	 * no docks to sail between them, ninety-nine per cent of the world's standable ground came
	 * out unreachable — Varrock and Falador included. A world that answers like that is a world
	 * the plugin has misread, and emptying it into the plinth would be the worst of the two
	 * mistakes. So the sweep counts first and moves nobody.
	 */
	private static final float MOST_CUT_OFF = 0.25f;

	/** Set once the warning about an unbelievable world has been given. */
	private boolean saidWorldUnreadable;

	/**
	 * Brings a golem home from ground that does not join up with home.
	 *
	 * <p>A golem only ever walks inside the space it is standing in — every other way of getting
	 * anywhere is a transport or a crossing. So the spaces and the transports make a graph, and a
	 * golem belongs in the part of it that can be reached from the plinth and walked back to it.
	 * Ground outside that is a trap however much of it there is: no golem could have arrived
	 * there, so one that is there was put there — by a rescue that reached across a channel, by a
	 * route since withdrawn, by a saved position from a build that allowed it. Most of the world
	 * is outside it, because the obstacles that would let a golem in have not been learned yet.
	 * That is the same statement from the other side, and the same answer: a golem cannot be
	 * somewhere it cannot get to.
	 *
	 * <p>Swept a few golems a tick rather than all of them every tick. Nothing about the shape of
	 * the world changes from one second to the next, and ten thousand golems would otherwise each
	 * ask the mesh where they were, forever.
	 */
	private void sweepForCutOff()
	{
		int roster = golems.size();
		for (int i = 0; i < Math.min(SWEEP_PER_TICK, roster); i++)
		{
			if (sweptTo >= roster)
			{
				// A pass finished: what it counted is what the next pass may act on.
				sweptTo = 0;
				if (sweepSeen > 0)
				{
					cutOffShare = sweepCutOff / (float) sweepSeen;
					if (cutOffShare > MOST_CUT_OFF && !saidWorldUnreadable)
					{
						saidWorldUnreadable = true;
						log.warn("{} of {} golems stand on ground that does not join up with home."
							+ " The world has been misread; none will be moved.",
							sweepCutOff, sweepSeen);
					}
				}
				sweepSeen = 0;
				sweepCutOff = 0;
			}
			sendHomeIfCutOff(golems.get(sweptTo++));
		}
	}

	private void sendHomeIfCutOff(Golem golem)
	{
		if (golem.isDying() || golem.inTransition() || golem.isAboard()
			|| golem.isSailing(roamContext.getTick()) || golem.isInInstance())
		{
			return;
		}
		int x = golem.getFineX() / Golem.TILE;
		int y = golem.getFineY() / Golem.TILE;
		// A pocket too small to wander with nothing leading in or out is a trap on the geometry
		// alone, and is acted on whatever the wider question has answered. A stepping stone is the
		// exception it always is: a golem between hops stands on one legitimately.
		boolean pocket = worldMesh.isSealedPocket(x, y, golem.getPlane()) && !transports.hasOrigin(x, y);

		sweepSeen++;
		boolean cutOff = worldMesh.isCutOff(x, y, golem.getPlane());
		sweepCutOff += cutOff ? 1 : 0;

		// Cut off is counted before it is believed: not acted on until a whole pass has been made,
		// and not while that pass says most of the world is unreachable, which means the tables are
		// missing rather than the golems are lost.
		boolean believable = cutOffShare >= 0 && cutOffShare <= MOST_CUT_OFF;
		if (!pocket && !(cutOff && believable))
		{
			return;
		}

		WorldPoint at = golem.currentTile();
		WorldPoint plinth = new WorldPoint(GolemContent.PLINTH_X, GolemContent.PLINTH_Y, 0);
		log.debug("Golem {} was {} at {}; brought home", golem.getId(),
			pocket ? "shut in a pocket" : "cut off", at);
		golem.relocate(plinth);
		golem.setInInstance(false);
		golem.noteUnstuck(roamContext.getTick());
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
		golem.relocate(plinth);
		golem.setInInstance(false);
		golem.noteUnstuck(roamContext.getTick());
	}

	/**
	 * This release, as the plugin's own files record it. It sits beside
	 * {@code runelite-plugin.properties} and {@code build.gradle}, which the Plugin Hub reads;
	 * this one is what the plugin writes into the data it keeps.
	 */
	static final String VERSION = "3.0";

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
		if (setting(ANNOUNCED_KEY) != null)
		{
			return;
		}
		for (String key : OLD_VERSION_KEYS)
		{
			if (setting(key) != null)
			{
				announcementPending = true;
				return;
			}
		}
		setSetting(ANNOUNCED_KEY, true);
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
		setSetting(ANNOUNCED_KEY, true);
	}

	/** Animated scenery currently on screen. Short-lived: each plays once and goes. */
	private final List<FakeProp> props = new ArrayList<>();

	/**
	 * Hulls drawn under golems at sea, keyed by the golem itself. Not by id: ids come from the
	 * crafting site and the game's NPC slot, which the game reuses, so two golems could share
	 * one raft — whichever was updated last had it, and if the other was ashore it took the
	 * raft away again, leaving a golem at the helm of nothing.
	 */
	private final Map<Golem, FakeRaft> rafts = new IdentityHashMap<>();

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

		// A crew has one boat between them, drawn under whoever has the helm. Everyone else is
		// standing on it, and a raft apiece would be eight rafts in a heap.
		GolemCrew crew = crews.crewOf(golem);
		if (crew != null && !crew.isHelm(golem))
		{
			afloat = false;
		}

		if (!afloat)
		{
			if (raft != null)
			{
				raft.detach();
				rafts.remove(golem);
			}
			return;
		}

		GolemBoat kind = crew == null ? GolemBoat.RAFT : crew.getBoat();
		if (raft == null)
		{
			Model hull = raftFactory.boatModel(kind);
			if (hull == null)
			{
				return;
			}
			raft = new FakeRaft(client, hull, modelFactory,
				golem.getFineX(), golem.getFineY(), golem.getOrientation(), kind.drawRadius(), golem.getBoatSeed());
			raft.addPart(raftFactory.rigModel(kind, false), kind.getMastAnimation(), kind.getMastX(), kind.getMastZ());
			raft.addPart(raftFactory.rigModel(kind, true), kind.getClothAnimation(), kind.getClothX(), kind.getClothZ());
			raft.attach();
			rafts.put(golem, raft);
		}

		// The hull follows the golem, which is at the helm at the stern, so the boat's middle is
		// ahead of it — the way it faces: 0 south, 512 west, 1024 north, 1536 east. How far ahead
		// is the boat's own business: a sloop's helm sits three and a half tiles back.
		double facing = golem.getOrientation() * Math.PI / 1024;
		int aheadX = (int) Math.round(-Math.sin(facing) * kind.getHelmOffset());
		int aheadY = (int) Math.round(-Math.cos(facing) * kind.getHelmOffset());
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
			raft.detach();
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

		// Drawn over the real object, where it stands and turned as it is turned: at the golem's
		// tile and facing south, a gate swung open beside the gate, at right angles to it. The
		// object is found rather than worked out, since only the scene knows its size and turn.
		TileObject real = sceneObject(wv, transport.getObjectId(), transport.getFromX(),
			transport.getFromY(), transport.getFromPlane());
		if (real == null)
		{
			return;
		}
		LocalPoint at = real.getLocalLocation();
		int localX = at.getX();
		int localY = at.getY();
		if (real instanceof DecorativeObject)
		{
			localX += ((DecorativeObject) real).getXOffset();
			localY += ((DecorativeObject) real).getYOffset();
		}
		int fineX = localX + wv.getBaseX() * Golem.TILE;
		int fineY = localY + wv.getBaseY() * Golem.TILE;
		int height = Perspective.getTileHeight(client, new LocalPoint(localX, localY, wv), transport.getFromPlane());
		// Scenery is turned in quarter turns as the scene loads; the copy is turned by the same.
		int turn = (configOf(real) >> 6 & 3) * 512;

		FakeProp drawn = new FakeProp(client, model,
			modelFactory.animationFor(prop.getAnimation()), prop.getAnimation(),
			fineX, fineY, transport.getFromPlane(), height, GolemContent.PROP_CYCLES);
		drawn.setFacing(() -> turn);

		client.registerRuneLiteObject(drawn);
		props.add(drawn);
	}

	/** How far from where a golem uses an object the object itself may be, in tiles. */
	private static final int OBJECT_REACH = 2;

	/** The object with this id nearest a tile, on that floor of the scene, or null if none is loaded. */
	private static TileObject sceneObject(WorldView wv, int objectId, int worldX, int worldY, int plane)
	{
		Scene scene = wv.getScene();
		Tile[][][] tiles = scene == null ? null : scene.getTiles();
		if (tiles == null || plane < 0 || plane >= tiles.length)
		{
			return null;
		}
		int sceneX = worldX - wv.getBaseX();
		int sceneY = worldY - wv.getBaseY();
		TileObject best = null;
		int bestSpan = Integer.MAX_VALUE;
		for (int x = sceneX - OBJECT_REACH; x <= sceneX + OBJECT_REACH; x++)
		{
			for (int y = sceneY - OBJECT_REACH; y <= sceneY + OBJECT_REACH; y++)
			{
				if (x < 0 || y < 0 || x >= tiles[plane].length || y >= tiles[plane][x].length || tiles[plane][x][y] == null)
				{
					continue;
				}
				Tile tile = tiles[plane][x][y];
				int span = Math.max(Math.abs(x - sceneX), Math.abs(y - sceneY));
				if (span >= bestSpan)
				{
					continue;
				}
				TileObject found = objectOn(tile, objectId);
				if (found != null)
				{
					best = found;
					bestSpan = span;
				}
			}
		}
		return best;
	}

	private static TileObject objectOn(Tile tile, int objectId)
	{
		if (tile.getWallObject() != null && tile.getWallObject().getId() == objectId)
		{
			return tile.getWallObject();
		}
		if (tile.getDecorativeObject() != null && tile.getDecorativeObject().getId() == objectId)
		{
			return tile.getDecorativeObject();
		}
		if (tile.getGroundObject() != null && tile.getGroundObject().getId() == objectId)
		{
			return tile.getGroundObject();
		}
		GameObject[] objects = tile.getGameObjects();
		if (objects != null)
		{
			for (GameObject object : objects)
			{
				if (object != null && object.getId() == objectId)
				{
					return object;
				}
			}
		}
		return null;
	}

	/** An object's placement bits: its type, and in bits 6 and 7 its turn. */
	private static int configOf(TileObject object)
	{
		return object instanceof GameObject ? ((GameObject) object).getConfig()
			: object instanceof WallObject ? ((WallObject) object).getConfig()
			: object instanceof DecorativeObject ? ((DecorativeObject) object).getConfig()
			: object instanceof GroundObject ? ((GroundObject) object).getConfig()
			: 0;
	}

	/**
	 * How often fireworks go off over a dancing golem, in ticks: every golem at the start of a
	 * celebration and every few ticks until it is over. The clip runs 1.8 seconds, so this is as
	 * near continuous as it can be without stacking one lot on the next.
	 */
	private static final int FIREWORK_EVERY = 3;

	/** The last tick fireworks were rolled for, so the roll is per tick and not per frame. */
	private int lastFireworkTick = -1;

	/** How near another golem has to be to count as company, in tiles. */
	private static final int COMPANY_TILES = 10;

	/** How many others the life of the party wants around it before it dances. */
	private static final int COMPANY = 2;

	/**
	 * The chance per tick that a golem with company dances, and how long it dances for.
	 *
	 * <p>Only about a golem in sixteen has the trait at all, and it needs two others beside it, so
	 * the roll has to be generous or the thing never happens where anyone is watching: at a
	 * thirtieth of this a full evening's play turned up nothing. As it stands an eligible golem in
	 * a crowd dances about every half minute, and the crowd is where the player is.
	 */
	private static final float PARTY_CHANCE = 0.02f;
	private static final int PARTY_TICKS = 17;

	/**
	 * How many golems may be on screen before one stops finding the player worth looking at.
	 *
	 * <p>A golem alone in the world with a player in front of it has every reason to stare. One
	 * of thirty at the plinth has seen you before, and thirty golems turning to watch at once is
	 * a guard of honour rather than curiosity.
	 */
	private static final int WATCHING_CROWD = 5;

	/** How near the player a golem watches from, the chance it does, and how long it looks. */
	private static final int WATCH_TILES = 12;
	private static final float WATCH_CHANCE = 0.01f;
	private static final int WATCH_TICKS = 8;

	/** The same for copying an emote, which is rarer to come by and likelier to be taken up. */
	private static final int MIMIC_TILES = 8;
	private static final float MIMIC_CHANCE = 0.5f;
	private static final int MIMIC_TICKS = 7;

	/**
	 * The emote the player has just begun, or -1. Read once a tick and held until it changes, so
	 * a four-second dance is one thing to copy rather than seven.
	 */
	private int playerEmote = -1;
	private int lastPlayerAnimation = -1;

	/** How near the player a friendly golem waves, the chance it does, and how long a wave takes. */
	private static final int GREET_TILES = 6;
	private static final float GREET_CHANCE = 0.05f;
	private static final int GREET_TICKS = 4;

	/** Ticks after a friendly golem's wave before it may wave again: six seconds. */
	private static final int GREET_GAP_TICKS = 10;

	/** The last tick the golems were asked whether they felt sociable. */
	private int lastSocialTick = -1;

	/** Whose fireworks go off and who feels like dancing: the golems' own generators are theirs. */
	private final Random moods = new Random();

	/** Models for the celebration, built once each: see GolemContent. */
	private final Map<Integer, Model> celebrationModels = new HashMap<>();

	/**
	 * Draws the fireworks over a golem. The client plays these on an actor, and a golem is a
	 * model of our own rather than an actor, so its model and sequence are drawn as scenery.
	 */
	private void spawnFirework(Golem golem, WorldView wv)
	{
		spawnAtGolem(golem, wv, GolemContent.FIREWORK_MODEL, GolemContent.FIREWORK_ANIMATION,
			GolemContent.FIREWORK_CYCLES);
	}

	/** Draws whatever the golem's dance move needs beside it: the air guitar's guitar. */
	private void spawnDanceProp(Golem golem, WorldView wv)
	{
		GolemDance move = golem.getDanceMove();
		if (move == null || move.getSpotanim() != GolemContent.SPOTANIM_AIR_GUITAR)
		{
			return;
		}
		spawnAtGolem(golem, wv, GolemContent.AIR_GUITAR_MODEL, GolemContent.AIR_GUITAR_ANIMATION,
			GolemContent.DANCE_PROP_CYCLES);
	}

	/**
	 * Draws a model on the golem's own tile, playing its own animation for a while. The golem
	 * stands still while it dances, so nothing has to follow it.
	 */
	private void spawnAtGolem(Golem golem, WorldView wv, int modelId, int animationId, int cycles)
	{
		if (wv == null)
		{
			return;
		}
		// Where the golem is drawn, which inside an instance is not where it is simulated.
		int localX = golem.getDrawFineX() - wv.getBaseX() * Golem.TILE;
		int localY = golem.getDrawFineY() - wv.getBaseY() * Golem.TILE;
		if (!Golem.isInScene(wv, localX, localY))
		{
			return;
		}
		Model model = celebrationModel(modelId);
		if (model == null)
		{
			return;
		}
		// And on the floor it is drawn on: inside an instance that is not the one it is simulated
		// on either, and fireworks went off a floor above or below it.
		int height = Perspective.getTileHeight(client,
			new LocalPoint(localX, localY, wv), golem.getDrawPlane());
		FakeProp drawn = new FakeProp(client, model, modelFactory.animationFor(animationId),
			animationId, golem.getDrawFineX(), golem.getDrawFineY(), golem.getDrawPlane(), height, cycles);
		drawn.setFacing(golem::drawOrientation);
		client.registerRuneLiteObject(drawn);
		props.add(drawn);
	}

	/** A celebration model, lit as the props are and kept: there are two of them and they repeat. */
	private Model celebrationModel(int modelId)
	{
		Model cached = celebrationModels.get(modelId);
		if (cached != null)
		{
			return cached;
		}
		try
		{
			ModelData data = client.loadModelData(modelId);
			if (data == null)
			{
				return null;
			}
			Model built = data.cloneVertices().light();
			celebrationModels.put(modelId, built);
			return built;
		}
		catch (RuntimeException e)
		{
			log.debug("Celebration model {} would not load", modelId, e);
			return null;
		}
	}

	/**
	 * Watches what the player is doing, so golems can copy it.
	 *
	 * <p>Only the emote tab's own clips, and only the moment one begins: an emote held for four
	 * seconds is one thing to copy, and a combat or skilling animation is not an emote at all.
	 */
	private void noteEmote()
	{
		Player me = client.getLocalPlayer();
		int animation = me == null ? -1 : me.getAnimation();
		playerEmote = animation != lastPlayerAnimation && GolemContent.isEmote(animation) ? animation : -1;
		lastPlayerAnimation = animation;
	}

	/**
	 * What a golem does because of what it is like: the life of the party dances when there is a
	 * crowd to dance in, and a friendly golem waves at the player.
	 *
	 * <p>Once a tick per golem in the scene, and only for the two traits that ask for it, so the
	 * count of who is nearby is paid for by the few golems that have them.
	 */
	private void beSociable(Golem golem, int tick, WorldPoint playerAt)
	{
		// Not mid-climb or at sea either: a golem turned to stare while crossing a log balance
		// stopped where it was, on nothing.
		if (!freeToWave(golem, tick))
		{
			return;
		}

		// Curiosity, which is about the player being a rarity rather than about the golem: one
		// golem in an empty field stares, one of thirty at the plinth has seen you before.
		if (playerAt != null && drawnGolems.size() <= WATCHING_CROWD)
		{
			WorldPoint at = golem.currentTile();
			int away = at.getPlane() == playerAt.getPlane() ? at.distanceTo2D(playerAt) : Integer.MAX_VALUE;
			int dx = playerAt.getX() - at.getX();
			int dy = playerAt.getY() - at.getY();
			if (playerEmote != -1 && away <= MIMIC_TILES && moods.nextFloat() < MIMIC_CHANCE)
			{
				golem.mimic(tick + MIMIC_TICKS, dx, dy, playerEmote);
				return;
			}
			if (away <= WATCH_TILES && moods.nextFloat() < WATCH_CHANCE)
			{
				golem.watch(tick + WATCH_TICKS, dx, dy);
				return;
			}
		}

		if (playerAt != null && GolemTrait.FRIENDLY.in(golem.getTraits())
			&& golem.mayGreetAgain(tick, GREET_GAP_TICKS) && moods.nextFloat() < GREET_CHANCE)
		{
			WorldPoint at = golem.currentTile();
			if (at.getPlane() == playerAt.getPlane() && at.distanceTo2D(playerAt) <= GREET_TILES)
			{
				// Turned to face the player: a wave over its shoulder is not a greeting.
				golem.greet(tick + GREET_TICKS, playerAt.getX() - at.getX(), playerAt.getY() - at.getY());
				return;
			}
		}

		if (GolemTrait.LIFE_OF_THE_PARTY.in(golem.getTraits())
			&& moods.nextFloat() < PARTY_CHANCE && company(golem) >= COMPANY)
		{
			golem.startParty(tick + PARTY_TICKS);
		}
	}

	/** How near two golems alone together must be to wave at each other, in tiles. */
	private static final int PAIR_TILES = 8;

	/** How long before a golem waves at another golem again, in ticks: five minutes. */
	private static final int PAIR_COOLDOWN_TICKS = 500;

	/**
	 * Two golems with nobody else about wave at each other, once.
	 *
	 * <p>Only when they are the only two golems in view: in a crowd a golem has others all round
	 * it and greeting each would be all it did, while two meeting on an empty road is exactly when
	 * people raise a hand. Once, and then not again for five minutes, so a pair walking the same
	 * way is not waving the whole length of it.
	 */
	private void greetEachOther(int tick)
	{
		if (drawnGolems.size() != 2)
		{
			return;
		}
		Golem one = drawnGolems.get(0);
		Golem other = drawnGolems.get(1);
		if (!freeToWave(one, tick) || !freeToWave(other, tick) || one.getPlane() != other.getPlane()
			|| !one.mayWaveAtGolem(tick, PAIR_COOLDOWN_TICKS) || !other.mayWaveAtGolem(tick, PAIR_COOLDOWN_TICKS))
		{
			return;
		}
		WorldPoint a = one.currentTile();
		WorldPoint b = other.currentTile();
		int apart = a.distanceTo2D(b);
		// Not two golems standing in one another either: there is no turning to face that.
		if (apart < 1 || apart > PAIR_TILES)
		{
			return;
		}
		one.waveAtGolem(tick + GREET_TICKS, b.getX() - a.getX(), b.getY() - a.getY(), tick);
		other.waveAtGolem(tick + GREET_TICKS, a.getX() - b.getX(), a.getY() - b.getY(), tick);
	}

	/** Whether a golem is doing nothing that a wave would interrupt. */
	private static boolean freeToWave(Golem golem, int tick)
	{
		return !golem.isDying() && !golem.isDancing() && !golem.isGreeting(tick) && !golem.isPartying(tick)
			&& !golem.inTransition() && !golem.isSailing(tick) && !golem.isAboard();
	}

	/** How many other golems are within a few tiles of this one, counted up to what is asked. */
	private int company(Golem golem)
	{
		WorldPoint at = golem.currentTile();
		int near = 0;
		for (Golem other : drawnGolems)
		{
			if (other != golem && other.getPlane() == at.getPlane()
				&& other.currentTile().distanceTo2D(at) <= COMPANY_TILES && ++near >= COMPANY)
			{
				return near;
			}
		}
		return near;
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

	/**
	 * Attaches or detaches a golem's drawn object as it crosses the scene boundary, which is
	 * what lets one wander off the edge of what is loaded and come back: leaving the scene
	 * costs it its renderer, not its existence.
	 */
	private void updateRenderer(Golem golem, WorldView wv, boolean inSceneTier, TileMap drawnPerTile)
	{
		boolean visible = inSceneTier
			&& wv != null
			&& wv.getPlane() == golem.getDrawPlane()
			&& inScene(wv, golem)
			// Aboard a boat, the player's or a crew's own, each golem has a place of its own on the
			// deck however many share the boat's tile: the cap is for golems heaped on one of land.
			&& (golem.isAboard() || golem.isCrewed() || drawnPerTile.addTo(RoamContext.tileKey(golem.getFineX() / Golem.TILE,
				golem.getFineY() / Golem.TILE, golem.getPlane()), 1) <= MAX_DRAWN_PER_TILE);

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
			raft.detach();
		}
	}

	// ---- per-tick upkeep ----

	/**
	 * Puts the Golems tab in the sidebar, or takes it away.
	 *
	 * <p>The panel itself is built either way and keeps its state: the tab is a way in, not the
	 * roster. Off, the plinth's own menu is how missing golems are revived; see GolemMenu.
	 */
	private void showSidebar(boolean shown)
	{
		if (navButton == null)
		{
			return;
		}
		if (shown && !sidebarShown)
		{
			clientToolbar.addNavigation(navButton);
		}
		else if (!shown && sidebarShown)
		{
			clientToolbar.removeNavigation(navButton);
		}
		sidebarShown = shown;
	}

	/** Whether the tab is in the sidebar, so it is neither added twice nor removed twice. */
	private boolean sidebarShown;

	/**
	 * Offers to revive missing golems from the plinth they were carved on, for a player with the
	 * sidebar off — and for anyone standing at the plinth, which is where it would occur to them.
	 *
	 * <p>On a plain right-click and not behind shift: the entry is there only while golems are
	 * missing, so it is never in the way of carving another one.
	 */
	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (!running)
		{
			return;
		}
		int missing = missingGolems(countLiving());
		if (missing <= 0)
		{
			return;
		}
		for (MenuEntry entry : event.getMenuEntries())
		{
			if (GolemContent.isPlinth(entry.getIdentifier()))
			{
				// Named as the plinth the player is pointing at, which is one of six carvings.
				menu.addReviveEntry(missing, entry.getTarget(), this::reviveMissing);
				return;
			}
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		obstacleObserver.onMenuOptionClicked(event);
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
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
		// Usually a comparison; the first tick a new account's count is known, its golems.
		loadAccount();
		announceUpdate();
		readDiaries();
		obstacleObserver.onGameTick();
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
			// Which of two golems keeps a name they would share is settled once per change, here;
			// rows already shown are only named again if one of them now answers to something else.
			boolean renamed = names.assign(golems);
			List<Golem> living = nearestFirst();
			panel.refresh(living, missingGolems(living.size()), renamed);
			if (renamed)
			{
				GolemPage open = page;
				pageRenamed(open == null ? null : open.getShowing());
			}
		}

		if (routesChanged)
		{
			applyLearnedRoutes();
			obstacleData.save();
		}

		// Who is waiting at a quayside, who has just cast off, and who has landed.
		crews.update(golems, roamContext.getTick(), roamContext);

		// Whether the player has just stepped aboard their ship, or off it, with golems following.
		shipmates.update(golems, client.getTickCount(), roamPlanner::landingTileNear);

		// A few golems a tick, asked whether the ground they are on joins up with home.
		sweepForCutOff();

		// Every tick, so a golem keeps up with the map as it is panned. Closed, this is one widget
		// lookup and nothing else.
		pointAtGolem();

		refreshMap();

		// Each read once: shutting down on the Swing thread clears them, and a check then a use of
		// the field could see it go in between.
		GolemListPanel list = panel;
		GolemPage open = page;
		boolean listing = list != null && list.isOnScreen();
		boolean pageOpen = open != null && open.isOpen();
		if ((listing || pageOpen) && (++ticksSincePlaces >= PLACES_TICKS || placesWanted))
		{
			ticksSincePlaces = 0;
			placesWanted = false;
			if (listing)
			{
				placeListed(list);
			}

			// And the page, if one is open, which is one golem and may not be among those listed:
			// kept up to date whether or not the list is showing, or even enabled.
			if (pageOpen)
			{
				Golem shown = open.getShowing();
				String where = whereabouts.of(shown, roamContext.getTick());
				boolean living = shown != null && golems.contains(shown) && !shown.isDying();
				SwingUtilities.invokeLater(() -> open.showPlace(shown, living ? where : "Gone", living));
			}
		}

		if (++ticksSinceCensus >= CENSUS_TICKS)
		{
			ticksSinceCensus = 0;
			census.recount(golems);

			// The same pass keeps each golem's own tally: where it has got to, and how much ground
			// it has covered since the last one. See GolemHistory.
			for (Golem golem : golems)
			{
				// At sea it has not walked anywhere, on the player's ship or a boat of its own: the
				// voyage is written down when it comes ashore, as sailed. Sampled mid-crossing, the
				// coast it passed was written down as walked to, and the landfall with it.
				if (golem.isAboard() || golem.isSailing(roamContext.getTick()))
				{
					continue;
				}
				WorldPoint at = golem.currentTile();
				golem.getHistory().sample(at.getX(), at.getY(), at.getPlane(), golem.getHome(), placeNames);
			}
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

	private void refreshMap()
	{
		GolemsDontDieConfig.MapGolems onMap = config.mapGolems();
		if (onMap == GolemsDontDieConfig.MapGolems.NONE && finding == null)
		{
			mapPoints.clear();
		}
		else
		{
			// The roster itself, not a copy of the living: this runs every tick whether or not the
			// map is open, and the map skips the dying golems itself once it knows it is.
			mapPoints.refresh(golems, onMap == GolemsDontDieConfig.MapGolems.NAMED,
				roamContext.getTick(), finding);
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

		// The map as soon as it opens, and every frame until it knows where it draws a cave.
		if (mapPoints.wantsFrame(golems))
		{
			refreshMap();
		}

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
			loadAccount();
			// Belt and braces next to onVarbitChanged: whichever of the two sees the
			// count first, the other is a no-op.
			syncTally();
			restorePending();
			return;
		}

		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			// Golems are permanent, so nothing is released here — only the renderers,
			// which belong to a scene that is going away. Any aboard the player's ship go back
			// to their quays first: the ship does not come with the player.
			shipmates.abandon(client.getTickCount());
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

			// The levels and the golem count are about to be sent again, and must not be taken for
			// news. And whatever the golems were doing for the player's benefit — a wave, a dance, a
			// crew making up at a quay — is over: those moments were timed against this session.
			celebration.reset();
			for (Golem golem : golems)
			{
				golem.forgetMoments();
			}
			crews.releaseMusters();

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

		if ("showSidebar".equals(event.getKey()))
		{
			showSidebar(config.showSidebar());
		}

		if (("autoName".equals(event.getKey()) || "nameStyle".equals(event.getKey())) && panel != null)
		{
			panel.namesChanged();
			GolemPage open = page;
			pageRenamed(open == null ? null : open.getShowing());
		}

		if ("maxGolems".equals(event.getKey()) || "limitGolems".equals(event.getKey()))
		{
			clientThread.invoke(() ->
			{
				enforceGolemLimit();
				saveGolems();
				// The revive button offers only what the limit leaves room for.
				rosterChanged = true;
			});
		}
	}

	// ---- persistence ----

	/** Every tile of every instance room, from the last time routes changed. */
	private Set<Long> instanceRoomTiles = Collections.emptySet();

	/** Tiles flooded from an instance route's landing before it counts as open ground. */
	private static final int ROOM_FLOOD = 4000;

	/**
	 * Tiles only a transport into an instance reaches: the template rooms golems walk while
	 * inside one, as {@link RoamContext#tileKey} keys. For golems saved before the plugin
	 * tracked which were in an instance, and ones that walked into the Mad Angel's sealed room
	 * before the planner learned not to.
	 */
	private Set<Long> instanceRooms()
	{
		Set<Long> rooms = new HashSet<>();
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

	/**
	 * Gives golems just made the next craft numbers, in the order they were made. Not before the save
	 * is restored: the restored golems take the numbers from one, and replace whatever is in memory.
	 */
	private void numberNewGolems()
	{
		if (pendingRestore.isEmpty())
		{
			CraftNumbers.number(golems, golem -> golem.getHistory().getFirstSeen());
		}
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
		Set<Long> rooms = instanceRooms();
		instanceRoomTiles = rooms;
		// When each golem was first known as saved, not as restored: a golem saved before that was
		// kept is given today's date on restore, which would number the oldest golems last.
		Map<Golem, Long> savedAge = new IdentityHashMap<>();
		for (int i = 0; i < saved.size(); i++)
		{
			Golem golem = store.revive(saved.get(i), i);
			if (golem != null)
			{
				savedAge.put(golem, saved.get(i).firstSeen);
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
		int numbered = CraftNumbers.number(golems, golem -> savedAge.getOrDefault(golem, 0L));
		if (numbered > 0)
		{
			log.debug("Gave {} restored golems craft numbers", numbered);
		}
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
		// Named rather than the current account: a save can land after the account has changed.
		if (rosterAccount == null)
		{
			return;
		}
		configManager.setConfiguration(GolemsDontDieConfig.GROUP, rosterAccount,
			GolemsDontDieConfig.SAVED_GOLEMS_KEY, store.serialise(livingGolems()));
	}

	private void saveIslandMemory()
	{
		if (!islandMemory.isDirty())
		{
			return;
		}
		setSetting(IslandMemory.MAP_KEY, islandMemory.serialise());
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
