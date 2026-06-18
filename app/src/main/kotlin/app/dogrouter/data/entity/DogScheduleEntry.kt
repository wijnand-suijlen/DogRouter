package app.dogrouter.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.DayOfWeek
import java.time.LocalTime

@Entity(
    tableName = "dog_schedule_entries",
    foreignKeys = [
        ForeignKey(
            entity = Dog::class,
            parentColumns = ["id"],
            childColumns = ["dogId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("dogId"),
        Index(value = ["dogId", "weekday"]),
    ],
)
data class DogScheduleEntry(
    @PrimaryKey val id: String,
    val dogId: String,
    val weekday: DayOfWeek,
    // null means no lower bound on pickup time.
    val earliestPickup: LocalTime?,
    // null means no upper bound on drop-off time.
    val latestDropoff: LocalTime?,
)
