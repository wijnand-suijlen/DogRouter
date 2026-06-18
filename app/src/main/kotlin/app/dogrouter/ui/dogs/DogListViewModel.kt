package app.dogrouter.ui.dogs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.db.DogDao
import app.dogrouter.data.entity.Dog
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class DogListViewModel(
    dogDao: DogDao,
) : ViewModel() {
    val dogs: StateFlow<List<Dog>> = dogDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
