# DogRouter status — working memo

Internal hand-off document so a fresh Claude session can pick up where we
left off. English only (internal design doc, not user-facing).

Last touched: 2026-06-20. 47 commits on `main`.

---

# ►► CURRENT FOCUS: optimise the solver and the model ◄◄

The **input model is now nearly complete** — dogs, owners, addresses with
coordinates, stop quirks, transport state, incompatibilities, and rich
schedule rules (weekday sets; independent start-from / start-by / home-by
bounds; per-rule duration; an `isAlternative` "morning OR afternoon"
flag). Import/export of all this works. **The weak link now is plan
QUALITY**: the solver finds *a* feasible plan but rarely a *good* one. This
is the next big push, and it is what STATUS should be organised around.

## The enabler: run the solver on the laptop (BUILT)

Everything under `domain/dayplan/` is **pure JVM Kotlin** — it only touches
Room entity *data classes* (no Android runtime, no Compose). So the solver
runs off-device, on the user's fast multi-core laptop, iterating on plan
quality in seconds instead of rebuilding the APK. The harness is a JUnit
"scenario" (cheapest path, like `DayPlannerScenarioTest`), in
`app/src/test/.../SolverHarness.kt`:

- `runSolverOnRealData` loads **`dogrouter-backup.json`** (repo root — the
  user's real 10-dog data; owner names/addresses, **private, now
  gitignored, do not push**) via `BackupFile` / the DTO mappers, runs
  `DayPlanner.plan` for each weekday, and prints the full plan timeline
  **plus the quality metrics below**. Run: `./run_solver.sh` (wraps the
  gradle `--tests "*SolverHarness"` invocation with `-PsolverOutput`; full
  report also written to `app/build/solver-report.txt`, gitignored).
  Knobs: `-Dsolver.day=MONDAY`, `-Dsolver.restarts=N`, `-Dsolver.seed=N`.
- `baselineAcrossSeeds` (gated by `-Dsolver.baseline`, run via
  `./run_baseline.sh`) runs every weekday across 10 seeds and writes
  **`docs/solver-baseline.md`** — min/median/mean/max per metric.
  Deterministic (fixed seeds + haversine) so it diffs cleanly: regenerate
  after a change and `git diff` shows the quality delta / any regression.
- Distances come from a **pluggable `RoutingProvider`**: currently
  straight-line haversine (instant, good for solver *logic*). Still TODO:
  feed *real* BRouter distances (export the matrix from the app once, or
  run `brouter-core` headless against the segment dir).

Use the Android Studio JBR for `JAVA_HOME` (see Build conventions).

## Quality metrics the harness prints (and we optimise against the baseline)

- **Conflicts** (unplaced walks) — must be 0.
- **Total day length** (HomeStart→HomeEnd elapsed) — **the current sole
  cost** (`score()`); still the user's primary criterion.
- **Per-dog walked vs required**, and **total over-walk** (minutes walked
  beyond what each rule asked). High over-walk flags a likely-suboptimal
  plan even though it is not (yet) in the objective.
- **On-foot vs cycling split**, and **number of bike mounts** (overhead
  paid).
