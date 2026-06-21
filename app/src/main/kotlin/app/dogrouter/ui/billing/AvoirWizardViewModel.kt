package app.dogrouter.ui.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.db.BillableServiceDao
import app.dogrouter.data.entity.BillableService
import app.dogrouter.domain.billing.InvoiceService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File

class AvoirWizardViewModel(
    private val serviceDao: BillableServiceDao,
    private val invoiceService: InvoiceService,
    private val ownerId: String,
    private val serviceId: String,
) : ViewModel() {

    private val _original = MutableStateFlow<BillableService?>(null)
    /** The already-paid service being corrected. */
    val original: StateFlow<BillableService?> = _original.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _result = Channel<File>(Channel.BUFFERED)
    /** Emits the credit-note PDF once created, to share it. */
    val result = _result.receiveAsFlow()

    init {
        viewModelScope.launch { _original.value = serviceDao.findById(serviceId) }
    }

    fun create(amountCents: Int) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                invoiceService.createCreditNote(ownerId, serviceId, amountCents)?.let { _result.send(it) }
            } finally {
                _busy.value = false
            }
        }
    }
}
