package app.dogrouter.domain.billing

import app.dogrouter.data.entity.BillableService
import app.dogrouter.data.entity.Invoice
import kotlin.math.abs
import java.time.LocalDate

/**
 * Builds the two URSSAF CSV tables. French locale: ';' separator and a decimal
 * comma so Excel/LibreOffice parse the amounts as numbers. A quarter column
 * (e.g. "2026-Q2") lets the walker sum each quarter's figure.
 *
 * For micro-BNC the declared turnover is the **receipts** (cash actually
 * received), so [receiptsCsv] — paid invoices and credit notes, test owners
 * excluded — is the table to declare from; [servicesCsv] is the full detail.
 */
object CsvExport {

    fun servicesCsv(
        services: List<BillableService>,
        ownerName: (String?) -> String,
    ): String = buildCsv(
        header = listOf("Datum", "Kwartaal", "Eigenaar", "Omschrijving", "Bedrag", "Betaald"),
        rows = services.sortedBy { it.date }.map { s ->
            listOf(
                s.date.toString(),
                quarter(s.date),
                ownerName(s.ownerId),
                s.description,
                csvEuros(s.amountCents),
                if (s.paid) "ja" else "nee",
            )
        },
    )

    /** Receipts (encaissements): acquitted invoices + credit notes, **excluding
     *  test owners**. Amount is negative for a credit note. */
    fun receiptsCsv(
        invoices: List<Invoice>,
        ownerName: (String) -> String,
    ): String {
        val receipts = invoices.filter { it.acquitted && !it.isTest }
        return buildCsv(
            header = listOf("Datum", "Kwartaal", "Factuurnummer", "Eigenaar", "Bedrag"),
            rows = receipts
                .sortedBy { it.acquittedDate ?: it.date }
                .map { inv ->
                    val date = inv.acquittedDate ?: inv.date
                    listOf(
                        date.toString(),
                        quarter(date),
                        inv.number,
                        ownerName(inv.ownerId),
                        csvEuros(inv.totalCents),
                    )
                },
        )
    }

    /** "2026-Q2" for a date in April–June 2026. */
    fun quarter(date: LocalDate): String = "${date.year}-Q${(date.monthValue - 1) / 3 + 1}"

    /** Amount in euros with a decimal comma and no symbol, e.g. "16,00" / "-8,00". */
    fun csvEuros(cents: Int): String {
        val sign = if (cents < 0) "-" else ""
        val a = abs(cents)
        return "$sign${a / 100},%02d".format(a % 100)
    }

    private fun buildCsv(header: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append(header.joinToString(";") { escape(it) }).append("\r\n")
        for (row in rows) sb.append(row.joinToString(";") { escape(it) }).append("\r\n")
        return sb.toString()
    }

    private fun escape(value: String): String =
        if (value.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
