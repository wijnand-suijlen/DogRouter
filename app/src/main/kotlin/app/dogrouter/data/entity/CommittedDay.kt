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
)
