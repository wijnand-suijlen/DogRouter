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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import java.time.LocalDate

/**
 * Builds the PDPTW [DayRoute] for a given date and keeps it live as dogs,
 * schedules, incompatibilities, or settings change. Shared by the Today
 * screen (which browses days) and Follow plan (which executes one day's
 * plan), so the planning pipeline lives in exactly one place.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DayPlanService(
    private val dogDao: DogDao,
    private val scheduleDao: DogScheduleDao,
    private val incompatibilityDao: DogIncompatibilityDao,
    private val settingsRepo: SettingsRepository,
    private val routingProvider: RoutingProvider,
) {
    /**
     * Emits null first to signal "recomputing" (so the UI can show a
     * loading state immediately), then the planned route. Re-emits on any
     * input change; consumers should collect with flatMapLatest so an
     * in-flight plan is cancelled when inputs change and yesterday's
     * events never mix with today's.
     */
    fun observePlan(date: LocalDate): Flow<DayRoute?> = combine(
        dogDao.observeAll(),
        scheduleDao.observeAll(),
        incompatibilityDao.observeAll(),
        settingsRepo.settings,
    ) { dogs, rules, incompatibilities, settings ->
        Inputs(date, dogs, rules, incompatibilities, settings)
    }.flatMapLatest { inputs ->
        flow {
            emit(null)
            emit(computePlan(inputs))
        }
    }

    private suspend fun computePlan(inputs: Inputs): DayRoute {
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
        return planner.plan(inputs.date, walks)
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
}
