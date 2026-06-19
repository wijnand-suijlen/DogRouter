package app.dogrouter.domain.dayplan

import app.dogrouter.domain.dayplan.constraints.CapacityConstraint
import app.dogrouter.domain.dayplan.constraints.IncompatibilityConstraint
import app.dogrouter.domain.dayplan.constraints.TimeWindowConstraint
import app.dogrouter.domain.dayplan.constraints.WalkDurationConstraint
import app.dogrouter.domain.planner.PlannedWalk
import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.domain.routing.RoutingProvider
import java.time.LocalDate

/**
 * Composes a day route from a list of scheduled walks using a greedy
 * insertion heuristic with explicit walk events:
 *
 *  1. Build a distance matrix between home + every walk's location.
 *  2. Start from `[HomeStart, HomeEnd]`.
 *  3. For each walk in priority order (tightest deadline first), try
 *     every feasible insertion and keep the cheapest one. Two modes
 *     are considered:
 *       - **New triplet** — pickup + own walk event + dropoff at a
 *         single chosen gap.
 *       - **Join** — pickup before some existing walk and dropoff
 *         after, adding the dog to that walk and extending its
 *         duration when needed.
 *  4. Walks the planner cannot place go into [DayRoute.conflicts].
 *
 * Constraints (capacity, time windows, walk-duration min/max, dog
 * incompatibilities) are pluggable via [PlanningConstraint] so future
 * rules (lunch breaks, end-of-day cutoff, ...) can be added without
 * touching the algorithm.
 */
