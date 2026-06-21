package app.dogrouter.domain.billing

import app.dogrouter.data.entity.BillableService
import app.dogrouter.data.entity.Invoice
import app.dogrouter.data.entity.InvoiceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CsvExportTest {

    private fun service(owner: String?, date: LocalDate, amount: Int, paid: Boolean) = BillableService(
        id = "s-$owner-$date-$amount", ownerId = owner, date = date, dogId = "d",
        description = "Walk", amountCents = amount, durationMinutes = 60, paid = paid, committedAt = 0L,
    )

    @Test
    fun quarterIsComputedFromTheMonth() {
        assertEquals("2026-Q1", CsvExport.quarter(LocalDate.of(2026, 3, 31)))
        assertEquals("2026-Q2", CsvExport.quarter(LocalDate.of(2026, 4, 1)))
        assertEquals("2026-Q4", CsvExport.quarter(LocalDate.of(2026, 12, 15)))
    }

    @Test
    fun amountsUseADecimalComma() {
        assertEquals("16,00", CsvExport.csvEuros(1600))
        assertEquals("8,50", CsvExport.csvEuros(850))
        assertEquals("-16,00", CsvExport.csvEuros(-1600))
    }

    @Test
    fun servicesCsvHasHeaderAndRows() {
        val csv = CsvExport.servicesCsv(
            listOf(service("o1", LocalDate.of(2026, 4, 2), 1600, paid = true)),
            ownerName = { "Alfa" },
        )
        val lines = csv.trim().split("\r\n")
        assertEquals("Datum;Kwartaal;Eigenaar;Omschrijving;Bedrag;Betaald", lines[0])
        assertEquals("2026-04-02;2026-Q2;Alfa;Walk;16,00;ja", lines[1])
    }

    @Test
    fun receiptsExcludeTestOwnersAndUnpaidInvoices() {
        val invoices = listOf(
            Invoice("R-1", "o1", LocalDate.of(2026, 4, 2), InvoiceKind.FACTURE, isTest = false,
                acquitted = true, acquittedDate = LocalDate.of(2026, 4, 3), totalCents = 1600),
            Invoice("R-2", "o2", LocalDate.of(2026, 4, 2), InvoiceKind.FACTURE, isTest = false,
                acquitted = false, acquittedDate = null, totalCents = 1000), // not paid → excluded
            Invoice("TEST-1", "o3", LocalDate.of(2026, 4, 2), InvoiceKind.FACTURE, isTest = true,
                acquitted = true, acquittedDate = LocalDate.of(2026, 4, 3), totalCents = 9999), // test → excluded
        )
        val csv = CsvExport.receiptsCsv(invoices, ownerName = { "Owner $it" })
        assertTrue(csv.contains("R-1"))
        assertFalse(csv.contains("R-2"))
        assertFalse(csv.contains("TEST-1"))
    }

    @Test
    fun creditNoteShowsAsNegativeReceipt() {
        val invoices = listOf(
            Invoice("A-1", "o1", LocalDate.of(2026, 5, 1), InvoiceKind.AVOIR, isTest = false,
                acquitted = true, acquittedDate = LocalDate.of(2026, 5, 1), totalCents = -1600),
        )
        val csv = CsvExport.receiptsCsv(invoices, ownerName = { "Alfa" })
        assertTrue(csv.contains("-16,00"))
    }

    @Test
    fun fieldsWithSeparatorAreQuoted() {
        val csv = CsvExport.servicesCsv(
            listOf(service("o1", LocalDate.of(2026, 4, 2), 1600, paid = false)),
            ownerName = { "Doe; Jane" },
        )
        assertTrue(csv.contains("\"Doe; Jane\""))
    }
}
