package app.dogrouter.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalTime

/**
 * A one-off commitment on a specific [date] during an exact time window
 * ([startTime]–[endTime]) at a fixed address (a doctor's visit, a client
 * intro). The planner keeps the walker dog-free and at this location for the
 * window and schedules the day's walks around it.
 */
@Entity(tableName = "appointments")
data class Appointment(
    @PrimaryKey val id: String,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val label: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
)
