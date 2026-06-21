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
    // Reference to the dog's [Owner] (billing/contact party). Nullable: a dog
    // may be temporarily without an owner. The source of truth for owner
    // details; [ownerName]/[ownerPhone] below are deprecated, kept only so old
    // data and backups still load.
    val ownerId: String? = null,
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
    // Whether the planner is allowed to walk this dog longer than the
    // minimum specified in any of its schedule rules. Default true —
    // most owners are happy with extra walk time. Puppies and dogs with
    // injuries (e.g. a sore paw) must be set to false so they walk
    // exactly the requested duration, never more.
    val allowLongerWalk: Boolean = true,
    // Whether the dog is currently walked. A temporarily paused dog (owner on
    // holiday, etc.) is set inactive so the planner skips it, without losing
    // its record. Toggled from the Dogs list; reactivated manually.
    val active: Boolean = true,
    val notes: String?,
    val createdAt: Long = System.currentTimeMillis(),
)
