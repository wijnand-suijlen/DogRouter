# DogRouter day-plan model as a CSP

A precise specification of the day-planning problem the solver in
`domain/dayplan/` actually solves: its variables (with domains) and its
constraints. Each item is given three ways — **plain English**, **predicate
logic**, and a **code citation** of the snippet that implements it.

This is a reference, not a tutorial. For *how* the search explores this model
(multi-start + LNS, the objective it optimises, the harness) see
`docs/STATUS.md`. This document covers *what* a feasible plan is.

> **Keep this in sync with the code.** When you add, remove, or change a
> variable, a domain, or a constraint in `domain/dayplan/`, update the matching
> section here in the same change. A drifted spec is worse than none. See the
> note in `CLAUDE.md`.

---

## 1. Problem shape

The problem is a **Pickup-and-Delivery Problem with Time Windows (PDPTW)**
specialised to one walker on one cargo bike, with two extra twists that an
ordinary PDPTW lacks:

- **Service = walking**, and a single required walk may be **split** across
  several shorter group walks (the demand is a *duration*, not a single visit).
- **Two transport modes per leg** — ride the cargo bike (dogs in the box) or
  walk the group on foot (bike parked) — with the mode constrained by each
  dog's physical transport state.

A plan is a single tour `HomeStart → … → HomeEnd` whose events are timed. The
solver builds it constructively and the constraints below decide whether a
candidate tour is **feasible**; infeasible-to-place options become *conflicts*.

### Notation used throughout

- `E = ⟨e₀, e₁, …, e_{n-1}⟩` — the ordered event sequence, with `e₀ = HomeStart`
  and `e_{n-1} = HomeEnd`. Events are of type `RouteEvent`
  (`domain/dayplan/RouteEvent.kt`).
- `τ(e)` — the start time of event `e` in seconds since midnight
  (`RouteEvent.timeSeconds`).
- **Leg `i`** (for `i ≥ 1`) — the travel from `e_{i-1}` to `e_i`. Its mode is
  `foot(i) ∈ {true,false}` (`true` = on foot) and its travel time is
  `travel(i)`.
- `aboard(i)` — the set of dogs that have been picked up before leg `i` and not
  yet dropped off, i.e. the dogs in transit *during* leg `i`. A dog is **not**
  aboard on the leg that fetches it (its pickup) but **is** aboard on the leg
  that delivers it (its dropoff).
- For a dog `g`: `w(g)` weight (kg), `cargo(g) = inCargoBike(g)` and
  `pack(g) = inBackpack(g)`, each in `{Yes, No, NotTested}`; `long(g) =
  allowLongerWalk(g)`.
- A **span** `s = (p, d)` pairs a `Pickup` `p` with its matching `Dropoff` `d`
  for one walk occurrence (FIFO per dog), with dog `dog(s)`, rule `rule(s)`,
  required seconds `req(s) = rule(s).durationMinutes · 60`
  (`domain/dayplan/WalkSpans.kt`, `walkSpans()`).
- `Walks(s)` — the `Walk` events inside `[τ(p), τ(d)]` whose dog list contains
  `dog(s)`.
- Constants from `AppSettings` / the `DayPlanner` constructor: `Cap`
  (`capacityKg`), `G_max` (`maxGroupSize`, default 4), `G_pref`
  (`preferredGroupSize`, default 3), `dayStart`, `dayEnd`, `buffer`
  (`stopBufferSeconds`), `v_bike`, `v_foot`, `overhead` (`bikeOverheadSeconds`).

---

## 2. Decision variables

The search makes only three *free* choices (V1–V3). Everything else (V4–V6) is
a **derived** variable: `DayPlanner.retimeAndCost` computes it deterministically
from V1–V3, so a candidate is fully defined once the placements and grouping are
fixed.

The **fixed frame** is not variable: `HomeStart`/`HomeEnd` are pinned at the
day's ends, and any `Appointment` is pre-placed at its given time; the solver
schedules dogs *around* these (`domain/dayplan/DayPlanner.kt`, `plan` →
`baseEvents`).

