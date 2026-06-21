package app.dogrouter.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/** "FACTURE" (a normal invoice) or "AVOIR" (a credit note / negative correction). */
object InvoiceKind {
    const val FACTURE = "FACTURE"
    const val AVOIR = "AVOIR"
}

/**
 * An issued invoice document. [number] is the unique, continuous invoice number
 * (a separate `TEST-…` series for test owners keeps the real series unbroken, a
 * French requirement). [acquitted] marks it paid (a "facture acquittée"); the
 * same number is reused when a facture is later paid. The generated PDF lives at
 * [pdfPath].
 */
@Entity(tableName = "invoices")
data class Invoice(
    @PrimaryKey val number: String,
    val ownerId: String,
    val date: LocalDate,
    val kind: String = InvoiceKind.FACTURE,
    val isTest: Boolean = false,
    val acquitted: Boolean = false,
    val acquittedDate: LocalDate? = null,
    val totalCents: Int,
    val pdfPath: String? = null,
)
