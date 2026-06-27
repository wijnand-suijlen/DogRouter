# Sleepover (boarding) dog — design

Internal design doc (English only, like STATUS.md). Captures the agreed
**passenger model** for boarding a client's dog at the walker's home for one
or more days, and the off-device **spike** that validates it before any Room /
UI work. Wishlist context: `docs/STATUS.md` → "Feature wishlist" item 2.

The day-planning solver this builds on lives in `domain/dayplan/`; the
authoritative CSP is `docs/CSP_MODEL.md` (new constraints below must land there
when the real implementation does — CLAUDE.md rule).

---

## 1. The problem, decomposed into two independent axes

A boarding dog needs walking through the day, but with no fixed time window.
How it is cared for is **not** a small set of modes — it is two orthogonal
axes (an earlier "A/B/C modes" framing was too flat):

- **Axis 1 — walk constraints.** Always-on for a boarding dog:
  - **Minimum walk duration: 15 min** (constant).
  - **Maximum gap (`tussenpoos`): N hours** (configurable) between the end of
    the previous walk (or the start of the day) and the start of the next.
    Same value regardless of the rest. **Hard** (a welfare floor — an old dog
    must not sit too long).
  - **Maximum walk duration — the single per-day knob the walker sets, and it
    is SOFT.** Under extreme weather (heatwave / severe cold) or for an old dog
    the walker caps the walk length (e.g. 30 min). 35 min against a 30 cap is
    fine; an hour is not. So it is a growing penalty, not a hard reject. Under
    normal conditions there is no cap, so the dog does full-length walks along
    the tour. Set **per boarding day by feel** — no good auto-rule.
- **Axis 2 — depot (where the dog is left between walks).** Independent of the
  cap; applies whenever the dog is not aboard:
  - the walker's home (always), and/or
  - the **dog's own home** when a per-dog **"key available"** flag is set (the
    walker holds the client's key, so the dog's own address is a flexible drop
    point). Without the key, only the walker's home.

"Comes along all day" vs "park it for short walks" is therefore **not a mode**.
It is the emergent result of the one soft cap: no cap ⇒ the dog stays aboard
and walks with every group (cheapest); a low cap ⇒ joining the long group
walks gets expensive, so the solver parks the dog and gives it dedicated short
walks. The behaviour falls out of the objective.

## 2. Three start/end anchors (position in the stay)

A boarding **stay** spans dates `[startDate, endDate]`. The dog's start- and
end-of-day location depends on where the date sits in that range:

| Day in the stay | Start anchor | End anchor | Events it adds |
| --- | --- | --- | --- |
| **First** | owner's home | walker's home | a real **Pickup at the owner's**; no external dropoff — the dog ends the day aboard at `HomeEnd` |
| **Middle** | walker's home | walker's home | none — the dog "lives at home"; walks anchor on the walker-home depot or ride along |
| **Last** | walker's home | owner's home | no external pickup; a real **Dropoff at the owner's** near day end |

So a boarding dog is **not** a symmetric pickup↔dropoff pair on one depot. Per
day the anchor adds at most one external stop. This means `WalkSpans` (today
strictly pickup↔dropoff pairs) must tolerate an **open span**:

- first day: aboard from its Pickup through to `HomeEnd` (no dropoff);
- middle/last day: present from `HomeStart` (no opening pickup).

Anchors are **derived per date** from the position in `[startDate, endDate]`;
the cap knob is set per boarding day.

## 3. The passenger model

The construction goal the walker stated: **try to take the dog along the whole
day from the start.** So the boarding dog is inserted **first**, as a presence
backbone, not last.

### Mechanism — reuse `NoDogLeftBehindConstraint`

`NoDogLeftBehindConstraint` already requires that **every dog aboard takes part
in every `Walk`** while it is aboard. That is exactly the passenger rule. So:

1. Place the boarding dog's **presence span** into the base events first: a
   `Pickup` at the start anchor and a `Dropoff` at the end anchor (for an
   open-ended anchor — walker home — the boundary is `HomeStart` / `HomeEnd`).
