package app.dogrouter.domain.routing

/**
 * Output of a routing call between two stops: physical road distance and
 * a profile-aware time estimate (so for the cargo bike the time already
 * accounts for the heavier mass and lower top speed).
 */
data class RouteEstimate(
    val distanceMeters: Int,
    val durationSeconds: Int,
)
