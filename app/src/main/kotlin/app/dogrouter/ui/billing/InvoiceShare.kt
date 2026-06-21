package app.dogrouter.ui.billing

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Share an invoice PDF via the system share sheet (email, print, save). */
internal fun sharePdf(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(intent, "Share invoice").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
