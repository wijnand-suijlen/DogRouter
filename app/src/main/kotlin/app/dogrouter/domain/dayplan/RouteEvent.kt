package app.dogrouter.domain.dayplan

import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.domain.routing.GeoPoint

/**
 * One moment in a planned working day. Events carry an absolute time
 * (seconds since midnight) and a physical location. Together they form
 * a [DayRoute].
 *
 * The walker is between events while cycling: travel from one event to
 * the next costs time but no event of its own. A [Walk] keeps the
 * walker in place — its [location] is whatever the previous event's
 * location was.
 */
sealed interface RouteEvent {
    val timeSeconds: Int
    val location: GeoPoint

    data class HomeStart(
        override val timeSeconds: Int,
        override val location: GeoPoint,
    ) : RouteEvent

    data class HomeEnd(
        override val timeSeconds: Int,
        override val location: GeoPoint,
    ) : RouteEvent

    data class Pickup(
        override val timeSeconds: Int,
        override val location: GeoPoint,
        val dog: Dog,
        val rule: DogScheduleRule,
    ) : RouteEvent

    data class Dropoff(
        override val timeSeconds: Int,
        override val location: GeoPoint,
        val dog: Dog,
    ) : RouteEvent

    data class Walk(
        override val timeSeconds: Int,
        override val location: GeoPoint,
        val dogs: List<Dog>,
        val durationSeconds: Int,
    ) : RouteEvent
}

/** Time spent at this event itself (excluding travel to reach it). */
fun RouteEvent.durationAtSeconds(stopBufferSeconds: Int): Int = when (this) {
    is RouteEvent.HomeStart, is RouteEvent.HomeEnd -> 0
    is RouteEvent.Pickup, is RouteEvent.Dropoff -> stopBufferSeconds
    is RouteEvent.Walk -> durationSeconds
}
