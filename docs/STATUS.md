# DogRouter status — working memo

Internal hand-off document so a fresh Claude session can pick up where we
left off. English only (internal design doc, not user-facing).

Last touched: 2026-06-20. 41 commits on `main`.

---

# ►► CURRENT FOCUS: optimise the solver and the model ◄◄

The **input model is now nearly complete** — dogs, owners, addresses with
coordinates, stop quirks, transport state, incompatibilities, and rich
schedule rules (weekday sets; independent start-from / start-by / home-by
bounds; per-rule duration; an `isAlternative` "morning OR afternoon"
flag). Import/export of all this works. **The weak link now is plan
QUALITY**: the solver finds *a* feasible plan but rarely a *good* one. This
is the next big push, and it is what STATUS should be organised around.

## The enabler: run the solver on the laptop, in the terminal

Everything under `domain/dayplan/` is **pure JVM Kotlin** — it only touches
Room entity *data classes* (no Android runtime, no Compose). So the solver
can run off-device, on the user's fast multi-core laptop, where we can
iterate on plan quality in seconds instead of rebuilding the APK and
eyeballing the phone. **Build this first.** A standalone runner that:

- Loads **`dogrouter-backup.json`** (committed in the repo root — the
  user's real 10-dog data; owner names/addresses, treat as private, do not
  push anywhere). It is the same JSON the app exports
  (`data/backup/BackupModels.kt`), so reuse `BackupFile` / the DTO mappers.
- Builds a `DistanceMatrix` from a **pluggable distance source**. Start
  with straight-line haversine (instant, good enough to iterate on solver
  *logic* and structure). Later feed *real* BRouter distances — either
  export the matrix once from the app, or try running `brouter-core` on
  the plain JVM against the downloaded segment dir (it is a Java lib; may
  work headless). Keep the source behind an interface so quality work is
  not blocked on routing accuracy.
- Runs `DayPlanner.plan(date, options, seed)` for a chosen weekday +
  `AppSettings`, and prints the plan timeline **plus quality metrics**
  (below). Parallelise restarts/seeds across cores (the laptop has them).

Shape options: a Gradle JVM/`application` module (e.g. `:solver-cli`) with
a `main()`, runnable via `./gradlew :solver-cli:run --args="..."`; or a
runnable JUnit "scenario" that prints (cheapest to start, already how
`DayPlannerScenarioTest` works). Use the Android Studio JBR for
`JAVA_HOME` (see Build conventions).

## Quality metrics the harness must print (and we should optimise)

- **Conflicts** (unplaced walks) — must be 0.
- **Total day length** (HomeStart→HomeEnd elapsed) — the current sole cost.
- **Per-dog walked vs required**, and **total over-walk** (minutes walked
  beyond what each rule asked).
- **On-foot vs cycling split**, and **number of bike mounts** (overhead
  paid).
- **Idle/waiting time** (waiting for `earliestStart` windows).
- **Wall-clock solve time** (so we can spend more restarts where it pays).

## Where plan quality is lost today (the weaknesses to attack)

1. **Greedy insertion, no backtracking.** `DayPlanner.buildOnce` inserts
   each `WalkOption` once into its cheapest feasible slot and never
   reconsiders. The ONLY escape from a bad structure is the randomised
   **multi-start** (`restarts`, default 60): re-run the whole greedy from
   shuffled orders, keep the best by `isBetterThan`. That is a weak
   metaheuristic — quality plateaus. **No local search** (remove-and-
   reinsert, or-opt/2-opt, group swaps), no simulated annealing, no LNS.
   This is the single biggest lever.
2. **Cost function is only day length.** `isBetterThan` → `score()` =
   elapsed seconds + a big per-dog-over-`preferredGroupSize` penalty.
   It does NOT penalise over-walking, idle/waiting, or the number of bike
   trips. "Shortest day" is therefore reachable by over-walking
   `allowLongerWalk` dogs or other shapes the user would not choose.
   Re-design the objective with explicit, harness-tunable terms.
