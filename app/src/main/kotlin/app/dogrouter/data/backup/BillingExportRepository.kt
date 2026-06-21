package app.dogrouter.data.backup

import app.dogrouter.data.db.BillableServiceDao
import app.dogrouter.data.db.InvoiceDao
import app.dogrouter.data.db.OwnerDao
import app.dogrouter.domain.billing.CsvExport
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the URSSAF export: a single ZIP containing the two CSV tables
 * (`wandelingen.csv` = all services, `ontvangsten.csv` = receipts) plus a full
 * `backup.json`, so the quarterly declaration always travels with a restorable
 * backup. CSVs get a UTF-8 BOM so Excel/LibreOffice show accents correctly.
 */
class BillingExportRepository(
    private val ownerDao: OwnerDao,
    private val serviceDao: BillableServiceDao,
    private val invoiceDao: InvoiceDao,
    private val backupRepo: BackupRepository,
) {
    suspend fun buildUrssafZip(): ByteArray {
        val owners = ownerDao.getAll().associateBy { it.id }
        val name: (String?) -> String = { id -> id?.let { owners[it]?.displayName }?.ifBlank { "(no name)" } ?: "(no owner)" }

        val servicesCsv = CsvExport.servicesCsv(serviceDao.getAll(), name)
        val receiptsCsv = CsvExport.receiptsCsv(invoiceDao.getAll()) { id -> name(id) }
        val backupJson = backupRepo.exportToJson()

        val bom = "\uFEFF"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putEntry("wandelingen.csv", bom + servicesCsv)
            zip.putEntry("ontvangsten.csv", bom + receiptsCsv)
            zip.putEntry("backup.json", backupJson)
        }
        return out.toByteArray()
    }

    private fun ZipOutputStream.putEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