2. The dog is now aboard for the whole interval. When regular dogs are inserted
   and create or join `Walk` events inside that interval, those walks **must**
   include the passenger to satisfy `NoDogLeftBehind` — so the walk-builders
   (`buildNewTriplet` / `buildJoinWalk` / `buildRideAlong`) include the dogs
   currently aboard at that position. Group-size and capacity therefore see the
   passenger from the start (a 4th slot, box weight), so regular dogs are placed
   around it rather than overflowing a group when the passenger is bolted on
   last.
3. **Parking is the exception, carved out of the presence.** Instead of one
   `Pickup…Dropoff`, the passenger gets `Pickup(start)…Dropoff(depot)…
   Pickup(depot)…Pickup…Dropoff(end)` — the middle hole is the parked period,
   during which it is not aboard and walks there do not include it. FIFO span
   pairing in `WalkSpans` already supports a dog with several spans in a day.

### Why the one knob yields both behaviours (cost argument)

`score()` = elapsed + `cyclingWeight`×ride-seconds + `overWalkWeight`×over-walk
+ oversize penalty, with a **new soft-cap term** added (see below). A ride-along
adds ~0 (no pickup detour, no extra ride). A dedicated depot walk adds a ride to
and from the depot plus its dwell. So:

- **no cap** → the passenger rides along every group walk for free → meenemen
  wins in the hill-climb;
- **low cap** → keeping the passenger in the long group walks costs soft-cap
  penalty; once that outweighs a depot detour the solver **parks** the dog and
  inserts dedicated short walks → parkeren emerges, with exactly the detour cost
  that makes "the day is too busy" visible (feeds the acceptance check, §6).

## 4. New / changed CSP pieces

- **`MaxGapConstraint`** (NEW, hard, time-dependent). For each boarding dog: no
  interval longer than `maxGapSeconds` between day start and the dog's first
  walk, between consecutive walks it joins (each ≥ `minWalkSeconds`), and — open
  question for the spike — up to the end anchor. Runs after `retimeAndCost`,
  like `TimeWindowConstraint`.
- **Soft max-walk-duration penalty** (NEW, objective term, not a constraint).
  For each `Walk` a capped boarding dog joins, add a penalty growing with the
  overshoot above the cap — **quadratic** (`overshoot² × weight`) so 5 min over
  is cheap and 30 min over is dear. Weight is a new tunable, far below
  `OVERSIZE_PENALTY_SECONDS`, alongside `cyclingWeight` / `overWalkWeight`.
- **`WalkDurationConstraint` is skipped for boarding dogs.** Their requirement
  is max-gap coverage, not a per-span `durationMinutes` total.
- **`WalkSpans` open span** support (see §2).

## 5. Data model (real implementation)

