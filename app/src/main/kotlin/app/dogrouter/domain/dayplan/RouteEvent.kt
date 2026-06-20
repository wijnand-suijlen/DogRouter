package app.dogrouter.domain.dayplan

import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.domain.routing.GeoPoint

/**
 * One moment in a planned working day. Events carry an absolute time
 * (seconds since midnight) and a physical location. Together they form a
 * [DayRoute].
 *
 * Between events the walker either rides the cargo bike (dogs in the box)
 * or walks the group on foot (bike parked). [arrivedByFoot] and
 * [incomingTravelSeconds] describe the leg that reached this event; they
 * are filled in by `DayPlanner.retimeAndCost` (default false / 0 until
 * then). On a foot leg the on-foot dogs are being walked, so its time
 * counts toward their walk duration.
 */
sealed interface RouteEvent {
    val timeSeconds: Int
    val location: GeoPoint
    val arrivedByFoot: Boolean
    val incomingTravelSeconds: Int

    data class HomeStart(
        override val timeSeconds: Int,
        override val location: GeoPoint,
        override val arrivedByFoot: Boolean = false,
        override val incomingTravelSeconds: Int = 0,
    ) : RouteEvent

    data class HomeEnd(
        override val timeSeconds: Int,
        override val location: GeoPoint,
        override val arrivedByFoot: Boolean = false,
        override val incomingTravelSeconds: Int = 0,
    ) : RouteEvent

    data class Pickup(
        override val timeSeconds: Int,
        override val location: GeoPoint,
        val dog: Dog,
        val rule: DogScheduleRule,
        override val arrivedByFoot: Boolean = false,
        override val incomingTravelSeconds: Int = 0,
    ) : RouteEvent

    data class Dropoff(
        override val timeSeconds: Int,
        override val location: GeoPoint,
        val dog: Dog,
        override val arrivedByFoot: Boolean = false,
        override val incomingTravelSeconds: Int = 0,
    ) : RouteEvent

    data class Walk(
        override val timeSeconds: Int,
        override val location: GeoPoint,
        val dogs: List<Dog>,
        val durationSeconds: Int,
        override val arrivedByFoot: Boolean = false,
        override val incomingTravelSeconds: Int = 0,
    ) : RouteEvent

    /**
     * Walk back to the parked cargo bike before riding on. Inserted purely
     * for presentation, AFTER planning, whenever a bike leg starts away from
     * where the bike was left during an on-foot stretch (see
     * `DayPlanner.withBikeFetches`): the walker first walks here —
     * [location] is where the bike stands — and only then rides on. The
     * leg into it is always on foot, so the walker (and the app's map) can
     * find the bike again. The solver itself never produces these.
     */
    data class FetchBike(
        override val timeSeconds: Int,
        override val location: GeoPoint,
        override val arrivedByFoot: Boolean = true,
        override val incomingTravelSeconds: Int = 0,
    ) : RouteEvent
}

/** Time spent at this event itself (excluding travel to reach it). */
fun RouteEvent.durationAtSeconds(stopBufferSeconds: Int): Int = when (this) {
    is RouteEvent.HomeStart, is RouteEvent.HomeEnd -> 0
    is RouteEvent.Pickup, is RouteEvent.Dropoff -> stopBufferSeconds
    is RouteEvent.Walk -> durationSeconds
    is RouteEvent.FetchBike -> 0
}

/** Copy of this event with its incoming-leg travel time replaced. */
fun RouteEvent.withIncomingTravel(seconds: Int): RouteEvent = when (this) {
    is RouteEvent.HomeStart -> copy(incomingTravelSeconds = seconds)
    is RouteEvent.HomeEnd -> copy(incomingTravelSeconds = seconds)
    is RouteEvent.Pickup -> copy(incomingTravelSeconds = seconds)
    is RouteEvent.Dropoff -> copy(incomingTravelSeconds = seconds)
    is RouteEvent.Walk -> copy(incomingTravelSeconds = seconds)
    is RouteEvent.FetchBike -> copy(incomingTravelSeconds = seconds)
}
