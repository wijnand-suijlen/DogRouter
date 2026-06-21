package app.dogrouter.domain.billing

import app.dogrouter.data.prefs.IssuerProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Frozen, self-contained snapshot of everything printed on an invoice, stored on
 * the invoice (`Invoice.renderJson`). It makes a reprint **identical** to the
 * original even if the issuer profile, owner or prices change later, and is the
 * single source both the in-app "regenerate" and the laptop `tools/
 * regenerate_invoices.py` script render from.
 */
@Serializable
data class InvoiceLineDto(val date: String, val description: String, val amountCents: Int)

@Serializable
data class InvoiceRenderDto(
    val issuer: IssuerProfile,
    val ownerName: String,
    val ownerAddress: String,
    val number: String,
    val date: String,
    val lines: List<InvoiceLineDto>,
    val totalCents: Int,
    val acquitted: Boolean,
    val acquittedDate: String?,
    val isTest: Boolean,
    val isCreditNote: Boolean,
)

object InvoiceRenderCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    fun encode(render: InvoiceRender): String = json.encodeToString(render.toDto())

    fun decode(text: String): InvoiceRender? =
        runCatching { json.decodeFromString<InvoiceRenderDto>(text).toRender() }.getOrNull()

    private fun InvoiceRender.toDto() = InvoiceRenderDto(
        issuer = issuer,
        ownerName = ownerName,
        ownerAddress = ownerAddress,
        number = number,
        date = date.toString(),
        lines = lines.map { InvoiceLineDto(it.date.toString(), it.description, it.amountCents) },
        totalCents = totalCents,
        acquitted = acquitted,
        acquittedDate = acquittedDate?.toString(),
        isTest = isTest,
        isCreditNote = isCreditNote,
    )

    private fun InvoiceRenderDto.toRender() = InvoiceRender(
        issuer = issuer,
        ownerName = ownerName,
        ownerAddress = ownerAddress,
        number = number,
        date = LocalDate.parse(date),
        lines = lines.map { InvoiceLine(LocalDate.parse(it.date), it.description, it.amountCents) },
        totalCents = totalCents,
        acquitted = acquitted,
        acquittedDate = acquittedDate?.let { LocalDate.parse(it) },
        isTest = isTest,
        isCreditNote = isCreditNote,
    )
}
