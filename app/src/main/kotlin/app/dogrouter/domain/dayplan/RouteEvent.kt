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
}

/** Time spent at this event itself (excluding travel to reach it). */
fun RouteEvent.durationAtSeconds(stopBufferSeconds: Int): Int = when (this) {
    is RouteEvent.HomeStart, is RouteEvent.HomeEnd -> 0
    is RouteEvent.Pickup, is RouteEvent.Dropoff -> stopBufferSeconds
    is RouteEvent.Walk -> durationSeconds
}
