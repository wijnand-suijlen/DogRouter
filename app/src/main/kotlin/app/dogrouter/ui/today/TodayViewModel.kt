package app.dogrouter.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.db.DogDao
import app.dogrouter.data.db.DogIncompatibilityDao
import app.dogrouter.data.db.DogScheduleDao
import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogIncompatibility
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.data.prefs.AppSettings
import app.dogrouter.data.prefs.SettingsRepository
import app.dogrouter.domain.dayplan.DayPlanner
import app.dogrouter.domain.dayplan.DayRoute
import app.dogrouter.domain.planner.PlannedWalk
import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.domain.routing.RoutingProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    dogDao: DogDao,
    scheduleDao: DogScheduleDao,
    incompatibilityDao: DogIncompatibilityDao,
    settingsRepo: SettingsRepository,
    private val routingProvider: RoutingProvider,
) : ViewModel() {

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    /**
     * Two-stage emission per input change:
     *  1. Emit null to mark the route as recomputing — the UI shows a
     *     loading state immediately.
     *  2. Run the PDPTW planner and emit the resulting DayRoute.
     *
     * flatMapLatest cancels an in-flight plan when inputs change, so we
     * never mix yesterday's events with today's.
     */
    val dayRoute: StateFlow<DayRoute?> = combine(
        _selectedDate,
        dogDao.observeAll(),
        scheduleDao.observeAll(),
        incompatibilityDao.observeAll(),
        settingsRepo.settings,
    ) { date, dogs, rules, incompatibilities, settings ->
        Inputs(date, dogs, rules, incompatibilities, settings)
    }.flatMapLatest { inputs ->
        flow {
            emit(null)
            emit(computePlan(inputs))
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings.DEFAULTS,
    )

    fun goToPreviousDay() {
        _selectedDate.value = _selectedDate.value.minusDays(1)
    }

    fun goToNextDay() {
        _selectedDate.value = _selectedDate.value.plusDays(1)
    }

    fun goToToday() {
        _selectedDate.value = LocalDate.now()
    }

    fun setDate(date: LocalDate) {
        _selectedDate.value = date
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
