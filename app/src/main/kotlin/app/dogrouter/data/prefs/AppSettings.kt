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
    // Walking speed on foot with a group of dogs. Used when the planner
    // moves between nearby stops on foot instead of by bike.
    val walkingSpeedKmh: Float,
    // Fixed overhead added to each bike ride: loading dogs into the cargo
    // box, unlocking, helmet on — and the reverse on arrival. This is what
    // makes short hops cheaper to walk than to bike.
    val bikeOverheadMinutes: Int,
    val homeAddress: String,
    val homeLatitude: Double?,
    val homeLongitude: Double?,
) {
    val hasHome: Boolean
        get() = homeLatitude != null && homeLongitude != null

    companion object {
        /**
         * Defaults rooted in SCOPE.md: ~70 kg cargo-bike payload, a
         * 5-minute buffer per stop for handovers, a slow 3 km/h on-foot
         * group pace, a 3-minute bike mount/dismount overhead, and no home
         * address yet — the walker enters that on first launch.
         */
        val DEFAULTS = AppSettings(
            bikeCapacityKg = 70f,
            stopBufferMinutes = 5,
            cyclingSpeedKmh = 15f,
            walkingSpeedKmh = 3f,
            bikeOverheadMinutes = 3,
            homeAddress = "",
            homeLatitude = null,
            homeLongitude = null,
        )
    }
}
