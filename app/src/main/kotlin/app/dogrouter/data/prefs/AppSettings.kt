package app.dogrouter.data.prefs

/**
 * Planning-time parameters used by the day planner. Values live in
 * DataStore-Preferences and reach the planner via [SettingsRepository].
 *
 * Cycling speed is intentionally absent: the on-device BRouter engine
 * derives the time estimate itself from the cargo-bike profile
 * (totalMass, bikerPower, drag, rolling resistance). Surfacing a UI
 * slider for "speed" that BRouter would silently ignore would mislead
 * the walker.
 */
data class AppSettings(
    val bikeCapacityKg: Float,
    val stopBufferMinutes: Int,
    val homeAddress: String,
    val homeLatitude: Double?,
    val homeLongitude: Double?,
) {
    val hasHome: Boolean
        get() = homeLatitude != null && homeLongitude != null

    companion object {
        /**
         * Defaults rooted in SCOPE.md: ~70 kg cargo-bike payload, a
         * 5-minute buffer per stop for handovers, and no home address
         * yet — the walker enters that on first launch.
         */
        val DEFAULTS = AppSettings(
            bikeCapacityKg = 70f,
            stopBufferMinutes = 5,
            homeAddress = "",
            homeLatitude = null,
            homeLongitude = null,
        )
    }
}
