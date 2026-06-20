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
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.random.Random

/**
 * Cost added per dog beyond the preferred group size. Far larger than any
 * realistic day length in seconds, so the planner never grows a group past
 * the preferred size merely to save time — only when it lets a dog be
 * placed that otherwise could not (fewer conflicts win first).
 */
private const val OVERSIZE_PENALTY_SECONDS = 1_000_000L

/**
 * Bias of LNS worst removal toward the costliest options. The pick index is
 * `rand^bias * size`; >1 skews toward index 0 (the worst) while still
 * sometimes reaching down the list, so the ruin is greedy-leaning but not
 * deterministic.
 */
private const val WORST_REMOVAL_BIAS = 4.0

/**
 * Share of the progress bar given to building the distance matrix; the
 * solver fills the rest. The matrix (BRouter, one route per point-pair) is
 * the slow part on-device, so it gets the larger share even though it has
 * fewer steps than the solver — better matches the perceived wait.
 */
private const val MATRIX_PROGRESS_FRACTION = 0.6f

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
    // Large-neighbourhood search after the multi-start: each iteration ruins
    // a few placed walks and greedily re-inserts them, keeping the result if
    // it scores better. 0 disables LNS (pure multi-start, the old behaviour).
    // Default tuned against the seed sweep: day length plateaus by ~200
    // iterations (see docs/STATUS.md / docs/solver-baseline.md).
    private val lnsIterations: Int = DEFAULT_LNS_ITERATIONS,
    private val lnsRemoveMin: Int = 1,
    private val lnsRemoveMax: Int = 3,
    // Walk-group size: never more than [maxGroupSize] dogs at once (hard),
    // and a strong preference for at most [preferredGroupSize] (soft, via a
    // planning-cost penalty so bigger groups are used only when needed).
    private val maxGroupSize: Int = 4,
    private val preferredGroupSize: Int = 3,
) {
    companion object {
        /** LNS iterations the planner runs by default; see the ctor note. */
        const val DEFAULT_LNS_ITERATIONS = 200
    }

    /**
     * Build a day route. [seed] drives the randomised multi-start search:
     * the same seed and inputs always yield the same plan (so it can be
     * cached), while a different seed explores other orderings — the hook
     * the Today "refresh" button uses to offer alternative plans.
     */
    suspend fun plan(
        date: LocalDate,
        options: List<WalkOption>,
        seed: Long = 0L,
        // Reports computation progress as a 0..1 fraction plus the phase, for
        // a determinate progress bar. No-op by default (tests / harness).
        onProgress: (fraction: Float, phase: PlanPhase) -> Unit = { _, _ -> },
    ): DayRoute {
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
        val matrix = DistanceMatrix.build(points, routingProvider) { done, total ->
            onProgress(MATRIX_PROGRESS_FRACTION * done / total, PlanPhase.ROUTING)
        }
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
        // The solver phase fills the bar from MATRIX_PROGRESS_FRACTION to 1
        // across every multi-start build and every LNS iteration.
        val solverSteps = restarts.coerceAtLeast(1) + lnsIterations.coerceAtLeast(0)
        var solverDone = 0
        fun reportSolver() {
            solverDone++
            onProgress(
                MATRIX_PROGRESS_FRACTION + (1f - MATRIX_PROGRESS_FRACTION) * solverDone / solverSteps,
                PlanPhase.OPTIMISING,
            )
        }

        var best: Solution? = null
        repeat(restarts.coerceAtLeast(1)) { iteration ->
            val order = if (iteration == 0) deadlineOrder else routable.shuffled(rng)
            val candidate = buildOnce(order, matrix, constraints)
            if (best == null || candidate.isBetterThan(best!!)) best = candidate
            reportSolver()
        }

        // LNS: ruin-and-recreate around the incumbent, hill-climbing on score.
        var current = best!!
        repeat(lnsIterations.coerceAtLeast(0)) {
            val candidate = ruinAndRecreate(current, matrix, constraints, rng)
            if (candidate.isBetterThan(current)) current = candidate
            if (current.isBetterThan(best!!)) best = current
            reportSolver()
        }
        return best!!.toDayRoute(date, unroutable).withBikeFetches()
    }

    /**
     * One LNS iteration: remove a few placed options (random ruin), then
     * greedily re-insert them — plus any already-unplaced options, so a
     * freed slot can also rescue a conflict — with [tryInsertOption]. The
     * insertion order is shuffled so repeated iterations explore differently.
     * On the rare retime failure mid-ruin, abort and keep [current] unchanged.
     */
    private fun ruinAndRecreate(
        current: Solution,
        matrix: DistanceMatrix,
        constraints: List<PlanningConstraint>,
        rng: Random,
    ): Solution {
        if (current.placed.isEmpty()) return current
        val k = rng.nextInt(lnsRemoveMin, lnsRemoveMax + 1).coerceIn(1, current.placed.size)
        // Alternate two ruin operators: plain random removal, and worst
        // removal (drop the options whose presence costs the day the most).
        val toRemove = if (rng.nextBoolean()) {
            current.placed.shuffled(rng).take(k)
        } else {
            worstOptions(current, k, matrix, rng)
        }
        var partial = current
        for (option in toRemove) {
            partial = remove(partial, option, matrix) ?: return current
        }
        return repair(partial, toRemove + partial.unplaced, matrix, constraints, rng)
    }

    /**
     * Worst removal: rank the placed options by how much removing one
     * shortens the day (its score drop), then pick [k] biased toward the
     * costliest — but randomised (`rand^bias` index) so the ruin is not
     * purely greedy and the search keeps exploring. An option whose removal
     * cannot retime contributes no gain and sinks to the bottom.
     */
    private fun worstOptions(
        current: Solution,
        k: Int,
        matrix: DistanceMatrix,
        rng: Random,
    ): List<WalkOption> {
        val base = current.score()
        val ranked = current.placed
            .sortedByDescending { option ->
                base - (remove(current, option, matrix)?.score() ?: base)
            }
            .toMutableList()
        val chosen = ArrayList<WalkOption>(k)
        repeat(k.coerceAtMost(ranked.size)) {
            val idx = (rng.nextDouble().pow(WORST_REMOVAL_BIAS) * ranked.size).toInt()
                .coerceIn(0, ranked.size - 1)
            chosen.add(ranked.removeAt(idx))
        }
        return chosen
    }

    /** Greedily insert [toReinsert] into [partial] (shuffled order). */
    private fun repair(
        partial: Solution,
        toReinsert: List<WalkOption>,
        matrix: DistanceMatrix,
        constraints: List<PlanningConstraint>,
        rng: Random,
    ): Solution {
        var events = partial.events
        val placed = partial.placed.toMutableList()
        val unplaced = mutableListOf<WalkOption>()
        for (option in toReinsert.shuffled(rng)) {
            val inserted = tryInsertOption(events, option, matrix, constraints)
            if (inserted != null) {
                events = inserted
                placed.add(option)
            } else {
                unplaced.add(option)
            }
        }
        return Solution(events, placed, unplaced)
    }

    /**
     * Presentation pass over the finished plan: split every "ride after an
     * on-foot stretch" into the walk back to the parked bike (a
     * [RouteEvent.FetchBike]) followed by the ride itself. So the plan — and
     * the app's per-leg maps — shows the walk to fetch the bike instead of
     * hiding it inside the bike leg.
     *
     * It replays the bike position (a foot leg leaves it parked; a bike leg
     * moves it) to know where the bike stands, and reads the already-recorded
     * [RouteEvent.returnToBikeSeconds] for how long the walk back takes. Times
     * are untouched; the bike leg's travel is split into the walk-back and the
     * ride, and the ride's own returnToBike is cleared so the foot time is not
     * double-counted. Travel totals are recomputed so the walk-back counts as
     * walking.
     *
     * With `bikeOverheadSeconds == 0` walking is never faster, so no foot legs
     * occur, no ride ever starts away from the bike, and this pass is a no-op.
     */
    private fun DayRoute.withBikeFetches(): DayRoute {
        if (events.size < 2) return this
        var bikePos = events.first().location
        val out = mutableListOf(events.first())
        for (i in 1 until events.size) {
            val event = events[i]
            val back = event.returnToBikeSeconds
            when {
                // In-place dwell or on-foot leg: the bike stays parked.
                event is RouteEvent.Walk || event.arrivedByFoot -> out.add(event)
                // Bike leg that started away from the bike: walk back to it
                // first (its own foot leg), then ride the remainder.
                back > 0 -> {
                    out.add(
                        RouteEvent.FetchBike(
                            timeSeconds = event.timeSeconds - (event.incomingTravelSeconds - back),
                            location = bikePos,
                            incomingTravelSeconds = back,
                        ),
                    )
                    out.add(event.withIncomingTravel(event.incomingTravelSeconds - back).withReturnToBike(0))
                    bikePos = event.location
                }
                // Ride that started where the bike already was.
                else -> {
                    out.add(event)
                    bikePos = event.location
                }
            }
        }
        val cycling = out.filter { !it.arrivedByFoot }.sumOf { it.incomingTravelSeconds }
        val walking = out.filterIsInstance<RouteEvent.Walk>().sumOf { it.durationSeconds } +
            out.filter { it.arrivedByFoot }.sumOf { it.incomingTravelSeconds }
        return copy(events = out, totalCyclingSeconds = cycling, totalWalkingSeconds = walking)
    }

    /**
     * A candidate plan in progress: the retimed event list plus the options
     * scheduled into it and the routable options that could not be placed.
     * This is the unit the multi-start (and, later, the LNS destroy/repair
     * search) works on; [toDayRoute] turns the winner into the public result.
     */
    internal class Solution(
        val events: List<RouteEvent>,
        val placed: List<WalkOption>,
        val unplaced: List<WalkOption>,
    )

    /**
     * A plan is better when it leaves fewer walks unplaced; then when its
     * [score] is lower. The score adds a big penalty per dog over the
     * preferred group size, so larger groups are used only when they place
     * a dog that otherwise could not go — never just to shorten the day.
     * (Comparing [unplaced] alone matches comparing total conflicts: the
     * unroutable options are constant across all candidates of one plan.)
     */
    private fun Solution.isBetterThan(other: Solution): Boolean = when {
        unplaced.size != other.unplaced.size -> unplaced.size < other.unplaced.size
        else -> score() < other.score()
    }

    private fun Solution.score(): Long =
        elapsedSeconds().toLong() + dogsOverPreferred().toLong() * OVERSIZE_PENALTY_SECONDS

    private fun Solution.dogsOverPreferred(): Int =
        events.filterIsInstance<RouteEvent.Walk>()
            .sumOf { (it.dogs.size - preferredGroupSize).coerceAtLeast(0) }

    private fun Solution.elapsedSeconds(): Int =
        if (events.size >= 2) events.last().timeSeconds - events.first().timeSeconds else 0

    /** Build the public [DayRoute] from a finished solution. */
    private fun Solution.toDayRoute(date: LocalDate, unroutable: List<PlanConflict>): DayRoute {
        // Cycling = the travel of bike legs; walking = dwell-walk durations
        // plus the travel of on-foot legs (which doubles as walk time).
        val totalCycling = events.filter { !it.arrivedByFoot }.sumOf { it.incomingTravelSeconds }
        val totalWalking = events.filterIsInstance<RouteEvent.Walk>().sumOf { it.durationSeconds } +
            events.filter { it.arrivedByFoot }.sumOf { it.incomingTravelSeconds }
        val conflicts = unroutable + unplaced.map {
            val reason = if (it.isChoice) {
                "No feasible slot for any of its time windows"
            } else {
                "No feasible slot in the day"
            }
            PlanConflict(it.dog, reason)
        }
        return DayRoute(date, events, totalCycling, totalWalking, conflicts)
    }

    /** One greedy build over a fixed insertion [order] of options. */
    internal fun buildOnce(
        order: List<WalkOption>,
        matrix: DistanceMatrix,
        constraints: List<PlanningConstraint>,
    ): Solution {
        var events: List<RouteEvent> = listOf(homeStart(), homeEnd(dayStartSeconds))
        val placed = mutableListOf<WalkOption>()
        val unplaced = mutableListOf<WalkOption>()
        for (option in order) {
            val inserted = tryInsertOption(events, option, matrix, constraints)
            if (inserted != null) {
                events = inserted
                placed.add(option)
            } else {
                unplaced.add(option)
            }
        }
        return Solution(events, placed, unplaced)
    }

    /**
     * Remove a placed [option] from [solution]: drop its pickup and dropoff
     * and take its dog out of every walk **within that span** (dropping a walk
     * that empties), then retime. Scoping to the span matters — a dog walked
     * twice in a day has two spans, and removing one must not touch the other.
     * Removing only relaxes constraints, so the result stays feasible. Used by
     * the LNS destroy step; the caller hands the removed option to the repair
     * step. Returns null when the option is not currently placed, or in the
     * rare case the shortened plan fails to retime (a removed stop can shift
     * the foot/bike mode choices) — the caller then leaves the solution as is.
     */
    internal fun remove(solution: Solution, option: WalkOption, matrix: DistanceMatrix): Solution? {
        val ruleIds = option.alternatives.mapTo(HashSet()) { it.rule.id }
        val span = solution.events.walkSpans().firstOrNull {
            it.pickup.dog.id == option.dog.id && it.pickup.rule.id in ruleIds
        } ?: return null
        val pickup = span.pickup
        val dropoff = span.dropoff ?: return null
        val dogId = option.dog.id
        val pIdx = solution.events.indexOfFirst { it === pickup }
        val dIdx = solution.events.indexOfFirst { it === dropoff }

        val reduced = ArrayList<RouteEvent>(solution.events.size)
        for ((i, e) in solution.events.withIndex()) {
            when {
                i == pIdx || i == dIdx -> Unit // drop this span's pickup / dropoff
                i in (pIdx + 1) until dIdx && e is RouteEvent.Walk && e.dogs.any { it.id == dogId } -> {
                    val remaining = e.dogs.filter { it.id != dogId }
                    if (remaining.isNotEmpty()) reduced.add(e.copy(dogs = remaining))
                    // else: the walk had only this dog — drop it entirely.
                }
                else -> reduced.add(e)
            }
        }

        val retimed = retimeAndCost(reduced, matrix)?.first ?: return null
        return Solution(
            events = retimed,
            placed = solution.placed.filter { it !== option },
            unplaced = solution.unplaced,
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
        val n = events.size
        val homeLocation = events.first().location

        // Phase 1 — legs. Decide each leg's mode (foot vs bike) and travel
        // time, and where each in-place Walk happens. This depends only on
        // positions/distances, NOT on how long the dwell walks are, so it can
        // run before durations are known. The walker's position and the
        // parked bike diverge while the group walks on foot (the bike stays
        // put); a bike leg first walks the group back to the parked bike.
        val byFoot = BooleanArray(n)
        val travel = IntArray(n)
        // On-foot walk-back portion of a bike leg (0 for foot legs / rides that
        // start at the bike). Counts as walk time for the dogs then aboard.
        val returnToBike = IntArray(n)
        val walkLoc = arrayOfNulls<GeoPoint>(n)
        var walkerPos = homeLocation
        var bikePos = homeLocation
        for (i in 1 until n) {
            val event = events[i]
            if (event is RouteEvent.Walk) {
                // In-place dwell where the walker stands (bike parked nearby).
                walkLoc[i] = walkerPos
            } else {
                val footTime = matrix.footSeconds(walkerPos, event.location)
                val back = matrix.footSeconds(walkerPos, bikePos)
                val bikeTotal = back + matrix.bikeSeconds(bikePos, event.location)
                // The day must end with the bike back home, so the final leg
                // always fetches the parked bike rather than walking.
                if (event !is RouteEvent.HomeEnd && footTime <= bikeTotal) {
                    byFoot[i] = true
                    travel[i] = footTime
                    walkerPos = event.location
                } else {
                    travel[i] = bikeTotal
                    returnToBike[i] = back
                    walkerPos = event.location
                    bikePos = event.location
                }
            }
        }

        // Phase 2 — dwell durations. Shorten each in-place walk by the on-foot
        // time the dog already accrues while aboard, so foot legs are true
        // double-duty rather than extra walking on top.
        val dwell = effectiveDwells(events, byFoot, travel, returnToBike)

        // Phase 3 — times. Walk forward accumulating travel, dwell, stop
        // buffers and earliestStart waits; bail if the day runs past its end.
        var t = dayStartSeconds
        val retimed = ArrayList<RouteEvent>(n)
        for (i in 0 until n) {
            val event = events[i]
            if (i > 0) t += travel[i]
            if (event is RouteEvent.Pickup) {
                val earliest = event.rule.earliestStart?.toSecondOfDay()
                if (earliest != null && earliest > t) t = earliest
            }
            val placed: RouteEvent = when (event) {
                is RouteEvent.HomeStart ->
                    event.copy(timeSeconds = t, arrivedByFoot = byFoot[i], incomingTravelSeconds = travel[i], returnToBikeSeconds = returnToBike[i])
                is RouteEvent.HomeEnd ->
                    event.copy(timeSeconds = t, arrivedByFoot = byFoot[i], incomingTravelSeconds = travel[i], returnToBikeSeconds = returnToBike[i])
                is RouteEvent.Pickup ->
                    event.copy(timeSeconds = t, arrivedByFoot = byFoot[i], incomingTravelSeconds = travel[i], returnToBikeSeconds = returnToBike[i])
                is RouteEvent.Dropoff ->
                    event.copy(timeSeconds = t, arrivedByFoot = byFoot[i], incomingTravelSeconds = travel[i], returnToBikeSeconds = returnToBike[i])
                is RouteEvent.Walk ->
                    event.copy(
                        timeSeconds = t,
                        location = walkLoc[i] ?: event.location,
                        durationSeconds = dwell[i],
                        arrivedByFoot = byFoot[i],
                        incomingTravelSeconds = travel[i],
                    )
                // The solver never builds FetchBike events; they are added by
                // withBikeFetches after planning. Handled here for exhaustiveness.
                is RouteEvent.FetchBike ->
                    event.copy(timeSeconds = t, arrivedByFoot = byFoot[i], incomingTravelSeconds = travel[i])
            }
            t += placed.durationAtSeconds(stopBufferSeconds)
            if (t > dayEndSeconds) return null
            retimed.add(placed)
        }

        // Push HomeStart forward to "leave home just in time": no point
        // standing at the first stop waiting for its window to open.
        // Adjusting also shrinks the day's elapsed cost, which steers the
        // algorithm toward schedules with less idle time.
        val firstNonHome = retimed.getOrNull(1)
        if (firstNonHome != null && retimed[0] is RouteEvent.HomeStart) {
            val effectiveLeave = maxOf(dayStartSeconds, firstNonHome.timeSeconds - firstNonHome.incomingTravelSeconds)
            retimed[0] = (retimed[0] as RouteEvent.HomeStart).copy(timeSeconds = effectiveLeave)
        }

        val cost = retimed.last().timeSeconds - retimed.first().timeSeconds
        return retimed to cost
    }

    /**
     * Effective in-place dwell per Walk. A dog needs `required` walk seconds;
     * the on-foot legs it takes while aboard already count (true double-duty),
     * so the in-place dwell only makes up the rest (the dog's "deficit").
     *
     * A walk is shared by a group, so its duration is the largest deficit any
     * member still needs from it. A dog walked across several walks (a split
     * span) has its deficit shared between those walks in proportion to their
     * inserted lengths — so a split stays a split and the parts still sum to
     * the deficit (rounded up, never under). Foot credit excludes the leg that
     * fetches the dog (see [footCreditSeconds]); legs are classified in phase
     * 1 and do not depend on durations, so this is well-defined here.
     *
     * With `bikeOverheadSeconds == 0` no leg is ever walked, so every deficit
     * equals the full required duration and dwells are unchanged.
     */
    private fun effectiveDwells(
        events: List<RouteEvent>,
        byFoot: BooleanArray,
        travel: IntArray,
        returnToBike: IntArray,
    ): IntArray {
        val n = events.size
        // Pair pickups with dropoffs by occurrence (FIFO), as indices, and for
        // each span record its deficit and the walk indices the dog joins.
        val open = HashMap<String, ArrayDeque<Int>>()
        val deficit = ArrayList<Int>()
        val spanWalks = ArrayList<List<Int>>()
        for (i in 0 until n) {
            when (val e = events[i]) {
                is RouteEvent.Pickup -> open.getOrPut(e.dog.id) { ArrayDeque() }.addLast(i)
                is RouteEvent.Dropoff -> {
                    val pIdx = open[e.dog.id]?.removeFirstOrNull() ?: continue
                    val required = (events[pIdx] as RouteEvent.Pickup).rule.durationMinutes * 60
                    // On-foot credit: every walked stretch after the pickup
                    // through the dropoff — a full foot leg, or the walk back
                    // to the bike at the start of a ride.
                    var foot = 0
                    for (k in pIdx + 1..i) foot += if (byFoot[k]) travel[k] else returnToBike[k]
                    val walks = ((pIdx + 1) until i).filter { k ->
                        val w = events[k]
                        w is RouteEvent.Walk && w.dogs.any { it.id == e.dog.id }
                    }
                    deficit.add(maxOf(0, required - foot))
                    spanWalks.add(walks)
                }
                else -> Unit
            }
        }

        val dwell = IntArray(n)
        for (i in 0 until n) {
            val e = events[i]
            if (e !is RouteEvent.Walk) continue
            var dur = e.durationSeconds // fallback if no span covers this walk
            var covered = false
            for (s in spanWalks.indices) {
                val walks = spanWalks[s]
                if (i !in walks) continue
                if (!covered) { dur = 0; covered = true }
                val sumOrig = walks.sumOf { (events[it] as RouteEvent.Walk).durationSeconds }
                val need = if (sumOrig > 0)
                    ceil(deficit[s].toDouble() * e.durationSeconds / sumOrig).toInt()
                else deficit[s]
                if (need > dur) dur = need
            }
            dwell[i] = dur
        }
        return dwell
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

    /**
     * Bike-ride time: cycling time plus the per-ride mount/dismount
     * overhead. A zero-distance hop (a second dog at the same address) is
     * not a ride at all, so it pays no overhead — the walker just steps
     * over on foot.
     */
    private fun DistanceMatrix.bikeSeconds(from: GeoPoint, to: GeoPoint): Int {
        val meters = metersBetween(from, to)
        if (meters == 0) return 0
        return (meters / cyclingMetersPerSecond).toInt() + bikeOverheadSeconds
    }

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
