package app.dogrouter.ui.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.db.BillableServiceDao
import app.dogrouter.data.db.OwnerDao
import app.dogrouter.data.entity.BillableService
import app.dogrouter.data.entity.Owner
import app.dogrouter.domain.billing.InvoiceService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

class OwnerAccountViewModel(
    private val ownerDao: OwnerDao,
    private val serviceDao: BillableServiceDao,
    private val invoiceService: InvoiceService,
    private val ownerId: String,
) : ViewModel() {

    val owner: StateFlow<Owner?> = ownerDao.observeAll()
        .map { all -> all.firstOrNull { it.id == ownerId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val services: StateFlow<List<BillableService>> = serviceDao.observeForOwner(ownerId)
        .map { list -> list.sortedWith(compareByDescending<BillableService> { it.date }.thenBy { it.description }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val balanceCents: StateFlow<Int> = services
        .map { list -> list.filter { !it.paid }.sumOf { it.amountCents } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Total walked minutes per month (newest first) — the figure an employer
     *  owner pays the walker on. */
    val monthlyMinutes: StateFlow<List<Pair<YearMonth, Int>>> = services
        .map { list ->
            list.groupBy { YearMonth.from(it.date) }
                .map { (month, ss) -> month to ss.sumOf { it.durationMinutes } }
                .sortedByDescending { it.first }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Add a hand-entered service (non-standard work) to this account. */
    fun addManualItem(description: String, amountCents: Int, date: LocalDate) {
        viewModelScope.launch {
            serviceDao.insert(
                BillableService(
                    id = UUID.randomUUID().toString(),
                    ownerId = ownerId,
                    date = date,
                    dogId = null,
                    description = description,
                    amountCents = amountCents,
                    durationMinutes = 0,
                    paid = false,
                    paidDate = null,
                    invoiceNumber = null,
                    isManual = true,
                    committedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** Remove a service entered by mistake — only while it is still unpaid. */
    fun removeService(service: BillableService) {
        if (service.paid) return
        viewModelScope.launch { serviceDao.delete(service) }
    }

    // --- Invoicing / payment --------------------------------------------------

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of unpaid services ticked for invoicing/payment. */
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _generatedPdf = Channel<File>(Channel.BUFFERED)
    /** Emits the PDF file just generated, so the screen can offer to share it. */
    val generatedPdf = _generatedPdf.receiveAsFlow()

    fun toggleSelected(id: String) {
        _selected.update { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    /** Issue a (proof) facture for the ticked services. */
    fun makeInvoice() = generate { ids -> invoiceService.makeInvoice(ownerId, ids) }

    /** Register payment for the ticked services (facture acquittée). */
    fun registerPayment() = generate { ids -> invoiceService.registerPayment(ownerId, ids) }

    private fun generate(block: suspend (ids: List<String>) -> File?) {
        val ids = _selected.value.toList()
        if (ids.isEmpty()) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val file = block(ids)
                _selected.value = emptySet()
                if (file != null) _generatedPdf.send(file)
            } finally {
                _busy.value = false
            }
        }
    }
}
