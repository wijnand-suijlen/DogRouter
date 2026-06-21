package app.dogrouter.domain.billing

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import app.dogrouter.data.prefs.IssuerProfile
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** One line on the invoice. */
data class InvoiceLine(val date: LocalDate, val description: String, val amountCents: Int)

/** Everything needed to render one invoice PDF. */
data class InvoiceRender(
    val issuer: IssuerProfile,
    val ownerName: String,
    val ownerAddress: String,
    val number: String,
    val date: LocalDate,
    val lines: List<InvoiceLine>,
    val totalCents: Int,
    val acquitted: Boolean,
    val acquittedDate: LocalDate?,
    val isTest: Boolean,
    val isCreditNote: Boolean = false,
)

/**
 * Renders a French micro-entrepreneur (BNC, non-TVA) invoice to a PDF with the
 * built-in [PdfDocument] (no extra dependency). Test invoices get a big diagonal
 * "TEST" watermark and a TEST-prefixed file name. Layout is deliberately plain;
 * exact wording is driven by the editable issuer profile.
 */
class InvoicePdfWriter(private val context: Context) {

    fun write(render: InvoiceRender): File {
        val pageWidth = 595
        val pageHeight = 842
        val margin = 40f

        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
        val canvas = page.canvas

        val title = Paint().apply { color = Color.BLACK; textSize = 20f; typeface = Typeface.DEFAULT_BOLD }
        val bold = Paint().apply { color = Color.BLACK; textSize = 11f; typeface = Typeface.DEFAULT_BOLD }
        val normal = Paint().apply { color = Color.BLACK; textSize = 11f }
        val small = Paint().apply { color = Color.DKGRAY; textSize = 9f }
        val rightAmount = Paint().apply { color = Color.BLACK; textSize = 11f; textAlign = Paint.Align.RIGHT }

        var y = margin + 8f

        // Issuer block (top-left).
        canvas.drawText(render.issuer.name.ifBlank { "—" }, margin, y, bold)
        y += 15f
        for (line in issuerLines(render.issuer)) {
            canvas.drawText(line, margin, y, small)
            y += 12f
        }

        // Title + number + date (top-right).
        val titleText = when {
            render.isCreditNote -> "FACTURE D'AVOIR"
            render.acquitted -> "FACTURE ACQUITTÉE"
            else -> "FACTURE"
        }
        val rightX = pageWidth - margin
        canvas.drawText(titleText, rightX, margin + 20f, Paint(title).apply { textAlign = Paint.Align.RIGHT })
        canvas.drawText("N° ${render.number}", rightX, margin + 38f, rightAmountBold())
        canvas.drawText("Date : ${dateStr(render.date)}", rightX, margin + 54f, Paint(small).apply { textAlign = Paint.Align.RIGHT })

        y = maxOf(y, margin + 80f) + 20f

        // Bill-to block.
        canvas.drawText("Facturé à :", margin, y, bold)
        y += 14f
        canvas.drawText(render.ownerName.ifBlank { "—" }, margin, y, normal)
        y += 13f
        for (line in render.ownerAddress.split("\n").filter { it.isNotBlank() }) {
            canvas.drawText(line, margin, y, normal)
            y += 13f
        }

        y += 16f
        // Table header.
        canvas.drawLine(margin, y, rightX, y, normal)
        y += 14f
        canvas.drawText("Date", margin, y, bold)
        canvas.drawText("Désignation", margin + 90f, y, bold)
        canvas.drawText("Montant", rightX, y, Paint(bold).apply { textAlign = Paint.Align.RIGHT })
        y += 6f
        canvas.drawLine(margin, y, rightX, y, normal)
        y += 16f

        for (line in render.lines) {
            canvas.drawText(dateStr(line.date), margin, y, normal)
            canvas.drawText(line.description.take(48), margin + 90f, y, normal)
            canvas.drawText(formatEuros(line.amountCents), rightX, y, rightAmount)
            y += 16f
        }

        y += 6f
        canvas.drawLine(margin + 300f, y, rightX, y, normal)
        y += 16f
        canvas.drawText("Total", margin + 300f, y, bold)
        canvas.drawText(formatEuros(render.totalCents), rightX, y, Paint(rightAmount).apply { typeface = Typeface.DEFAULT_BOLD })
        y += 24f

        if (render.acquitted && render.acquittedDate != null) {
            canvas.drawText("Facture acquittée le ${dateStr(render.acquittedDate)}.", margin, y, normal)
            y += 18f
        }

        // Legal mentions footer.
        var footerY = pageHeight - margin - 12f * render.issuer.legalMentions.split("\n").size
        for (line in render.issuer.legalMentions.split("\n")) {
            canvas.drawText(line, margin, footerY, small)
            footerY += 12f
        }

        // TEST watermark (diagonal).
        if (render.isTest) {
            val wm = Paint().apply {
                color = Color.argb(40, 200, 0, 0)
                textSize = 140f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            canvas.save()
            canvas.rotate(-35f, pageWidth / 2f, pageHeight / 2f)
            canvas.drawText("TEST", pageWidth / 2f, pageHeight / 2f, wm)
            canvas.restore()
        }

        doc.finishPage(page)

        val dir = File(context.filesDir, "invoices").apply { mkdirs() }
        // The number already carries the "TEST-" prefix for test invoices, so the
        // file name needs no extra one.
        val safe = render.number.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "$safe.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private fun rightAmountBold() =
        Paint().apply { color = Color.BLACK; textSize = 11f; textAlign = Paint.Align.RIGHT; typeface = Typeface.DEFAULT_BOLD }

    private fun issuerLines(issuer: IssuerProfile): List<String> = buildList {
        issuer.address.split("\n").filter { it.isNotBlank() }.forEach { add(it) }
        if (issuer.siret.isNotBlank()) add("SIRET : ${issuer.siret}")
        if (issuer.email.isNotBlank()) add(issuer.email)
        if (issuer.phone.isNotBlank()) add(issuer.phone)
    }

    private fun dateStr(date: LocalDate): String =
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.FRANCE))
}
