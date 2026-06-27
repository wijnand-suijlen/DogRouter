# DogRouter status — working memo

Internal hand-off document so a fresh Claude session can pick up where we
left off. English only (internal design doc, not user-facing).

Last touched: 2026-06-27. 110 commits on `main`.

> **Recently built (2026-06-25→27): the sleepover/boarding ("oppashond")
> feature** — a per-dog day status (Dutch UI labels → `DogStatus` enum: UIT=OFF,
> WANDEL=WALK, OPHAAL=BOARD_ARRIVE, LOGEER=BOARD_STAY, BRENG=BOARD_LEAVE)
> replacing `Dog.active`, an all-day passenger solver, conflict-driven depot
> parking, and the Settings/Dogs/Today UI. It is *largely built* (see wishlist #2
> below and `docs/SLEEPOVER_DESIGN.md`); `docs/CSP_MODEL.md` is in sync.
> Remaining: the per-dog `shortWalksOverride` field, its UI, and the boarding
> soft-cap path all exist — only its hook for plain WALK dogs in the solver is
> still pending; plus the "is there room?" acceptance check (shares wishlist #1's
> capacity/advice model).

---

# ►► CURRENT FOCUS: optimise the solver and the model ◄◄

The **input model is now nearly complete** — dogs, owners, addresses with
coordinates, stop quirks, transport state, incompatibilities, rich schedule
rules (weekday sets; independent start-from / start-by / home-by bounds;
per-rule duration; an `isAlternative` "morning OR afternoon" flag), and now a
per-dog day **status** (boarding/sleepover). Import/export of all this works.
**The weak link now is plan QUALITY**: the solver finds *a* feasible plan but
rarely a *good* one. This is the next big push, and it is what STATUS should be
organised around.

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
  Knobs: `-Dsolver.day=MONDAY`, `-Dsolver.restarts=N`, `-Dsolver.seed=N`,
  `-Dsolver.lns=N`, `-Dsolver.backup=<file>`, `-Dsolver.router=brouter`,
  `-Dsolver.dump`.
- `baselineAcrossSeeds` (gated by `-Dsolver.baseline`, run via
  `./run_baseline.sh`) runs every weekday across 10 seeds and writes
  **`docs/solver-baseline.md`** — min/median/mean/max per metric.
  Deterministic (fixed seeds + haversine) so it diffs cleanly: regenerate
  after a change and `git diff` shows the quality delta / any regression.
- Distances come from a **pluggable `RoutingProvider`**: straight-line
  haversine by default (instant, good for solver *logic*); **real BRouter**
  road distances with `-Dsolver.router=brouter` (`RealBRouterRouting` runs
  `brouter-core` headless over `brouter-data/` — profile + the `E0_N45`
  segment, gitignored). That reproduces on-device plans on the laptop;
  `-Dsolver.dump` prints the pairwise matrix (BRouter vs straight line). The
  baseline still uses haversine for determinism. The harness mirrors the app:
  it schedules only `WALK`-status dogs (OFF dogs skipped; boarding-status dogs
  are passengers, set up separately in the boarding spikes).

Use the Android Studio JBR for `JAVA_HOME` (see Build conventions).

## Quality metrics the harness prints (and we optimise against the baseline)

