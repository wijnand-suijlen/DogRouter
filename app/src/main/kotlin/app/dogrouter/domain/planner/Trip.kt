package app.dogrouter.domain.planner

/**
 * One outing on the cargo bike: a set of walks whose combined weight fits
 * the bike capacity. [exceedsCapacity] is true only when a single dog
 * already weighs more than the configured capacity; the trip is still
 * created (so the day is plannable) but the UI flags it.
 */
data class Trip(
    val walks: List<PlannedWalk>,
    val totalWeightKg: Float,
    val exceedsCapacity: Boolean,
)