**No calendar for now (walker's call 2026-06-25).** Rather than a date-range
`BoardingStay`, boarding is a **per-dog day status** the walker flips by hand,
replacing the `Dog.active` boolean with an enum. Decisions locked 2026-06-26:
table-recreate migration (A), long enum names (B), the soft cap also for WALK
dogs (C).

### `DogStatus` enum — lives in `data/entity`

A plain enum with only `isBoarding` (the data layer must NOT depend on the
domain layer, where `BoardingAnchor` lives — domain→data is the allowed
direction, established by `DayPlanner import data.entity.Dog`):

```kotlin
enum class DogStatus(val isBoarding: Boolean) {
    OFF(false),          // UIT — ignored, no walks/boarding (= old active = false)
    WALK(false),         // WANDEL — normal schedule-rule walks only
    BOARD_ARRIVE(true),  // OPHAAL — collected at owner, ends at walker (boarding day 1)
    BOARD_STAY(true),    // LOGEER — starts and ends at walker (middle day)
    BOARD_LEAVE(true),   // BRENG  — starts at walker, returned to owner (last day)
}
```

### Anchor derivation — a domain extension (`domain/dayplan`)

```kotlin
fun DogStatus.anchors(): Pair<BoardingAnchor, BoardingAnchor>? = when (this) {
    DogStatus.BOARD_ARRIVE -> BoardingAnchor.OWNER_HOME  to BoardingAnchor.WALKER_HOME
    DogStatus.BOARD_STAY   -> BoardingAnchor.WALKER_HOME to BoardingAnchor.WALKER_HOME
    DogStatus.BOARD_LEAVE  -> BoardingAnchor.WALKER_HOME to BoardingAnchor.OWNER_HOME
    else -> null
}
```

A multi-day stay is BOARD_ARRIVE (day 1) → BOARD_STAY (middle) → BOARD_LEAVE
(last), flipped by hand each day.

### `Dog` entity changes

- **Replace** `val active: Boolean` with `val status: DogStatus = DogStatus.WALK`.
  `active` is dropped entirely; "considered today" = `status != OFF`.
- **Add** `val keyAvailable: Boolean = false` — walker holds the key, so the dog's
  own address can be a depot (UI: "Stop" section).
- **Add** `val shortWalksOverride: Boolean = false` — caps the dog's walks at 30
  min (extreme weather / old dog). For a **boarding** dog → `capSeconds` (soft).
  For a **WALK** dog → also the soft cap (decision C), not a hard rewrite. (UI:
  "Walk constraints" section, next to `allowLongerWalk`.)

### Migration v14 → v15 (table recreate, drops `active`)

`DROP COLUMN` is unreliable on older Android SQLite, so recreate the `dogs`
table without `active`: `CREATE dogs_new (… status TEXT NOT NULL DEFAULT 'WALK',
keyAvailable INTEGER NOT NULL DEFAULT 0, shortWalksOverride INTEGER NOT NULL
DEFAULT 0 …)`, `INSERT … SELECT …, CASE WHEN active = 0 THEN 'OFF' ELSE 'WALK'
END, 0, 0 FROM dogs`, `DROP dogs`, `ALTER … RENAME dogs_new TO dogs`, recreate
indices. `DogStatus` stored as TEXT name via a new `Converters` pair (mirrors
`TransportState`). Bump `AppDatabase` to v15; regenerate `app/schemas/15.json`.

### AppSettings (new boarding knobs)

`boardingMaxGapMinutes` (180, hard), `boardingMinWalkMinutes` (15),
`boardingShortWalkMinutes` (30, the cap value), `boardingCapWeight` (30, the
soft-penalty weight already in the solver). DataStore + Settings UI.

### Backup (version 6 → 7)

`DogDto`: `active` → `status` (default `WALK`), add `keyAvailable` /
`shortWalksOverride` (defaults), so old backups still load (absent `status` →
`WALK`; an old `active = false` has no representation in v7 — acceptable, or map
during import if a v6 `active` field is present). `BACKUP_VERSION = 7`.

### Solver wiring (`DayPlanService.computePlan`)

Build one `BoardingPassenger` per `status.isBoarding` dog (anchors from
`status.anchors()`, `capSeconds = if (shortWalksOverride) boardingShortWalkMinutes
* 60 else null`, gap/min-walk from settings); pass to `DayPlanner(boardingPassengers
=, boardingCapWeight =)`. Regular `WalkOption`s come only from `status == WALK`
dogs (a boarding dog's fixed-window rules are ignored while it boards); the
`active` filters become `status != OFF`.

## 6. Acceptance — "is there room?" (later, depends on wishlist #1)

Only accept a boarding stay when the day(s) are not too busy. This is the same
capacity / cancellation-advice model as wishlist #1 (no-cargo-bike day), run
forward across the stay's dates. Out of scope for the spike; the spike only
needs to surface unplaceable coverage as a conflict.

---

## 7. The spike (spike-B: passenger model)

Goal: prove the passenger model fits cleanly on the retimer and the
group-size / capacity bookkeeping, **off-device** in `SolverHarness`, before any
Room / UI / persistence. Build the minimum behind a flag; do not wire
production.

### Minimum to build

- A boarding descriptor (dog + depot + `capSeconds?` + `maxGapSeconds` +
  `minWalkSeconds` + start/end anchor) passed into a harness scenario.
- Presence-span seeding (Pickup/Dropoff at anchors) **before** regular insertion.
- Walk-builders include currently-aboard dogs (so passengers ride along and
  `NoDogLeftBehind` is satisfied by construction).
- `MaxGapConstraint` + the soft-cap score term.
- `WalkSpans` open-span tolerance.
- Synthetic dogs (`Alfa`, `Bravo`, … — fictional per the anonymisation rule)
  layered on a busy day from the existing harness data.

### Scenarios and what each proves

| # | Setup | Measure | Expected |
| --- | --- | --- | --- |
| 1 | boarding dog, **no cap**, busy middle day | extra day length vs no boarding dog; # dedicated depot walks | ~0 dedicated, ~0 extra length (rides along) |
| 2 | same day, **cap 30 min** | duration of joined walks; # depot walks; all gaps | overshoot tolerated to ~35, an hour avoided, parking appears |
| 3 | all three **anchors** (first/middle/last day) | first/last external stop location; open span retimes correctly | Pickup-at-owner day 1, Dropoff-at-owner last day, none mid |
| 4 | **key** on vs off | depot location of dedicated walks | on = owner's home available, off = walker's home only |
| 5 | **max gap** | largest gap | ≤ maxGap (hard holds) |

### Judging passenger model & cost

- Re-run scenario 1 forcing dedicated walks (pure-(a) emulation): if passenger
  no-cap stays close to "day without the boarding dog" while pure-(a) adds
  notable day length / cycling, the passenger model earns its complexity.
- Record solve-time overhead (presence seeding + max-gap repair) as the cost of
  the model.

### Stage 1 findings (built & measured)

Stage 1 is implemented behind a flag (`DayPlanner` boarding params, gated so
regular plans are byte-identical; `SolverHarness.boardingSpike`, `-Dsolver.boarding`)
and run on the real data. Results:

- **The passenger model works and is the right default.** With the presence span
  pinned as the day's backbone (boarding Pickup right after HomeStart, Dropoff
  right before HomeEnd; `boardingPinned`), `includeAboardPassengers` +
  `NoDogLeftBehindConstraint` make the dog ride along every nested group walk,
  and `MaxGapConstraint` (enforced during search) keeps it from collapsing the
  span. Anchors (first day = pickup at owner; middle/last) and the key-as-depot
  flag work. Max-gap holds. No conflicts. Regular-plan tests still green.
- **Continuous presence is correct — do NOT default to parking.** The walker
  only takes a boarding dog on a quiet day (it fills empty capacity, a low-margin
  service that binds clients). So "comes along all day" is the intended default;
  parking is a fallback (cap / weather, §1; or capacity genuinely impossible),
  not the norm.
- **The day-length cost IS the acceptance signal.** Carrying the dog occupies a
  group slot in every walk, so a busy day de-groups and the cost spikes — which
  is exactly the "this day is too heavy, don't accept it / which dog to drop"
  signal that feeds the capacity-advice model (§6 / wishlist #1):
  - quiet day (Thu, 7 dogs): day +1h00, **cycling +15m** — cheap, fills capacity;
  - busy day (Wed, 10 dogs): day +2h23, cycling +1h52 — refuse / too heavy.
- **Fixed:** the "leave home just in time" retime now skips leading home-located
  events (HomeStart + a home boarding pickup), so collecting a dog that was home
  overnight no longer makes the day start artificially early (was inflating the
  cost by ~1h of phantom idle). Regular plans unaffected (firstAway stays 1).

### Known rough edges (stage 1, to tidy)

- Degenerate 0-min boarding-only `Walk` events (e.g. `[Alfa] 0min`) appear where
  the dog is briefly aboard alone between others' dropoff/pickup. Harmless (zero
  time, don't count toward max-gap) but should be filtered out.
- `MaxGapConstraint` measures from the first pickup and ignores the trailing gap
  to the end anchor (open question).
- A boarding dog aboard all day conflicts with a dog-free `Appointment` (it would
  be aboard during it) — needs parking during the appointment (not yet handled;
  spike scenarios have no appointments).

### Open risks the spike must resolve

- **Open spans** through the retimer (`aboard` seeded at `HomeStart`; aboard at
  `HomeEnd`) and through every span-based constraint / `effectiveDwells`.
- **LNS interaction** (`ruinAndRecreate` / `remove`): the presence span should
  be a fixed backbone the LNS does not ruin; confirm `remove` never extracts it
  and repair never strands coverage.
- **Multi-trip (STATUS weakness #6):** confirm a depot Dropoff + later Pickup
  on the depot address suffices and no real multi-trip restructuring is needed.
