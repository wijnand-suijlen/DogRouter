package app.dogrouter.domain.billing

import app.dogrouter.data.db.BillableServiceDao
import app.dogrouter.data.db.InvoiceDao
import app.dogrouter.data.db.OwnerDao
import app.dogrouter.data.entity.Invoice
import app.dogrouter.data.entity.InvoiceKind
import app.dogrouter.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.LocalDate

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
        // share (so a facture turned acquittée keeps its number). The facture
        // button itself always issues a fresh number reflecting current settings
        // (e.g. the prefix), so a re-issued facture isn't stuck on an old number.
        val sharedNumber = if (pay) services.mapNotNull { it.invoiceNumber }.distinct().singleOrNull() else null
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

        val file = pdfWriter.write(
            InvoiceRender(
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
            ),
        )

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

    private fun formatNumber(counter: Int, isTest: Boolean, prefix: String): String =
        (if (isTest) "TEST-" else "") + prefix + "%04d".format(counter)
}