3. **On-foot model is half-done** (`retimeAndCost`). Foot legs credit a
   dog's duration but do NOT shorten the in-place dwell `Walk.durationSeconds`
   (still set to the full required at insertion), so dogs are over-walked
   and the foot time is not truly double-duty — only the bike-overhead
   saving is realised. The auto-foot choice is **greedy per leg** with no
   lookahead at the resulting return-to-bike cost.
4. **Capacity counts on-foot dogs as box weight.** `CapacityConstraint`
   sums every aboard dog continuously; on a foot phase the dogs are on
   leashes, not in the cargo box, so capacity should be **bike-leg-only**.
   Over-restrictive for heavy on-foot groups (group-size cap already
   bounds the on-foot group).
5. **Walk grouping is emergent, not deliberate.** Groups form by Mode B/C
   joins; the planner never clusters dogs by proximity/time first, so good
   clusters depend on the random insertion order and luck.
6. **Single continuous tour.** One HomeStart→…→HomeEnd tour; no notion of
   multiple home-returning trips. Interleaving pickups/dropoffs under
   capacity is the only structure available.
7. **Distance matrix rebuilt per plan; no cross-date cache.**
   `DistanceMatrix.build` calls BRouter once per point-pair on every
   uncached plan. Fine for ≤10 dogs but it is the slow part and is
   on-device only — the reason iterating on the phone is painful.
8. **dayStart/dayEnd hardcoded 08:00–20:00** in the `DayPlanner` ctor.

## Candidate directions (decide with the user next session)

- **Local-search improvement pass** on top of multi-start: remove-and-
  reinsert each option against the finished plan; or-opt/2-opt the tour;
  swap dogs between groups; iterate to a local optimum. Cheapest big win.
- **Metaheuristic**: simulated annealing or **LNS** (destroy a few stops,
  greedily repair) — natural fit; the harness makes tuning feasible.
- **Finish the on-foot model**: make dwell `Walk.durationSeconds` adapt so
  foot legs shorten it (true double-duty); make capacity bike-leg-only.
- **Objective redesign** with weighted terms (over-walk, idle, bike-mount
  count), weights tuned against real days in the harness.
- Reconsider single-tour vs **multi-trip**.

---

# Solver & model — current design (authoritative reference)

`domain/dayplan/` — all JVM-pure:

- **`DayPlanner.kt`** (~460 lines): randomised **multi-start greedy**.
  `plan(date, options: List<WalkOption>, seed)` builds a `DistanceMatrix`
  once, runs `restarts` (default 60) builds — restart 0 is deadline-sorted
  order, the rest are `Random(seed)` shuffles — and keeps the best by
  `isBetterThan` (fewest conflicts, then lowest `score()` = elapsed +
  group-oversize penalty). `buildOnce` inserts each option via
  `tryInsertOption` → `tryInsert`, which tries: **Mode A** new
  pickup-walk-dropoff triplet; **Mode B** join an existing walk and extend
  its duration; **Mode C** ride along several existing walks without
  extending (splits one duration across sessions). `tryInsertOption` tries
  each alternative of a `WalkOption` and keeps the cheapest (exclusive
  choice → exactly one scheduled).
- **`retimeAndCost`** (inside DayPlanner): walks the event list forward
  assigning `timeSeconds`, choosing per leg **bike** (metres/cyclingSpeed +
  fixed `bikeOverheadSeconds`) vs **on foot** (metres/walkingSpeed),
  whichever is faster. Tracks the walker AND the parked bike: a foot leg
  leaves the bike put; a bike leg first walks back to the parked bike; the
  final HomeEnd leg is forced to bike so the bike ends home. Sets each
  event's `arrivedByFoot` + `incomingTravelSeconds`. Waits at a pickup for
  `earliestStart`. **Safety invariant**: `bikeOverheadSeconds == 0` ⇒
  walking is never faster ⇒ every leg bikes ⇒ identical to the old
  bike-only behaviour (this keeps all legacy tests valid).
