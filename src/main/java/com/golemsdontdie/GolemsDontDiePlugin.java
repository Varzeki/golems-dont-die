package com.golemsdontdie;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.NPC;
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
 * leaving the scene costs it its renderer, not its existence. Nothing is simulated at
 * all while the player is away from the island.
 *
 * <p>Purely cosmetic. Nothing here sends anything to the server, and the copies are
 * visible only to the player running the plugin.
 *
 * @see IslandMemory for the island's passability map
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

		boolean nearIsland = islandMemory.playerNearIsland();

		// Path searches are rationed per frame. A golem only searches when it finishes
		// a walk, which is rare individually — but with hundreds of them the arrivals
		// bunch up, and a frame that runs fifty breadth-first searches is a visible
		// stutter. Golems denied a search simply stand still for a frame and ask again,
		// which is indistinguishable from the pause they take between walks anyway.
		int searchBudget = PATH_SEARCHES_PER_FRAME;

		// Resolved once rather than per golem. Both of these were being fetched or
		// built five hundred times a frame to produce the same answer.
		WorldView wv = client.getTopLevelWorldView();

		for (Golem golem : golems)
		{
			if (nearIsland && elapsed > 0)
			{
				if (golem.advance(elapsed, islandMemory, pathfinder, searchBudget > 0))
				{
					searchBudget--;
				}
			}
			updateRenderer(golem, wv, nearIsland);
		}

		reapCrumbled();
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
	private void updateRenderer(Golem golem, WorldView wv, boolean nearIsland)
	{
		boolean visible = nearIsland
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

	@Subscribe
	public void onGameTick(GameTick event)
	{
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
			lastGameCycle = -1;
			islandMemory.sceneChanged();
			return;
		}

		if (state == GameState.LOGGED_IN)
		{
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