- **Idle/waiting time** (waiting for `earliestStart` windows).
- **Walk-backs to bike** (how many, and how many carried a dog).
- **Wall-clock solve time** (stdout only; non-deterministic, kept out of
  the tracked baseline).

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
   **This is now the dominant remaining over-walk source**: with the
   on-foot model finished (#3), foot legs are "free" walk time, so the
   day-length-only objective will over-use them (foot overshoot) and
   group a short-requirement dog into a longer dog's walk without penalty.
   Re-design the objective with explicit, harness-tunable terms. **The
   user has deferred this; day length stays primary for now.** A light,
   tunable over-walk term alongside day length is the agreed next step.
3. ~~**On-foot model is half-done.**~~ **DONE (2026-06-20).** `retimeAndCost`
   is now three phases — **legs** (foot vs bike, position-only, dwell-
   independent), **dwell** (`effectiveDwells`), **times**. The in-place
   dwell `Walk.durationSeconds` now shrinks by the on-foot time a dog
   accrues while aboard, so foot legs are **true double-duty** (a dog
   walked enough on foot can get a 0-min dwell). A dog's deficit
   (`required − footCredit`) is shared across a split span's walks in
   proportion to their inserted lengths (never under-walks; a shared walk
   stays as long as its most-demanding member needs). The walk back to a
   parked bike is credited as walk time **during the search** too
   (`returnToBikeSeconds` on the leg; `RouteEvent.onFootSeconds`), and the
   foot-credit definition was fixed to exclude the leg that *fetches* a dog
   (it is not aboard yet) — shared by `footCreditSeconds`, the
   `WalkDuration` constraint and the harness alike. Result on the seed
   sweep: day length down every weekday, dwell ~halved, conflicts still 0,
   every placed dog walks ≥ its required (exact for single walks). The
   over-walk that remains is structural → #2 / #5, not the dwell logic.
   Still open: the foot-vs-bike choice itself is **greedy per leg** with no
   lookahead at the return-to-bike cost.
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

- **Objective redesign** with weighted terms (over-walk, idle, bike-mount
  count) alongside day length, weights tuned against the baseline in the
  harness. **Agreed next step, currently deferred by the user** (#2). Keep
  day length primary; add a light over-walk term to stop foot overshoot and
  mismatched-duration grouping.
- **Local-search improvement pass** on top of multi-start: remove-and-
  reinsert each option against the finished plan; or-opt/2-opt the tour;
  swap dogs between groups; iterate to a local optimum (#1, #5).
- **Metaheuristic**: simulated annealing or **LNS** (destroy a few stops,
  greedily repair) — natural fit; the harness makes tuning feasible.
- **Capacity bike-leg-only** (#4): on a foot phase dogs are on leashes, not
  in the box — adjacent to the now-finished on-foot model.
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
- **`retimeAndCost`** (inside DayPlanner): three phases. **(1) legs** —
  per leg choose **bike** (metres/cyclingSpeed + fixed `bikeOverheadSeconds`)
  vs **on foot** (metres/walkingSpeed), whichever is faster; position-only,
  so it does not depend on dwell durations. Tracks the walker AND the parked
  bike: a foot leg leaves the bike put; a bike leg first walks back to it
  (recorded as `returnToBikeSeconds`); the final HomeEnd leg is forced to
  bike so the bike ends home. **(2) dwell** (`effectiveDwells`) — set each
  walk's in-place duration to what its dogs still need after their on-foot
  credit (see #3 above). **(3) times** — assign `timeSeconds`, wait at a
  pickup for `earliestStart`, bail past `dayEndSeconds`. Sets each event's
  `arrivedByFoot` / `incomingTravelSeconds` / `returnToBikeSeconds`.
  **Safety invariant**: `bikeOverheadSeconds == 0` ⇒ walking never faster ⇒
  every leg bikes ⇒ no walk-backs ⇒ full dwells ⇒ identical to the old
  bike-only behaviour (keeps all legacy tests valid). After the multi-start
  picks a winner, **`withBikeFetches`** (presentation pass) splits each ride
  that started away from the bike into a `FetchBike` foot leg + the ride, so
  the plan and the app's per-leg maps show the walk to the bike. The solver
  search never sees `FetchBike` events (keeps insertion index math intact).
- **`DistanceMatrix.kt`**: stores **metres** per point-pair (symmetric);
  built from `RoutingProvider.route().distanceMeters`. The planner turns
  metres into bike or foot time. FALLBACK 30 km when routing fails.
- **`RouteEvent.kt`**: sealed HomeStart / Pickup(dog, rule) / Walk(dogs,
  durationSeconds) / Dropoff(dog) / HomeEnd / **FetchBike** (walk back to the
  parked bike; display-only, added by `withBikeFetches`). Each carries
  `timeSeconds`, `location`, and (filled by the retimer) `arrivedByFoot` +
  `incomingTravelSeconds` + **`returnToBikeSeconds`** (on-foot part of a bike
  leg). **`onFootSeconds`** = the walked part of any leg (whole leg if on
  foot, else the walk-back).
- **`WalkOption.kt`**: one thing to schedule — one required walk, or an
  exclusive choice of alternatives (same dog, pick one). Built in
  `DayPlanService` by grouping a dog's rules (alternatives → one option).
- **`WalkSpans.kt`**: pairs pickups↔dropoffs per occurrence (FIFO per dog),
  so a dog walked twice in a day is handled. **`footCreditSeconds`** =
  on-foot walk time a span's dog accrues while aboard (full foot legs + bike
  legs' walk-back), excluding the leg that fetches it. Shared by the
  retimer's dwell phase, the `WalkDuration` constraint and the harness.
- **`constraints/`** (6, `PlanningConstraint`): `Capacity`,
  `TimeWindow` (earliest pickup / latest **walk-start** / latest dropoff,
  each optional), `WalkDuration` (dwell walks + `footCreditSeconds` in the
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
cap, same-address no-overhead, nearby-dogs-walked-on-foot),
`BackupModelsTest.kt` (export/import round-trip), and the off-device
`SolverHarness.kt` (the laptop harness above — not an assertion test;
`runSolverOnRealData` prints, `baselineAcrossSeeds` regenerates the
baseline).

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
7. `docs/solver-baseline.md` — current quality metrics per weekday; the
   reference to beat. Regenerate with `./run_baseline.sh`.
