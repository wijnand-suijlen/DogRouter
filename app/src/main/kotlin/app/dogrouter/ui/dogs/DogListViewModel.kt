package app.dogrouter.ui.dogs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.db.DogDao
import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.TransportState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class DogListViewModel(
    private val dogDao: DogDao,
) : ViewModel() {

    val dogs: StateFlow<List<Dog>> = dogDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addExampleDog() {
        viewModelScope.launch {
            dogDao.insert(
                Dog(
                    id = UUID.randomUUID().toString(),
                    name = "Test ${(dogs.value.size + 1)}",
                    breed = null,
                    weightKg = 10f,
                    photoUri = null,
                    ownerName = "Owner",
                    ownerPhone = null,
                    address = "—",
                    stopNotes = null,
                    inCargoBike = TransportState.NotTested,
                    inBackpack = TransportState.NotTested,
                    notes = null,
                ),
            )
        }
    }

    fun deleteDog(dog: Dog) {
        viewModelScope.launch {
            dogDao.delete(dog)
        }
    }
}
