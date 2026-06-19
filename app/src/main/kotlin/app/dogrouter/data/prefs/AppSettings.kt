package app.dogrouter.data.prefs

/**
 * Planning-time parameters used by the day planner. Values live in
 * DataStore-Preferences and reach the planner via [SettingsRepository].
 */
data class AppSettings(
    val cyclingSpeedKmh: Float,
    val bikeCapacityKg: Float,
    val stopBufferMinutes: Int,
) {
    companion object {
        /**
         * Defaults rooted in SCOPE.md and a cargo-bike walker's real
         * pace: ~15 km/h cycling with dogs aboard, ~70 kg payload, a
         * 5-minute buffer per stop for handovers.
         */
        val DEFAULTS = AppSettings(
            cyclingSpeedKmh = 15f,
            bikeCapacityKg = 70f,
            stopBufferMinutes = 5,
        )
    }
}
