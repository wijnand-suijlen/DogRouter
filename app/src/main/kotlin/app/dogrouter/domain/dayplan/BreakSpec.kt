package app.dogrouter.domain.dayplan

import app.dogrouter.domain.routing.GeoPoint

/**
 * Request for a dog-free mid-day break: the planner fits one
 * [durationSeconds] break at the cheapest of [locations], starting within
 * `[windowStartSeconds, windowEndSeconds]` (seconds since midnight), at a
 * point in the day where no dog is aboard. Null/absent means no break.
 */
data class BreakSpec(
    val locations: List<GeoPoint>,
    val windowStartSeconds: Int,
    val windowEndSeconds: Int,
    val durationSeconds: Int,
)
