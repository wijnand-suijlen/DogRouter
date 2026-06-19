# DogRouter status — working memo

Internal hand-off document so a fresh Claude session can pick up where we
left off. English only (internal design doc, not user-facing).

Last touched: 2026-06-19. Twenty-one commits on `main`.

## What works today

- **Dogs tab**: list, add, edit, delete. Per dog: name, breed, weight,
  photo (URI, no Photo Picker yet), owner name + phone, address with
  BAN-autocomplete + tap-on-map picker + mini-map preview, stop notes
  with time adjustment, transport state (cargo bike / backpack), walk
  constraints (`allowLongerWalk` + incompatibility chips), schedule
  rules with weekday multi-select + time window + duration + an
  "either/or" flag (mark rules mutually exclusive — walk one per day).
- **Settings tab**: home address picker (same widgets as dog stop),
  cycling speed (km/h, user override), bike capacity, stop buffer,
  BRouter map download (~125 MB IDF segment) + self-test, and **data
  export/import** (Storage Access Framework file picker; JSON backup of
  dogs, schedules, incompatibilities, settings — for moving between
  phones; import replaces all data, behind a confirm dialog).
- **Today tab**: PDPTW event timeline. Date picker with prev/next/today
  controls. Summary card with on-the-clock + cycling + walking totals.
  Red conflict panel if any walks are unschedulable. "Start trip" FAB
  (shown when the day has events) hands off to Follow plan. A refresh
  action in the app bar asks the randomised solver for an alternative
  plan for that day. Each cycling leg row has a map icon that opens the
  full-screen route map (no inline map in the list — see the leg-maps
  note below).
- **Follow plan**: full-screen on-the-bike execution of one day's plan.
  Current stop dominates (big ETA, title, address, owner phone, quirks
  highlighted), next two stops listed below, large "Done — next stop" /
  "Finish trip" button advances, Back corrects a mis-tap, progress bar +
  "Stop n of N", and a "Trip complete" end state. Hides the bottom bar.
  The plan comes from the shared `DayPlanService`, the same pipeline
  Today uses. When a stop is reached by cycling from the previous one,
  the card shows an inline street-map overview of the leg (`CyclingLegMap`,
  tiled osmdroid); tapping it opens the full-screen interactive map.
- **Leg maps**: geometry comes from `RoutingProvider.routeGeometry()`,
  cached per leg via `LegGeometryCache`.
  - **Follow plan** shows one inline overview map at a time
    (`CyclingLegMap` → a static, tile-backed `RouteLegMap` under a tap
    overlay). One MapView at a time is fine.
  - **Today** lists many legs, so it shows a tap-to-open **map icon** per
    leg instead of an inline map — many simultaneous osmdroid MapViews in
    the list caused memory/ANR pressure.
  - **Full screen**: `LegMapScreen` (`RouteLegMap` interactive) gives
    pinch-zoom and pan, reachable from both screens.
  - Falls back to a straight line if BRouter cannot trace the leg.
- **BRouter** running embedded on-device via `org.btools:brouter-core`
  from GitHub Packages. Profile `bakfiets.brf` shipped in assets,
  derived from trekking.brf. Lookups.dat also shipped.
- **Routing flow**: BRouter for the road network and distance,
  user-configured cycling speed for the time. We do NOT use BRouter's
  kinematic time estimate. `RoutingProvider.routeGeometry()` exposes the
  BRouter track as a polyline for map drawing (Follow plan); the planner
  still uses `route()`, which keeps no geometry per cost-matrix cell.
