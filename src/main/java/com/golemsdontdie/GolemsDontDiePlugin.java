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
	 * Development only: measures what a shortcut really does when the player uses one.
	 * Remove before release, along with its three event hooks.
	 */
	@Inject
	private ShortcutRecon shortcutRecon;

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

	@Provides
	GolemsDontDieConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GolemsDontDieConfig.class);
	}

	@Override
	protected void startUp()
	{
		// Saved map first, then the shipped baseline underneath it — anything the
		// player has actually walked beats a static export of the same ground.
		islandMemory.deserialise(configManager.getConfiguration(GolemsDontDieConfig.GROUP, IslandMemory.MAP_KEY));
		islandMemory.loadBundled();

		// The transport table is read-only and shared by every golem, so it is loaded
		// once here rather than lazily on first use — a first-use load would land in the
		// middle of a frame, which is the one place a few milliseconds is noticeable.
		worldMesh.load();
		propFactory.load();
		transports.load();
		roamContext = new RoamContext(islandMemory, pathfinder, transports, abilities);
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

		renderCallbacks.register(drawCallback);
		callbackRegistered = true;

		lastGameCycle = -1;
		clientThread.invokeLater(this::restorePending);
	}

	@Override
	protected void shutDown()
	{
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
		// Development only; remove with the rest of the recon.
		shortcutRecon.onAnimationChanged(event);

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

		// Resolved once rather than per golem. Both of these were being fetched or
		// built five hundred times a frame to produce the same answer.
		WorldView wv = client.getTopLevelWorldView();

		// One context for the whole frame. Only the two per-golem knobs change as the
		// roster is walked; everything else in it is the same for every golem.
		roamContext.setTick(client.getTickCount());
		roamContext.setMaySearchSea(true);

		Player local = client.getLocalPlayer();
		WorldPoint playerAt = local == null ? null : local.getWorldLocation();

		for (Golem golem : golems)
		{
			// Which tier this golem is in is a question about this golem, not about the
			// player's proximity to Wyrmscraig. That distinction is the whole change: the
			// old gate switched every golem off at once whenever the player walked away
			// from the island, which was correct only while the island was all there was.
			GolemTier tier = playerAt == null
				? GolemTier.FAR
				: GolemTier.of(wv, golem.getFineX() / Golem.TILE, golem.getFineY() / Golem.TILE,
					golem.getPlane(), playerAt.getX(), playerAt.getY(), playerAt.getPlane());

			golem.setTier(tier, roamContext, roamPlanner);

			if (tier == GolemTier.FAR)
			{
				golem.advanceFar(roamContext, roamPlanner);
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

			updateRenderer(golem, wv, tier == GolemTier.SCENE);
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
		if (tier != GolemTier.FAR && !golem.isDying() && !golem.inTransition())
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
		golem.relocate(safe);
		golem.noteUnstuck(tick);
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
	private void updateRenderer(Golem golem, WorldView wv, boolean inSceneTier)
	{
		boolean visible = inSceneTier
			&& wv != null
			&& wv.getPlane() == golem.getPlane()
			&& inScene(wv, golem);

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
			golem.getFineX() - wv.getBaseX() * Golem.TILE,
			golem.getFineY() - wv.getBaseY() * Golem.TILE);
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
		shortcutRecon.onMenuOptionClicked(event);
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		shortcutRecon.onGameTick();
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
	}

	// ---- persistence ----

	private void restorePending()
	{
		if (pendingRestore.isEmpty() || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		List<GolemStore.SavedGolem> saved = new ArrayList<>(pendingRestore);
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
