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
 *
 * [returnToBikeSeconds] is the on-foot part of an otherwise-bike leg: when
 * a ride starts away from the parked bike, the walker first walks the group
 * back to it. That stretch is walked (it counts toward the aboard dogs'
 * walk time) even though the leg as a whole is a bike leg. It is 0 for foot
 * legs and for rides that start where the bike already is.
 */
sealed interface RouteEvent {
    val timeSeconds: Int
    val location: GeoPoint
    val arrivedByFoot: Boolean
    val incomingTravelSeconds: Int
    val returnToBikeSeconds: Int

    data class HomeStart(
        override val timeSeconds: Int,
        override val location: GeoPoint,
        override val arrivedByFoot: Boolean = false,
        override val incomingTravelSeconds: Int = 0,
        override val returnToBikeSeconds: Int = 0,
    ) : RouteEvent

    data class HomeEnd(
        override val timeSeconds: Int,
        override val location: GeoPoint,
        override val arrivedByFoot: Boolean = false,
        override val incomingTravelSeconds: Int = 0,
        override val returnToBikeSeconds: Int = 0,
    ) : RouteEvent

    data class Pickup(
        override val timeSeconds: Int,
        override val location: GeoPoint,
        val dog: Dog,
        val rule: DogScheduleRule,
        override val arrivedByFoot: Boolean = false,
        override val incomingTravelSeconds: Int = 0,
        override val returnToBikeSeconds: Int = 0,
    ) : RouteEvent

    data class Dropoff(
        override val timeSeconds: Int,
        override val location: GeoPoint,
        val dog: Dog,
        override val arrivedByFoot: Boolean = false,
        override val incomingTravelSeconds: Int = 0,
        override val returnToBikeSeconds: Int = 0,
    ) : RouteEvent

    data class Walk(
        override val timeSeconds: Int,
        override val location: GeoPoint,
        val dogs: List<Dog>,
        val durationSeconds: Int,
        override val arrivedByFoot: Boolean = false,
        override val incomingTravelSeconds: Int = 0,
        override val returnToBikeSeconds: Int = 0,
    ) : RouteEvent

    /**
     * A dog-free mid-day break at one of the walker's break locations. The
     * planner slots one in (when requested) at an empty point in the day
     * inside the break window; see `DayPlanner.insertBreak`.
     */
    data class Break(
        override val timeSeconds: Int,
        override val location: GeoPoint,
        val durationSeconds: Int,
        // The break may not start before this (the break window opens); the
        // retimer waits, like a pickup's earliestStart.
        val earliestStartSeconds: Int = 0,
        // True for a lunch taken at home (vs a break location).
        val atHome: Boolean = false,
        override val arrivedByFoot: Boolean = false,
        override val incomingTravelSeconds: Int = 0,
        override val returnToBikeSeconds: Int = 0,
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
        override val returnToBikeSeconds: Int = 0,
    ) : RouteEvent
}

/** On-foot seconds in this event's incoming leg: the whole leg when walked,
 *  otherwise just the walk back to the parked bike. Doubles as walk time for
 *  the dogs aboard during it. */
val RouteEvent.onFootSeconds: Int
    get() = if (arrivedByFoot) incomingTravelSeconds else returnToBikeSeconds

/** Time spent at this event itself (excluding travel to reach it). */
fun RouteEvent.durationAtSeconds(stopBufferSeconds: Int): Int = when (this) {
    is RouteEvent.HomeStart, is RouteEvent.HomeEnd -> 0
    is RouteEvent.Pickup, is RouteEvent.Dropoff -> stopBufferSeconds
    is RouteEvent.Walk -> durationSeconds
    is RouteEvent.Break -> durationSeconds
    is RouteEvent.FetchBike -> 0
}

/** Copy of this event with its incoming-leg travel time replaced. */
fun RouteEvent.withIncomingTravel(seconds: Int): RouteEvent = when (this) {
    is RouteEvent.HomeStart -> copy(incomingTravelSeconds = seconds)
    is RouteEvent.HomeEnd -> copy(incomingTravelSeconds = seconds)
    is RouteEvent.Pickup -> copy(incomingTravelSeconds = seconds)
    is RouteEvent.Dropoff -> copy(incomingTravelSeconds = seconds)
    is RouteEvent.Walk -> copy(incomingTravelSeconds = seconds)
    is RouteEvent.Break -> copy(incomingTravelSeconds = seconds)
    is RouteEvent.FetchBike -> copy(incomingTravelSeconds = seconds)
}

/** Copy of this event with its walk-back-to-bike portion replaced. */
fun RouteEvent.withReturnToBike(seconds: Int): RouteEvent = when (this) {
    is RouteEvent.HomeStart -> copy(returnToBikeSeconds = seconds)
    is RouteEvent.HomeEnd -> copy(returnToBikeSeconds = seconds)
    is RouteEvent.Pickup -> copy(returnToBikeSeconds = seconds)
    is RouteEvent.Dropoff -> copy(returnToBikeSeconds = seconds)
    is RouteEvent.Walk -> copy(returnToBikeSeconds = seconds)
    is RouteEvent.Break -> copy(returnToBikeSeconds = seconds)
    is RouteEvent.FetchBike -> copy(returnToBikeSeconds = seconds)
}