- **PDPTW planner** (`domain/dayplan/DayPlanner.kt`): randomised
  multi-start greedy. Each start inserts walks in some order using three
  modes (new pickup-walk-dropoff triplet; join an existing walk and
  extend it; ride along several existing walks without extending them,
  which splits one required duration across shorter sessions); the best
  of `restarts` builds is kept. A `seed` makes a build reproducible (for
  caching) while different seeds explore alternatives (the refresh
  button). Pluggable `PlanningConstraint` interface with four
  concrete checks today: capacity, time windows, walk duration
  (min + max for `allowLongerWalk=false`), incompatibilities. Constraints
  pair pickups↔dropoffs per occurrence (`walkSpans`), so a dog with two
  schedule rules (two walks in a day) is handled correctly. The planner
  takes `WalkOption`s: a single alternative is a required walk, several are
  an exclusive choice (a rule's `isAlternative` flag) where exactly one is
  scheduled — "end of morning OR end of afternoon".
- **Tests**: JVM unit tests under `app/src/test`. `DayPlannerScenarioTest`
  reproduces the 19-June report (`planningsprobleem-19juni`) and covers
  two-rule dogs, splitting, determinism and exclusive choice with a fake
  routing provider; `BackupModelsTest` covers the export/import round-trip.
- **Schema** at v5. Migrations for 1→2 … 4→5 (4→5 adds the rule
  `isAlternative` flag) in `data/db/Migrations.kt`.

## Architecture snapshot

```
data/
  entity/  Dog, DogScheduleRule, DogIncompatibility, TransportState
  db/      AppDatabase (v4), DogDao, DogScheduleDao,
           DogIncompatibilityDao, Migrations, Converters
  prefs/   AppSettings, SettingsRepository (DataStore)
  backup/  BackupModels (JSON DTOs), BackupRepository (export/import)
  remote/  AddressSuggestion, BanApi (autocomplete + reverse)
  routing/ RoutingDataPaths, RoutingDataInstaller,
           BRouterRoutingProvider
domain/
  planner/  PlannedWalk (the only survivor of the old planner)
  dayplan/  RouteEvent (sealed: HomeStart/Pickup/Dropoff/Walk/HomeEnd),
            DayRoute, PlanConflict, PlanningConstraint, WalkOption,
            DistanceMatrix, DayPlanner,
            DayPlanService (shared plan pipeline: Today + Follow plan)
            constraints/  Capacity, TimeWindow, WalkDuration,
                          Incompatibility
  routing/  RouteEstimate, RoutingProvider, GeoPoint,
            LegGeometryCache (memoises route geometry per leg)
ui/
  common/  AddressAutocompleteField, AddressMapPreview,
           CyclingLegMap (inline overview, Follow plan),
           RouteLegMap + LegMapScreen (full-screen osmdroid map)
  dogs/    DogListScreen + ViewModel, DogEditScreen + ViewModel,
           ScheduleEditor, ScheduleRuleDraft
  today/   TodayScreen, TodayViewModel
  followplan/ FollowPlanScreen + FollowPlanViewModel (on-the-bike
              execution, "Start trip" target)
  history/  HistoryScreen (stub)
  settings/ SettingsScreen, SettingsViewModel
  addresspicker/ AddressPickerScreen + ViewModel
  navigation/   AppNavigation, TabDestination
  theme/        Color, Theme, Type
```

## Known issues / loose ends

1. **Walk.location goes stale on Mode B** join. When dog Y joins dog X's
   existing walk, the walk's `location` stays at X's home. Travel time
   from the walk to the next event is then computed from the stale
   location, underestimating real travel. Not yet user-reported but
   real. Fix: in `DayPlanner.retimeAndCost`, when placing a Walk, set
   its location to `retimed.last().location`.

2. **Waiting time shows as cycling in the timeline**. If pickup waits
   for its window to open, the displayed leg time between previous
   event and the pickup includes the wait. Cosmetic; we should split
   wait into its own row.

3. **Walk-splitting exploited via randomised multi-start.** Mode C
   (ride-along) lets one required duration be split across several walks;
   the planner now builds from many insertion orders (`restarts`, seeded
   `Random`) and keeps the best (fewest unplaced, then shortest day), so
   it discovers the multi-session structures splitting needs. The 19-June
   Alfa now rides along several sessions instead of one dedicated 120-min
   walk. The cost function minimises day length, not over-walking, so an
   `allowLongerWalk=true` dog may be walked a bit longer than asked when
   that shortens the day overall.

4. **(Resolved) Plan caching.** `DayPlanService` now caches plans by
   (inputs, seed) in an LRU map (the service is a Koin single, so the
   cache spans screens and dates). Re-opening Follow plan or returning to
   a day is a cache hit — no BRouter, no solver. The cache invalidates
   when any planner-relevant input changes (and on `refresh`, which bumps
   the date's seed). Remaining nuance: the key includes the full `Inputs`,
   so editing an irrelevant dog field (e.g. photo) also invalidates it —
   harmless, just an occasional needless recompute.

5. **dayStart / dayEnd are hardcoded 08:00–20:00** in the
   `DayPlanner` constructor. Belongs in Settings eventually.

6. **`bakfiets.brf` profile parameters** are very conservative
   (totalMass=180, bikerPower=80, S_C_x=0.45) which makes BRouter's
   ROUTE CHOICE more pessimistic about hills than necessary. Now that
   we override BRouter's time, the user might want a lighter profile
   so BRouter picks faster routes even through small hills. Defer.

## Roadmap, prioritised

### Done: Follow-plan execution screen
The **Follow-plan** screen ("Start trip" handoff from Today) is built
(2026-06-19) — see `docs/SCREENS.md` #2 and "What works today" above.
Today's "Start trip" FAB passes the selected date to
`FollowPlanRoutes.route(date)`; `FollowPlanScreen` + `FollowPlanViewModel`
walk through the day's events one stop at a time. Plan computation was
extracted from `TodayViewModel` into `DayPlanService` so both screens
share one pipeline.

Remaining polish (not blocking):
- **Resumable across exit**: step progress lives in the ViewModel, so it
  survives rotation but resets if you leave and re-enter the screen. The
  SCREENS doc wants a resumable suspended trip — needs persistence
  (DataStore or a small Room row keyed by date).
- **Dog photo**: the current-stop card is text-only. No image loader is
  in the project yet (the dog list has none either); adding one (e.g.
  Coil) needs the user's OK first.
- **Conflicts** (unscheduled walks) are not surfaced in Follow plan.

### Next round candidates (pick one)
1. **Walk-location bug fix** (item 1 above). Tiny code change, real
   correctness improvement.
2. **Test on real multi-dog day** with the user and iterate based on
   what they see. Probably reveals smaller issues we have not thought
   of.
3. **Waiting-time row in timeline** (item 2). Cosmetic but clarifying.
4. **dayStart / dayEnd in Settings** (item 5). Small.
5. **Cost matrix cache** across plan invocations (item 4). Medium.

### Medium-term
- **Manual override** of the plan (drag-drop reorder, mark a dog as
  not-doing-today, etc.). These are the Today "fine-tune" actions that
  `docs/SCREENS.md` lists as planned-but-not-built.
- **Walk merging optimisation** post-pass.
- **Walk splitting** in the planner for the rare cases where a
  no-longer-walk dog cannot ride along with a long-walking dog.

### Defer
- Photo Picker (user explicitly deferred this; less interesting than
  the planner).
- History tab (in-scope per `SCOPE.md` but not started; nav stub only).
- Profile-tuning workflow (sliders that write to bakfiets.brf).

### Dropped
- **Week tab** — removed from v1 (decided 2026-06-19). It only
  visualised derived schedule data already editable under Dogs, and its
  "tap a day" navigation is covered by Today's date picker. Rationale
  kept under "Considered, not in v1" in `docs/SCREENS.md`. **Done
  (2026-06-19):** `TabDestination.Week`, `WeekScreen.kt`, and its
  `composable` in `AppNavigation.kt` are deleted; the bottom bar now has
  four tabs (Today, Dogs, History, Settings).

## Project conventions to keep in mind

- **Workspace boundary**: stay inside
  `/Users/wijnand/Documents/src/DogRouter/`. Enforced via deny rules
  in `.claude/settings.local.json`.
- **Language**: Dutch in chat with the user, English in source,
  comments, commit messages, primary docs.
- **Commit-message apostrophes** break the heredoc + bash trick we
  use. Rephrase to avoid `'` in the body.
- **User context**: Wijnand Suijlen, Dutch-speaking, lives and works
  in Meudon (92) in Île-de-France. Two phones, Android 15 and 16.
- **Build**: GitHub PAT with `read:packages` scope required for the
  brouter artifact; documented in `README.md`. There is no system JDK on
  the dev machine — the only JDK is the one bundled with Android Studio,
  so CLI builds need `JAVA_HOME` pointed at it:

  ```
  JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    ./gradlew :app:compileDebugKotlin
  ```

  The Gradle wrapper (8.13) is committed, so no separate Gradle install
  is needed.
- **Schema migrations**: any change to a Room entity requires a
  migration AND a schema-version bump. Schema JSON files are
  committed to `app/schemas/`.
- **Routing math**: BRouter for distance and route choice, user's
  `cyclingSpeedKmh` setting for time. We do NOT use BRouter's
  internal kinematic time. See `DistanceMatrix.build`.

## Documents to read on session start

1. `CLAUDE.md` — global project rules.
2. `SCOPE.md` — what v1 does and does not do.
3. `docs/SCREENS.md` — screen inventory and design rationale.
4. `docs/ROUTING_ENGINES.md` — why BRouter, what we ruled out.
5. This file.