### V1 — Alternative selection (placement)

**English.** Each thing to schedule is a `WalkOption` — either one required
walk or an exclusive *choice* of alternatives for the same dog ("end of morning
OR end of afternoon"). For every option the solver places **exactly one**
alternative, or leaves the option unplaced (→ a conflict).

**Logic.** For each option `o ∈ O` with alternatives `alt(o)`:

```
x(o) ∈ alt(o) ∪ {⊥}        (⊥ = unplaced → conflict)
```

**Code.** `domain/dayplan/WalkOption.kt`; selection in
`DayPlanner.tryInsertOption`:

```kotlin
data class WalkOption(val alternatives: List<PlannedWalk>) { … }
// tryInsertOption: try each alternative and keep the cheapest feasible result,
// so an exclusive choice schedules exactly one walk.
for (alternative in option.alternatives) {
    val placed = tryInsert(events, alternative, matrix, constraints) ?: continue
    …
}
```

### V2 — Event sequence (route order & insertion positions)

**English.** The order of all `Pickup`, `Walk`, and `Dropoff` events along the
single tour (between the fixed `HomeStart`/`Appointment`/`HomeEnd` anchors). A
placement chooses where in the current sequence the dog's pickup, walk, and
dropoff go.

**Logic.** `E` is a permutation/interleaving of the placed events with `e₀ =
HomeStart`, `e_{n-1} = HomeEnd`, and (see C1) every pickup before its dropoff.

**Code.** `domain/dayplan/DayPlanner.kt`, the three insertion modes
(`insertNewTriplet`, `insertJoinWalk`, `insertRideAlong`) called from
`tryInsert`:

```kotlin
mutable.add(pickPos, RouteEvent.Pickup(0, loc, walk.dog, walk.rule))
mutable.add(walkPos, RouteEvent.Walk(0, loc, listOf(walk.dog), walk.rule.durationMinutes * 60))
mutable.add(dropPos, RouteEvent.Dropoff(0, loc, walk.dog))
```

### V3 — Walk grouping (membership)

**English.** Which dogs share each `Walk`. A dog can get its own walk (Mode A),
join an existing walk and extend it (Mode B), or ride along one or more existing
walks without lengthening them so its required time is split across them
(Mode C).

**Logic.** Each `Walk` event `w` carries a dog set `dogs(w) ⊆ D`; grouping is
the assignment of dogs to walk events. (Bounded by C6/C7.)

**Code.** `RouteEvent.Walk.dogs`; the join/ride-along that mutate it:

```kotlin
// Mode B (insertJoinWalk): add the dog and extend the walk.
val combinedDogs = existing.dogs + walk.dog
val updatedWalk = existing.copy(dogs = combinedDogs, durationSeconds = combinedDuration)
// Mode C (insertRideAlong): tag along without extending.
if (e is RouteEvent.Walk && i in pickPos until dropPos) result.add(e.copy(dogs = e.dogs + walk.dog))
```

### V4 — Leg transport mode (derived)

**English.** For every travel leg, whether the walker rides the bike (dogs in
the box) or walks the group on foot (bike parked). Chosen by the retimer as the
faster mode, *subject to* the transport constraint C9.

**Logic.** `foot : {1,…,n-1} → {true, false}`, with C9 governing which values
are admissible. `HomeEnd` is forced to bike (the bike must end at home).

**Code.** `domain/dayplan/DayPlanner.kt`, `retimeAndCost` phase 1 (the group
cap is the separate hard constraint C7, not part of the mode choice):

```kotlin
val canBike = canRideBike(aboard)
if (event !is RouteEvent.HomeEnd && (footTime <= bikeTotal || !canBike)) {
    byFoot[i] = true; travel[i] = footTime; …
} else {
    travel[i] = bikeTotal; returnToBike[i] = back; …
}
```

### V5 — Dwell duration per walk (derived)

**English.** How long each in-place `Walk` lasts. A dog's on-foot travel while
aboard already counts as walk time (true double-duty), so the in-place dwell
only makes up the remaining deficit; a shared walk lasts as long as its
most-demanding member still needs.

**Logic.** `δ : Walks → ℕ`, `δ(w) = max over members of that member's remaining
deficit served by `w`` (see C4 for the duration accounting).

**Code.** `domain/dayplan/DayPlanner.kt`, `effectiveDwells` (result written into
`Walk.durationSeconds`):

```kotlin
val dwell = effectiveDwells(events, byFoot, travel, returnToBike)
…
is RouteEvent.Walk -> event.copy(…, durationSeconds = dwell[i], …)
```

### V6 — Event times (derived)

**English.** The absolute clock time of every event, obtained by walking the
tour forward and accumulating travel, dwell, stop buffers, and any waits for a
window to open.

**Logic.** `τ : E → ℕ` defined by the timing recurrence in C11.

**Code.** `domain/dayplan/DayPlanner.kt`, `retimeAndCost` phase 3 (assigns
`timeSeconds`).

---

## 3. Constraints

A plan is **feasible** iff every constraint below holds. Hard constraints C1–C9
are checked either structurally (V2/WalkSpans) or by a `PlanningConstraint`
(`domain/dayplan/constraints/`), applied after retiming via:

```kotlin
// DayPlanner.violation(events): first constraint to object wins.
private fun List<PlanningConstraint>.violation(events: List<RouteEvent>): String? {
    for (c in this) c.violation(events)?.let { return it }
    return null
}
```

C10–C11 are enforced inside `retimeAndCost` (a `null` return = infeasible
candidate). The constraint set assembled per plan:

```kotlin
val constraints = listOf(
    CapacityConstraint(capacityKg), TimeWindowConstraint(), WalkDurationConstraint(),
    IncompatibilityConstraint(incompatibilities), NoDogLeftBehindConstraint(),
    GroupSizeConstraint(maxGroupSize), AppointmentConstraint(),
)
```

### C1 — Pickup/dropoff pairing and ordering

**English.** Every placed walk has exactly one pickup and one matching dropoff,
and the pickup comes before the dropoff. The same dog walked twice in a day has
two independent spans (FIFO pairing).

**Logic.** For every span `s = (p, d)`:

```
d ≠ null  ∧  τ(p) ≤ τ(d)
∧  each Pickup pairs with exactly one later Dropoff of the same dog (FIFO)
```

**Code.** Pairing in `domain/dayplan/WalkSpans.kt` (`walkSpans()`); ordering
checked in `TimeWindowConstraint`:

```kotlin
is RouteEvent.Dropoff ->
    open[event.dog.id]?.removeFirstOrNull()?.let { spans.add(WalkSpan(it, event)) }
…
if (dropoff.timeSeconds < pickup.timeSeconds) return "${pickup.dog.name} dropoff is before its pickup"
```

### C2 — Cargo capacity

**English.** The total weight of dogs in the box never exceeds the cargo
capacity. A single dog heavier than capacity is itself a violation. (Counts all
aboard dogs against the box — slightly conservative for a backpack dog; see C9.)

**Logic.** For every leg `i`:

```
Σ_{g ∈ aboard(i)} w(g)  ≤  Cap
```

**Code.** `domain/dayplan/constraints/CapacityConstraint.kt`:

```kotlin
is RouteEvent.Pickup -> {
    weight += e.dog.weightKg
    if (weight > capacityKg) return "Capacity exceeded after picking up ${e.dog.name} …"
}
is RouteEvent.Dropoff -> weight -= e.dog.weightKg
```

### C3 — Time windows

**English.** Per occurrence, three independent optional bounds: the pickup is no
earlier than `earliestStart`; the dog's **first walk** starts no later than
`latestStart` (bounding the actual walk, not the pickup); the dropoff is no
later than `latestEnd`.

**Logic.** For every span `s = (p, d)` with rule `r = rule(s)`, letting
`firstWalk(s) = min_{w ∈ Walks(s)} τ(w)`:

```
(r.earliestStart ≠ ⊥  ⇒  τ(p)         ≥ r.earliestStart)
(r.latestStart   ≠ ⊥  ⇒  firstWalk(s) ≤ r.latestStart)
(r.latestEnd     ≠ ⊥  ⇒  τ(d)         ≤ r.latestEnd)
```

**Code.** `domain/dayplan/constraints/TimeWindowConstraint.kt`:

```kotlin
if (earliest != null && pickup.timeSeconds < earliest.toSecondOfDay()) return …
if (firstWalk != null && firstWalk.timeSeconds > latestStart.toSecondOfDay()) return …
if (latest != null && dropoff.timeSeconds > latest.toSecondOfDay()) return …
```

(The `earliestStart` lower bound is also actively *waited for* in
`retimeAndCost` phase 3; see C11.)

### C4 — Walk duration (the split rule)

**English.** Per occurrence, the dog's total walked time — the sum of the
in-place walks it joins **plus** the on-foot travel it does while aboard — must
be at least the required duration. For a dog with `allowLongerWalk = false`
(puppies, injuries) the total must *equal* the requirement, never exceed it.

**Logic.** For every span `s = (p, d)`, with
`walked(s) = Σ_{w ∈ Walks(s)} δ(w) + footCredit(s)`:

```
walked(s) ≥ req(s)
∧  ( ¬long(dog(s))  ⇒  walked(s) = req(s) )
```

where `footCredit(s)` is the on-foot seconds the dog accrues while aboard
(full foot legs + the walk-back portion of bike legs), excluding the leg that
fetches it.

**Code.** `domain/dayplan/constraints/WalkDurationConstraint.kt`, with
`footCreditSeconds` from `domain/dayplan/WalkSpans.kt`:

```kotlin
val totalWalked = dwellWalked + span.footCreditSeconds(events)
val required = pickup.rule.durationMinutes * 60
if (totalWalked < required) return "${pickup.dog.name} walked … needs …"
if (!pickup.dog.allowLongerWalk && totalWalked > required) return "… but cap is …"
```

### C5 — Incompatibility

**English.** Two dogs marked incompatible may never be together at all — not in
the box, and not on the same walk. The check is on simultaneous *aboard*-ness
(picked up and not yet dropped), which already covers walking together: every
walk participant is aboard (C6), so two dogs on one walk are aboard at the same
time and are caught.

**Logic.** Let `Inc ⊆ D × D` be the symmetric incompatibility set. At every
point in the route (write `aboard(t)` for the dogs picked up and not yet
dropped at time `t`):

```
∀ t. ∀ g, h ∈ aboard(t):  {g, h} ∉ Inc
```

**Code.** `domain/dayplan/constraints/IncompatibilityConstraint.kt`:

```kotlin
is RouteEvent.Pickup -> {
    for (existing in inBag) if (newId.toCanonicalPair(existing) in pairs)
        return "${event.dog.name} and ${dogNames[existing]} cannot share a trip"
    inBag.add(newId)
}
is RouteEvent.Dropoff -> inBag.remove(event.dog.id)
```

### C6 — No dog left behind

**English.** The walker never leaves a dog sitting in the bike while walking the
others: at every walk, every aboard dog takes part.

**Logic.** For every `Walk` event `w` at index `i`:

```
aboard(i) ⊆ dogs(w)
```

**Code.** `domain/dayplan/constraints/NoDogLeftBehindConstraint.kt`:

```kotlin
is RouteEvent.Walk -> {
    val walking = event.dogs.mapTo(HashSet()) { it.id }
    val left = inBag.entries.firstOrNull { it.key !in walking }
    if (left != null) return "${left.value} is in the bike but not in the walk"
}
```

### C7 — Group size (hard ceiling)

**English.** At most `maxGroupSize` (4) dogs in the walker's care at any
moment — both in any single walk AND aboard between walks. The cap holds in
every mode: picking another dog up or carrying some back never leaves the rest
behind (they all come along), and a bike breakdown means the whole group must
be walkable on foot. So the bound is not just per-walk; it holds at all times,
which in turn bounds the on-foot walk-back to a parked bike for free. The real
working cap is 3; 4 is a forced exception (a *signal* of too many dogs that
day), expressed as a soft penalty in the objective, not here.

**Logic.** With `aboard(t)` the dogs picked up and not yet dropped at time `t`:

```
( ∀ t. |aboard(t)| ≤ G_max )   ∧   ( ∀ Walk w. |dogs(w)| ≤ G_max )
```

The aboard bound implies the per-walk bound (a walk's dogs are all aboard);
both are checked so the message points at the right spot.

**Code.** `domain/dayplan/constraints/GroupSizeConstraint.kt`:

```kotlin
is RouteEvent.Pickup -> {
    aboard[event.dog.id] = event.dog.name
    if (aboard.size > maxDogs)
        return "${aboard.size} dogs aboard after picking up ${event.dog.name}, over the maximum of $maxDogs"
}
is RouteEvent.Dropoff -> aboard.remove(event.dog.id)
is RouteEvent.Walk -> if (event.dogs.size > maxDogs)
    return "A walk has ${event.dogs.size} dogs, over the maximum of $maxDogs"
```

### C8 — Appointments

**English.** For each fixed appointment, the walker must arrive by its start
time and have no dog aboard during it.

**Logic.** For every `Appointment` event `e`:

```
τ(e) ≤ startSeconds(e)   ∧   aboard(at e) = ∅
```

**Code.** `domain/dayplan/constraints/AppointmentConstraint.kt`:

```kotlin
is RouteEvent.Appointment -> {
    if (e.timeSeconds > e.startSeconds) return "${e.label} would start late …"
    if (aboard > 0) return "$aboard dog(s) aboard during ${e.label}"
}
```

### C9 — Transport mode feasibility (cargo box / backpack)

**English.** Each dog's physical transport state governs whether a leg may be
**cycled**: only when *every* aboard dog can be carried — in the cargo box
(`inCargoBike = Yes`, total box weight bounded by C2) or in the backpack
(`inBackpack = Yes`), and the backpack holds **at most one** dog. A dog that can
use neither forces the leg on foot (walking is always available, capped only by
the group size C7). `No` and `NotTested` both block a channel (conservative).
The final `HomeEnd` leg is forced to bike so the bike ends at home.

**Logic.** Define, for a set `A` of aboard dogs,

```
canBike(A) ≡ ( ∀ g ∈ A: cargo(g)=Yes ∨ pack(g)=Yes )
             ∧ |{ g ∈ A : cargo(g) ≠ Yes }| ≤ 1      // at most one backpack dog
```

Then for every leg `i`:

```
( foot(i)=false ⇒ canBike(aboard(i)) )           // riding ⇒ all carriable
∧ ( e_i = HomeEnd ⇒ foot(i)=false )                // bike ends at home
```

(Group size on any leg — foot or the walk-back of a bike leg — is C7, not here.)

**Code.** `domain/dayplan/DayPlanner.kt`, `canRideBike` + `retimeAndCost`
phase 1:

```kotlin
private fun canRideBike(aboard: List<Dog>): Boolean {
    var backpackDogs = 0
    for (dog in aboard) when {
        dog.inCargoBike == TransportState.Yes -> Unit       // rides in the box
        dog.inBackpack == TransportState.Yes -> backpackDogs++
        else -> return false
    }
    return backpackDogs <= 1
}
…
val canBike = canRideBike(aboard)
if (event !is RouteEvent.HomeEnd && (footTime <= bikeTotal || !canBike)) { /* foot */ }
else { /* bike */ }
```

### C10 — Day-end cutoff

**English.** No event may be scheduled after the end of the working day.

**Logic.** For every event `e`:  `τ(e) ≤ dayEnd`.

**Code.** `domain/dayplan/DayPlanner.kt`, `retimeAndCost` phase 3:

```kotlin
t += placed.durationAtSeconds(stopBufferSeconds)
if (t > dayEndSeconds) return null
```

### C11 — Time consistency (the timing recurrence)

**English.** Times are not free: each event starts after the previous one
finishes, plus travel to reach it, and the walker waits when arriving before a
window opens (a pickup's `earliestStart`, a break window, an appointment start).
The day starts no earlier than `dayStart`, and `HomeStart` is pulled forward to
"leave just in time".

**Logic.** With dwell-or-service `dur(e)` (`durationAtSeconds`: stop buffer for
pickups/dropoffs, `δ` for walks, the fixed length for breaks/appointments, 0 at
home), travel `travel(i)`, and per-event lower bounds `open(e)` (earliestStart /
break-window / appointment-start, else 0):

```
τ(e_0) = dayStart
τ(e_i) = max( open(e_i),  τ(e_{i-1}) + dur(e_{i-1}) + travel(i) )
travel(i) = foot(i) ? meters(i)/v_foot : meters(i)/v_bike + overhead
```

**Code.** `domain/dayplan/DayPlanner.kt`, `retimeAndCost` phase 3 + the
speed helpers:

```kotlin
if (i > 0) t += travel[i]
if (event is RouteEvent.Pickup) { val earliest = …; if (earliest != null && earliest > t) t = earliest }
if (event is RouteEvent.Break && event.earliestStartSeconds > t) t = event.earliestStartSeconds
if (event is RouteEvent.Appointment && event.startSeconds > t) t = event.startSeconds
…
t += placed.durationAtSeconds(stopBufferSeconds)
```
```kotlin
private fun DistanceMatrix.bikeSeconds(from, to) =
    if (meters == 0) 0 else (meters / cyclingMetersPerSecond).toInt() + bikeOverheadSeconds
private fun DistanceMatrix.footSeconds(from, to) = (metersBetween(from, to) / walkingMetersPerSecond).toInt()
```

---

## 4. Objective (for completeness — not a feasibility constraint)

Among feasible plans the search prefers, **lexicographically**: (1) fewer
unplaced options (conflicts), then (2) lower `score()`:

```
score = elapsed_seconds
      + cyclingWeight · cycling_seconds
      + OVERSIZE_PENALTY · Σ_w max(0, |dogs(w)| − G_pref)
```

The oversize term is how the soft "prefer at most 3 per walk" preference is expressed
(C7 is only the hard ceiling of 4). `domain/dayplan/DayPlanner.kt`:

```kotlin
private fun Solution.isBetterThan(other: Solution): Boolean = when {
    unplaced.size != other.unplaced.size -> unplaced.size < other.unplaced.size
    else -> score() < other.score()
}
private fun Solution.score(): Long =
    elapsedSeconds().toLong() + (cyclingWeight * cyclingSeconds()).toLong() +
        dogsOverPreferred().toLong() * OVERSIZE_PENALTY_SECONDS
```

---

## 5. Known modelling approximations

These are deliberate simplifications, documented so the spec is honest:

- **Capacity counts every aboard dog as box weight** (C2), regardless of mode
  or channel. On a foot leg every aboard dog walks on a leash (nobody is in the
  box — not even a backpack-capable dog), yet all still count against `Cap`; and
  on a bike leg the backpack dog is on the walker's back, not in the box, yet
  also counts. Conservative; see "weakness #4" in `docs/STATUS.md`.
- **The box has no count cap, only a weight cap** (C2). Physically ~3 dogs fit;
  the model only limits weight, so light dogs could in principle exceed that.
  The group cap C7 (`|aboard| ≤ G_max`) bounds it in practice (≤ 4 aboard
  anywhere), so this never bites today, but a true "max 3 in the box" count is
  not modelled.
- **Foot distance = cycling distance** (`DistanceMatrix` reuses the BRouter
  cycling distance for walking). Good enough for the short hops where walking is
  chosen.
- **Single continuous tour** — one `HomeStart → … → HomeEnd`; no multi-trip
  structure.
- **`dayStart`/`dayEnd` fixed** at 08:00–20:00 in the `DayPlanner` constructor.
