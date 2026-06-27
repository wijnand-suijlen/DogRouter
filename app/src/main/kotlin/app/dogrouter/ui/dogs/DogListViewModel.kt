package app.dogrouter.ui.dogs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.db.DogDao
import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DogListViewModel(
    private val dogDao: DogDao,
) : ViewModel() {
    val dogs: StateFlow<List<Dog>> = dogDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Set a dog's day status (off / normal walks / one of the boarding day
     *  positions). OFF keeps the dog but skips it in the planner. */
    fun setStatus(dog: Dog, status: DogStatus) {
        if (dog.status == status) return
        viewModelScope.launch { dogDao.update(dog.copy(status = status)) }
    }
}
