package app.dogrouter.domain.dayplan

import app.dogrouter.domain.dayplan.constraints.CapacityConstraint
import app.dogrouter.domain.dayplan.constraints.GroupSizeConstraint
import app.dogrouter.domain.dayplan.constraints.IncompatibilityConstraint
import app.dogrouter.domain.dayplan.constraints.NoDogLeftBehindConstraint
import app.dogrouter.domain.dayplan.constraints.TimeWindowConstraint
import app.dogrouter.domain.dayplan.constraints.WalkDurationConstraint
import app.dogrouter.domain.planner.PlannedWalk
import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.domain.routing.RoutingProvider
import java.time.LocalDate
import kotlin.random.Random

/**
 * Cost added per dog beyond the preferred group size. Far larger than any
 * realistic day length in seconds, so the planner never grows a group past
 * the preferred size merely to save time — only when it lets a dog be
 * placed that otherwise could not (fewer conflicts win first).
 */
private const val OVERSIZE_PENALTY_SECONDS = 1_000_000L

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
    // On-foot group pace, and the fixed overhead added to every bike ride
    // (load dogs in the box, unlock, helmet, and the reverse on arrival).
    private val walkingSpeedKmh: Float = 3f,
    private val bikeOverheadSeconds: Int = 0,
    private val dayStartSeconds: Int = 8 * 3600,
    private val dayEndSeconds: Int = 20 * 3600,
    private val restarts: Int = 60,
    // Walk-group size: never more than [maxGroupSize] dogs at once (hard),
    // and a strong preference for at most [preferredGroupSize] (soft, via a
    // planning-cost penalty so bigger groups are used only when needed).
    private val maxGroupSize: Int = 4,
    private val preferredGroupSize: Int = 3,
) {
    /**
     * Build a day route. [seed] drives the randomised multi-start search:
     * the same seed and inputs always yield the same plan (so it can be
     * cached), while a different seed explores other orderings — the hook
     * the Today "refresh" button uses to offer alternative plans.
     */
    suspend fun plan(date: LocalDate, options: List<WalkOption>, seed: Long = 0L): DayRoute {
        if (home == null) {
            return DayRoute(date, emptyList(), 0, 0, options.map { PlanConflict(it.dog, "Home address not set") })
        }
        if (options.isEmpty()) {
            return DayRoute(date, listOf(homeStart(), homeEnd(dayStartSeconds)), 0, 0, emptyList())
        }
        val routable = options.filter { it.dog.latitude != null && it.dog.longitude != null }
        val unroutable = options.filter { it.dog.latitude == null || it.dog.longitude == null }
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

        val points = (routable.map { GeoPoint(it.dog.latitude!!, it.dog.longitude!!) } + home).toSet()
        val matrix = DistanceMatrix.build(points, routingProvider)
        val constraints = listOf(
            CapacityConstraint(capacityKg),
            TimeWindowConstraint(),
            WalkDurationConstraint(),
            IncompatibilityConstraint(incompatibilities),
            NoDogLeftBehindConstraint(),
            GroupSizeConstraint(maxGroupSize),
        )

        // Multi-start: build several plans from different insertion orders
        // and keep the best. The deterministic deadline order is always one
        // of them, so more restarts never produce a worse result. The matrix
        // is built once above; each restart is pure in-memory work.
        val rng = Random(seed)
        val deadlineOrder = routable.sortedBy { it.deadlineSeconds() }
        var best: DayRoute? = null
        repeat(restarts.coerceAtLeast(1)) { iteration ->
            val order = if (iteration == 0) deadlineOrder else routable.shuffled(rng)
            val candidate = buildOnce(date, order, unroutable, matrix, constraints)
            if (best == null || candidate.isBetterThan(best!!)) best = candidate
        }
        return best!!
    }

    /**
     * A plan is better when it leaves fewer walks unplaced; then when its
     * [score] is lower. The score adds a big penalty per dog over the
     * preferred group size, so larger groups are used only when they place
     * a dog that otherwise could not go — never just to shorten the day.
     */
    private fun DayRoute.isBetterThan(other: DayRoute): Boolean = when {
        conflicts.size != other.conflicts.size -> conflicts.size < other.conflicts.size
        else -> score() < other.score()
    }

    private fun DayRoute.score(): Long =
        elapsedSeconds().toLong() + dogsOverPreferred().toLong() * OVERSIZE_PENALTY_SECONDS

    private fun DayRoute.dogsOverPreferred(): Int =
        events.filterIsInstance<RouteEvent.Walk>()
            .sumOf { (it.dogs.size - preferredGroupSize).coerceAtLeast(0) }

    private fun DayRoute.elapsedSeconds(): Int =
        if (events.size >= 2) events.last().timeSeconds - events.first().timeSeconds else 0

    /** One greedy build over a fixed insertion [order] of options. */
    private fun buildOnce(
        date: LocalDate,
        order: List<WalkOption>,
        unroutable: List<PlanConflict>,
        matrix: DistanceMatrix,
        constraints: List<PlanningConstraint>,
    ): DayRoute {
        var events: List<RouteEvent> = listOf(homeStart(), homeEnd(dayStartSeconds))
        val conflicts = mutableListOf<PlanConflict>()
        conflicts.addAll(unroutable)

        for (option in order) {
            val placed = tryInsertOption(events, option, matrix, constraints)
            if (placed != null) {
                events = placed
            } else {
                val reason = if (option.isChoice) {
                    "No feasible slot for any of its time windows"
                } else {
                    "No feasible slot in the day"
                }
                conflicts.add(PlanConflict(option.dog, reason))
            }
        }

        val totalCycling = events.zipWithNext { a, b ->
            if (b is RouteEvent.Walk) 0 else matrix.cyclingOnlySeconds(a.location, b.location)
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

    /**
     * Place a [WalkOption]: try each alternative and keep the cheapest
     * feasible result, so an exclusive choice schedules exactly one walk.
     * Returns null when no alternative fits.
     */
    private fun tryInsertOption(
        events: List<RouteEvent>,
        option: WalkOption,
        matrix: DistanceMatrix,
        constraints: List<PlanningConstraint>,
    ): List<RouteEvent>? {
        var best: List<RouteEvent>? = null
        var bestCost = Int.MAX_VALUE
        for (alternative in option.alternatives) {
            val placed = tryInsert(events, alternative, matrix, constraints) ?: continue
            val cost = if (placed.size >= 2) placed.last().timeSeconds - placed.first().timeSeconds else 0
            if (cost < bestCost) {
                best = placed
                bestCost = cost
            }
        }
        return best
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
                val travel = if (event is RouteEvent.Walk) 0 else matrix.bikeSeconds(prev.location, event.location)
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
            val travelHomeToFirst = matrix.bikeSeconds(retimed[0].location, firstNonHome.location)
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

    private val cyclingMetersPerSecond: Double = (cyclingSpeedKmh / 3.6).coerceAtLeast(0.1)
    private val walkingMetersPerSecond: Double = (walkingSpeedKmh / 3.6).coerceAtLeast(0.1)

    /** Bike-ride time: cycling time plus the per-ride mount/dismount overhead. */
    private fun DistanceMatrix.bikeSeconds(from: GeoPoint, to: GeoPoint): Int =
        (metersBetween(from, to) / cyclingMetersPerSecond).toInt() + bikeOverheadSeconds

    /** Cycling time only (no overhead), for the displayed cycling total. */
    private fun DistanceMatrix.cyclingOnlySeconds(from: GeoPoint, to: GeoPoint): Int =
        (metersBetween(from, to) / cyclingMetersPerSecond).toInt()

    /** On-foot time between two points (no overhead). */
    private fun DistanceMatrix.footSeconds(from: GeoPoint, to: GeoPoint): Int =
        (metersBetween(from, to) / walkingMetersPerSecond).toInt()

    private fun PlannedWalk.geoPoint(): GeoPoint =
        GeoPoint(dog.latitude!!, dog.longitude!!)

    private fun PlannedWalk.deadlineSeconds(): Int =
        listOfNotNull(rule.latestStart, rule.latestEnd)
            .minOfOrNull { it.toSecondOfDay() } ?: Int.MAX_VALUE

    /** Tightest deadline among the option's alternatives drives its priority. */
    private fun WalkOption.deadlineSeconds(): Int =
        alternatives.minOf { it.deadlineSeconds() }
}
