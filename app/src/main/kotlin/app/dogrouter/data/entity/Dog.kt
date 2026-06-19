package app.dogrouter.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dogs")
data class Dog(
    @PrimaryKey val id: String,
    val name: String,
    val breed: String?,
    val weightKg: Float,
    val photoUri: String?,
    val ownerName: String,
    val ownerPhone: String?,
    val address: String,
    // Coordinates for the pickup/drop-off address. Set only when the
    // address came from a validated source (e.g. BAN autocomplete);
    // null when the walker typed free text without picking a suggestion.
    val latitude: Double? = null,
    val longitude: Double? = null,
    // Free-text quirks about the stop (e.g. "ring bell, wait ~3 min").
    val stopNotes: String?,
    // Fixed time adjustment in minutes applied to the leg arriving at this stop.
    val stopAdjustmentMinutes: Int = 0,
    val inCargoBike: TransportState = TransportState.NotTested,
    val inBackpack: TransportState = TransportState.NotTested,
    val notes: String?,
    val createdAt: Long = System.currentTimeMillis(),
)