- **`DistanceMatrix.kt`**: stores **metres** per point-pair (symmetric);
  built from `RoutingProvider.route().distanceMeters`. The planner turns
  metres into bike or foot time. FALLBACK 30 km when routing fails.
- **`RouteEvent.kt`**: sealed HomeStart / Pickup(dog, rule) / Walk(dogs,
  durationSeconds) / Dropoff(dog) / HomeEnd. Each carries `timeSeconds`,
  `location`, and (filled by the retimer) `arrivedByFoot` +
  `incomingTravelSeconds`.
- **`WalkOption.kt`**: one thing to schedule — one required walk, or an
  exclusive choice of alternatives (same dog, pick one). Built in
  `DayPlanService` by grouping a dog's rules (alternatives → one option).
- **`WalkSpans.kt`**: pairs pickups↔dropoffs per occurrence (FIFO per dog),
  so a dog walked twice in a day is handled.
- **`constraints/`** (6, `PlanningConstraint`): `Capacity`,
  `TimeWindow` (earliest pickup / latest **walk-start** / latest dropoff,
  each optional), `WalkDuration` (sum of dwell walks + on-foot legs in the
  span vs required; max enforced for `allowLongerWalk=false`),
  `Incompatibility`, `NoDogLeftBehind` (every aboard dog must be in each
  walk), `GroupSize` (hard `maxGroupSize`=4; soft preference for
  `preferredGroupSize`=3 lives in `score()`).
- **`DayPlanService.kt`**: shared pipeline (Today + Follow plan). Builds
  `WalkOption`s from the weekday's rules, constructs `DayPlanner` from
  `AppSettings`, **caches** plans by `(inputs, seed)` in an LRU; `refresh`
  bumps a date's seed to ask for an alternative plan.

Settings feeding the solver (`AppSettings`): `bikeCapacityKg`,
`stopBufferMinutes`, `cyclingSpeedKmh`, **`walkingSpeedKmh`** (3),
**`bikeOverheadMinutes`** (3), home coordinates.

Tests: `app/src/test/.../DayPlannerScenarioTest.kt` (fake straight-line
router; covers the 19-June report, two-rule dogs, splitting, determinism,
exclusive choice, latest-start-bounds-the-walk, no-one-left-behind, group
cap, same-address no-overhead, nearby-dogs-walked-on-foot) and
`BackupModelsTest.kt` (export/import round-trip).

---

# What works today (app surface — reference)

- **Dogs tab**: full CRUD. Per dog: name, breed, weight, photo URI (no
  Photo Picker), owner + phone, BAN-autocomplete/map-picker address, stop
  notes + time adjustment, transport state, `allowLongerWalk` +
  incompatibility chips, and schedule rules (weekdays; start-from /
  start-by / home-by; duration; "either/or" flag).
- **Settings**: home picker, cycling speed, **walking speed**, bike
  capacity, stop buffer, **bike mount/dismount overhead**, BRouter map
  download + self-test, and **data export/import** (SAF; import replaces
  all, behind a confirm dialog).
- **Today**: PDPTW timeline with date picker, summary (on-the-clock /
  cycling / walking), conflict panel, **refresh** (new seed → alternative
  plan), "Start trip" FAB, per-leg "on foot"/"cycling" label + tap-to-open
  full-screen route map.
- **Follow plan**: full-screen on-the-bike execution; current stop
  dominant, next two below, Done/Back/Finish, progress, inline tiled leg
  map.
- **Leg maps**: `RoutingProvider.routeGeometry()` polylines, cached
  (`LegGeometryCache`); inline tiled map in Follow plan, tap-to-open icon
  in Today (many MapViews caused ANR), full-screen interactive
  `LegMapScreen`.
- **BRouter** embedded on-device (`org.btools:brouter-core`), `bakfiets.brf`
  profile. Distance from BRouter, time from the user's cycling speed (we do
  NOT use BRouter's kinematic time).
- **Schema** at v6 (migrations 1→2 … 5→6; 4→5 adds `isAlternative`, 5→6
  adds `latestStart`).

## Architecture snapshot

