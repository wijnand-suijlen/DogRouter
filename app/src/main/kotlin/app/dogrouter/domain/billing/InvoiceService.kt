package app.dogrouter.domain.billing

import app.dogrouter.data.db.BillableServiceDao
import app.dogrouter.data.db.InvoiceDao
import app.dogrouter.data.db.OwnerDao
import app.dogrouter.data.entity.BillableService
import app.dogrouter.data.entity.Invoice
import app.dogrouter.data.entity.InvoiceKind
import app.dogrouter.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * Creates invoices (factures) and registers payments (factures acquittées) for
 * a set of billable services, generating the PDF and keeping the running account
 * in sync. Real and test owners use separate, continuous number series.
 */
class InvoiceService(
    private val invoiceDao: InvoiceDao,
    private val serviceDao: BillableServiceDao,
    private val ownerDao: OwnerDao,
    private val settingsRepo: SettingsRepository,
    private val pdfWriter: InvoicePdfWriter,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    /** Issue a (proof) facture for the selected services. Returns the PDF, or
     *  null if nothing valid was selected. */
    suspend fun makeInvoice(ownerId: String, serviceIds: List<String>): File? =
        generate(ownerId, serviceIds, pay = false)

    /** Register payment: mark the selected services paid and produce a facture
     *  acquittée (reusing an existing facture number when they share one). */
    suspend fun registerPayment(ownerId: String, serviceIds: List<String>): File? =
        generate(ownerId, serviceIds, pay = true)

    private suspend fun generate(ownerId: String, serviceIds: List<String>, pay: Boolean): File? {
        val owner = ownerDao.findById(ownerId) ?: return null
        val services = serviceIds.mapNotNull { serviceDao.findById(it) }.filter { it.ownerId == ownerId }
        if (services.isEmpty()) return null
        val settings = settingsRepo.settings.first()
        val total = services.sumOf { it.amountCents }

        // When registering payment, reuse the facture number the services already
        // share (so a facture turned acquittée keeps its number) — but only if it
        // already matches the current prefix/series; otherwise issue a fresh,
        // correctly-prefixed number (a stale number from before the prefix was set
        // shouldn't carry over). The facture button always issues a fresh number.
        val expectedPrefix = (if (owner.isTest) "TEST-" else "") + settings.issuer.invoiceNumberPrefix
        val sharedNumber = if (pay) {
            services.mapNotNull { it.invoiceNumber }.distinct().singleOrNull()?.takeIf { it.startsWith(expectedPrefix) }
        } else {
            null
        }
        val reuse = sharedNumber?.let { invoiceDao.findByNumber(it) }
        val number = reuse?.number ?: formatNumber(
            counter = settingsRepo.takeNextInvoiceNumber(owner.isTest),
            isTest = owner.isTest,
            prefix = settings.issuer.invoiceNumberPrefix,
        )
        val date = reuse?.date ?: today()
        val acquitted = pay || reuse?.acquitted == true
        val acquittedDate = when {
            pay -> today()
            else -> reuse?.acquittedDate
        }

        val render = InvoiceRender(
            issuer = settings.issuer,
            ownerName = owner.displayName,
            ownerAddress = owner.billingAddress,
            number = number,
            date = date,
            lines = services.map { InvoiceLine(it.date, it.description, it.amountCents) },
            totalCents = total,
            acquitted = acquitted,
            acquittedDate = acquittedDate,
            isTest = owner.isTest,
        )
        val file = pdfWriter.write(render)

        invoiceDao.upsert(
            Invoice(
                number = number,
                ownerId = ownerId,
                date = date,
                kind = InvoiceKind.FACTURE,
                isTest = owner.isTest,
                acquitted = acquitted,
                acquittedDate = acquittedDate,
                totalCents = total,
                pdfPath = file.absolutePath,
                renderJson = InvoiceRenderCodec.encode(render),
            ),
        )

        serviceDao.updateAll(
            services.map {
                it.copy(
                    invoiceNumber = number,
                    paid = if (pay) true else it.paid,
                    paidDate = if (pay) acquittedDate else it.paidDate,
                )
            },
        )
        return file
    }

    /**
     * Create a credit note (facture d'avoir) of [amountCents] for an already-
     * paid [originalServiceId]: a negative, settled service plus an AVOIR invoice
     * and its PDF. The negative amount lands in the receipts of its own quarter,
     * correcting the turnover. Returns the PDF, or null if inputs are missing.
     */
    suspend fun createCreditNote(ownerId: String, originalServiceId: String, amountCents: Int): File? {
        val owner = ownerDao.findById(ownerId) ?: return null
        val original = serviceDao.findById(originalServiceId) ?: return null
        val settings = settingsRepo.settings.first()
        val amount = -kotlin.math.abs(amountCents)
        val date = today()
        val number = formatNumber(
            counter = settingsRepo.takeNextInvoiceNumber(owner.isTest),
            isTest = owner.isTest,
            prefix = settings.issuer.invoiceNumberPrefix,
        )
        val description = "Avoir : ${original.description}"

        serviceDao.insert(
            BillableService(
                id = UUID.randomUUID().toString(),
                ownerId = ownerId,
                date = date,
                dogId = original.dogId,
                description = description,
                amountCents = amount,
                durationMinutes = 0,
                paid = true,
                paidDate = date,
                invoiceNumber = number,
                isManual = true,
                committedAt = System.currentTimeMillis(),
            ),
        )

        val render = InvoiceRender(
            issuer = settings.issuer,
            ownerName = owner.displayName,
            ownerAddress = owner.billingAddress,
            number = number,
            date = date,
            lines = listOf(InvoiceLine(date, original.description, amount)),
            totalCents = amount,
            acquitted = true,
            acquittedDate = date,
            isTest = owner.isTest,
            isCreditNote = true,
        )
        val file = pdfWriter.write(render)

        invoiceDao.upsert(
            Invoice(
                number = number,
                ownerId = ownerId,
                date = date,
                kind = InvoiceKind.AVOIR,
                isTest = owner.isTest,
                acquitted = true,
                acquittedDate = date,
                totalCents = amount,
                pdfPath = file.absolutePath,
                renderJson = InvoiceRenderCodec.encode(render),
            ),
        )
        return file
    }

    /**
     * Re-render an existing invoice's PDF from its frozen snapshot (identical to
     * the original). Falls back to reconstructing from the linked services +
     * current issuer/owner for invoices issued before snapshots existed. Returns
     * null if the invoice is gone.
     */
    suspend fun regenerate(number: String): File? {
        val invoice = invoiceDao.findByNumber(number) ?: return null
        val render = InvoiceRenderCodec.decode(invoice.renderJson) ?: reconstructRender(invoice) ?: return null
        return pdfWriter.write(render)
    }

    private suspend fun reconstructRender(invoice: Invoice): InvoiceRender? {
        val owner = ownerDao.findById(invoice.ownerId) ?: return null
        val settings = settingsRepo.settings.first()
        val services = serviceDao.findByInvoiceNumber(invoice.number).sortedBy { it.date }
        return InvoiceRender(
            issuer = settings.issuer,
            ownerName = owner.displayName,
            ownerAddress = owner.billingAddress,
            number = invoice.number,
            date = invoice.date,
            lines = services.map { InvoiceLine(it.date, it.description, it.amountCents) },
            totalCents = invoice.totalCents,
            acquitted = invoice.acquitted,
            acquittedDate = invoice.acquittedDate,
            isTest = invoice.isTest,
            isCreditNote = invoice.kind == InvoiceKind.AVOIR,
        )
    }

    private fun formatNumber(counter: Int, isTest: Boolean, prefix: String): String =
        (if (isTest) "TEST-" else "") + prefix + "%04d".format(counter)
}
