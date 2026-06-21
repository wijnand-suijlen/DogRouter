package app.dogrouter.domain.dayplan

import app.dogrouter.data.db.AppointmentDao
import app.dogrouter.data.db.DogDao
import app.dogrouter.data.db.DogIncompatibilityDao
import app.dogrouter.data.db.DogScheduleDao
import app.dogrouter.data.db.SavedPlanDao
import app.dogrouter.data.entity.Appointment
import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogIncompatibility
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.data.entity.SavedPlan
import app.dogrouter.data.prefs.AppSettings
import app.dogrouter.data.prefs.SettingsRepository
import app.dogrouter.domain.planner.PlannedWalk
import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.domain.routing.RoutingProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.LocalTime

private const val MAX_CACHED_PLANS = 32

/**
 * Builds the PDPTW [DayRoute] for a given date and keeps it live as dogs,
 * schedules, incompatibilities, or settings change. Shared by the Today
 * screen (which browses days) and Follow plan (which executes one day's
 * plan), so the planning pipeline lives in exactly one place.
 *
 * Plans are cached by (inputs, seed): while the inputs and seed are
 * unchanged the same plan is returned without re-running BRouter or the
 * solver — so re-opening Follow plan or returning to a day is instant.
 * [refresh] bumps a date's seed to ask the randomised solver for a
 * different plan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DayPlanService(
    private val dogDao: DogDao,
    private val scheduleDao: DogScheduleDao,
    private val incompatibilityDao: DogIncompatibilityDao,
    private val appointmentDao: AppointmentDao,
    private val savedPlanDao: SavedPlanDao,
    private val settingsRepo: SettingsRepository,
    private val routingProvider: RoutingProvider,
) {
    private val seeds = MutableStateFlow<Map<LocalDate, Long>>(emptyMap())
    private val breaks = MutableStateFlow<Map<LocalDate, Boolean>>(emptyMap())

    // Access-ordered LRU: keeps the most recently used plans, evicts the rest.
    private val cache = object : LinkedHashMap<CacheKey, DayRoute>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<CacheKey, DayRoute>): Boolean =
            size > MAX_CACHED_PLANS
    }

    /**
     * Emits the [PlanState] for [date]. On a cache hit it emits [Ready]
     * straight away; on a miss it emits [Loading] (with a 0..1 progress
     * fraction the solver reports) and then [Ready] with the computed plan.
     * Re-emits when inputs or the date's seed change.
     */
    fun observePlan(date: LocalDate): Flow<PlanState> {
        val requests = combine(seeds, breaks) { s, b ->
            PlanRequest(s[date] ?: 0L, b[date] ?: false)
        }
        // Settings + appointments + the pinned/edited plan (if any) for the date.
        val settingsApptSaved = combine(
            settingsRepo.settings,
            appointmentDao.observeAll(),
            savedPlanDao.observeForDate(date),
        ) { s, a, saved -> Triple(s, a, saved) }
        return combine(
            dogDao.observeAll(),
            scheduleDao.observeAll(),
            incompatibilityDao.observeAll(),
            settingsApptSaved,
            requests,
        ) { dogs, rules, incompatibilities, (settings, appointments, saved), request ->
            PlanContext(Inputs(date, dogs, rules, incompatibilities, settings, appointments), request, saved)
        }.flatMapLatest { ctx ->
            channelFlow {
                // A pinned/edited plan wins: show it (rehydrated against current
                // dogs) instead of re-solving. A stale one (a referenced dog was
                // deleted) fails to rehydrate and we fall back.
                ctx.saved?.let { saved ->
                    SavedPlanCodec.decode(saved.planJson, date, ctx.inputs.dogs)?.let {
                        send(PlanState.Ready(it))
                        return@channelFlow
                    }
                }
                val key = CacheKey(ctx.inputs, ctx.request)
                cached(key)?.let {
                    send(PlanState.Ready(it))
                    return@channelFlow
                }
                send(PlanState.Loading(0f, PlanPhase.ROUTING))
                val plan = computePlan(ctx.inputs, ctx.request) { fraction, phase ->
                    trySend(PlanState.Loading(fraction, phase))
                }
                store(key, plan)
                send(PlanState.Ready(plan))
            }.conflate()
        }
    }

    /** Whether [date] currently has a hand-edited plan pinned. */
    fun observeIsEdited(date: LocalDate): Flow<Boolean> =
        savedPlanDao.observeForDate(date).map { it?.edited == true }

    /** Active dogs with coordinates — the candidates for a hand-added walk. */
    fun observeAddableDogs(): Flow<List<Dog>> =
        dogDao.observeAll().map { dogs ->
            dogs.filter { it.active && it.latitude != null && it.longitude != null }
        }

    /**
     * Mark a dog as not walked on [date]: drop it from [current], re-time the
     * rest, and pin the result. The plan flow then re-emits the edited plan.
     */
    suspend fun markDogNotToday(date: LocalDate, current: DayRoute, dogId: String) {
        pinEdited(date, removeDog(current, dogId))
    }

    /**
     * Set the [minutes] a single walk lasts (the [eventIndex]-th event of
     * [current], which must be a [RouteEvent.Walk]), re-time, and pin. The
     * manual duration is kept by re-timing with recomputeDwells = false.
     */
    suspend fun setWalkDuration(date: LocalDate, current: DayRoute, eventIndex: Int, minutes: Int) {
        val walk = current.events.getOrNull(eventIndex) as? RouteEvent.Walk ?: return
        val events = current.events.toMutableList()
        events[eventIndex] = walk.copy(durationSeconds = minutes.coerceAtLeast(0) * 60)
        pinEdited(date, current.copy(events = events))
    }

    /**
     * Add an extra walk for an existing [dogId] of [minutes] on [date]: a
     * pickup + walk + dropoff at the dog's address, inserted at the end of the
     * day (re-time places it), with an ad-hoc rule (no windows) stored inline
     * in the saved plan. The walker can shorten/move it with the other edits.
     */
    suspend fun addWalk(date: LocalDate, current: DayRoute, dogId: String, minutes: Int) {
        val dog = dogDao.findById(dogId) ?: return
        val lat = dog.latitude ?: return
        val lon = dog.longitude ?: return
        val loc = GeoPoint(lat, lon)
        val dur = minutes.coerceAtLeast(1)
        val rule = DogScheduleRule(
            id = "adhoc-$dogId-${System.currentTimeMillis()}",
            dogId = dogId, weekdaysMask = 0,
            earliestStart = null, latestStart = null, latestEnd = null,
            durationMinutes = dur, isAlternative = false,
        )
        val events = current.events.toMutableList()
        // Just before HomeEnd (the last event). Add in reverse so the final
        // order is pickup -> walk -> dropoff.
        val insertAt = (events.size - 1).coerceAtLeast(1)
        events.add(insertAt, RouteEvent.Dropoff(0, loc, dog))
        events.add(insertAt, RouteEvent.Walk(0, loc, listOf(dog), dur * 60))
        events.add(insertAt, RouteEvent.Pickup(0, loc, dog, rule))
        pinEdited(date, current.copy(events = events))
    }

    /**
     * Force a one-off, dog-free appointment into [date]'s plan: a fixed
     * commitment at [lat]/[lon] the walker must reach by [startSeconds] and
     * stay at until [endSeconds] (a doctor's visit, the shop, a manual lunch).
     * Inserted at its chronological spot and re-timed; [warningsFor] flags it
     * if a dog would still be aboard then.
     */
    suspend fun addAppointment(
        date: LocalDate,
        current: DayRoute,
        label: String,
        startSeconds: Int,
        endSeconds: Int,
        lat: Double,
        lon: Double,
    ) {
        val appt = RouteEvent.Appointment(
            timeSeconds = 0,
            location = GeoPoint(lat, lon),
            durationSeconds = (endSeconds - startSeconds).coerceAtLeast(0),
            startSeconds = startSeconds,
            label = label,
        )
        val events = current.events.toMutableList()
        val idx = events.indexOfFirst { it !is RouteEvent.HomeStart && it.timeSeconds >= startSeconds }
        val insertAt = if (idx < 1) events.size - 1 else idx
        events.add(insertAt, appt)
        pinEdited(date, current.copy(events = events))
    }

    /**
     * Pin the start time of the pickup at [eventIndex] to [secondsOfDay] (the
     * walker waits there until then) by setting its rule's earliestStart, then
     * re-time and pin.
     */
    suspend fun setStopTime(date: LocalDate, current: DayRoute, eventIndex: Int, secondsOfDay: Int) {
        val pickup = current.events.getOrNull(eventIndex) as? RouteEvent.Pickup ?: return
        val clamped = secondsOfDay.coerceIn(0, 24 * 3600 - 1)
        val newRule = pickup.rule.copy(earliestStart = LocalTime.ofSecondOfDay(clamped.toLong()))
        val events = current.events.toMutableList()
        events[eventIndex] = pickup.copy(rule = newRule)
        pinEdited(date, current.copy(events = events))
    }

    /**
     * Move the standalone walk at [walkEventIndex] one step [earlier] (or later)
     * in the day by swapping it with the adjacent standalone walk, then re-time
     * and pin. No-op if the walk is grouped/split or has no adjacent standalone
     * walk to swap with (see [moveStandaloneWalk]).
     */
    suspend fun moveWalk(date: LocalDate, current: DayRoute, walkEventIndex: Int, earlier: Boolean) {
        val reordered = moveStandaloneWalk(current.events, walkEventIndex, earlier) ?: return
        pinEdited(date, current.copy(events = reordered))
    }

    /** Re-time [edited] (keeping manual durations) and persist it as the pinned
     *  plan for [date], carrying over its conflicts. */
    private suspend fun pinEdited(date: LocalDate, edited: DayRoute) {
        val settings = settingsRepo.settings.first()
        // Incompatibilities are irrelevant to a pure re-time, so pass none.
        val retimed = buildPlanner(settings, emptySet())
            .retime(date, edited.events, recomputeDwells = false)
            ?.copy(conflicts = edited.conflicts)
            ?: edited
        savedPlanDao.upsert(
            SavedPlan(date, SavedPlanCodec.encode(retimed), edited = true, updatedAt = System.currentTimeMillis()),
        )
    }

    /** Constraint warnings for a (possibly hand-edited) plan — shown but not
     *  enforced, so the walker can keep an edit that bends a rule. */
    suspend fun warningsFor(route: DayRoute): List<String> {
        val settings = settingsRepo.settings.first()
        val pairs = incompatibilityDao.observeAll().first()
            .map { canonicalPair(it.dogIdA, it.dogIdB) }.toSet()
        return PlanVerifier.violations(
            route = route,
            capacityKg = settings.bikeCapacityKg,
            stopBufferSeconds = settings.stopBufferMinutes * 60,
            incompatibilities = pairs,
        )
    }

    /** Pin an already-timed [route] as the saved plan for [date] as-is (no
     *  re-time) — used by undo to restore a previous edit state. */
    suspend fun pinPlan(date: LocalDate, route: DayRoute) {
        savedPlanDao.upsert(
            SavedPlan(date, SavedPlanCodec.encode(route), edited = true, updatedAt = System.currentTimeMillis()),
        )
    }

    /** Discard a pinned/edited plan for [date], reverting to the solver. */
    suspend fun discardSavedPlan(date: LocalDate) = savedPlanDao.deleteForDate(date)

    /** Remove a dog's pickups, dropoffs and walk membership (drop emptied
     *  walks) and any conflict for it. Pure; the caller re-times the result. */
    private fun removeDog(route: DayRoute, dogId: String): DayRoute {
        val events = route.events.mapNotNull { e ->
            when (e) {
                is RouteEvent.Pickup -> e.takeIf { it.dog.id != dogId }
                is RouteEvent.Dropoff -> e.takeIf { it.dog.id != dogId }
                is RouteEvent.Walk -> {
                    val remaining = e.dogs.filter { it.id != dogId }
                    when {
                        remaining.isEmpty() -> null
                        remaining.size != e.dogs.size -> e.copy(dogs = remaining)
                        else -> e
                    }
                }
                else -> e
            }
        }
        return route.copy(events = events, conflicts = route.conflicts.filter { it.dog.id != dogId })
    }

    /** Ask for a different plan for [date] (advances its solver seed). */
    fun refresh(date: LocalDate) {
        seeds.update { it + (date to ((it[date] ?: 0L) + 1)) }
    }

    /** Whether [date]'s plan should include a mid-day break. */
    fun observeBreakRequested(date: LocalDate): Flow<Boolean> = breaks.map { it[date] ?: false }

    fun setBreakRequested(date: LocalDate, requested: Boolean) {
        breaks.update { it + (date to requested) }
    }

    private fun cached(key: CacheKey): DayRoute? = synchronized(cache) { cache[key] }
    private fun store(key: CacheKey, plan: DayRoute) {
        synchronized(cache) { cache[key] = plan }
    }

    private suspend fun computePlan(
        inputs: Inputs,
        request: PlanRequest,
        onProgress: (Float, PlanPhase) -> Unit,
    ): DayRoute {
        val bit = 1 shl (inputs.date.dayOfWeek.value - 1)
        val rulesForDay = inputs.rules.filter { (it.weekdaysMask and bit) != 0 }
        // Paused dogs are skipped: their rules find no dog and drop out below.
        val dogById = inputs.dogs.filter { it.active }.associateBy { it.id }

        // Group each dog's rules: every non-alternative rule is its own
        // required walk; all of a dog's alternative rules become one option
        // the planner satisfies by picking exactly one.
        val options: List<WalkOption> = rulesForDay
            .groupBy { it.dogId }
            .flatMap { (dogId, rules) ->
                val dog = dogById[dogId] ?: return@flatMap emptyList()
                val (alternatives, mandatory) = rules.partition { it.isAlternative }
                buildList {
                    mandatory.forEach { add(WalkOption(listOf(PlannedWalk(dog, it)))) }
                    if (alternatives.isNotEmpty()) {
                        add(WalkOption(alternatives.map { PlannedWalk(dog, it) }))
                    }
                }
            }

        val pairs = inputs.incompatibilities
            .map { canonicalPair(it.dogIdA, it.dogIdB) }
            .toSet()
        val planner = buildPlanner(inputs.settings, pairs)
        val breakSpec = inputs.settings.breakSpec().takeIf { request.breakRequested }
        val appointments = inputs.appointments
            .filter { it.date == inputs.date }
            .map { appt ->
                RouteEvent.Appointment(
                    timeSeconds = 0,
                    location = GeoPoint(appt.latitude, appt.longitude),
                    durationSeconds = (appt.endTime.toSecondOfDay() - appt.startTime.toSecondOfDay())
                        .coerceAtLeast(0),
                    startSeconds = appt.startTime.toSecondOfDay(),
                    label = appt.label,
                )
            }
        // Off the main thread: the matrix build (BRouter) and the solver
        // (multi-start + LNS) are CPU-bound and would otherwise freeze the UI
        // and trip an ANR. The flow's loading/result emissions stay on the
        // collector's context.
        return withContext(Dispatchers.Default) {
            planner.plan(inputs.date, options, request.seed, onProgress, breakSpec, appointments)
        }
    }

    private fun buildPlanner(settings: AppSettings, pairs: Set<Pair<String, String>>) = DayPlanner(
        routingProvider = routingProvider,
        home = settings.homeGeoPoint(),
        capacityKg = settings.bikeCapacityKg,
        stopBufferSeconds = settings.stopBufferMinutes * 60,
        cyclingSpeedKmh = settings.cyclingSpeedKmh,
        incompatibilities = pairs,
        cyclingWeight = settings.cyclingWeight,
        overWalkWeight = settings.overWalkWeight,
        walkingSpeedKmh = settings.walkingSpeedKmh,
        bikeOverheadSeconds = settings.bikeOverheadMinutes * 60,
        lnsIterations = settings.lnsIterations,
    )

    /** A [BreakSpec] from the settings. Home lunch works even with no break
     *  locations, so the spec is built whenever a break is requested. */
    private fun AppSettings.breakSpec(): BreakSpec = BreakSpec(
        locations = breakLocations.map { GeoPoint(it.latitude, it.longitude) },
        windowStartSeconds = breakWindowStart.toSecondOfDay(),
        windowEndSeconds = breakWindowEnd.toSecondOfDay(),
        durationSeconds = breakDurationMinutes * 60,
        homeLunchMinFreeSeconds = homeLunchMinFreeMinutes * 60,
    )

    private fun canonicalPair(a: String, b: String): Pair<String, String> =
        if (a < b) a to b else b to a

    private fun AppSettings.homeGeoPoint(): GeoPoint? {
        val lat = homeLatitude ?: return null
        val lon = homeLongitude ?: return null
        return GeoPoint(lat, lon)
    }

    private data class Inputs(
        val date: LocalDate,
        val dogs: List<Dog>,
        val rules: List<DogScheduleRule>,
        val incompatibilities: List<DogIncompatibility>,
        val settings: AppSettings,
        val appointments: List<Appointment>,
    )

    private data class PlanRequest(val seed: Long, val breakRequested: Boolean)

    private data class CacheKey(val inputs: Inputs, val request: PlanRequest)

    private data class PlanContext(val inputs: Inputs, val request: PlanRequest, val saved: SavedPlan?)
}
