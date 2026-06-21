package app.dogrouter.ui.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.db.InvoiceDao
import app.dogrouter.data.entity.Invoice
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class OwnerInvoicesViewModel(
    invoiceDao: InvoiceDao,
    ownerId: String,
) : ViewModel() {
    val invoices: StateFlow<List<Invoice>> = invoiceDao.observeForOwner(ownerId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
