package app.dogrouter.domain.planner

/**
 * Packs walks into cargo-bike trips using First-Fit-Decreasing constrained
 * by weight AND time-window feasibility:
 *
 *  1. Sort walks by dog weight, heaviest first.
 *  2. For each walk, drop it into the first trip whose remaining weight
 *     capacity is enough AND whose running window, intersected with the
 *     walk's window, still has enough duration for the longest walk in
 *     the proposed trip; otherwise start a new trip.
 *  3. A trip's running window is the intersection of all member windows,
 *     tightened as walks are added. Two abutting windows like 09:00–11:00
 *     and 11:00–13:00 intersect to a zero-length window, which cannot fit
 *     any non-zero walk — so abutting (not overlapping) walks never share
 *     a trip.
 *
 * Good enough for ≤ 20 dogs per day. Exact constrained bin-packing is
 * NP-hard; at this scale FFD with feasibility checks produces sensible
 * groupings cheaply.
 *
 * Edge cases:
 *  - A dog whose own weight exceeds capacity still gets a trip to itself,
 *    marked with [Trip.exceedsCapacity] = true so the UI can flag it.
 *  - A single walk whose own window is shorter than its duration still
 *    gets a trip to itself — the data is inconsistent and surfacing it
 *    in the UI is better than silently dropping it.
 */
object TripPacker {
    fun pack(walks: List<PlannedWalk>, capacityKg: Float): List<Trip> {
        if (walks.isEmpty()) return emptyList()
        val sorted = walks.sortedByDescending { it.dog.weightKg }
        val bins = mutableListOf<MutableBin>()
        for (walk in sorted) {
            val walkWindow = walk.window()
            val walkDuration = walk.rule.durationMinutes.toLong()
            val target = bins.firstOrNull { bin ->
                val combined = bin.window.intersect(walkWindow) ?: return@firstOrNull false
                val fitsWeight = bin.totalWeight + walk.dog.weightKg <= capacityKg
                val newMaxDuration = maxOf(bin.maxDurationMinutes, walkDuration)
                val fitsTime = combined.durationMinutes >= newMaxDuration
                fitsWeight && fitsTime
            }
            if (target != null) {
                target.walks.add(walk)
                // Safe !! — the firstOrNull above already verified non-null.
                target.window = target.window.intersect(walkWindow)!!
                target.maxDurationMinutes = maxOf(target.maxDurationMinutes, walkDuration)
            } else {
                bins.add(
                    MutableBin(
                        walks = mutableListOf(walk),
                        window = walkWindow,
                        maxDurationMinutes = walkDuration,
                    ),
                )
            }
        }
        // FFD packs by weight, but the walker wants the day to read
        // chronologically. Sort the resulting trips by their earliest
        // feasible start, then by their latest feasible end.
        return bins
            .map { it.toTrip(capacityKg) }
            .sortedWith(compareBy({ it.window.from }, { it.window.until }))
    }

    private class MutableBin(
        val walks: MutableList<PlannedWalk>,
        var window: TimeWindow,
        var maxDurationMinutes: Long,
    ) {
        val totalWeight: Float get() = walks.fold(0f) { acc, w -> acc + w.dog.weightKg }

        fun toTrip(capacityKg: Float): Trip {
            val total = totalWeight
            return Trip(
                walks = walks.toList(),
                totalWeightKg = total,
                window = window,
                exceedsCapacity = total > capacityKg,
            )
        }
    }
}
