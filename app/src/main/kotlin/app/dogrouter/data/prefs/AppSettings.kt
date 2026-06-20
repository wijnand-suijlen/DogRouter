package app.dogrouter.data.prefs

import java.time.LocalTime

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
    // How much a minute of cycling counts against a minute of day length in
    // the planner's objective. 1.0 = equal (a minute saved cycling is worth a
    // minute longer day); higher avoids cycling more; 0 = only day length.
    val cyclingWeight: Float,
    val homeAddress: String,
    val homeLatitude: Double?,
    val homeLongitude: Double?,
    // Optional mid-day break: when the walker enables it on Today, the planner
    // tries to fit a [breakDurationMinutes] break (dog-free) at the nearest
    // [breakLocations] spot, somewhere between [breakWindowStart] and
    // [breakWindowEnd].
    val breakWindowStart: LocalTime,
    val breakWindowEnd: LocalTime,
    val breakDurationMinutes: Int,
    val breakLocations: List<BreakLocation>,
    // When the mid-day free gap is at least this long, the planner prefers a
    // lunch at home (staying there until just in time) over a break location.
    val homeLunchMinFreeMinutes: Int,
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
            cyclingWeight = 1f,
            homeAddress = "",
            homeLatitude = null,
            homeLongitude = null,
            breakWindowStart = LocalTime.of(12, 0),
            breakWindowEnd = LocalTime.of(16, 0),
            breakDurationMinutes = 30,
            breakLocations = emptyList(),
            homeLunchMinFreeMinutes = 120,
        )
    }
}
