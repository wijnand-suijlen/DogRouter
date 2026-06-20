package app.dogrouter.domain.dayplan

import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.domain.routing.RoutingProvider
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
        return meters[from to to] ?: meters[to to from] ?: fallbackMeters(from, to)
    }

    companion object {
        /**
         * Road detour over straight-line distance. When BRouter cannot route a
         * pair, a flat large constant (the old behaviour) made nearby dogs look
         * unreachable and badly distorted the plan; a straight-line estimate
         * with this factor keeps the plan sensible instead.
         */
        private const val ROAD_DETOUR_FACTOR = 1.3

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
                    cache[a to b] = estimate?.distanceMeters ?: fallbackMeters(a, b)
                    onPair(++done, total)
                }
            }
            return DistanceMatrix(cache)
        }

        /** Straight-line distance plus a road detour — the fallback when a
         *  routing engine cannot answer for a pair. */
        private fun fallbackMeters(from: GeoPoint, to: GeoPoint): Int {
            val r = 6_371_000.0
            val dLat = Math.toRadians(to.latitude - from.latitude)
            val dLon = Math.toRadians(to.longitude - from.longitude)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(from.latitude)) * cos(Math.toRadians(to.latitude)) *
                sin(dLon / 2) * sin(dLon / 2)
            val straight = r * 2 * atan2(sqrt(h), sqrt(1 - h))
            return (straight * ROAD_DETOUR_FACTOR).toInt()
        }
    }
}
