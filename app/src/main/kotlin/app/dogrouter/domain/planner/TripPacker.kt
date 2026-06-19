package app.dogrouter.domain.planner

/**
 * Packs walks into cargo-bike trips by weight, using First-Fit-Decreasing:
 *
 *  1. Sort walks by dog weight, heaviest first.
 *  2. For each walk, drop it into the first trip with enough remaining
 *     capacity; otherwise start a new trip.
 *
 * Good enough for ≤ 20 dogs per day — exact bin-packing is NP-hard but
 * FFD is provably within 11/9 of optimal and runs in O(n²) on this scale.
 *
 * A dog whose own weight already exceeds the capacity still gets a trip
 * to itself, marked with [Trip.exceedsCapacity] = true so the UI can
 * flag the overflow.
 */
object TripPacker {
    fun pack(walks: List<PlannedWalk>, capacityKg: Float): List<Trip> {
        if (walks.isEmpty()) return emptyList()
        val sorted = walks.sortedByDescending { it.dog.weightKg }
        val bins = mutableListOf<MutableList<PlannedWalk>>()
        for (walk in sorted) {
            val target = bins.firstOrNull { bin ->
                bin.sumWeight() + walk.dog.weightKg <= capacityKg
            }
            if (target != null) {
                target.add(walk)
            } else {
                bins.add(mutableListOf(walk))
            }
        }
        return bins.map { bin ->
            val total = bin.sumWeight()
            Trip(
                walks = bin.toList(),
                totalWeightKg = total,
                exceedsCapacity = total > capacityKg,
            )
        }
    }

    private fun List<PlannedWalk>.sumWeight(): Float =
        fold(0f) { acc, w -> acc + w.dog.weightKg }
}