```
data/
  entity/  Dog, DogScheduleRule (incl. latestStart, isAlternative),
           DogIncompatibility, TransportState
  db/      AppDatabase (v6), DogDao, DogScheduleDao,
           DogIncompatibilityDao, Migrations, Converters
  prefs/   AppSettings (incl. walkingSpeedKmh, bikeOverheadMinutes),
           SettingsRepository (DataStore)
  backup/  BackupModels (JSON DTOs), BackupRepository (export/import)
  remote/  AddressSuggestion, BanApi
  routing/ RoutingDataPaths, RoutingDataInstaller, BRouterRoutingProvider
domain/
  planner/  PlannedWalk
  dayplan/  RouteEvent, DayRoute, PlanConflict, PlanningConstraint,
            WalkOption, WalkSpans, DistanceMatrix, DayPlanner, DayPlanService
            constraints/  Capacity, TimeWindow, WalkDuration,
                          Incompatibility, NoDogLeftBehind, GroupSize
  routing/  RouteEstimate, RoutingProvider, GeoPoint, LegGeometryCache
ui/
  common/  AddressAutocompleteField, AddressMapPreview, CyclingLegMap,
           RouteLegMap + LegMapScreen
  dogs/ today/ followplan/ history(stub)/ settings/ addresspicker/
  navigation/ (4 tabs: Today, Dogs, History, Settings)  theme/
```

## Smaller known issues (not the focus, but real)

- **Waiting time shows as cycling in the timeline** — a pickup waiting for
  its window inflates the displayed leg. Cosmetic.
- **`bakfiets.brf` is very conservative** (totalMass=180, bikerPower=80,
  S_C_x=0.45) → pessimistic route choice on hills. Since we override
  BRouter's time anyway, a lighter profile might pick faster routes.
- **Plan cache key is the whole `Inputs`** — editing an irrelevant dog
  field (e.g. photo) invalidates it. Harmless, occasional needless
  recompute.
- **`Walk.location` stale-on-join** (old issue) is now effectively fixed:
  the retimer sets a Walk's location to the walker's current position.

## App-surface follow-ups (deferred while solver is the focus)

- Follow plan: resumable-across-exit (persist step), dog photo (needs an
  image loader → user OK), surface conflicts.
- Manual override of the plan (drag-reorder, mark a dog not-doing-today).
- History tab (stub; in-scope per SCOPE for invoicing).
- Photo Picker (user deferred).

## Project conventions

- **Workspace boundary**: stay inside `/Users/wijnand/Documents/src/DogRouter/`
  (enforced via `.claude/settings.local.json`).
- **Language**: Dutch with the user; English in source/comments/commits/docs.
- **Commit messages**: avoid `'` in the body (breaks the heredoc trick).
- **User**: Wijnand Suijlen, Dutch-speaking, Meudon (92), Île-de-France.
  Two phones (Android 15/16). **Laptop is fast + multi-core** — use it for
  the solver harness.
- **Build**: GitHub PAT (`read:packages`) for the brouter artifact (see
  README). No system JDK — point `JAVA_HOME` at the Android Studio JBR:
  ```
  JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    ./gradlew :app:testDebugUnitTest      # solver tests, fast
  ```
  Gradle wrapper (8.13) is committed. Phone serial for installs: `dd979738`
  (FP4, Android 15); `ANDROID_SERIAL=$serial ./gradlew :app:installDebug`.
- **Schema migrations**: entity change ⇒ migration + version bump + commit
  the `app/schemas/*.json`.
- **Routing math**: BRouter distance, user `cyclingSpeedKmh` for time; we
  do NOT use BRouter's kinematic time.

## Documents to read on session start

1. `CLAUDE.md` — global project rules.
2. **This file** — start here; the focus is the solver/model.
3. `SCOPE.md` — what v1 does / does not do.
4. `docs/SCREENS.md` — screen inventory and rationale.
5. `docs/ROUTING_ENGINES.md` — why BRouter.
6. `dogrouter-backup.json` (repo root) — the real test data for the harness.
