package app.dogrouter.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.db.DogDao
import app.dogrouter.data.db.DogScheduleDao
import app.dogrouter.data.prefs.AppSettings
import app.dogrouter.data.prefs.SettingsRepository
import app.dogrouter.domain.planner.DayPlan
import app.dogrouter.domain.planner.PlannedWalk
import app.dogrouter.domain.planner.TripPacker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate

class TodayViewModel(
    dogDao: DogDao,
    scheduleDao: DogScheduleDao,
    settingsRepo: SettingsRepository,
) : ViewModel() {

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    val dayPlan: StateFlow<DayPlan> = combine(
        _selectedDate,
        dogDao.observeAll(),
        scheduleDao.observeAll(),
        settingsRepo.settings,
    ) { date, dogs, rules, settings ->
        val bit = 1 shl (date.dayOfWeek.value - 1)
        val rulesForDay = rules.filter { (it.weekdaysMask and bit) != 0 }
        val dogById = dogs.associateBy { it.id }
        val walks: List<PlannedWalk> = rulesForDay.mapNotNull { rule ->
            dogById[rule.dogId]?.let { PlannedWalk(it, rule) }
        }
        DayPlan(date = date, trips = TripPacker.pack(walks, settings.bikeCapacityKg))
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
        _selectedDate.update { it.minusDays(1) }
    }

    fun goToNextDay() {
        _selectedDate.update { it.plusDays(1) }
    }

    fun goToToday() {
        _selectedDate.value = LocalDate.now()
    }

    fun setDate(date: LocalDate) {
        _selectedDate.value = date
    }
}