- **Conflicts** (unplaced walks) — must be 0.
- **Total day length** (HomeStart→HomeEnd elapsed) — the dominant term in
  `score()` and the user's primary criterion, now alongside a
  **`cyclingWeight`×ride-seconds** term (default 1.0).
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
   multi-start now seeds an **LNS** pass (ruin-and-recreate, default 25
   iterations — see the ACTIVE roadmap below) that reconsiders the structure
   and cut median day length materially. Still greedy *within* a repair, and
   no SA / group moves / 2-opt yet (roadmap items #4/#3/#5 below), but the
   "never reconsiders" plateau is broken.
2. **Cost function — day length + cycling + over-walk (redesigned).**
   `isBetterThan` → `score()` = elapsed seconds + **`cyclingWeight` ×
   ride-seconds** + **`overWalkWeight` × over-walk-seconds** + a big
   per-dog-over-`preferredGroupSize` penalty.
   The cycling term (added 2026-06-20, `AppSettings.cyclingWeight`, default
   1.0, tunable on Settings) fixed the makespan-only flatness that wasted
   cycling hidden in idle: median cycling dropped 30–53 min/day on the baseline
   for 0–17 min more day length. The **over-walk term** (added 2026-06-21,
   `AppSettings.overWalkWeight`, default **0.1**, tunable on Settings; light by
   design per the walker — a dog may walk ~30 min longer to save ~5 min of day)
   cut median over-walk substantially (e.g. Mon 2h40→1h26, Wed 3h13→1h48) with
   day-length medians flat or better (Thu 6h47→6h28), conflicts still 0 — only
   a worst-seed tail on Monday got longer (heuristic). Over-walk uses the shared
   `WalkSpan.walkedSeconds` (same accounting as the `WalkDuration` constraint).
   **Still NOT penalised: idle/waiting.**
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
   over-walk that remains is structural → weaknesses #2 / #5, not the dwell logic.
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
7. ~~**Distance matrix rebuilt per plan; no cross-date cache.**~~ **DONE
   (2026-06-22).** `RouteDistanceCache` (`domain/routing/`, a DI `single`)
   memoises BRouter distances per **unordered point-pair**, persisted to a JSON
   file in `filesDir` (lazy load, debounced atomic save). Road distance depends
   only on the endpoints, so the same pair is reused across every weekday and
   across app restarts; one new/moved address routes only its own ~N pairs, not
   a full N² rebuild. Mirrors `LegGeometryCache`: keyed on exact coords, a
   routing failure is NOT cached (straight-line fallback, retried). Invalidated
   when the installed BRouter data fingerprint (segment + profile file
   sizes/mtimes) changes. No eviction (stays well under ~1 MB for years).
   `DistanceMatrix.build(..., routeCache)` uses it; `null` = no cache
   (tests/harness). Wired through `DayPlanner` (ctor `routeCache`) and
   `DayPlanService`.
8. **dayStart/dayEnd hardcoded 08:00–20:00** in the `DayPlanner` ctor.

## ►► ACTIVE: solver algorithm — LNS roadmap ◄◄

Replacing the weak multi-start (weakness #1) with local search. (This section
was written when the objective was pure day length; the cycling and over-walk
terms — weakness #2 — have **since landed** in `score()`, so LNS now optimises
the full objective, not just makespan.) **Biggest makespan headroom is
structural**: walk-sharing via
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
   cacheable. `lnsIterations` defaults to `DEFAULT_LNS_ITERATIONS = 25`
   (re-tuned 2026-06-22 by the restarts × LNS sweep on the true `score()`
   objective; was 200, well past the plateau) and is **user-tunable on
   Settings** (`AppSettings.lnsIterations`, a 0–100 slider) to trade quality
   for speed. (The search is now multi-start LNS — see the design section —
   so total LNS work is restarts × lnsIterations.) Measured gain vs the old multi-start:
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

Historical caveat (now resolved): when LNS optimised pure day length it traded
more over-walk for a shorter day — which is why the over-walk term (weakness #2,
`overWalkWeight`) was added to `score()`. It is now live, so LNS already weighs
over-walk against day length.

## Other directions (later)

- **Objective redesign** (#2): DONE — cycling term (`cyclingWeight`) and a
  light over-walk term (`overWalkWeight`, default 0.1). Remaining objective
  idea: an idle/waiting term (still unpenalised).
- **Capacity bike-leg-only** (#4 in the weakness list): on a foot phase dogs
  are on leashes, not in the box — adjacent to the finished on-foot model.
- Reconsider single-tour vs **multi-trip**.

## Solver-speed work — progress + parked options D-G

Profiling showed `retimeAndCost` dominated (~80% of solve time), driven by how
OFTEN it runs (the O(n³) position enumeration in `tryInsert`) plus heavy
per-call allocation. Brainstormed alternatives, ranked by effort/reward.

**Done so far (pure Kotlin, all baseline byte-identical):**
- **B — structural pre-filter.** Check the time-independent constraints
  (capacity, group size, incompatibility, no-dog-left-behind) on the un-retimed
  candidate before retime; a structurally infeasible insertion skips retime.
  Skips ~96% of retimes (retime 80% → ~21% of solve time).
- **Allocation-free structural check** (`structurallyFeasible`). Replaces those
  four constraint objects' per-call maps with one pass over a reused scratch
  array (capacity as a multiset, the rest a presence-set by dog id). Structural
  check 57% → ~8%. Cross-checked vs the real constraints by `StructuralFilterTest`.
- **Candidate-list reuse.** `tryInsert` builds each candidate into one reused
  scratch list instead of a fresh `toMutableList()` per candidate. **Marginal on
  wall-clock (~1.5%)** — the list *object* was not the bottleneck — but it cuts
  GC pressure, which helps more on-device than the laptop profiler shows.

**Result:** Wednesday/lns=25 went ~2.52s → **~0.66s** (~3.8×). `retimeAndCost`
is now ~38% of solve time (was ~80%).

**STOPPED here (2026-06-22, walker's call — fast enough on-device).** The
remaining cost is split between retime (~38%, 58k calls) and the sheer
**candidate count** (~1.4M per plan, each still built + structurally scanned).
The list-OBJECT allocation was not the lever; the per-candidate WORK × count is.
Genuine next levers if needed: **D** (fewer candidates — quality trade) or **A**
(incremental retime — quality-neutral, ~38% ceiling) or full **C** (avoid
materialising/scanning every candidate via a virtual index-based structural
check). **C (further) and A were NOT done.** Parked options:

- **D — Smaller/smarter neighborhood (candidate lists).** Only try insertion
  positions near a dog's k nearest stops (from the matrix) and inside feasible
  time windows; cap the pickup→dropoff span. O(n³) → ~O(n·k). Big call-count
  cut, but a *light quality trade* (may miss a good non-local insertion). Medium
  effort.
- **E — Parallel candidate evaluation.** The per-candidate evaluations are
  independent; fan out over cores (coroutines on Dispatchers.Default). Phone has
  6-8 cores ⇒ ~4-6× wall-clock. Quality-neutral if the argmin is a stable
  tie-break (by candidate index) to keep determinism. Does not reduce total work
  (more battery/heat). Medium effort.
- **F — Native C++ kernel (NDK/JNI or Kotlin/Native).** Port the hot kernel
  (retime + cost + constraints) to C++ over flat arrays; call per insertion
  batch to amortise JNI. 5-20× on arithmetic, but NDK build, JNI marshalling
  (needs the data-oriented form from C first), two implementations to keep in
  sync, harder debugging, and the off-device JVM harness no longer covers the
  real path. High effort. Best as a follow-on AFTER C.
- **G — Dedicated solver (Google OR-Tools CP-SAT / Routing-VRP).** Model the
  PDPTW for a mature engine; `retimeAndCost` disappears. Highest ceiling
  (possibly better plans AND faster), highest effort/risk: a several-MB native
  dependency per ABI, Android integration, and our unusual on-foot-double-duty /
  walk-back-credit / dwell-sharing semantics do not map cleanly onto the
  standard routing model (likely CP-SAT with custom constraints = large
  modelling effort). Apache-2 license is fine, but CLAUDE.md requires checking
  before adding a vendor/framework.

Honourable mention (combinable, not a separate track): two-tier cost — rank
candidates with a cheap approximation and run exact retime only on the few best;
and/or memoise retime results on a hash of the event structure.

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

- **`DayPlanner.kt`**: **multi-start LNS.**
  `plan(date, options: List<WalkOption>, seed)` builds a `DistanceMatrix`
  once, then runs `restarts` (default 8) restarts — restart 0 deadline-
  sorted, the rest `Random(seed)` shuffles. **Each restart builds one greedy
  seed and then runs its OWN LNS pass** of `lnsIterations` (default 25)
  **ruin-and-recreate** iterations (`ruinAndRecreate`: random / `worstOptions`
  removal → `remove` → `repair`), hill-climbing on `isBetterThan` (fewest
  conflicts, then lowest `score()` = elapsed + `cyclingWeight`×ride-seconds +
  `overWalkWeight`×over-walk + group-oversize penalty); the global best across
  restarts wins. Total LNS work = restarts × lnsIterations. One `Random(seed)`
  drives every shuffle, ruin and repair in sequence, so plans are
  deterministic / cacheable. **Both knobs are user-tunable on Settings**
  (`AppSettings.restarts` 1–10, `lnsIterations` 0–100). Defaults set by the
  restarts × LNS sweep (`SolverHarness.sweepRestartsAndLns`, `-Dsolver.sweep`):
  the objective's big gains come by ~8 restarts / ~25 iterations, small past
  them; pure multi-start (lns=0) gets stuck on oversize groups. A **`Solution`**
  (events + placed/unplaced options) is the working unit; `toDayRoute` makes
  the public result. `buildOnce` / `repair` insert each option via
  `tryInsertOption` → `tryInsert`: **Mode A** new pickup-walk-dropoff
  triplet; **Mode B** join an existing walk and extend its duration; **Mode
  C** ride along several existing walks without extending (splits one
  duration). Each candidate is first checked against the **time-independent
  constraints** (capacity, group size, incompatibility, no-dog-left-behind —
  `ConstraintSet.structural`) on the un-retimed structure, so a structurally
  infeasible insertion skips the expensive `retimeAndCost` entirely (the
  accepted set is identical, since structural ⊆ full — ~96% of retimes are
  skipped, baseline byte-identical). `remove` extracts a placed option's span (drop pickup/dropoff,
  take the dog out of that span's walks) and retimes — removal is *not*
  monotonic on makespan, which is fine (accept only after repair).
- **`retimeAndCost`** (inside DayPlanner): three phases. **(1) legs** —
  per leg choose **bike** (metres/cyclingSpeed + fixed `bikeOverheadSeconds`)
  vs **on foot** (metres/walkingSpeed), whichever is faster; position-only,
  so it does not depend on dwell durations. **Transport state is respected
  here** (`canRideBike`): a leg whose aboard dogs cannot all be carried —
  each needs `inCargoBike == Yes` (rides the box) OR `inBackpack == Yes` with
  at most ONE backpack dog at a time; `No`/`NotTested` for both blocks — is
  forced on foot regardless of which is faster. The on-foot group cap is a
  separate hard constraint (`GroupSizeConstraint`, `|aboard| ≤ maxGroupSize` at
  all times, so it also bounds the on-foot walk-back to a parked bike), not part
  of this mode choice. The soft ≤`preferredGroupSize` (3) preference still lives
  in `score()` on Walk events. Box weight stays bounded by
  `CapacityConstraint` (which still counts a backpack dog's weight against the
  box — slightly conservative, fine while backpack dogs are small/light).
  Tracks the walker AND the parked
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
  metres into bike or foot time. FALLBACK when routing fails: haversine ×
  `ROAD_DETOUR_FACTOR` (1.3), not a flat 30 km (which poisoned plans).
- **`RouteEvent.kt`**: sealed HomeStart / Pickup(dog, rule) / Walk(dogs,
  durationSeconds) / Dropoff(dog) / HomeEnd / **Break** (optional dog-free
  lunch, post-pass) / **Appointment** (fixed dog-free commitment, pre-placed,
  see #2 wishlist) / **FetchBike** (walk back to the parked bike;
  display-only, added by `withBikeFetches`). Each carries
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
- **`constraints/`** (8, `PlanningConstraint`): `Capacity`,
  `TimeWindow` (earliest pickup / latest **walk-start** / latest dropoff,
  each optional), `WalkDuration` (dwell walks + `footCreditSeconds` in the
  span vs required; max enforced for `allowLongerWalk=false`; **skips
  boarding dogs** — keyed on `dog.status.isBoarding`, so they may end aboard
  with no dropoff and PlanVerifier does not false-flag the open span),
  `Incompatibility`, `NoDogLeftBehind` (every aboard dog must be in each
  walk), `GroupSize` (hard `maxGroupSize`=4 on the dogs **aboard at any moment**
  — not just per walk, so picking up / carrying back never exceeds it and the
  walk-back stays bounded; soft preference for `preferredGroupSize`=3 lives in
  `score()`), `Appointment` (reach each fixed appointment on time, no dog aboard
  during it), and **`MaxGap`** (boarding only, added to the set only when the day
  has boarding dogs: ≥1 qualifying walk and no gap > the configured max — the
  CSP's C12). See `docs/CSP_MODEL.md` and the boarding block below.
- **Boarding (sleepover) — `BoardingPassenger.kt`, `MaxGapConstraint.kt`,
  parking in `DayPlanner`.** A boarding dog (`Dog.status.isBoarding`) is not a
  `WalkOption` but an **all-day passenger**: `plan` seeds a `Pickup` at its start
  anchor (kept outermost via `boardingPinned`) and — only for an owner-home end
  anchor (`BOARD_LEAVE`) — a `Dropoff`; otherwise it stays aboard to `HomeEnd`.
  `includeAboardPassengers` folds it into every nested group walk so
  `NoDogLeftBehind` makes it ride along; `MaxGapConstraint` bounds the gap
  between its walks; a soft over-cap term (`boardingCapWeight`) backs
  `shortWalksOverride`. **Conflict-driven parking** (`parkingRepair` →
  `parkAndReinsert` / `tryPlaceSolo`, default ON via `DayPlanService`) rescues a
  regular dog the passenger would block (incompatibility / capacity / group) by
  dropping it at the **nearest depot** (`BoardingPassenger.allowedDepots`) over a
  run of walks and re-inserting the blocked dogs — only ever *adds* a rescue,
  never drops/delays another dog. `cleanBoarding` strips the at-home seeded
  pickup and empty 0-min walks from the presented plan; the "leave just in time"
  pass now defers the whole leading no-window chain (HomeStart + boarding
  pickups) so an Ophaal dog is collected just before the first walk, not at dawn.
  Full design + spikes: `docs/SLEEPOVER_DESIGN.md`; CSP: C1/C4 exceptions + C12
  in `docs/CSP_MODEL.md`.
- **`DayPlanService.kt`**: shared pipeline (Today + Follow plan). Builds
  `WalkOption`s from the weekday's rules (only `WALK`-status dogs) and
  `BoardingPassenger`s from boarding-status dogs, constructs `DayPlanner` from
  `AppSettings`, **caches** plans by `(inputs, seed)` in an LRU; `refresh`
  bumps a date's seed to ask for an alternative plan.

Settings feeding the solver (`AppSettings`): `bikeCapacityKg`,
`stopBufferMinutes`, `cyclingSpeedKmh`, **`walkingSpeedKmh`** (3),
**`bikeOverheadMinutes`** (3), **`cyclingWeight`** (1.0, objective term),
**`overWalkWeight`** (0.1, objective term), **`restarts`** (8, user slider
1–10) and **`lnsIterations`** (25, user slider 0–100), the break
window/duration/locations + `homeLunchMinFreeMinutes`, the **boarding** knobs
**`boardingMaxGapMinutes`** (180), **`boardingMinWalkMinutes`** (15),
**`boardingShortWalkMinutes`** (30) — all three user-editable on Settings — and
**`boardingCapWeight`** (30, internal, no UI), and home coordinates.

Tests: `app/src/test/.../DayPlannerScenarioTest.kt` (fake straight-line
router; covers the 19-June report, two-rule dogs, splitting, determinism,
exclusive choice, latest-start-bounds-the-walk, no-one-left-behind, group
cap, same-address no-overhead, nearby-dogs-walked-on-foot,
dogs-scheduled-around-a-fixed-appointment — **pinned to
`lnsIterations = 0`** so they test construction + constraints, not LNS),
`RemoveOperatorTest.kt` (the LNS remove operator: span dropped, plan stays
feasible), `BackupModelsTest.kt` (export/import round-trip), and the
off-device `SolverHarness.kt` (the laptop harness above — not an assertion
test; `runSolverOnRealData` prints, `baselineAcrossSeeds` regenerates the
baseline; both honour `-Dsolver.lns=N`).

`PlanVerifier.kt` (+ `PlanVerifierTest.kt`) is an executable check of the CSP
(`docs/CSP_MODEL.md`) on a FINAL `DayRoute` — the plan after the presentation
pass, which the in-search constraints never see. It reuses the constraint
objects for C1–C8 and re-implements C9–C11 independently (C11 pragmatic). A
randomised property test asserts the solver never emits an infeasible plan;
`runSolverOnRealData` now also runs it per day and fails on any violation.

---

# What works today (app surface — reference)

- **Dogs tab**: full CRUD. Per dog: name, breed, weight, photo URI (no
  Photo Picker), owner + phone, BAN-autocomplete/map-picker address (+ a
  **"I have the key"** flag for boarding depots), stop notes + time adjustment,
  transport state, `allowLongerWalk` + **`shortWalksOverride`** +
  incompatibility chips, and schedule rules (weekdays; start-from /
  start-by / home-by; duration; "either/or" flag). The list row has a per-dog
  **day-status selector** (Uit/Wandel/Ophaal/Logeer/Breng) replacing the old
  active toggle.
- **Settings**: home picker, cycling speed, **walking speed**, bike
  capacity, stop buffer, **bike mount/dismount overhead**, cycling weight,
  **over-walk weight**, search effort, a **Boarding (sleepover)** section
  (max gap / min walk / short-walk cap), BRouter map download + self-test, and
  **data export/import** (SAF; import replaces all, behind a confirm dialog).
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
- **Schema** at v15 (migrations 1→2 … 14→15; highlights: 4→5 `isAlternative`,
  5→6 `latestStart`, 6→7 `Dog.active`, 7→8 `appointments`, 8→9 `saved_plans`,
  9→10 `owners` + billing tables follow, 14→15 **replaces `Dog.active` with a
  `DogStatus` enum** (table recreate) and adds `keyAvailable` /
  `shortWalksOverride`).
- **Manual plan edit (Fase 0–1b)**: a plan can be pinned/hand-edited per date
  (`SavedPlan`, JSON via `SavedPlanCodec`; dogs by id rehydrated against the
  current dogs, each pickup's **rule stored inline** so a plan is a snapshot
  and can hold an ad-hoc walk). `DayPlanService` shows a saved plan instead of
  re-solving. Edits (Today edit mode): **mark a dog not-today**, **set a walk's
  duration** (tap a walk), **pin a stop's start time** (tap a pickup → sets its
  `earliestStart`), **add a walk** (FAB → pick dog + minutes; ad-hoc rule), and
  **force a dog-free appointment** (FAB → label + window + BAN address; inserted
  as a `RouteEvent.Appointment` and re-timed — for a doctor/shop/manual lunch),
  **move a standalone walk earlier/later** (Fase 2a: up/down arrows swap two
  adjacent single-dog triplets — safe, no aboard/grouping change; `PlanReorder`),
  and **regroup a dog** (Fase 2b: a pickup's ⋮ menu → walk alone / walk with
  another walk; `PlanRegroup`). A pickup's edit actions (time, regroup, not
  today) now live in that ⋮ menu.
  All re-time via `DayPlanner.retime` with `recomputeDwells = false` (a hand-set
  duration survives later edits) and pin the result. **Undo** (per-date stack)
  and Refresh/Revert. A `PlanVerifier`-backed **warnings panel** flags an edit
  that bends a constraint (kept anyway — "warn but allow"; `PlanVerifier` now
  lives in main).
- **Edit-modus = drag-and-drop chip editor (done)**: the old per-operation
  buttons/menus (up/down arrows, ⋮ regroup, `PlanReorder`/`PlanRegroup`) are
  replaced by one block/chip editor where **position = execution order**, fixing
  the "split a dog → it lands last and its start time won't move it forward" bug
  at the root (retime never reorders; it only waits). Tapping the pencil explodes
  the shown plan into draggable chips (`Calvin-LL/Reorderable`, long-press the
  drag handle): **Pickup/Dropoff/Walk/Break/Appointment** reorder freely, clamped
  to the `pickup ≤ walk ≤ dropoff` invariant; a **leg** indicator above each chip
  toggles foot/bike (`LegMode` override on the event); tap a **walk** to set its
  duration, a **pickup** to pin its start time; a walk's secondary icon
  **splits/merges**; a pickup's **✕** drops the dog for today; the FAB adds a walk
  or a forced appointment. Adjacent walk chips (a shared walk) share a tinted
  background. Each settle re-times (`commitEdit`: merge adjacent walks →
  `retime(recomputeDwells=false, allowInfeasible=true)` → pin); an impossible
  plan is shown and flagged (red time past 20:00 + the warnings panel) rather
  than dropped. Per-date undo; Done finalises. Domain foundation: `LegMode` on
  `RouteEvent` (honoured in `retimeAndCost` phase 1, BIKE even when C9 forbids),
  `retime(allowInfeasible)`, and pure transforms in **`PlanEdit.kt`**; `legMode`
  round-trips through `SavedPlanCodec`. Was the keystone for billing (now done).

## Architecture snapshot

```
data/
  entity/  Dog (incl. status: DogStatus, keyAvailable, shortWalksOverride),
           DogStatus (OFF/WALK/BOARD_ARRIVE/BOARD_STAY/BOARD_LEAVE),
           DogScheduleRule (incl. latestStart, isAlternative),
           DogIncompatibility, TransportState, SavedPlan (pinned plan JSON)
  db/      AppDatabase (v15), DogDao, DogScheduleDao,
           DogIncompatibilityDao, AppointmentDao, SavedPlanDao, Migrations, Converters
  prefs/   AppSettings (incl. cyclingWeight, overWalkWeight, lnsIterations,
           break + boarding fields), BreakLocation, SettingsRepository (DataStore)
  backup/  BackupModels (JSON DTOs, v7), BackupRepository (export/import)
  remote/  AddressSuggestion, BanApi
  routing/ RoutingDataPaths, RoutingDataInstaller, BRouterRoutingProvider
domain/
  planner/  PlannedWalk
  dayplan/  RouteEvent, DayRoute, PlanConflict, PlanningConstraint,
            WalkOption, WalkSpans, DistanceMatrix, BreakSpec, BoardingPassenger,
            DayPlanner, DayPlanService, SavedPlanCodec, PlanVerifier, PlanEdit
            constraints/  Capacity, TimeWindow, WalkDuration, Incompatibility,
                          NoDogLeftBehind, GroupSize, Appointment, MaxGap
  routing/  RouteEstimate, RoutingProvider, GeoPoint, LegGeometryCache,
            RouteDistanceCache
ui/
  common/  AddressAutocompleteField, AddressMapPreview, CyclingLegMap,
           RouteLegMap + LegMapScreen
  dogs/ owners/ today/ followplan/ billing/ settings/ addresspicker/
  navigation/ (4 tabs: Today, Dogs, Billing, Settings)  theme/
```

## Smaller known issues (not the focus, but real)

- ~~**Waiting time shows as cycling in the timeline**~~ **FIXED.** The Today
  timeline now renders idle time (a stop waiting for its window) as its own
  "waiting" row, so the leg shows only travel and the timestamps add up.
  `TodayScreen.buildTimelineRows` computes the wait (gap beyond the previous
  stop's service + this leg's travel, mirroring the harness `waitSeconds`);
  `TodayViewModel` exposes `stopBufferSeconds` for it.
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
- **Temporarily pause a dog** — now the `DogStatus.OFF` ("Uit") value of the
  per-dog status enum (was the `Dog.active` boolean, replaced in v15); OFF dogs
  are kept but skipped by the planner; set via the status selector on the Dogs
  list.
- **Incidental appointments** — one-off, per-date `Appointment` (date + exact
  window + label + address; Room v8 + backup). Pre-placed as a fixed
  `RouteEvent.Appointment`; `AppointmentConstraint` keeps the walker on time
  and dog-free during it while the dogs schedule around it (a dog that can
  only be walked then becomes a conflict). Entered on the Planning screen.

**Pending, in priority order:**
1. **No-cargo-bike day + cancellation advice.** A per-day vehicle mode
   (cargo bike / backpack + normal bike / on foot) with reduced capacity and
   speed; the planner makes an adapted plan AND advises **which dogs are best
   to cancel** (needs a per-dog cancellation cost / priority, not just the
   conflict count). Introduces the capacity/advice model.
2. **Sleepover (boarding) dog.** Board a client's dog for one or more days
   (full design + spike: `docs/SLEEPOVER_DESIGN.md`).

   **STATUS: largely built (2026-06-27).** Per-dog status enum (Room v15 +
   backup v7), the all-day passenger solver (seeded presence, ride-along via
   `NoDogLeftBehind`, `MaxGapConstraint`, soft cap term), conflict-driven
   parking (amortised multi-walk, nearest depot), the warnings-panel and
   just-in-time-pickup fixes, and the Dogs/Today UI all landed. The CSP spec
   (`docs/CSP_MODEL.md`) is in sync (boarding twist, open-span C1 exception, C4
   exemption, new C12 max-gap). Remaining: Settings sliders for the boarding
   knobs, `shortWalksOverride` for plain WALK dogs in the solver, and the
   "is there room?" acceptance check (shares the #1 capacity/advice model).

   The dog has a daily walk requirement with **no fixed time window**, governed
   by two **independent axes** (an earlier A/B/C "modes" framing was too flat
   and is replaced by this):

   - **Walk constraints (always on for a sleepover dog):**
     - **Minimum walk duration: 15 min** (constant).
     - **Maximum gap (`tussenpoos`): N hours** (configurable) between the end
       of the previous walk (or the start of the day) and the start of the
       next — the *same* in all cases. **Hard** (welfare floor).
     - **Maximum walk duration — SOFT, the single per-day knob.** Under extreme
       weather (heatwave / severe cold) or for an old dog the walker caps the
       walk length (e.g. 30 min); 35 min is fine, an hour is not — a growing
       penalty, not a hard reject. No cap ⇒ the dog rides along every walk.
       Per-dog this is the **"short walks override" checkbox** below (caps the
       dog's walks at 30 min) — it also applies to a plain WANDEL dog in extreme
       weather, so it lives on the dog, not only on a sleepover.
   - **Depot — where the dog is left between walks (independent axis):** the
     walker's home, or the **dog's own home** when the per-dog **"key available"**
     flag is set (the walker holds the client's key). Without the key, only the
     walker's home.

   **Default is "comes along all day", NOT parking** — the walker only takes a
   boarding dog on a quiet day (it fills empty capacity; a low-margin service
   that binds clients: ~€25 vs €24 for a regular 2 h walk). So tag-along is the
   intended norm; parking is the fallback (cap / weather, or capacity genuinely
   impossible). The day-length cost of carrying it IS the "is this day too
   heavy?" signal that feeds the capacity-advice model (#1): quiet day ≈ free
   (cycling +15 min on the spike), busy day spikes (refuse the stay / drop a
   dog). Spike confirmed the passenger model (`BoardingPassenger`,
   `MaxGapConstraint`, soft-cap term in `score()`); see the design doc.

   **No calendar for now (walker's call 2026-06-25).** Instead of a date-range
   boarding "stay", boarding is expressed as a **per-dog day status** the walker
   flips manually, replacing the `Dog.active` boolean with an enum (the three
   boarding statuses are the day's start/end **anchors** from the design doc):
   - **UIT** — no walks, no boarding; the dog is fully ignored (= today's
     `active = false`).
   - **WANDEL** — only the normal schedule-rule walks (today's behaviour).
   - **OPHAAL** — picked up at the dog's address at the agreed time, ends the day
     at the walker's home (boarding **first day**; anchor owner→walker).
   - **LOGEER** — starts and ends the day at the walker's home (boarding
     **middle day**; anchor walker→walker).
   - **BRENG** — starts at the walker's home, dropped at the dog's address at the
     agreed time (boarding **last day**; anchor walker→owner).

   A multi-day stay is then: OPHAAL on day 1, LOGEER on the middle days, BRENG
   on the last, flipped by hand each day (crude but no calendar needed; a real
   calendar comes later, see the membership/agenda note below). Every dog also
   needs a persistent **"key available"** flag (depot choice) and the
   **"short walks override"** checkbox (next to `allowLongerWalk`).

Priority rationale: ascending complexity and dependencies — the sleepover
(#2) reuses what the lunch break already built (home visits) and the
capacity / "is there room / which to drop" advice model from #1, so it is
built last.

**Captured 2026-06-21, expanded 2026-06-25 (not yet designed) — membership over
time + built-in agenda, data-model impact:**
The walker wants to manage *which dogs* a day involves over time, not just
shape a single day — and to **answer a client yes/no on the spot** and have the
answer recorded. The driving scenarios (2026-06-25): a regular client says
"we're on holiday for a few weeks from next Monday"; a prospect calls "can you
walk my dog every day from next week?"; "can you board him for a week?"; "can
you also do a morning walk this week?". The walker wants to look, say yes or no,
and on yes have a **built-in agenda** capture it. Related needs:
- **Per-date dog availability (declarative).** A dog has one global on/off
  (now the **status enum** UIT/WANDEL/OPHAAL/LOGEER/BRENG, §2) plus a *reactive*
  plan edit ("mark a dog not-today", Fase 0). Wanted: a per-`(dog, date)`
  override the walker sets **ahead of time** (owner away next week; a one-off
  extra morning walk this week), separate from editing a generated plan. Likely
  a per-date exception table, e.g. `DogDateOverride(dogId, date, …)`, consulted
  when building the day's walk options. The boarding statuses are the same shape
  — a per-`(dog, date)` status — so the no-calendar manual-flip (§2) is the
  stepping stone to this.
- **Incidental dog on a date + clone.** Schedule a one-off dog for a single
  day, with a **clone-from-a-regular-dog** action that copies its details and
  normal walk times as a starting point. Generalises schedule rules to allow
  one-off date-scoped walks (or a one-off dog record); reuses the clone.
- **Longer-term acceptance / capacity validation (the yes/no answer).** Beyond
  one day: can the walker **take on a new dog** (walking, or boarding) over a
  horizon of days? Look-ahead feasibility across the affected dates → a
  **yes/no** the walker can give immediately, then commit to the agenda. Same
  capacity model as the sleepover "is there room?" check (#2) and the
  cancellation/capacity-advice model (#1), run forward across dates.

**Built-in agenda + Google Calendar (captured 2026-06-25):** the membership
changes above (a booked stay, a holiday pause, an accepted new dog) need to be
*recorded somewhere* — a built-in agenda/calendar. The walker also wants the
**day plan itself to land in the phone's Google Calendar**, and considers the
**Follow plan screen then largely superfluous** (the calendar app on the phone
becomes the during-the-day view). CAUTION / open: CLAUDE.md forbids pulling in
proprietary Google SDKs (Maps/Firebase/etc.) without checking first. The clean
path that avoids that is the **Android system Calendar Provider**
(`CalendarContract`, an OS content provider) or an **ICS/CalDAV export** — these
sync to whatever calendar account the phone has (incl. Google) **without** a
Google SDK or a paid API, fitting the OSM / no-vendor ethos. Confirm the
approach with the walker before building. This is a sizeable new direction (a
calendar data model + sync), to be scoped separately; it sits behind the
sleepover (which is being done **without** a calendar first, §2).

## App-surface follow-ups (deferred while solver is the focus)

- Follow plan: resumable-across-exit (persist step), dog photo (needs an
  image loader → user OK), surface conflicts. **NOTE (2026-06-25): the walker
  now considers Follow plan largely superfluous** in favour of the day plan
  appearing in the phone's Google Calendar (see the agenda note in the wishlist)
  — so don't invest further here until that direction is decided.
- Manual override of the plan — **done**, as a drag-and-drop chip editor
  (position = execution order; `Calvin-LL/Reorderable`, `PlanEdit` transforms,
  `LegMode` override, `commitEdit` with `allowInfeasible`).
- **Billing — done** (replaced the History tab). Owners as a first-class entity
  (dog → owner dropdown, owner screen; `isEmployer`/`isTest`). Per-rule price
  (`Pricing`: €4 + €3/quarter, capped €24; second same-owner dog in a walk half
  price). A day plan is **committed** to per-dog services on owners' running
  accounts (frozen amounts, idempotent, plan snapshot kept; billed once per
  pickup→dropoff span). Billing screen: balances / employer monthly hours,
  manual items, committed-days list. **Invoices**: issuer profile + continuous
  number series (real / TEST) in settings; facture / facture acquittée PDFs via
  `PdfDocument` (French BNC; TEST watermark + series), shared via FileProvider;
  per-owner invoice list. Each invoice freezes a **render snapshot**
  (`renderJson`) so a reprint is identical; the in-app share re-renders from it,
  and `tools/regenerate_invoices.py` rebuilds the PDFs from a `backup.json` on a
  laptop (headless Chrome, else print-from-HTML). **URSSAF export**: a ZIP with
  `wandelingen.csv` + `ontvangsten.csv` (test owners excluded, quarter column) +
  a full `backup.json`. **Credit notes** (facture d'avoir) via a step-by-step
  wizard. All new entities round-trip through the backup (version 7 — bumped
  for the boarding `DogStatus` / `keyAvailable` / `shortWalksOverride` fields and
  the boarding settings).
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
4. `docs/CSP_MODEL.md` — the authoritative CSP (variables + constraints); keep
   it in sync when changing `domain/dayplan/`.
5. `docs/SLEEPOVER_DESIGN.md` — the boarding/sleepover feature design + spikes.
6. `docs/SCREENS.md` — screen inventory and rationale.
7. `docs/ROUTING_ENGINES.md` — why BRouter.
8. `dogrouter-backup.json` (repo root) — the real test data for the harness.
9. `docs/solver-baseline.md` — current quality metrics per weekday; the
   reference to beat. Regenerate with `./run_baseline.sh`.
