# Open bugs — obstacle traversal

Observed in game, 12 Sep. Grouped by suspected root cause rather than by symptom,
because several of these are the same fault seen from different angles.

Status: `open` / `fixed` / `needs-data`.

---

## A. The shape of a traversal is wrong

These all come from the same place: we record *that* an obstacle moved the player
and roughly how long it took, but not the internal structure of the movement —
when it starts relative to the animation, which way the player faces, or how many
separate actions it was.

| # | Symptom | Status |
|---|---|---|
| A1 | No pause before hopping at a stepping stone; the player visibly pauses | needs-data |
| A2 | Return journey skips the middle stone, crossing the whole gap in one | open |
| A3 | Bottom rockslide: golem turns around, ends a tile away after a climb animation | open |
| A4 | Rockslide speed inconsistent — sometimes fast, sometimes slow | open |
| A5 | Climbing *down* the rockslide, golems face outwards instead of holding the rock | open |

**A1.** Measured once from `FINE` rows and found no wind-up, but that measurement
started *at* the animation, which is exactly where the wind-up would already have
ended. The `PRE` lookback now records the second before a traversal; re-measure
from that before concluding anything.

**A2.** A learned route of `62262: 2565,2217 -> 2565,2221` is four tiles across
three stones — the whole crossing recorded as one action. The recorder has since
been fixed to split on the next click, but **the bad route is already saved with
enough sightings to be trusted**, and it sits alongside the correct per-hop rows
from the shipped table. A golem picking the learned one clears the crossing in a
single jump.

**A5.** Climbing down, a player faces the rock and moves backwards. We always face
the direction of travel, so the golem climbs down facing away from the cliff.

---

## B. Movement kind is wrong

| # | Symptom | Status |
|---|---|---|
| B1 | Golems glide through the cathedral door instead of teleporting across it | open |

Research established that ladders and teleport-doors use `anim` followed by a hard
`p_telejump` — no interpolation at all — while stepping stones and stiles use
`p_exactmove`, which is interpolated. We glide anything within eight tiles on the
same plane, so a teleport-door gets slid through.

---

## C. What happens after a traversal

| # | Symptom | Status |
|---|---|---|
| C1 | Golems entering the cathedral leave again immediately, most of the time | open |
| C2 | Golems go up a ladder and straight back down | fixed — see E |
| C3 | Golems go up a staircase and straight back down | fixed — see E |

Two suspects, and they may both be live.

**The reverse is not on cooldown.** `TransportMemory` exists to stop a golem pacing
back through the same door, and it keys on the transport's `reverse` index — which
is resolved when the shipped table is built. A *learned* transport is created at
runtime with `reverse = -1`, so nothing stops a golem using the return trip on the
very next tick.

**The far side may not be somewhere it can plan.** The shipped mesh's reachability
fill spreads through shipped transports only, so an upper floor reachable only by an
unlisted staircase is neither land nor a large enough component. `isSafe` now also
accepts ground the live harvest knows — but that harvest is session-scoped, so it
only helps where the player has been *this session*.

---

## D. Data and cleanup

| # | Symptom | Status |
|---|---|---|
| D1 | Golems inside the inaccessible Mad Angel static boss room | open |
| D2 | The stile shows green but no golem uses it | open |

**D1.** The room exists in the world data and is only ever entered as an instance.
An earlier attempt to seal it — blocking every solid object from the cache — sealed
cathedral interiors and the stile's own ground as well, and was reverted. These
golems need clearing out regardless of whether the underlying hole is closed.

**D2.** The stile's origin tile is impassable in the mesh, which the research says is
correct and by design: obstacles routinely stand on blocked tiles and the game
teleports the player onto the object before animating. Golems will not walk to a
tile the planner rejects, so they never reach it.

---

## E. Golems out of view, and floors nothing reaches

Measured with `dev-tools/roamsim.sh`, which runs the plugin's own far planner over the
shipped data: 300 golems placed at dungeon entrances around the world plus 50 at the
plinth, for an hour of game time each.

| # | Symptom | Status |
|---|---|---|
| E1 | Upper floors and the cathedral basement are nowhere a golem will go | fixed |
| E2 | The sea off the cathedral is walkable ground to golems | fixed |
| E3 | One ladder into a dungeon takes a far golem about an hour | fixed |
| E4 | Far golems take a transport and immediately the one back | fixed |
| E5 | Far golems at a dungeon entrance never get past the first floor | fixed |
| E6 | Stepping stones at 3149–3154, 3363 trap golems between the middle stones | fixed (avoided) |

**E1.** The shipped land fill only reaches ground connected by the tables it was built
from, and no table has Wyrmscraig's ladders or stairs. Collision was there; the land bit
was not. Any floor a transport starts or ends on is now land at runtime
(`WorldMesh.admitTransportEnds`), and the ladders and staircases ship in
`dev-tools/transports/wyrmscraig_recorded.tsv`. Partly harvested regions no longer
save unseen tiles as walls (`IslandMemory` seen mask).

**E2.** The client does not block open sea, so harvests — and the shipped island map, itself
a harvest — recorded it as walkable. The mesh's ocean bit now overrules every source.

**E3–E5.** The landing was the last waypoint of a walking route, so a jump of six thousand
tiles was walked; far golems never put transports on cooldown; transports were found by
random probes that almost never hit a dungeon's few; and straight lines do not exist
underground. Before and after: 1.8 → 88 transports per golem per hour, 27% → 2% straight
back, 37% → 0% of ticks with no plan, deepest chain 4 → 32.

**E6.** Shortest Path lists every hop onto and between those stones but neither hop off the
end stones onto a bank. Landings are now followed along transport chains to real ground
(`TransportNetwork.leadsToGround`), so golems do not start that crossing. The data gap is
upstream.

---

## F. Sailing

Golems had never once sailed. Measured with `dev-tools/probe/com/golemsdontdie/DockProbe.java`
and `dev-tools/roamsim.sh` (which now sails).

| # | Symptom | Status |
|---|---|---|
| F1 | No golem ever boards a boat | fixed |
| F2 | Golems would sail on nothing — no boat model found | fixed, unverified in game |
| F3 | Half of all crossings fail, each stalling the client for a quarter second | fixed |
| F4 | 22 of 61 docks have no land a golem can board from | fixed |
| F5 | A golem sailing in view is pulled back onto the beach | fixed |
| F6 | Far golems almost never reach a dock | fixed |
| F7 | Leaving the cave dock slides the golem across the map | fixed |

**F1.** `docks.gz` holds six shorts a dock (buoy x, y, plane; gangplank x, y, plane) and
`SailingDocks` read an int, two shorts and a byte, so every dock was somewhere like 0,3038 on
plane 12. The gangplank is now the quayside where there is one.

**F2.** The boat table lists crews and the `SAILING_BOAT_SAIL01_*` NPCs are sails; the hull is
the classic "Boat", NPC 3834, found with `dev-tools/BoatRecon.java`. Its height is now the ground
under it, like the golem's. Both need looking at in game.

**F3.** Every crossing between moorings is computed offline into `sea-routes.gz` by
`BuildSeaRoutes` (1,770 pairs, none unreachable, 18 KB). The live search is only a fallback.

**F4–F7.** Dock floors are admitted as land; the in-view rescue skips golems at sea; far golems
walk to a dock in reach one plan in ten and sail from it more often than one passing by;
passages hold the golem at the quayside and then put it ashore. A save mid-voyage records the
landfall. Simulated hour: 15 voyages among 50 Wyrmscraig golems, 12 of them over 200 tiles from home.
