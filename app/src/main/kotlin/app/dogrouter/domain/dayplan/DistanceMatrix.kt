package app.dogrouter.domain.dayplan

import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.domain.routing.RoutingProvider

/**
 * Precomputed cycling-time matrix between a finite set of points.
 * Treats routes as symmetric: A→B and B→A share an entry. The error
 * from one-way streets is small versus the optimisation gain of one
 * routing call per pair.
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

        suspend fun build(points: Set<GeoPoint>, routing: RoutingProvider): DistanceMatrix {
            val cache = mutableMapOf<Pair<GeoPoint, GeoPoint>, Int>()
            val list = points.toList()
            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val a = list[i]
                    val b = list[j]
                    val r = routing.route(a.latitude, a.longitude, b.latitude, b.longitude)
                    cache[a to b] = r?.durationSeconds ?: FALLBACK_SECONDS
                }
            }
            return DistanceMatrix(cache)
        }
    }
}
