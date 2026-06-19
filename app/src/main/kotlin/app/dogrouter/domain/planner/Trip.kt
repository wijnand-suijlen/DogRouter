package app.dogrouter.domain.planner

/**
 * One outing on the cargo bike: a set of walks whose combined weight fits
 * the bike capacity and whose time windows all overlap. [window] is the
 * intersection of every member walk's window. [exceedsCapacity] is true
 * only when a single dog already weighs more than the configured capacity.
 *
 * Routing fields are filled in AFTER the packer produces the trip:
 *  - [walks] order is the optimised traversal order chosen by the router.
 *  - [legs] holds the routes between consecutive walks (size = walks.size - 1).
 *  - [fromHomeLeg] / [toHomeLeg] hold the bookends from and to the walker's
 *    home address; both null when no home is configured.
 *  - [totalTravelSeconds] sums all legs including the home bookends.
 *
 * All routing fields are null when routing has not yet run, the segment
 * data is missing, or no stops have coordinates.
 */
data class Trip(
    val walks: List<PlannedWalk>,
    val totalWeightKg: Float,
    val window: TimeWindow,
    val exceedsCapacity: Boolean,
    val legs: List<RouteLeg>? = null,
    val fromHomeLeg: RouteLeg? = null,
    val toHomeLeg: RouteLeg? = null,
    val totalTravelSeconds: Int? = null,
)
