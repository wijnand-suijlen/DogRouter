package app.dogrouter.data.prefs

import kotlinx.serialization.Serializable

/**
 * A place where the walker can take a break (dog-free). Stored as part of
 * [AppSettings] — a short, user-managed list — rather than a Room table.
 * [label] is the formatted address; coordinates are WGS84.
 */
@Serializable
data class BreakLocation(
    val label: String,
    val latitude: Double,
    val longitude: Double,
)
