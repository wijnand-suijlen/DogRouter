package app.dogrouter.ui.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.db.CommittedDayDao
import app.dogrouter.data.db.DogDao
import app.dogrouter.domain.dayplan.DayRoute
import app.dogrouter.domain.dayplan.SavedPlanCodec
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

/** Decodes the committed plan snapshot for [date] (against current dogs) so the
 *  exact plan that was billed can be shown read-only. */
class CommittedDayDetailViewModel(
    committedDayDao: CommittedDayDao,
    dogDao: DogDao,
    val date: LocalDate,
) : ViewModel() {

    val route: StateFlow<DayRoute?> =
        combine(committedDayDao.observeForDate(date), dogDao.observeAll()) { day, dogs ->
            day?.planJson?.takeIf { it.isNotBlank() }?.let { SavedPlanCodec.decode(it, date, dogs) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
