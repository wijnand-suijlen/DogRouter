# DogRouter status — working memo

Internal hand-off document so a fresh Claude session can pick up where we
left off. English only (internal design doc, not user-facing).

Last touched: 2026-06-20. 54 commits on `main`.

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

1. ~~**Greedy insertion, no backtracking.**~~ **LARGELY ADDRESSED.** The
   multi-start now seeds an **LNS** pass (ruin-and-recreate, default 200
   iterations — see the ACTIVE roadmap above) that reconsiders the structure
   and cut median day length materially. Still greedy *within* a repair, and
   no SA / group moves / 2-opt yet (#4/#3/#5), but the "never reconsiders"
   plateau is broken.
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

## ►► ACTIVE: solver algorithm — LNS roadmap ◄◄

Replacing the weak multi-start (weakness #1) with local search. Objective
stays day length (`score()`) for now — the over-walk term (#2) is still
deferred. **Biggest makespan headroom is structural**: walk-sharing via
grouping, and bike-mount overhead (~70–100 min/day at 10 min each), both
currently set by the luck of the greedy insertion order. So structure-
reconsidering search wins most. Five proposals, ranked by expected
makespan gain:

1. ~~**LNS — ruin-and-recreate.**~~ **DONE & ADOPTED (default).** Each
   iteration ruins K placed options — alternating **random** and **worst**
   removal (drop those whose presence costs the day most, randomised
   `rand^bias` pick) — and greedily re-inserts them (plus any unplaced, so a
   freed slot can rescue a conflict) with `tryInsertOption`, hill-climbing on
   `score()`. One `Random(seed)` drives it all, so plans stay deterministic /
   cacheable. `lnsIterations` defaults to `DEFAULT_LNS_ITERATIONS = 200`
   (tuned: day length plateaus by ~200, identical at 500/1k/3k; ~2 s/plan,
   ~5 s heaviest day, haversine). Measured gain vs the old multi-start:
   median day length **Mon 7h16→6h30, Thu 7h25→6h35, Wed 7h49→7h28, Fri
   7h21→7h15**, Tue ~unchanged, and the per-seed spread collapses (every
   seed now reaches essentially the same near-optimum). `docs/solver-
   baseline.md` is regenerated with LNS on. Key pieces in `DayPlanner`:
   `Solution`, `buildOnce`, `remove` (surgical span extraction, tested by
   `RemoveOperatorTest`), `ruinAndRecreate`, `worstOptions`, `repair`.
2. **ALNS — related (Shaw) removal + regret-k repair + adaptive operator
   weights.** The high-ceiling upgrade of #1: regret repair places tight
   windows / hard dogs better; related removal re-clusters groups. Most
   ultimate gain, more code. Build after #1.
3. **Group-restructuring local search.** A targeted neighborhood on the
   walk-grouping itself: move a dog between concurrent walks, merge two
   groups into one shared walk, split an oversized one. Hits walk-sharing +
   mounts directly; standalone or as an extra repair move inside #1/#2.
4. **Simulated-annealing acceptance.** Replace keep-best with cooling-
   probability acceptance over #1/#3 moves to escape the plateau. A small
   multiplier layer, not a neighborhood.
5. **Or-opt / 2-opt sequence + mode LS.** Trim travel and merge adjacent
   rides. Lowest structural headroom here, and the shared-walk / precedence
   semantics make segment moves fiddly. Optional polish.

**Build order: ~~#1~~ → #4 → #3 → #2**, #5 optional. Each step measured
against `docs/solver-baseline.md` (regenerate → diff = gain / regression).
**#1 done; #4 (SA acceptance) is next** — LNS currently hill-climbs, so SA
acceptance is the cheapest way to push past the plateau further, then group
moves (#3), then full ALNS (#2: regret-k repair + Shaw removal + adaptive
weights, of which worst removal is a first taste).

Caveat: LNS optimises pure day length, so it trades more over-walk for a
shorter day until the #2 over-walk term lands — consistent with "day length
primary", but it makes that term the natural follow-up. (Seen in the new
baseline: over-walk is mixed / slightly up.)

## Other directions (later)

- **Objective redesign** (#2): light, tunable over-walk term alongside day
  length; weights tuned against the baseline. Deferred by the user.
- **Capacity bike-leg-only** (#4 in the weakness list): on a foot phase dogs
  are on leashes, not in the box — adjacent to the finished on-foot model.
- Reconsider single-tour vs **multi-trip**.

## Domain facts on grouping (from the walker)

- **The real walk-group cap is 3, not 4.** A group of 4 is an exception that
  "should not really happen" — it only appears when a dog cannot otherwise be
  placed, and in practice it means the walker is over capacity that day and
  would *drop a client* to get back to ≤3. So a planned 4-group is a signal
  of too many dogs, not an acceptable outcome (worth surfacing as a warning).
  The model already encodes this softly — hard cap 4, huge
  `OVERSIZE_PENALTY_SECONDS` per dog over `preferredGroupSize` (3), so 4 is
  used only when unavoidable — which matches the walker's intent.
- **Incompatibility is often total:** a dog marked incompatible with one dog
  is usually incompatible with *all*, so several dogs must always be walked
  **alone**. Useful for bounds and for any future deliberate-grouping work.

## Shelved: cheap makespan lower bound (parked — doubtful value)

Idea (parked at the walker's request): a fast lower bound on day length, to
measure the optimality gap and to early-stop LNS. `LB = max(walk-floor,
travel-floor) + fixed costs`, each a valid floor (≤ optimum), O(P²) from the
matrix:
- **walk-floor** = Σ required ÷ group cap. Use **3** here (the real cap; see
  above), and count the always-solo dogs (total incompatibility) separately
  at their **full** duration — only the groupable dogs are divided.
- **travel-floor** = MST({home + addresses}) ÷ cyclingSpeed.
- **fixed** = stop buffers (2 per occurrence) + ≥1 bike overhead.

Parked because it is likely **too loose to be useful**: the day is
walk-dominated and the plans cycle back and forth a lot, so the travel-floor
(and even a window-aware walk-floor) sits far below the real optimum. The
convergence evidence (plateau by ~200 iters, identical across seeds) is the
stronger near-optimality signal; a practical LNS early-stop would be
"K iterations without improvement", not the LB.

---

# Solver & model — current design (authoritative reference)

`domain/dayplan/` — all JVM-pure:

- **`DayPlanner.kt`**: **multi-start greedy seeding an LNS pass.**
  `plan(date, options: List<WalkOption>, seed)` builds a `DistanceMatrix`
  once, runs `restarts` (default 60) greedy builds — restart 0 deadline-
  sorted, the rest `Random(seed)` shuffles — to get an incumbent `Solution`,
  then runs `lnsIterations` (default 200) of **ruin-and-recreate**
  (`ruinAndRecreate`: random / `worstOptions` removal → `remove` → `repair`),
  hill-climbing on `isBetterThan` (fewest conflicts, then lowest `score()` =
  elapsed + group-oversize penalty). One `Random(seed)` drives multi-start,
  ruin and repair, so plans are deterministic / cacheable. A **`Solution`**
  (events + placed/unplaced options) is the working unit; `toDayRoute` makes
  the public result. `buildOnce` / `repair` insert each option via
  `tryInsertOption` → `tryInsert`: **Mode A** new pickup-walk-dropoff
  triplet; **Mode B** join an existing walk and extend its duration; **Mode
  C** ride along several existing walks without extending (splits one
  duration). `remove` extracts a placed option's span (drop pickup/dropoff,
  take the dog out of that span's walks) and retimes — removal is *not*
  monotonic on makespan, which is fine (accept only after repair).
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
cap, same-address no-overhead, nearby-dogs-walked-on-foot — **pinned to
`lnsIterations = 0`** so they test construction + constraints, not LNS),
`RemoveOperatorTest.kt` (the LNS remove operator: span dropped, plan stays
feasible), `BackupModelsTest.kt` (export/import round-trip), and the
off-device `SolverHarness.kt` (the laptop harness above — not an assertion
test; `runSolverOnRealData` prints, `baselineAcrossSeeds` regenerates the
baseline; both honour `-Dsolver.lns=N`).

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

## Feature wishlist (from the walker)

Walker-requested day-shaping features beyond the dogs' own schedules.

**Done:**
- **Lunch break** — Planning screen (window, duration, break locations);
  the planner fits one dog-free break into an empty mid-day gap inside the
  window, **prefers a lunch at home** when the free gap is long (default
  ≥120 min, staying home until just in time), red panel when none fits;
  per-date toggle on Today.
- **Temporarily pause a dog** — `Dog.active`; paused dogs are kept but
  skipped by the planner; toggle on the Dogs list.

**Pending, in priority order:**
1. **Incidental appointments.** One-off, per-date, at a fixed exact time
   window (doctor, client intro), possibly at a location. The planner works
   the day around it as a hard dog-free block. Reuses the break / non-dog
   "Break"-style machinery and the Planning screen. *Cheapest — do next.*
2. **No-cargo-bike day + cancellation advice.** A per-day vehicle mode
   (cargo bike / backpack + normal bike / on foot) with reduced capacity and
   speed; the planner makes an adapted plan AND advises **which dogs are best
   to cancel** (needs a per-dog cancellation cost / priority, not just the
   conflict count). Introduces the capacity/advice model.
3. **Sleepover (boarding) dog.** Board a client's dog at the walker's home
   for one or more days. Two modes: (a) fit dog + good weather → it **comes
   along all day** (effectively a dog living at the home address with a daily
   walk requirement and no fixed window); (b) older dog / extreme heat or
   cold → **keep it home and return a few times** (often coinciding with the
   lunch / home visit) for a few short walks starting and ending at home.
   Only accept a sleepover **when the day is not too busy** (an "is there
   room?" check). Most complex; reuses #2/#3's capacity & advice and the
   home-lunch home-visits — build last, likely in stages (Mode A then B).

Priority rationale: ascending complexity and dependencies — #2 reuses what
the break already built; #3's "is there room / which to drop" advice and
the home-lunch home-visits both feed the sleepover dog (#3, then #5).

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
