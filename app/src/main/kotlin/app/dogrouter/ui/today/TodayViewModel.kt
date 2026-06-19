package app.dogrouter.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.db.DogDao
import app.dogrouter.data.db.DogScheduleDao
import app.dogrouter.data.prefs.AppSettings
import app.dogrouter.data.prefs.SettingsRepository
import app.dogrouter.domain.planner.DayPlan
import app.dogrouter.domain.planner.PlannedWalk
import app.dogrouter.domain.planner.Trip
import app.dogrouter.domain.planner.TripPacker
import app.dogrouter.domain.planner.TripRouter
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
    settingsRepo: SettingsRepository,
    routingProvider: RoutingProvider,
) : ViewModel() {

    private val tripRouter = TripRouter(routingProvider)

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    /**
     * Two-stage emission per (date, dogs, rules, settings) change:
     *   1. Pack walks into trips → emit immediately so the user sees
     *      who is going out without waiting on the router.
     *   2. Ask the router to fill in leg distances and times → emit again
     *      with the enriched plan.
     *
     * mapLatest at the outer level guarantees that an in-flight routing
     * pass is cancelled when inputs change, so we never paste yesterday's
     * leg times onto today's trip list.
     */
    val dayPlan: StateFlow<DayPlan> = combine(
        _selectedDate,
        dogDao.observeAll(),
        scheduleDao.observeAll(),
        settingsRepo.settings,
    ) { date, dogs, rules, settings ->
        DayPlanInputs(date, dogs, rules, settings)
    }.flatMapLatest { inputs ->
        flow {
            val basic = packBasicPlan(inputs)
            emit(basic)
            if (basic.trips.isNotEmpty()) {
                val routed = basic.copy(trips = tripRouter.routeAll(basic.trips))
                emit(routed)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DayPlan(date = LocalDate.now(), trips = emptyList()),
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

    private fun packBasicPlan(inputs: DayPlanInputs): DayPlan {
        val bit = 1 shl (inputs.date.dayOfWeek.value - 1)
        val rulesForDay = inputs.rules.filter { (it.weekdaysMask and bit) != 0 }
        val dogById = inputs.dogs.associateBy { it.id }
        val walks: List<PlannedWalk> = rulesForDay.mapNotNull { rule ->
            dogById[rule.dogId]?.let { PlannedWalk(it, rule) }
        }
        val trips: List<Trip> = TripPacker.pack(walks, inputs.settings.bikeCapacityKg)
        return DayPlan(date = inputs.date, trips = trips)
    }

    private data class DayPlanInputs(
        val date: LocalDate,
        val dogs: List<app.dogrouter.data.entity.Dog>,
        val rules: List<app.dogrouter.data.entity.DogScheduleRule>,
        val settings: AppSettings,
    )
}
