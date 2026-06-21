package app.dogrouter.ui.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.db.BillableServiceDao
import app.dogrouter.data.db.OwnerDao
import app.dogrouter.data.entity.Owner
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth

/** One owner's roll-up for the billing overview. */
data class OwnerSummary(
    val owner: Owner,
    val unpaidCents: Int,
    val currentMonthMinutes: Int,
)

class BillingOverviewViewModel(
    ownerDao: OwnerDao,
    serviceDao: BillableServiceDao,
    private val today: () -> YearMonth = { YearMonth.now() },
) : ViewModel() {

    val summaries: StateFlow<List<OwnerSummary>> =
        combine(ownerDao.observeAll(), serviceDao.observeAll()) { owners, services ->
            val month = today()
            owners.map { owner ->
                val own = services.filter { it.ownerId == owner.id }
                OwnerSummary(
                    owner = owner,
                    unpaidCents = own.filter { !it.paid }.sumOf { it.amountCents },
                    currentMonthMinutes = own
                        .filter { YearMonth.from(it.date) == month }
                        .sumOf { it.durationMinutes },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
