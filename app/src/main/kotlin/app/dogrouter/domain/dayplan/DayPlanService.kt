package app.dogrouter.domain.dayplan

import app.dogrouter.data.db.DogDao
import app.dogrouter.data.db.DogIncompatibilityDao
import app.dogrouter.data.db.DogScheduleDao
import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogIncompatibility
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.data.prefs.AppSettings
import app.dogrouter.data.prefs.SettingsRepository
import app.dogrouter.domain.planner.PlannedWalk
import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.domain.routing.RoutingProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.LocalDate

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
    private val settingsRepo: SettingsRepository,
    private val routingProvider: RoutingProvider,
) {
    private val seeds = MutableStateFlow<Map<LocalDate, Long>>(emptyMap())

    // Access-ordered LRU: keeps the most recently used plans, evicts the rest.
    private val cache = object : LinkedHashMap<CacheKey, DayRoute>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<CacheKey, DayRoute>): Boolean =
            size > MAX_CACHED_PLANS
    }

    /**
     * Emits the plan for [date]. On a cache hit it emits the cached plan
     * straight away; on a miss it emits null (so the UI can show a loading
     * state) and then the freshly computed plan. Re-emits when inputs or
     * the date's seed change.
     */
    fun observePlan(date: LocalDate): Flow<DayRoute?> = combine(
        dogDao.observeAll(),
        scheduleDao.observeAll(),
        incompatibilityDao.observeAll(),
        settingsRepo.settings,
        seeds.map { it[date] ?: 0L },
    ) { dogs, rules, incompatibilities, settings, seed ->
        Inputs(date, dogs, rules, incompatibilities, settings) to seed
    }.flatMapLatest { (inputs, seed) ->
        flow {
            val key = CacheKey(inputs, seed)
            cached(key)?.let {
                emit(it)
                return@flow
            }
            emit(null)
            val plan = computePlan(inputs, seed)
            store(key, plan)
            emit(plan)
        }
    }

    /** Ask for a different plan for [date] (advances its solver seed). */
    fun refresh(date: LocalDate) {
        seeds.update { it + (date to ((it[date] ?: 0L) + 1)) }
    }

    private fun cached(key: CacheKey): DayRoute? = synchronized(cache) { cache[key] }
    private fun store(key: CacheKey, plan: DayRoute) {
        synchronized(cache) { cache[key] = plan }
    }

    private suspend fun computePlan(inputs: Inputs, seed: Long): DayRoute {
        val bit = 1 shl (inputs.date.dayOfWeek.value - 1)
        val rulesForDay = inputs.rules.filter { (it.weekdaysMask and bit) != 0 }
        val dogById = inputs.dogs.associateBy { it.id }
        val walks: List<PlannedWalk> = rulesForDay.mapNotNull { rule ->
            dogById[rule.dogId]?.let { PlannedWalk(it, rule) }
        }
        val pairs = inputs.incompatibilities
            .map { canonicalPair(it.dogIdA, it.dogIdB) }
            .toSet()
        val planner = DayPlanner(
            routingProvider = routingProvider,
            home = inputs.settings.homeGeoPoint(),
            capacityKg = inputs.settings.bikeCapacityKg,
            stopBufferSeconds = inputs.settings.stopBufferMinutes * 60,
            cyclingSpeedKmh = inputs.settings.cyclingSpeedKmh,
            incompatibilities = pairs,
        )
        return planner.plan(inputs.date, walks, seed)
    }

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
    )

    private data class CacheKey(val inputs: Inputs, val seed: Long)
}
