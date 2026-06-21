package app.dogrouter.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * Record that a day's plan has been committed to billing: used to list
 * committed days (traceability) and to stop a date being committed twice.
 */
@Entity(tableName = "committed_days")
data class CommittedDay(
    @PrimaryKey val date: LocalDate,
    val committedAt: Long,
    val serviceCount: Int,
    val totalCents: Int,
    // Snapshot of the committed day plan (SavedPlanCodec JSON) so the exact plan
    // that was billed can be shown later, even if the editable plan changes.
    // Empty for days committed before this snapshot existed.
    val planJson: String = "",
)
