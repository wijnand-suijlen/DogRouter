package app.dogrouter.domain.planner

/**
 * One outing on the cargo bike: a set of walks whose combined weight fits
 * the bike capacity and whose individual time windows all overlap. The
 * [window] is the intersection of every member walk's window — the time
 * range in which this trip can take place. [exceedsCapacity] is true only
 * when a single dog already weighs more than the configured capacity; the
 * trip is still created (so the day stays plannable) but the UI flags it.
 *
 * [legs] and [totalTravelSeconds] are filled in by the router AFTER the
 * packer has produced the trip; both are null when routing has not yet
 * happened, when the segment file is missing, or when none of the stops
 * have coordinates.
 */
data class Trip(
    val walks: List<PlannedWalk>,
    val totalWeightKg: Float,
    val window: TimeWindow,
    val exceedsCapacity: Boolean,
    val legs: List<RouteLeg>? = null,
    val totalTravelSeconds: Int? = null,
)