class DayPlanner(
    private val routingProvider: RoutingProvider,
    private val home: GeoPoint?,
    private val capacityKg: Float,
    private val stopBufferSeconds: Int,
    private val cyclingSpeedKmh: Float,
    private val incompatibilities: Set<Pair<String, String>>,
    private val dayStartSeconds: Int = 8 * 3600,
    private val dayEndSeconds: Int = 20 * 3600,
) {
    suspend fun plan(date: LocalDate, walks: List<PlannedWalk>): DayRoute {
        if (home == null) {
            return DayRoute(date, emptyList(), 0, 0, walks.map { PlanConflict(it.dog, "Home address not set") })
        }
        if (walks.isEmpty()) {
            return DayRoute(date, listOf(homeStart(), homeEnd(dayStartSeconds)), 0, 0, emptyList())
        }
        val routable = walks.filter { it.dog.latitude != null && it.dog.longitude != null }
        val unroutable = walks.filter { it.dog.latitude == null || it.dog.longitude == null }
            .map { PlanConflict(it.dog, "No coordinates for this address") }

        if (!routingProvider.isReady()) {
            return DayRoute(
                date = date,
                events = listOf(homeStart(), homeEnd(dayStartSeconds)),
                totalCyclingSeconds = 0,
                totalWalkingSeconds = 0,
                conflicts = unroutable + routable.map { PlanConflict(it.dog, "Routing data not installed") },
            )
        }

        val points = (routable.map { it.geoPoint() } + home).toSet()
        val matrix = DistanceMatrix.build(points, routingProvider, cyclingSpeedKmh)
        val constraints = listOf(
            CapacityConstraint(capacityKg),
            TimeWindowConstraint(),
            WalkDurationConstraint(),
            IncompatibilityConstraint(incompatibilities),
        )

        val sorted = routable.sortedBy { it.deadlineSeconds() }
        var events: List<RouteEvent> = listOf(homeStart(), homeEnd(dayStartSeconds))
        val conflicts = mutableListOf<PlanConflict>()
        conflicts.addAll(unroutable)

        for (walk in sorted) {
            val placed = tryInsert(events, walk, matrix, constraints)
            if (placed != null) {
                events = placed
            } else {
                conflicts.add(PlanConflict(walk.dog, "No feasible slot in the day"))
            }
        }

        val totalCycling = events.zipWithNext { a, b ->
            if (b is RouteEvent.Walk) 0 else matrix.secondsBetween(a.location, b.location)
        }.sum()
        val totalWalking = events.filterIsInstance<RouteEvent.Walk>().sumOf { it.durationSeconds }

        return DayRoute(
            date = date,
            events = events,
            totalCyclingSeconds = totalCycling,
            totalWalkingSeconds = totalWalking,
            conflicts = conflicts,
        )
    }

    private fun tryInsert(
        events: List<RouteEvent>,
        walk: PlannedWalk,
        matrix: DistanceMatrix,
        constraints: List<PlanningConstraint>,
    ): List<RouteEvent>? {
        var best: List<RouteEvent>? = null
        var bestCost = Int.MAX_VALUE

        // Mode A: new walk for this dog only.
        for (pickPos in 1 until events.size) {
            for (walkPos in pickPos + 1..events.size) {
                for (dropPos in walkPos + 1..events.size + 1) {
                    val candidate = insertNewTriplet(events, walk, pickPos, walkPos, dropPos, matrix)
                    val (eventList, cost) = candidate ?: continue
                    if (cost >= bestCost) continue
                    if (constraints.violation(eventList) != null) continue
                    best = eventList
                    bestCost = cost
                }
            }
        }

        // Mode B: join an existing walk event. dropPos uses original-list
        // indices; the actual insertion happens at dropPos + 1 in the
        // post-pickup-insert list, and must stay strictly below HomeEnd
        // so we cap at events.size - 1 (not events.size).
        for ((walkIdx, existing) in events.withIndex()) {
            if (existing !is RouteEvent.Walk) continue
            for (pickPos in 1..walkIdx) {
                for (dropPos in walkIdx + 1 until events.size) {
                    val candidate = insertJoinWalk(events, walk, pickPos, walkIdx, dropPos, matrix)
                    val (eventList, cost) = candidate ?: continue
                    if (cost >= bestCost) continue
                    if (constraints.violation(eventList) != null) continue
                    best = eventList
                    bestCost = cost
                }
            }
        }

        // Mode C: ride along — pick the dog up, let it tag along through one
        // or more existing walks WITHOUT lengthening them, then drop it off.
        // The walk-duration constraint sums the walks in the span, so this is
        // how a long required walk gets split across several shorter group
        // walks the walker is doing anyway (cheap: no extra walking time).
        for (pickPos in 1 until events.size - 1) {
            for (dropPos in pickPos + 1 until events.size) {
                val spannedWalks = (pickPos until dropPos).count { events[it] is RouteEvent.Walk }
                if (spannedWalks == 0) continue
                val candidate = insertRideAlong(events, walk, pickPos, dropPos, matrix)
                val (eventList, cost) = candidate ?: continue
                if (cost >= bestCost) continue
                if (constraints.violation(eventList) != null) continue
                best = eventList
                bestCost = cost
            }
        }

        return best
    }

    private fun insertRideAlong(
        events: List<RouteEvent>,
        walk: PlannedWalk,
        pickPos: Int,
        dropPos: Int,
        matrix: DistanceMatrix,
    ): Pair<List<RouteEvent>, Int>? {
        val loc = walk.geoPoint()
        val result = mutableListOf<RouteEvent>()
        for (i in 0..events.size) {
            if (i == pickPos) result.add(RouteEvent.Pickup(0, loc, walk.dog, walk.rule))
            if (i == dropPos) result.add(RouteEvent.Dropoff(0, loc, walk.dog))
            if (i < events.size) {
                val e = events[i]
                // Add the dog to every existing walk inside the carry span;
                // their durations stay unchanged (the dog only rides along).
                if (e is RouteEvent.Walk && i in pickPos until dropPos) {
                    result.add(e.copy(dogs = e.dogs + walk.dog))
                } else {
                    result.add(e)
                }
            }
        }
        return retimeAndCost(result, matrix)
    }

    private fun insertNewTriplet(
        events: List<RouteEvent>,
        walk: PlannedWalk,
        pickPos: Int,
        walkPos: Int,
        dropPos: Int,
        matrix: DistanceMatrix,
    ): Pair<List<RouteEvent>, Int>? {
        val loc = walk.geoPoint()
        val mutable = events.toMutableList()
        // Insert markers; times re-derived after.
        mutable.add(pickPos, RouteEvent.Pickup(0, loc, walk.dog, walk.rule))
        mutable.add(walkPos, RouteEvent.Walk(0, loc, listOf(walk.dog), walk.rule.durationMinutes * 60))
        mutable.add(dropPos, RouteEvent.Dropoff(0, loc, walk.dog))
        return retimeAndCost(mutable, matrix)
    }

    private fun insertJoinWalk(
        events: List<RouteEvent>,
        walk: PlannedWalk,
        pickPos: Int,
        existingWalkIdx: Int,
        dropPos: Int,
        matrix: DistanceMatrix,
    ): Pair<List<RouteEvent>, Int>? {
        val loc = walk.geoPoint()
        val existing = events[existingWalkIdx] as RouteEvent.Walk
        val combinedDuration = maxOf(existing.durationSeconds, walk.rule.durationMinutes * 60)
        val combinedDogs = existing.dogs + walk.dog
        val updatedWalk = existing.copy(dogs = combinedDogs, durationSeconds = combinedDuration)

        val mutable = events.toMutableList()
        mutable[existingWalkIdx] = updatedWalk
        // Insert pickup (shifts existing indices ≥ pickPos by 1).
        mutable.add(pickPos, RouteEvent.Pickup(0, loc, walk.dog, walk.rule))
        // Dropoff position relative to original was `dropPos`; after pickup
        // insertion all indices ≥ pickPos moved by 1, so dropoff lands at
        // dropPos + 1 in the new list.
        val adjustedDrop = dropPos + 1
        if (adjustedDrop > mutable.size) return null
        mutable.add(adjustedDrop, RouteEvent.Dropoff(0, loc, walk.dog))
        return retimeAndCost(mutable, matrix)
    }

    /**
     * Walk through [events] forward, filling in [RouteEvent.timeSeconds]
     * for each based on cycling time from the previous location and the
     * time spent at the previous event.
     *
     * If a pickup would arrive before its rule's earliestStart, the
     * walker waits at the pickup location until the window opens — the
     * extra idle time pushes every subsequent event later. Without this
     * the planner refuses every walk whose earliestStart is later than
     * the trivial 8 AM + travel arrival time.
     *
     * Returns the retimed list together with the total elapsed seconds
     * (HomeStart to HomeEnd), or null if any event would land after
     * [dayEndSeconds].
     */
    private fun retimeAndCost(events: MutableList<RouteEvent>, matrix: DistanceMatrix): Pair<List<RouteEvent>, Int>? {
        var t = dayStartSeconds
        val retimed = mutableListOf<RouteEvent>()
        for ((i, event) in events.withIndex()) {
            if (i > 0) {
                val prev = retimed.last()
                val travel = if (event is RouteEvent.Walk) 0 else matrix.secondsBetween(prev.location, event.location)
                t += travel
            }
            if (event is RouteEvent.Pickup) {
                val earliest = event.rule.earliestStart?.toSecondOfDay()
                if (earliest != null && earliest > t) t = earliest
            }
            val placed: RouteEvent = when (event) {
                is RouteEvent.HomeStart -> event.copy(timeSeconds = t)
                is RouteEvent.HomeEnd -> event.copy(timeSeconds = t)
                is RouteEvent.Pickup -> event.copy(timeSeconds = t)
                is RouteEvent.Dropoff -> event.copy(timeSeconds = t)
                is RouteEvent.Walk -> event.copy(timeSeconds = t)
            }
            t += placed.durationAtSeconds(stopBufferSeconds)
            if (t > dayEndSeconds) return null
            retimed.add(placed)
        }

        // Push HomeStart forward to "leave home just in time": no point
        // standing at the first pickup waiting for its window to open.
        // Adjusting also shrinks the day's elapsed cost, which steers the
        // algorithm toward schedules with less idle time.
        val firstNonHome = retimed.getOrNull(1)
        if (firstNonHome != null && retimed[0] is RouteEvent.HomeStart) {
            val travelHomeToFirst = matrix.secondsBetween(retimed[0].location, firstNonHome.location)
            val effectiveLeave = maxOf(dayStartSeconds, firstNonHome.timeSeconds - travelHomeToFirst)
            retimed[0] = (retimed[0] as RouteEvent.HomeStart).copy(timeSeconds = effectiveLeave)
        }

        val cost = retimed.last().timeSeconds - retimed.first().timeSeconds
        return retimed to cost
    }

    private fun List<PlanningConstraint>.violation(events: List<RouteEvent>): String? {
        for (c in this) {
            c.violation(events)?.let { return it }
        }
        return null
    }

    private fun homeStart() = RouteEvent.HomeStart(dayStartSeconds, home!!)
    private fun homeEnd(t: Int) = RouteEvent.HomeEnd(t, home!!)

    private fun PlannedWalk.geoPoint(): GeoPoint =
        GeoPoint(dog.latitude!!, dog.longitude!!)

    private fun PlannedWalk.deadlineSeconds(): Int =
        rule.latestEnd?.toSecondOfDay() ?: Int.MAX_VALUE
}
