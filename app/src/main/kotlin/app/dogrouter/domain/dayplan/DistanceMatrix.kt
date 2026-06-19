package app.dogrouter.domain.dayplan

import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.domain.routing.RoutingProvider

/**
 * Precomputed cycling-time matrix between a finite set of points.
 * Treats routes as symmetric: A→B and B→A share an entry. The error
 * from one-way streets is small versus the optimisation gain of one
 * routing call per pair.
 *
 * BRouter is used for its accurate cycling distance — the actual road
 * network with cargo-bike-aware choices — but its kinematic model
 * underestimates a loaded cargo bike, so the time we store is
 * `distance / userCyclingSpeedKmh` instead of BRouter's own duration.
 */
class DistanceMatrix(
    private val seconds: Map<Pair<GeoPoint, GeoPoint>, Int>,
) {
    fun secondsBetween(from: GeoPoint, to: GeoPoint): Int {
        if (from == to) return 0
        return seconds[from to to] ?: seconds[to to from] ?: FALLBACK_SECONDS
    }

    companion object {
        /** Used when routing fails for a pair; large enough to deter the planner. */
        private const val FALLBACK_SECONDS = 30 * 60

        suspend fun build(
            points: Set<GeoPoint>,
            routing: RoutingProvider,
            cyclingSpeedKmh: Float,
        ): DistanceMatrix {
            val metersPerSecond = (cyclingSpeedKmh / 3.6).coerceAtLeast(0.1)
            val cache = mutableMapOf<Pair<GeoPoint, GeoPoint>, Int>()
            val list = points.toList()
            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val a = list[i]
                    val b = list[j]
                    val estimate = routing.route(a.latitude, a.longitude, b.latitude, b.longitude)
                    val seconds = if (estimate == null) {
                        FALLBACK_SECONDS
                    } else {
                        (estimate.distanceMeters / metersPerSecond).toInt()
                    }
                    cache[a to b] = seconds
                }
            }
            return DistanceMatrix(cache)
        }
    }
}
