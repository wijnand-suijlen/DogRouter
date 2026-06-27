package app.dogrouter.domain.dayplan

import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.data.entity.TransportState
import app.dogrouter.domain.dayplan.constraints.AppointmentConstraint
import app.dogrouter.domain.dayplan.constraints.CapacityConstraint
import app.dogrouter.domain.dayplan.constraints.GroupSizeConstraint
import app.dogrouter.domain.dayplan.constraints.IncompatibilityConstraint
import app.dogrouter.domain.dayplan.constraints.MaxGapConstraint
import app.dogrouter.domain.dayplan.constraints.NoDogLeftBehindConstraint
import app.dogrouter.domain.dayplan.constraints.TimeWindowConstraint
import app.dogrouter.domain.dayplan.constraints.WalkDurationConstraint
import app.dogrouter.domain.planner.PlannedWalk
import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.domain.routing.RouteDistanceCache
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
    // Optional cross-day / cross-launch distance cache (see RouteDistanceCache).
    // Null = build every matrix from scratch (the default for tests/harness).
    private val routeCache: RouteDistanceCache? = null,
    private val home: GeoPoint?,
    private val capacityKg: Float,
    private val stopBufferSeconds: Int,
    private val cyclingSpeedKmh: Float,
    private val incompatibilities: Set<Pair<String, String>>,
    // Weight of cycling time in the objective, relative to day length (1.0 =
    // a minute saved cycling is worth a minute longer day). See AppSettings.
    private val cyclingWeight: Float = 1f,
    // Weight of over-walk (minutes walked beyond a dog's required duration) in
    // the objective, relative to day length. Light by design; 0 = ignore. The
    // default is 0 so existing tests/harness see the old objective unless set.
    private val overWalkWeight: Float = 0f,
    // On-foot group pace, and the fixed overhead added to every bike ride
    // (load dogs in the box, unlock, helmet, and the reverse on arrival).
    private val walkingSpeedKmh: Float = 3f,
    private val bikeOverheadSeconds: Int = 0,
    private val dayStartSeconds: Int = 8 * 3600,
    private val dayEndSeconds: Int = 20 * 3600,
    private val restarts: Int = DEFAULT_RESTARTS,
    // Large-neighbourhood search run per restart: each iteration ruins a few
    // placed walks and greedily re-inserts them, keeping the result if it
    // scores better. 0 disables LNS (pure multi-start). Default tuned against
    // the restarts × LNS sweep: the objective's big gains come by ~25
    // iterations, small past it (see docs/STATUS.md).
    private val lnsIterations: Int = DEFAULT_LNS_ITERATIONS,
    private val lnsRemoveMin: Int = 1,
    private val lnsRemoveMax: Int = 3,
    // Walk-group size: never more than [maxGroupSize] dogs at once (hard),
    // and a strong preference for at most [preferredGroupSize] (soft, via a
    // planning-cost penalty so bigger groups are used only when needed).
    private val maxGroupSize: Int = 4,
    private val preferredGroupSize: Int = 3,
    // Boarding ("sleepover") dogs present across the day as passengers, seeded
    // aboard from their start anchor to their end anchor (see BoardingPassenger
    // and docs/SLEEPOVER_DESIGN.md). Empty by default — inert unless boarding
    // dogs are supplied, so regular plans are byte-identical.
    private val boardingPassengers: List<BoardingPassenger> = emptyList(),
    // Weight of the SOFT max-walk-duration penalty for a capped boarding dog:
    // per joined Walk over its cap, score adds boardingCapWeight × overshoot²
    // (overshoot in minutes). 0 = ignore the cap (pure meenemen). Far below
    // OVERSIZE_PENALTY_SECONDS. Drives the park-vs-ride-along trade (stage 2).
    private val boardingCapWeight: Float = 0f,
    // When true, a boarding dog may be temporarily PARKED at its depot to rescue
    // a regular dog it would otherwise block (incompatibility / capacity / group
    // size): a Dropoff@depot + later Pickup@depot carves a hole in its presence
    // so the regular dog can be walked while it waits at the depot. Parking is a
    // last resort, used only to place an otherwise-unplaceable regular option;
    // it never drops a regular dog. See docs/SLEEPOVER_DESIGN.md.
    private val boardingParkingEnabled: Boolean = false,
) {
    companion object {
        /** Multi-start seeds the planner builds by default; see the ctor note. */
        const val DEFAULT_RESTARTS = 8

        /** LNS iterations per restart the planner runs by default; see the ctor note. */
        const val DEFAULT_LNS_ITERATIONS = 25
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
        // When set, fit a dog-free break into the finished plan (see insertBreak).
        breakSpec: BreakSpec? = null,
        // Fixed dog-free commitments pre-placed in the day; dogs schedule around
        // them (see AppointmentConstraint). Sorted by start time here.
        appointments: List<RouteEvent.Appointment> = emptyList(),
    ): DayRoute {
        if (home == null) {
            return DayRoute(date, emptyList(), 0, 0, options.map { PlanConflict(it.dog, "Home address not set") })
        }
        if (options.isEmpty() && appointments.isEmpty() && boardingPassengers.isEmpty()) {
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

        // The fixed events the day is built around: home, the appointments (in
        // time order), home.
        val sortedAppointments = appointments.sortedBy { it.startSeconds }
        // Boarding ("sleepover") passengers: seed each aboard for the day so it
        // rides along (includeAboardPassengers + NoDogLeftBehind). A Pickup at
        // the start anchor makes it aboard; regular options nest inside.
        //   - A pickup at the OWNER's home (BOARD_ARRIVE) is a real morning
        //     collect and is shown. A pickup at the walker's home (BOARD_STAY /
        //     BOARD_LEAVE) is just "the dog is already here" — seeded at zero
        //     cost and stripped from the final plan (see cleanBoarding).
        //   - A Dropoff is seeded ONLY for an OWNER-home end anchor (BOARD_LEAVE,
        //     a real evening return). For a walker-home end anchor the dog simply
        //     stays aboard through HomeEnd (it lives here while boarding) — so no
        //     spurious evening dropoff, and HomeEnd stays the bike-home final leg.
        // The LNS never ruins this backbone (the synthetic rule id is not a
        // placed WalkOption). See docs/SLEEPOVER_DESIGN.md.
        val passengerPickups = boardingPassengers.map {
            RouteEvent.Pickup(0, it.startLocation(), it.dog, boardingRule(it.dog.id))
        }
        val passengerDropoffs = boardingPassengers
            .filter { it.endAnchor == BoardingAnchor.OWNER_HOME }
            .map { RouteEvent.Dropoff(0, it.endLocation(), it.dog) }
        val baseEvents: List<RouteEvent> =
            listOf(homeStart()) + passengerPickups + sortedAppointments +
                passengerDropoffs + homeEnd(dayStartSeconds)

        val points = (
            routable.map { GeoPoint(it.dog.latitude!!, it.dog.longitude!!) } +
                home + (breakSpec?.locations ?: emptyList()) +
                sortedAppointments.map { it.location } +
                boardingPassengers.flatMap { it.allowedDepots + it.startLocation() + it.endLocation() }
            ).toSet()
        val matrix = DistanceMatrix.build(
            points,
            routingProvider,
            onPair = { done, total ->
                onProgress(MATRIX_PROGRESS_FRACTION * done / total, PlanPhase.ROUTING)
            },
            routeCache = routeCache,
        )
        val constraints = listOf(
            CapacityConstraint(capacityKg),
            TimeWindowConstraint(),
            WalkDurationConstraint(),
            IncompatibilityConstraint(incompatibilities),
            NoDogLeftBehindConstraint(),
            GroupSizeConstraint(maxGroupSize),
            AppointmentConstraint(),
        ) + if (boardingPassengers.isNotEmpty()) listOf(MaxGapConstraint(boardingPassengers)) else emptyList()

        // Multi-start: build several plans from different insertion orders
        // and keep the best. The deterministic deadline order is always one
        // of them, so more restarts never produce a worse result. The matrix
        // is built once above; each restart is pure in-memory work.
        val rng = Random(seed)
        val deadlineOrder = routable.sortedBy { it.deadlineSeconds() }
        // The solver phase fills the bar from MATRIX_PROGRESS_FRACTION to 1
        // across every multi-start build and every LNS iteration. Each restart
        // does one greedy build plus its own LNS pass (multi-start LNS), so the
        // step count is restarts × (1 build + lnsIterations).
        val solverSteps = restarts.coerceAtLeast(1) * (1 + lnsIterations.coerceAtLeast(0))
        var solverDone = 0
        fun reportSolver() {
            solverDone++
            onProgress(
                MATRIX_PROGRESS_FRACTION + (1f - MATRIX_PROGRESS_FRACTION) * solverDone / solverSteps,
                PlanPhase.OPTIMISING,
            )
        }

        SolverProfile.enabled = System.getProperty("solver.profile").toBoolean()
        if (SolverProfile.enabled) SolverProfile.reset()

        // Multi-start LNS: each restart builds one greedy seed (restart 0 uses
        // the deterministic deadline order, the rest random shuffles) and then
        // runs its OWN LNS pass — ruin-and-recreate hill-climbing on score — on
        // that seed. The global best across all restarts wins. Restarts escape
        // local optima the single LNS would be stuck in; the LNS reconsiders
        // structure within each. One Random(seed) drives every shuffle, ruin
        // and repair in sequence, so the whole search stays deterministic.
        var best: Solution? = null
        repeat(restarts.coerceAtLeast(1)) { iteration ->
            val order = if (iteration == 0) deadlineOrder else routable.shuffled(rng)
            val buildStart = if (SolverProfile.enabled) System.nanoTime() else 0L
            var current = buildOnce(order, matrix, constraints, baseEvents)
            if (SolverProfile.enabled) SolverProfile.restartNanos += System.nanoTime() - buildStart
            reportSolver()

            val lnsStart = if (SolverProfile.enabled) System.nanoTime() else 0L
            repeat(lnsIterations.coerceAtLeast(0)) {
                val candidate = ruinAndRecreate(current, matrix, constraints, rng)
                if (candidate.isBetterThan(current)) current = candidate
                reportSolver()
            }
            if (SolverProfile.enabled) SolverProfile.lnsNanos += System.nanoTime() - lnsStart

            if (best == null || current.isBetterThan(best!!)) best = current
        }

        // Rescue regular dogs the boarding passenger blocks (incompatibility /
        // capacity / group size) by temporarily parking it at its depot. Only
        // when something is unplaced; never drops a regular dog (it strictly
        // reduces the conflict count or is discarded). See parkingRepair.
        if (boardingParkingEnabled && boardingPassengers.isNotEmpty() && best!!.unplaced.isNotEmpty()) {
            best = parkingRepair(best!!, matrix, constraints)
        }
        SolverProfile.lastScoreSec = best!!.score()

        val withBreak = if (breakSpec != null) insertBreak(best!!, breakSpec, matrix, constraints) else null
        val solution = withBreak ?: best!!
        return solution.toDayRoute(date, unroutable).cleanBoarding().withBikeFetches()
            .copy(breakUnavailable = breakSpec != null && withBreak == null)
    }

    /**
     * Re-time an externally edited plan: keep the given event **order and
     * grouping**, but recompute leg modes, travel, dwell and times from
     * scratch, then re-add the presentation-only bike-fetch legs. Used by
     * manual plan edits (the order/grouping is the walker's; the timing stays
     * the planner's). Presentation [RouteEvent.FetchBike] events in the input
     * are dropped and regenerated. Returns null if routing is unavailable or
     * the edited plan cannot be timed within the day. Conflicts are not
     * produced here (empty); the caller carries any over and validates
     * separately. [recomputeDwells] = false keeps the walk durations already
     * set (so a manual duration survives a later structural edit).
     * [allowInfeasible] = true keeps timing a plan that overruns the day's end
     * (the editor shows it; PlanVerifier flags the overrun) instead of failing.
     */
    suspend fun retime(
        date: LocalDate,
        events: List<RouteEvent>,
        recomputeDwells: Boolean = true,
        allowInfeasible: Boolean = false,
    ): DayRoute? {
        if (home == null || !routingProvider.isReady()) return null
        val core = events.filterNot { it is RouteEvent.FetchBike }.toMutableList()
        if (core.size < 2) return null
        val points = (core.map { it.location } + home).toSet()
        val matrix = DistanceMatrix.build(points, routingProvider, routeCache = routeCache)
        val retimed = retimeAndCost(core, matrix, recomputeDwells, allowInfeasible)?.first ?: return null
        // withBikeFetches recomputes the cycling/walking totals.
        return DayRoute(date, retimed, 0, 0, emptyList()).withBikeFetches()
    }

    /**
     * Fit one dog-free break into a finished [solution]. Prefers a lunch at
     * home when there is a long mid-day free gap ([insertHomeLunch]); else a
     * break at the nearest break location ([insertCafeBreak]). Returns null
     * when no break fits — the day is then left break-less.
     */
    private fun insertBreak(
        solution: Solution,
        spec: BreakSpec,
        matrix: DistanceMatrix,
        constraints: List<PlanningConstraint>,
    ): Solution? =
        insertHomeLunch(solution, spec, matrix, constraints)
            ?: insertCafeBreak(solution, spec, matrix, constraints)

    /**
     * Lunch at home when the day has a long enough mid-day free gap: go home
     * after the morning and stay there until just in time for the afternoon,
     * so the would-be idle is spent at home rather than waiting at a stop.
     * Picks the longest qualifying gap; null if none reaches the threshold.
     */
    private fun insertHomeLunch(
        solution: Solution,
        spec: BreakSpec,
        matrix: DistanceMatrix,
        constraints: List<PlanningConstraint>,
    ): Solution? {
        val home = home ?: return null
        if (spec.homeLunchMinFreeSeconds <= 0) return null
        val events = solution.events

        // Longest empty free gap (idle in the no-break plan) overlapping the
        // window and at least the threshold.
        var aboard = 0
        var bestGap = -1
        var bestIdle = -1
        for (i in events.indices) {
            when (events[i]) {
                is RouteEvent.Pickup -> aboard++
                is RouteEvent.Dropoff -> aboard--
                else -> Unit
            }
            if (aboard != 0 || i >= events.size - 1) continue
            val next = events[i + 1]
            val gapStart = events[i].timeSeconds + events[i].durationAtSeconds(stopBufferSeconds)
            if (next.timeSeconds <= spec.windowStartSeconds || gapStart >= spec.windowEndSeconds) continue
            val idle = next.timeSeconds - (gapStart + next.incomingTravelSeconds)
            if (idle >= spec.homeLunchMinFreeSeconds && idle > bestIdle) {
                bestIdle = idle
                bestGap = i
            }
        }
        if (bestGap < 0) return null

        fun place(durationSeconds: Int): List<RouteEvent>? = retimeAndCost(
            events.toMutableList().apply {
                add(bestGap + 1, RouteEvent.Break(0, home, durationSeconds, atHome = true))
            },
            matrix,
        )?.first

        // Pass 1: a minimum lunch; measure the idle still left after it.
        val pass1 = place(spec.durationSeconds) ?: return null
        val lunch = pass1.getOrNull(bestGap + 1) as? RouteEvent.Break ?: return null
        val after = pass1.getOrNull(bestGap + 2) ?: return null
        val idleAfter =
            after.timeSeconds - (lunch.timeSeconds + lunch.durationSeconds + after.incomingTravelSeconds)

        // Pass 2: extend the lunch to absorb that idle — leave home just in time.
        val retimed = place(spec.durationSeconds + idleAfter.coerceAtLeast(0)) ?: return null
        if (constraints.violation(retimed) != null) return null
        return Solution(retimed, solution.placed, solution.unplaced)
    }

    /**
     * Fit a break at a break location: try every empty-handed gap and every
     * location, retime and validate, and keep the cheapest where the break
     * starts inside the window with no constraint broken.
     */
    private fun insertCafeBreak(
        solution: Solution,
        spec: BreakSpec,
        matrix: DistanceMatrix,
        constraints: List<PlanningConstraint>,
    ): Solution? {
        if (spec.locations.isEmpty()) return null
        val events = solution.events
        var bestEvents: List<RouteEvent>? = null
        var bestElapsed = Int.MAX_VALUE
        var aboard = 0
        for (i in events.indices) {
            when (events[i]) {
                is RouteEvent.Pickup -> aboard++
                is RouteEvent.Dropoff -> aboard--
                else -> Unit
            }
            // Insert into the gap after event i, only when empty-handed and
            // not after the final HomeEnd.
            if (aboard != 0 || i >= events.size - 1) continue
            for (location in spec.locations) {
                val candidate = events.toMutableList()
                candidate.add(
                    i + 1,
                    RouteEvent.Break(0, location, spec.durationSeconds, spec.windowStartSeconds),
                )
                val retimed = retimeAndCost(candidate, matrix)?.first ?: continue
                val placed = retimed.getOrNull(i + 1) as? RouteEvent.Break ?: continue
                if (placed.timeSeconds !in spec.windowStartSeconds..spec.windowEndSeconds) continue
                if (constraints.violation(retimed) != null) continue
                val elapsed = retimed.last().timeSeconds - retimed.first().timeSeconds
                if (elapsed < bestElapsed) {
                    bestElapsed = elapsed
                    bestEvents = retimed
                }
            }
        }
        return bestEvents?.let { Solution(it, solution.placed, solution.unplaced) }
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
    /**
     * Presentation cleanup for boarding plans, run before [withBikeFetches]:
     *
     *  - **Strip a boarding dog's pickup at the walker's home.** While boarding,
     *    the dog lives at the walker's home, so a home "pickup" is not a real
     *    collect — it only anchors the passenger during the search. It is seeded
     *    at zero travel right after HomeStart, so dropping it shifts nothing; the
     *    dog simply appears in the day's walks. A pickup at the OWNER's home
     *    (BOARD_ARRIVE) is a real collect and is kept.
     *  - **Drop empty 0-minute walks.** A split span can leave a walk with no
     *    in-place dwell (its dog's time is covered by other walks / on-foot
     *    legs); with an all-day passenger these placeholders multiply and clutter
     *    the timeline. They carry no time and no travel, so removing them is
     *    purely cosmetic.
     *
     * The solver keeps both while searching (the pickup anchors the passenger,
     * the walks split durations); this only tidies the final plan. Totals are
     * recomputed by the following [withBikeFetches]. No-op without boarding dogs.
     */
    private fun DayRoute.cleanBoarding(): DayRoute {
        if (boardingDogIds.isEmpty()) return this
        val homeLocation = home
        // Only the dog's FIRST pickup is the seeded presence start to strip when
        // at home; a later pickup at home is a real re-collect after parking.
        val firstPickupIdx = HashMap<String, Int>()
        events.forEachIndexed { i, e ->
            if (e is RouteEvent.Pickup && e.dog.id in boardingDogIds) {
                firstPickupIdx.putIfAbsent(e.dog.id, i)
            }
        }
        val cleaned = events.filterIndexed { i, e ->
            val stripPickup = e is RouteEvent.Pickup && e.dog.id in boardingDogIds &&
                e.location == homeLocation && firstPickupIdx[e.dog.id] == i
            val emptyWalk = e is RouteEvent.Walk && e.durationSeconds == 0
            !(stripPickup || emptyWalk)
        }
        return copy(events = cleaned)
    }

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
        elapsedSeconds().toLong() +
            (cyclingWeight * cyclingSeconds()).toLong() +
            (overWalkWeight * overWalkSeconds()).toLong() +
            boardingCapPenalty() +
            dogsOverPreferred().toLong() * OVERSIZE_PENALTY_SECONDS

    /** Soft penalty for a capped boarding dog walked longer than its cap: per
     *  joined Walk, `boardingCapWeight × overshoot-minutes²` (quadratic, so a
     *  small overshoot is cheap and a large one dear). 0 when no cap / weight 0.
     *  This is what tips a capped passenger from riding along into being parked
     *  and walked short (stage 2). */
    private fun Solution.boardingCapPenalty(): Long {
        if (boardingCapWeight == 0f || boardingById.isEmpty()) return 0L
        var penalty = 0.0
        for (e in events) {
            if (e !is RouteEvent.Walk) continue
            for (d in e.dogs) {
                val cap = boardingById[d.id]?.capSeconds ?: continue
                val overMin = (e.durationSeconds - cap).coerceAtLeast(0) / 60.0
                penalty += boardingCapWeight * overMin * overMin
            }
        }
        return penalty.toLong()
    }

    /** Pure ride time (excludes the on-foot walk-back folded into a bike leg). */
    private fun Solution.cyclingSeconds(): Int =
        events.sumOf { if (it.arrivedByFoot) 0 else it.incomingTravelSeconds - it.returnToBikeSeconds }

    /** Seconds walked beyond what each dog's rule required, summed over every
     *  pickup→dropoff span (the over-walk the objective lightly discourages). */
    private fun Solution.overWalkSeconds(): Int =
        events.walkSpans()
            .filter { it.pickup.dog.id !in boardingDogIds }
            .sumOf { span ->
                val required = span.pickup.rule.durationMinutes * 60
                (span.walkedSeconds(events) - required).coerceAtLeast(0)
            }

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
        baseEvents: List<RouteEvent> = listOf(homeStart(), homeEnd(dayStartSeconds)),
    ): Solution {
        var events: List<RouteEvent> = baseEvents
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
                    // Keep the walk only if a real (non-passenger) dog still walks
                    // it. A walk left with only boarding passengers is an orphan —
                    // the passenger merely rode along the removed dog's walk — so
                    // drop it rather than leave a stray solo-passenger walk behind.
                    if (remaining.any { it.id !in boardingDogIds }) reduced.add(e.copy(dogs = remaining))
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
     * Rescue the still-unplaced regular options by parking a boarding passenger
     * at its depot. Two complementary moves, each only ever *adding* a rescue
     * (strictly fewer conflicts), so a regular dog is never dropped or pushed
     * past its window to make room:
     *
     *  - **A) Amortised multi-walk parking** ([parkAndReinsert]): drop the
     *    passenger once over a run of the walks it rides, then re-insert as many
     *    unplaced options as fit into that one parked window (joining the
     *    now-passenger-free walks or as solo walks), and re-collect once. One
     *    detour rescues several dogs. Repeated while it keeps improving.
     *  - **B) Solo fallback** ([tryPlaceSolo]): for a leftover victim that only
     *    fits an empty-handed gap (no group walk in its window), park the
     *    passenger just for a solo walk there.
     *
     * [MaxGapConstraint] bounds the parked hole in the passenger's walks, and
     * every candidate is fully retimed and checked against all constraints, so a
     * rescue is accepted only when it breaks nothing else.
     */
    private fun parkingRepair(
        solution: Solution,
        matrix: DistanceMatrix,
        constraints: List<PlanningConstraint>,
    ): Solution {
        var current = solution
        // A) Amortised multi-walk parking, repeated so separate runs can each
        // rescue a different cluster of dogs.
        for (passenger in boardingPassengers) {
            while (current.unplaced.isNotEmpty()) {
                val walkIdx = current.events.indices.filter { i ->
                    val e = current.events[i]
                    e is RouteEvent.Walk && e.dogs.any { d -> d.id == passenger.dog.id }
                }
                var bestRun: Solution? = null
                for (a in walkIdx.indices) {
                    for (b in a until walkIdx.size) {
                        val cand = parkAndReinsert(current, passenger, walkIdx[a], walkIdx[b], matrix, constraints)
                        if (cand != null && (bestRun == null || cand.isBetterThan(bestRun!!))) bestRun = cand
                    }
                }
                if (bestRun != null && bestRun!!.isBetterThan(current)) current = bestRun!! else break
            }
        }
        // B) Solo fallback for anything still unplaced.
        for (option in current.unplaced.toList()) {
            val placed = tryPlaceSolo(current, option, matrix, constraints) ?: continue
            if (placed.isBetterThan(current)) current = placed
        }
        return current
    }

    /**
     * Park [passenger] over the run of its walks from index [fromWalk] to
     * [toWalk] — drop it at the nearest depot just before the run, remove it
     * from those walks, and re-collect it just after — then greedily re-insert
     * every unplaced option
     * into the parked plan ([tryInsertOption], which can join the freed walks or
     * add a solo one). Returns the improved solution if at least one option is
     * placed and the whole plan stays feasible (the parked hole respects
     * [MaxGapConstraint]); null otherwise.
     */
    private fun parkAndReinsert(
        solution: Solution,
        passenger: BoardingPassenger,
        fromWalk: Int,
        toWalk: Int,
        matrix: DistanceMatrix,
        constraints: List<PlanningConstraint>,
    ): Solution? {
        val events = solution.events
        val id = passenger.dog.id
        val depot = nearestDepot(passenger, events[fromWalk].location, matrix)
        val parked = ArrayList<RouteEvent>(events.size + 2)
        for (i in events.indices) {
            if (i == fromWalk) parked.add(RouteEvent.Dropoff(0, depot, passenger.dog))
            val e = events[i]
            if (i in fromWalk..toWalk && e is RouteEvent.Walk && e.dogs.any { it.id == id }) {
                val remaining = e.dogs.filter { it.id != id }
                if (remaining.isNotEmpty()) parked.add(e.copy(dogs = remaining)) // else drop emptied walk
            } else {
                parked.add(e)
            }
            if (i == toWalk) parked.add(RouteEvent.Pickup(0, depot, passenger.dog, boardingRule(id)))
        }
        var current = retimeAndCost(parked, matrix)?.first ?: return null
        // Removing the passenger only relaxes the structural constraints; the one
        // thing that can newly break is its own max-gap (the parked hole). Reject
        // the run early when the hole is too long.
        if (constraints.violation(current) != null) return null
        val placed = ArrayList<WalkOption>()
        val stillUnplaced = ArrayList<WalkOption>()
        for (option in solution.unplaced) {
            val inserted = tryInsertOption(current, option, matrix, constraints)
            if (inserted != null) { current = inserted; placed.add(option) } else stillUnplaced.add(option)
        }
        if (placed.isEmpty()) return null
        return Solution(current, solution.placed + placed, stillUnplaced)
    }

    /**
     * Place the leftover [option] as a solo walk in an empty-handed gap of a
     * passenger (only the passenger aboard), parking it just for that walk:
     * `Dropoff(passenger)@depot, Pickup(O), Walk(O), Dropoff(O), Pickup(passenger)@depot`.
     * Covers a victim with no group walk in its window to join. Cheapest feasible
     * placement across passengers and gaps; null if none fits.
     */
    private fun tryPlaceSolo(
        solution: Solution,
        option: WalkOption,
        matrix: DistanceMatrix,
        constraints: List<PlanningConstraint>,
    ): Solution? {
        val events = solution.events
        var best: List<RouteEvent>? = null
        var bestCost = Int.MAX_VALUE
        for (passenger in boardingPassengers) {
            for (alternative in option.alternatives) {
                for (pos in parkablePositions(events, passenger)) {
                    val depot = nearestDepot(passenger, alternative.geoPoint(), matrix)
                    val cand = buildParkBlock(events, pos, alternative, passenger, depot)
                    includeAboardPassengers(cand)
                    if (!structurallyFeasible(cand)) continue
                    val (eventList, cost) = retimeAndCost(cand, matrix) ?: continue
                    if (cost >= bestCost) continue
                    if (constraints.violation(eventList) != null) continue
                    best = eventList
                    bestCost = cost
                }
            }
        }
        return best?.let {
            Solution(it, solution.placed + option, solution.unplaced.filter { o -> o !== option })
        }
    }

    /** The allowed depot nearest [ref] (road metres) — the dog's own home when
     *  the key is held, else the walker's home; minimises the park detour. */
    private fun nearestDepot(passenger: BoardingPassenger, ref: GeoPoint, matrix: DistanceMatrix): GeoPoint =
        passenger.allowedDepots.minByOrNull { matrix.metersBetween(ref, it) } ?: passenger.depot

    /**
     * Gap positions (insert-before indices) where ONLY [passenger] is aboard —
     * the passenger has been picked up, not yet dropped off, and no other dog is
     * in the bag. A solo park block can be carved only here: dropping the
     * passenger and walking the rescued dog alone would otherwise strand any
     * other aboard dog (it must be in every walk — NoDogLeftBehind).
     */
    private fun parkablePositions(events: List<RouteEvent>, passenger: BoardingPassenger): List<Int> {
        val id = passenger.dog.id
        val positions = ArrayList<Int>()
        var passengerAboard = false
        var othersAboard = 0
        for (i in events.indices) {
            if (passengerAboard && othersAboard == 0) positions.add(i)
            when (val e = events[i]) {
                is RouteEvent.Pickup -> if (e.dog.id == id) passengerAboard = true else othersAboard++
                is RouteEvent.Dropoff -> if (e.dog.id == id) passengerAboard = false else othersAboard--
                else -> Unit
            }
        }
        return positions
    }

    /** Build the un-retimed solo park block for [walk] at [pos], parking the
     *  passenger at [depot]. */
    private fun buildParkBlock(
        events: List<RouteEvent>,
        pos: Int,
        walk: PlannedWalk,
        passenger: BoardingPassenger,
        depot: GeoPoint,
    ): MutableList<RouteEvent> {
        val oLoc = walk.geoPoint()
        val out = ArrayList<RouteEvent>(events.size + 5)
        out.addAll(events.subList(0, pos))
        out.add(RouteEvent.Dropoff(0, depot, passenger.dog))
        out.add(RouteEvent.Pickup(0, oLoc, walk.dog, walk.rule))
        out.add(RouteEvent.Walk(0, oLoc, listOf(walk.dog), walk.rule.durationMinutes * 60))
        out.add(RouteEvent.Dropoff(0, oLoc, walk.dog))
        out.add(RouteEvent.Pickup(0, depot, passenger.dog, boardingRule(passenger.dog.id)))
        out.addAll(events.subList(pos, events.size))
        return out
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
        // One reused scratch list for the candidate structure: every insertion
        // is built into this same buffer (cleared + refilled) instead of
        // allocating a fresh list per candidate (~millions per plan). Safe
        // because retimeAndCost reads it and returns its own new list, and the
        // search is single-threaded; `best` only ever holds a retimed result,
        // never the scratch.
        val cand = ArrayList<RouteEvent>(events.size + 3)

        // Evaluate one structural candidate. The time-independent constraints
        // (capacity, group size, incompatibility, no-dog-left-behind) are
        // checked allocation-free on the un-retimed structure FIRST
        // ([structurallyFeasible]), so a structurally infeasible insertion skips
        // the expensive retime entirely. That check is exact, and the accepted
        // set is identical to checking the full constraints after retime
        // (structural ⊆ full), so this only saves work — it never changes which
        // plan wins.
        fun consider(structural: MutableList<RouteEvent>?) {
            if (structural == null) return
            // Keep boarding passengers pinned as the day's backbone (presence
            // span outermost), then fold them into every nested group walk
            // (NoDogLeftBehind) before either feasibility check.
            if (!boardingPinned(structural)) return
            includeAboardPassengers(structural)
            if (!structurallyFeasible(structural)) return
            val (eventList, cost) = retimeAndCost(structural, matrix) ?: return
            if (cost >= bestCost) return
            if (constraints.violation(eventList) != null) return
            best = eventList
            bestCost = cost
        }

        // Mode A: new walk for this dog only.
        for (pickPos in 1 until events.size) {
            for (walkPos in pickPos + 1..events.size) {
                for (dropPos in walkPos + 1..events.size + 1) {
                    if (SolverProfile.enabled) SolverProfile.modeA++
                    consider(buildNewTriplet(cand, events, walk, pickPos, walkPos, dropPos))
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
                    if (SolverProfile.enabled) SolverProfile.modeB++
                    consider(buildJoinWalk(cand, events, walk, pickPos, walkIdx, dropPos))
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
                if (SolverProfile.enabled) SolverProfile.modeC++
                consider(buildRideAlong(cand, events, walk, pickPos, dropPos))
            }
        }

        return best
    }

    /** Build the un-retimed Mode C ride-along into [out] (cleared + refilled). */
    private fun buildRideAlong(
        out: MutableList<RouteEvent>,
        events: List<RouteEvent>,
        walk: PlannedWalk,
        pickPos: Int,
        dropPos: Int,
    ): MutableList<RouteEvent> {
        val loc = walk.geoPoint()
        out.clear()
        for (i in 0..events.size) {
            if (i == pickPos) out.add(RouteEvent.Pickup(0, loc, walk.dog, walk.rule))
            if (i == dropPos) out.add(RouteEvent.Dropoff(0, loc, walk.dog))
            if (i < events.size) {
                val e = events[i]
                // Add the dog to every existing walk inside the carry span;
                // their durations stay unchanged (the dog only rides along).
                if (e is RouteEvent.Walk && i in pickPos until dropPos) {
                    out.add(e.copy(dogs = e.dogs + walk.dog))
                } else {
                    out.add(e)
                }
            }
        }
        return out
    }

    /** Build the un-retimed Mode A triplet into [out] (cleared + refilled). */
    private fun buildNewTriplet(
        out: MutableList<RouteEvent>,
        events: List<RouteEvent>,
        walk: PlannedWalk,
        pickPos: Int,
        walkPos: Int,
        dropPos: Int,
    ): MutableList<RouteEvent> {
        val loc = walk.geoPoint()
        out.clear()
        out.addAll(events)
        // Insert markers; times re-derived by retime.
        out.add(pickPos, RouteEvent.Pickup(0, loc, walk.dog, walk.rule))
        out.add(walkPos, RouteEvent.Walk(0, loc, listOf(walk.dog), walk.rule.durationMinutes * 60))
        out.add(dropPos, RouteEvent.Dropoff(0, loc, walk.dog))
        return out
    }

    /** Build the un-retimed Mode B join into [out] (cleared + refilled). Null
     *  when the dropoff position would fall past the list end. */
    private fun buildJoinWalk(
        out: MutableList<RouteEvent>,
        events: List<RouteEvent>,
        walk: PlannedWalk,
        pickPos: Int,
        existingWalkIdx: Int,
        dropPos: Int,
    ): MutableList<RouteEvent>? {
        val loc = walk.geoPoint()
        val existing = events[existingWalkIdx] as RouteEvent.Walk
        val combinedDuration = maxOf(existing.durationSeconds, walk.rule.durationMinutes * 60)
        val combinedDogs = existing.dogs + walk.dog
        val updatedWalk = existing.copy(dogs = combinedDogs, durationSeconds = combinedDuration)

        out.clear()
        out.addAll(events)
        out[existingWalkIdx] = updatedWalk
        // Insert pickup (shifts existing indices ≥ pickPos by 1).
        out.add(pickPos, RouteEvent.Pickup(0, loc, walk.dog, walk.rule))
        // Dropoff position relative to original was `dropPos`; after pickup
        // insertion all indices ≥ pickPos moved by 1, so dropoff lands at
        // dropPos + 1 in the new list.
        val adjustedDrop = dropPos + 1
        if (adjustedDrop > out.size) return null
        out.add(adjustedDrop, RouteEvent.Dropoff(0, loc, walk.dog))
        return out
    }

    /**
     * Whether the dogs currently [aboard] can be carried on a bike leg, given
     * each dog's transport state. A dog rides in the cargo box only with
     * `inCargoBike == Yes`; a dog that cannot use the box may ride in the
     * backpack with `inBackpack == Yes`, but the backpack holds at most one
     * dog. A dog that can use neither (No or NotTested for both) makes a bike
     * leg impossible, so the leg falls back to walking. The box weight against
     * the configured capacity is enforced separately by [CapacityConstraint].
     */
    private fun canRideBike(aboard: List<Dog>): Boolean {
        var backpackDogs = 0
        for (dog in aboard) {
            when {
                dog.inCargoBike == TransportState.Yes -> Unit // rides in the box
                dog.inBackpack == TransportState.Yes -> backpackDogs++
                else -> return false
            }
        }
        return backpackDogs <= 1
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
     * [dayEndSeconds]. The on-foot group cap is enforced separately as a hard
     * constraint (see [canRideBike] for the transport-mode rule it pairs with).
     */
    private fun retimeAndCost(
        events: MutableList<RouteEvent>,
        matrix: DistanceMatrix,
        recomputeDwells: Boolean = true,
        allowInfeasible: Boolean = false,
    ): Pair<List<RouteEvent>, Int>? {
        if (!SolverProfile.enabled) {
            return retimeAndCostImpl(events, matrix, recomputeDwells, allowInfeasible)
        }
        SolverProfile.retimeCalls++
        return SolverProfile.measure({ SolverProfile.retimeNanos += it }) {
            retimeAndCostImpl(events, matrix, recomputeDwells, allowInfeasible)
        }
    }

    private fun retimeAndCostImpl(
        events: MutableList<RouteEvent>,
        matrix: DistanceMatrix,
        recomputeDwells: Boolean = true,
        allowInfeasible: Boolean = false,
    ): Pair<List<RouteEvent>, Int>? {
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
        // Dogs in transit during the current leg, used to honour each dog's
        // transport state: a dog that cannot ride the cargo bike forces its
        // legs on foot. A pickup's dog is not aboard on the leg that fetches
        // it; a dropoff's dog still is on the leg that delivers it, then leaves.
        val aboard = ArrayList<Dog>()
        for (i in 1 until n) {
            val event = events[i]
            if (event is RouteEvent.Walk) {
                // In-place dwell where the walker stands (bike parked nearby).
                walkLoc[i] = walkerPos
            } else {
                val footTime = matrix.footSeconds(walkerPos, event.location)
                val back = matrix.footSeconds(walkerPos, bikePos)
                val bikeTotal = back + matrix.bikeSeconds(bikePos, event.location)
                // A dog that cannot ride in the box (and is not the lone
                // backpack dog) forces this leg on foot, whatever the times.
                // The on-foot group cap is a separate hard constraint
                // (GroupSizeConstraint, |aboard| <= maxGroupSize at all times),
                // so it is not re-checked here.
                val canBike = canRideBike(aboard)
                // The day must end with the bike back home, so the final leg
                // always fetches the parked bike rather than walking.
                val auto = event !is RouteEvent.HomeEnd && (footTime <= bikeTotal || !canBike)
                // A hand-set override wins over the automatic choice. BIKE is
                // honoured even when a non-rideable dog is aboard (an impossible
                // plan the editor may show; PlanVerifier flags it afterwards).
                val goFoot = when (event.legMode) {
                    LegMode.FOOT -> true
                    LegMode.BIKE -> false
                    LegMode.AUTO -> auto
                }
                if (goFoot) {
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
            when (event) {
                is RouteEvent.Pickup -> aboard.add(event.dog)
                is RouteEvent.Dropoff -> aboard.removeAll { it.id == event.dog.id }
                else -> Unit
            }
        }

        // Phase 2 — dwell durations. Shorten each in-place walk by the on-foot
        // time the dog already accrues while aboard, so foot legs are true
        // double-duty rather than extra walking on top. Manual edits pass
        // recomputeDwells=false to keep the durations already on the walks
        // (walker-owned once a plan is pinned).
        val dwell = if (recomputeDwells) {
            effectiveDwells(events, byFoot, travel, returnToBike)
        } else {
            IntArray(n) { (events[it] as? RouteEvent.Walk)?.durationSeconds ?: 0 }
        }

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
            if (event is RouteEvent.Break && event.earliestStartSeconds > t) {
                t = event.earliestStartSeconds
            }
            if (event is RouteEvent.Appointment && event.startSeconds > t) {
                t = event.startSeconds
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
                is RouteEvent.Break ->
                    event.copy(timeSeconds = t, arrivedByFoot = byFoot[i], incomingTravelSeconds = travel[i], returnToBikeSeconds = returnToBike[i])
                is RouteEvent.Appointment ->
                    event.copy(timeSeconds = t, arrivedByFoot = byFoot[i], incomingTravelSeconds = travel[i], returnToBikeSeconds = returnToBike[i])
                // The solver never builds FetchBike events; they are added by
                // withBikeFetches after planning. Handled here for exhaustiveness.
                is RouteEvent.FetchBike ->
                    event.copy(timeSeconds = t, arrivedByFoot = byFoot[i], incomingTravelSeconds = travel[i])
            }
            t += placed.durationAtSeconds(stopBufferSeconds)
            // A hand-edited plan may legitimately run past the day's end; the
            // editor keeps showing it (allowInfeasible) and PlanVerifier flags
            // the overrun rather than the plan silently vanishing.
            if (!allowInfeasible && t > dayEndSeconds) return null
            retimed.add(placed)
        }

        // Leave home — and collect any boarding dog — just in time. Walk back
        // from the first time-constrained event and delay the whole leading
        // chain that has no earliest-time requirement of its own — HomeStart,
        // home stops, and **boarding-dog pickups** (which carry no window) — by
        // the idle the walker would otherwise wait at that constrained event. So
        // instead of collecting an Ophaal dog at dawn and then standing around an
        // hour until the first walk's window opens, the walker leaves late enough
        // to ride home→owner→first dog straight through. For a regular day the
        // chain is just HomeStart, so this matches the old "leave just in time".
        if (retimed.firstOrNull() is RouteEvent.HomeStart) {
            // Extent of the leading no-window chain.
            var chainEnd = 0
            while (chainEnd + 1 < retimed.size) {
                val e = retimed[chainEnd + 1]
                val noWindow = (e.incomingTravelSeconds == 0 && e.location == homeLocation) ||
                    (e is RouteEvent.Pickup && e.dog.status.isBoarding)
                if (!noWindow) break
                chainEnd++
            }
            val anchor = retimed.getOrNull(chainEnd + 1)
            if (anchor != null) {
                // Idle the walker would wait at the anchor (it arrived before its
                // window); delay the chain by it so the anchor is reached on time.
                val arrival = retimed[chainEnd].timeSeconds +
                    retimed[chainEnd].durationAtSeconds(stopBufferSeconds) + anchor.incomingTravelSeconds
                val slack = (anchor.timeSeconds - arrival).coerceAtLeast(0)
                if (slack > 0) {
                    for (i in 0..chainEnd) {
                        retimed[i] = when (val e = retimed[i]) {
                            is RouteEvent.HomeStart -> e.copy(timeSeconds = e.timeSeconds + slack)
                            is RouteEvent.Pickup -> e.copy(timeSeconds = e.timeSeconds + slack)
                            else -> e
                        }
                    }
                }
            }
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
        if (!SolverProfile.enabled) {
            for (c in this) c.violation(events)?.let { return it }
            return null
        }
        SolverProfile.constraintCalls++
        return SolverProfile.measure({ SolverProfile.constraintNanos += it }) {
            for (c in this) c.violation(events)?.let { return@measure it }
            null
        }
    }

    /**
     * Allocation-free, EXACT equivalent of the four time-independent
     * constraints — capacity, group size, incompatibility, no-dog-left-behind —
     * evaluated on the un-retimed [events]. Returns true iff all four would pass
     * (`CapacityConstraint` ∧ `GroupSizeConstraint` ∧ `IncompatibilityConstraint`
     * ∧ `NoDogLeftBehindConstraint`). Used as the insertion pre-filter so a
     * structurally infeasible candidate skips the expensive retime; being exact
     * (not just sound) keeps the plan byte-identical (cross-checked by
     * [StructuralFilterTest] and the unchanged baseline).
     *
     * Replaces those constraints' per-call maps/sets with a tiny reused scratch
     * array: the aboard set is bounded by `maxGroupSize`. Capacity is a multiset
     * (weight added per pickup, removed per dropoff); the other three are a
     * presence-set keyed by dog id (a second pickup of an aboard dog is a no-op,
     * a dropoff removes the id) — exactly the semantics of the map-based
     * constraints, including a dog briefly aboard twice. The solver runs one
     * plan() single-threaded, so the instance scratch needs no synchronisation.
     */
    internal fun structurallyFeasible(events: List<RouteEvent>): Boolean {
        val present = presentScratch
        var n = 0
        var weight = 0f
        for (e in events) {
            when (e) {
                is RouteEvent.Pickup -> {
                    val id = e.dog.id
                    incompatibleWith[id]?.let { foes ->
                        for (i in 0 until n) if (present[i]!! in foes) return false
                    }
                    weight += e.dog.weightKg
                    if (weight > capacityKg) return false
                    var seen = false
                    for (i in 0 until n) if (present[i] == id) { seen = true; break }
                    if (!seen) {
                        present[n++] = id
                        if (n > maxGroupSize) return false
                    }
                }
                is RouteEvent.Dropoff -> {
                    weight -= e.dog.weightKg
                    val id = e.dog.id
                    for (i in 0 until n) if (present[i] == id) {
                        present[i] = present[--n]
                        present[n] = null
                        break
                    }
                }
                is RouteEvent.Walk -> {
                    if (e.dogs.size > maxGroupSize) return false
                    for (i in 0 until n) {
                        val pid = present[i]
                        var found = false
                        for (d in e.dogs) if (d.id == pid) { found = true; break }
                        if (!found) return false
                    }
                }
                else -> Unit
            }
        }
        return true
    }

    // Reused across the single-threaded insertion search of one plan() call so
    // the structural pre-filter allocates nothing per candidate. Sized
    // maxGroupSize + 1: the group-size check rejects once one over the cap is
    // aboard, so the index never runs past this slot.
    private val presentScratch = arrayOfNulls<String>(maxGroupSize + 1)

    // dog id -> ids it may never share the bike with (symmetric), from
    // [incompatibilities]. O(1) membership, no Pair allocation per check.
    private val incompatibleWith: Map<String, Set<String>> =
        buildMap<String, MutableSet<String>> {
            for ((a, b) in incompatibilities) {
                getOrPut(a) { HashSet() }.add(b)
                getOrPut(b) { HashSet() }.add(a)
            }
        }

    // Boarding passengers indexed by dog id, and the set of their dog ids: used
    // to add them to walks (includeAboardPassengers), to skip the WalkDuration
    // check and the over-walk term, and to apply the soft cap penalty.
    private val boardingById: Map<String, BoardingPassenger> =
        boardingPassengers.associateBy { it.dog.id }
    private val boardingDogIds: Set<String> = boardingById.keys

    /** Synthetic schedule rule for a boarding dog's seeded pickup: no duration
     *  requirement (max-gap coverage governs instead) and no window. */
    private fun boardingRule(dogId: String) = DogScheduleRule(
        id = "boarding-$dogId",
        dogId = dogId,
        weekdaysMask = 0,
        earliestStart = null,
        latestStart = null,
        latestEnd = null,
        durationMinutes = 0,
    )

    /**
     * Whether the boarding passengers are still pinned as the day's backbone:
     * every boarding Pickup sits before all regular activity (right after
     * HomeStart) and every boarding Dropoff after it (right before HomeEnd). The
     * seeded presence span must stay outermost so the passenger is aboard the
     * whole day (picked up at home for free, not via a mid-day home detour) and
     * regular walks nest inside it. Insertion candidates that would float a
     * passenger pickup past the first walk, or a dropoff before the last, are
     * rejected — that is what keeps "take her along the whole day" the shape.
     */
    private fun boardingPinned(events: List<RouteEvent>): Boolean {
        if (boardingDogIds.isEmpty()) return true
        var firstRegular = -1
        var lastRegular = -1
        for (i in events.indices) {
            val regular = when (val e = events[i]) {
                is RouteEvent.Pickup -> e.dog.id !in boardingDogIds
                is RouteEvent.Dropoff -> e.dog.id !in boardingDogIds
                is RouteEvent.Walk, is RouteEvent.Break, is RouteEvent.Appointment -> true
                else -> false
            }
            if (regular) {
                if (firstRegular < 0) firstRegular = i
                lastRegular = i
            }
        }
        if (firstRegular < 0) return true
        for (i in events.indices) {
            val e = events[i]
            if (e is RouteEvent.Pickup && e.dog.id in boardingDogIds && i > firstRegular) return false
            if (e is RouteEvent.Dropoff && e.dog.id in boardingDogIds && i < lastRegular) return false
        }
        return true
    }

    /**
     * Add every boarding passenger currently aboard to each [RouteEvent.Walk]
     * that does not already include it. A passenger is seeded aboard for its
     * presence interval, so [NoDogLeftBehindConstraint] requires every walk
     * there to include it (it rides along — "meenemen"); this makes that hold
     * by construction, so the candidate passes both the structural pre-filter
     * and the full check. Mutates [events] in place (the reused candidate
     * scratch). No-op when there are no boarding passengers.
     */
    private fun includeAboardPassengers(events: MutableList<RouteEvent>) {
        if (boardingById.isEmpty()) return
        val aboard = LinkedHashMap<String, Dog>()
        for (i in events.indices) {
            when (val e = events[i]) {
                is RouteEvent.Pickup -> if (e.dog.id in boardingDogIds) aboard[e.dog.id] = e.dog
                is RouteEvent.Dropoff -> if (e.dog.id in boardingDogIds) aboard.remove(e.dog.id)
                is RouteEvent.Walk -> {
                    if (aboard.isEmpty()) continue
                    val have = e.dogs.mapTo(HashSet()) { it.id }
                    val missing = aboard.values.filter { it.id !in have }
                    if (missing.isNotEmpty()) events[i] = e.copy(dogs = e.dogs + missing)
                }
                else -> Unit
            }
        }
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
