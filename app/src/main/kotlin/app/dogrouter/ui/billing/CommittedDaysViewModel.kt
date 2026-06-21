package app.dogrouter.ui.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.db.CommittedDayDao
import app.dogrouter.data.entity.CommittedDay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class CommittedDaysViewModel(
    committedDayDao: CommittedDayDao,
) : ViewModel() {
    val days: StateFlow<List<CommittedDay>> = committedDayDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
