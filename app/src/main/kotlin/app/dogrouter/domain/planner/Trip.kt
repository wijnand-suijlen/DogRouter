package app.dogrouter.domain.planner

/**
 * One outing on the cargo bike: a set of walks whose combined weight fits
 * the bike capacity and whose individual time windows all overlap. The
 * [window] is the intersection of every member walk's window — the time
 * range in which this trip can take place. [exceedsCapacity] is true only
 * when a single dog already weighs more than the configured capacity; the
 * trip is still created (so the day stays plannable) but the UI flags it.
 */
data class Trip(
    val walks: List<PlannedWalk>,
    val totalWeightKg: Float,
    val window: TimeWindow,
    val exceedsCapacity: Boolean,
)
