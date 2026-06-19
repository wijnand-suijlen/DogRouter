package app.dogrouter.ui.addresspicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.remote.AddressSuggestion
import app.dogrouter.data.remote.BanApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface AddressPickerEvent {
    data class Picked(val address: AddressSuggestion) : AddressPickerEvent
    data object NoResult : AddressPickerEvent
}

class AddressPickerViewModel(
    private val banApi: BanApi,
) : ViewModel() {

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _events = Channel<AddressPickerEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun pickCenter(latitude: Double, longitude: Double) {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            val result = banApi.reverse(latitude, longitude)
            _loading.value = false
            if (result != null) {
                _events.send(AddressPickerEvent.Picked(result))
            } else {
                _events.send(AddressPickerEvent.NoResult)
            }
        }
    }
}
