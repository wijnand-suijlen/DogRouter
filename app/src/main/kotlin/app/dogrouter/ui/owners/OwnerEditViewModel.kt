package app.dogrouter.ui.owners

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.db.OwnerDao
import app.dogrouter.data.entity.Owner
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class OwnerFormState(
    val firstName: String = "",
    val lastName: String = "",
    val billingAddress: String = "",
    val phone: String = "",
    val email: String = "",
    val isEmployer: Boolean = false,
    val isTest: Boolean = false,
    val loading: Boolean = false,
)

sealed interface OwnerEditEvent {
    data object Closed : OwnerEditEvent
    data class ValidationError(val message: String) : OwnerEditEvent
}

class OwnerEditViewModel(
    private val ownerDao: OwnerDao,
    private val ownerId: String?,
) : ViewModel() {

    val isNew: Boolean = ownerId == null

    private val _state = MutableStateFlow(OwnerFormState(loading = !isNew))
    val state: StateFlow<OwnerFormState> = _state.asStateFlow()

    private val _events = Channel<OwnerEditEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        if (ownerId != null) loadExisting(ownerId)
    }

    private fun loadExisting(id: String) {
        viewModelScope.launch {
            val o = ownerDao.findById(id) ?: run {
                _events.send(OwnerEditEvent.Closed)
                return@launch
            }
            _state.update {
                it.copy(
                    firstName = o.firstName,
                    lastName = o.lastName,
                    billingAddress = o.billingAddress,
                    phone = o.phone.orEmpty(),
                    email = o.email.orEmpty(),
                    isEmployer = o.isEmployer,
                    isTest = o.isTest,
                    loading = false,
                )
            }
        }
    }

    fun update(transform: OwnerFormState.() -> OwnerFormState) = _state.update(transform)

    fun save() {
        viewModelScope.launch {
            val s = _state.value
            if (s.firstName.isBlank() && s.lastName.isBlank()) {
                _events.send(OwnerEditEvent.ValidationError("A first or last name is required"))
                return@launch
            }
            val existing = ownerId?.let { ownerDao.findById(it) }
            val owner = Owner(
                id = ownerId ?: UUID.randomUUID().toString(),
                firstName = s.firstName.trim(),
                lastName = s.lastName.trim(),
                billingAddress = s.billingAddress.trim(),
                phone = s.phone.trim().ifBlank { null },
                email = s.email.trim().ifBlank { null },
                isEmployer = s.isEmployer,
                isTest = s.isTest,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            )
            if (isNew) ownerDao.insert(owner) else ownerDao.update(owner)
            _events.send(OwnerEditEvent.Closed)
        }
    }
}
