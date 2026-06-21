package app.dogrouter.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * One billable line on an owner's running account: a walk done on [date] (or a
 * hand-added item when [isManual]). Created when a day plan is committed; the
 * [amountCents] is frozen at commit time. A service is either unpaid (counts
 * toward the running balance) or [paid] (settled by a payment / facture
 * acquittée). [invoiceNumber] records the last invoice it appeared on.
 */
@Entity(tableName = "billable_services")
data class BillableService(
    @PrimaryKey val id: String,
    val ownerId: String?,
    val date: LocalDate,
    val dogId: String?,
    val description: String,
    val amountCents: Int,
    val durationMinutes: Int,
    val paid: Boolean = false,
    val paidDate: LocalDate? = null,
    val invoiceNumber: String? = null,
    val isManual: Boolean = false,
    val committedAt: Long,
)
