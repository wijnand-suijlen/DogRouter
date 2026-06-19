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
  rules with weekday multi-select + time window + duration.
- **Settings tab**: home address picker (same widgets as dog stop),
  cycling speed (km/h, user override), bike capacity, stop buffer,
  BRouter map download (~125 MB IDF segment) + self-test.
- **Today tab**: PDPTW event timeline. Date picker with prev/next/today
  controls. Summary card with on-the-clock + cycling + walking totals.
  Red conflict panel if any walks are unschedulable.
- **BRouter** running embedded on-device via `org.btools:brouter-core`
  from GitHub Packages. Profile `bakfiets.brf` shipped in assets,
  derived from trekking.brf. Lookups.dat also shipped.
- **Routing flow**: BRouter for the road network and distance,
  user-configured cycling speed for the time. We do NOT use BRouter's
  kinematic time estimate.
- **PDPTW planner** (`domain/dayplan/DayPlanner.kt`): greedy insertion
  heuristic, two modes (new pickup-walk-dropoff triplet, or join an
  existing walk). Pluggable `PlanningConstraint` interface with four
  concrete checks today: capacity, time windows, walk duration
  (min + max for `allowLongerWalk=false`), incompatibilities.
- **Schema** at v4. Migrations for 1→2, 2→3, 3→4 all in
  `data/db/Migrations.kt`.

## Architecture snapshot

```
data/
  entity/  Dog, DogScheduleRule, DogIncompatibility, TransportState
  db/      AppDatabase (v4), DogDao, DogScheduleDao,
           DogIncompatibilityDao, Migrations, Converters
  prefs/   AppSettings, SettingsRepository (DataStore)
  remote/  AddressSuggestion, BanApi (autocomplete + reverse)
  routing/ RoutingDataPaths, RoutingDataInstaller,
           BRouterRoutingProvider
domain/
  planner/  PlannedWalk (the only survivor of the old planner)
  dayplan/  RouteEvent (sealed: HomeStart/Pickup/Dropoff/Walk/HomeEnd),
            DayRoute, PlanConflict, PlanningConstraint,
            DistanceMatrix, DayPlanner
            constraints/  Capacity, TimeWindow, WalkDuration,
                          Incompatibility
  routing/  RouteEstimate, RoutingProvider, GeoPoint
ui/
  common/  AddressAutocompleteField, AddressMapPreview
  dogs/    DogListScreen + ViewModel, DogEditScreen + ViewModel,
           ScheduleEditor, ScheduleRuleDraft
  today/   TodayScreen, TodayViewModel
  followplan/ FollowPlanScreen (full-screen stub, "Start trip" target)
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

3. **No walk-splitting**. Each dog gets exactly one walk event in the
   algorithm. Cannot generate "Yankee alone 45 min, then with Ouna for
   15 min". For now Mode B always extends the walk to the max
   duration; with `allowLongerWalk=true` (default) that is acceptable.

4. **Cost matrix not cached across dates**. Switching days rebuilds the
   matrix with fresh BRouter calls. For ≤5 dogs about 10 calls = a few
   seconds. Becomes annoying as dog count grows.

5. **dayStart / dayEnd are hardcoded 08:00–20:00** in the
   `DayPlanner` constructor. Belongs in Settings eventually.

6. **`bakfiets.brf` profile parameters** are very conservative
   (totalMass=180, bikerPower=80, S_C_x=0.45) which makes BRouter's
   ROUTE CHOICE more pessimistic about hills than necessary. Now that
   we override BRouter's time, the user might want a lighter profile
   so BRouter picks faster routes even through small hills. Defer.

## Roadmap, prioritised

### Promoted: Follow-plan execution screen
The **Follow-plan** screen ("Start trip" handoff from Today) is now a
named v1 screen, not a "someday" item — see `docs/SCREENS.md` #2.
**Stubbed and wired** (2026-06-19): Today shows a "Start trip" extended
FAB when a plan has events; tapping it navigates to
`FollowPlanRoutes.ROUTE`, a full-screen `FollowPlanScreen` that hides the
bottom bar (via `FULL_SCREEN_ROUTES` in `AppNavigation`) and exits back
to Today on close or system back.
The real execution UI is still to build: current stop dominates (dog name
+ photo, address, quirks, ETA), next 1–2 stops smaller below, single tap
to advance, resumable on exit. This is the intended next major feature
once a small correctness/cosmetic item is cleared.

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
- Settings: data export/import (in-scope per SCOPE).
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
  brouter artifact; documented in `README.md`.
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
