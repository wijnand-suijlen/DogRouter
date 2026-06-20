package app.dogrouter.domain.dayplan

import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.domain.routing.RoutingProvider

/**
 * Precomputed road-distance matrix (metres) between a finite set of points.
 * Treats routes as symmetric: A→B and B→A share an entry. The error from
 * one-way streets is small versus the optimisation gain of one routing call
 * per pair.
 *
 * Stores DISTANCE, not time, so the planner can turn it into either a bike
 * time (distance ÷ cyclingSpeed + overhead) or an on-foot time
 * (distance ÷ walkingSpeed). BRouter's cycling distance is reused as the
 * on-foot distance too — a deliberate approximation, good enough for the
 * short hops where walking is chosen.
 */
class DistanceMatrix(
    private val meters: Map<Pair<GeoPoint, GeoPoint>, Int>,
) {
    fun metersBetween(from: GeoPoint, to: GeoPoint): Int {
        if (from == to) return 0
        return meters[from to to] ?: meters[to to from] ?: FALLBACK_METERS
    }

    companion object {
        /** Used when routing fails for a pair; large enough to deter the planner. */
        private const val FALLBACK_METERS = 30_000

        suspend fun build(
            points: Set<GeoPoint>,
            routing: RoutingProvider,
            // Reports routing progress as (pairs done, total pairs) — building
            // the matrix is the slow part on-device, so the UI tracks it.
            onPair: (done: Int, total: Int) -> Unit = { _, _ -> },
        ): DistanceMatrix {
            val cache = mutableMapOf<Pair<GeoPoint, GeoPoint>, Int>()
            val list = points.toList()
            val total = list.size * (list.size - 1) / 2
            var done = 0
            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val a = list[i]
                    val b = list[j]
                    val estimate = routing.route(a.latitude, a.longitude, b.latitude, b.longitude)
                    cache[a to b] = estimate?.distanceMeters ?: FALLBACK_METERS
                    onPair(++done, total)
                }
            }
            return DistanceMatrix(cache)
        }
    }
}
