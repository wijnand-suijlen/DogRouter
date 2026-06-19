package app.dogrouter.data.prefs

/**
 * Planning-time parameters used by the day planner. Values live in
 * DataStore-Preferences and reach the planner via [SettingsRepository].
 *
 * Cycling speed is a user-tunable override on top of BRouter: BRouter
 * picks the actual road network (cycle paths, no stairs, ecargobike
 * profile) so its distances are accurate, but its kinematic model is
 * conservative for a real cargo bike with dogs aboard. We use
 * BRouter's distance and divide by [cyclingSpeedKmh] to land on
 * realistic times.
 */
data class AppSettings(
    val bikeCapacityKg: Float,
    val stopBufferMinutes: Int,
    val cyclingSpeedKmh: Float,
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
            cyclingSpeedKmh = 15f,
            homeAddress = "",
            homeLatitude = null,
            homeLongitude = null,
        )
    }
}
