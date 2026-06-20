package app.dogrouter.ui.dogs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.db.DogDao
import app.dogrouter.data.entity.Dog
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DogListViewModel(
    private val dogDao: DogDao,
) : ViewModel() {
    val dogs: StateFlow<List<Dog>> = dogDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Pause or resume a dog; a paused dog is kept but skipped by the planner. */
    fun setActive(dog: Dog, active: Boolean) {
        if (dog.active == active) return
        viewModelScope.launch { dogDao.update(dog.copy(active = active)) }
    }
}
