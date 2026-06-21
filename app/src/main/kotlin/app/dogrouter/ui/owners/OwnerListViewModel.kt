package app.dogrouter.ui.owners

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.db.OwnerDao
import app.dogrouter.data.entity.Owner
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class OwnerListViewModel(
    ownerDao: OwnerDao,
) : ViewModel() {
    val owners: StateFlow<List<Owner>> = ownerDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
