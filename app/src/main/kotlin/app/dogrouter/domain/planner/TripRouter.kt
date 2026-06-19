package app.dogrouter.domain.planner

import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.domain.routing.RouteEstimate
import app.dogrouter.domain.routing.RoutingProvider

/**
 * Turns packed-but-unrouted trips into trips with leg distances,
 * optimised stop order, and home-leg bookends.
 *
 * For each trip:
 *  1. Collect the routable subset of walks (those that have a lat/lon).
 *     Walks without coordinates pass through unchanged at the end of the
 *     order, with null-estimate legs into and out of them.
 *  2. Build a cost matrix between every (home + routable) pair using the
 *     [routingProvider]. Identical-position pairs are zero, and missing
 *     routes record Int.MAX_VALUE so the optimiser avoids them.
 *  3. Ask [StopOrdering] for the cheapest order through the routable
 *     stops, anchored at home when one is configured.
 *  4. Emit a [Trip] copy with `walks` in the optimised order and the
 *     leg / home-leg / total-travel fields populated.
 */
class TripRouter(
    private val routingProvider: RoutingProvider,
) {
    suspend fun routeAll(trips: List<Trip>, home: GeoPoint?): List<Trip> {
        if (!routingProvider.isReady()) return trips
        return trips.map { trip -> route(trip, home) }
    }

    private suspend fun route(trip: Trip, home: GeoPoint?): Trip {
        if (trip.walks.isEmpty()) return trip

        val routable = trip.walks.mapNotNull { walk -> walk.geoPoint()?.let { walk to it } }
        val nonRoutable = trip.walks.filter { it.geoPoint() == null }

        if (routable.isEmpty()) {
            // Nothing we can route; report empty legs for clarity.
            return trip.copy(
                legs = if (trip.walks.size > 1) List(trip.walks.size - 1) { RouteLeg(null) } else emptyList(),
                fromHomeLeg = home?.let { RouteLeg(null) },
                toHomeLeg = home?.let { RouteLeg(null) },
                totalTravelSeconds = 0,
            )
        }

        val matrix = buildCostMatrix(routable.map { it.second }, home)
        val orderIndices = StopOrdering.order(
            homePresent = home != null,
            stops = routable.indices.toList(),
        ) { from, to -> matrix.duration(from, to) }

        val orderedRoutable = orderIndices.map { routable[it].first }
        val orderedWalks = orderedRoutable + nonRoutable

        // Inter-stop legs: routable→routable (from the matrix) followed by
        // null-estimate legs for any tail of un-routable stops.
        val routableLegs = (0 until orderIndices.size - 1).map { i ->
            matrix.leg(orderIndices[i], orderIndices[i + 1])
        }
        val tailLegs = List(nonRoutable.size) { RouteLeg(null) }
        val legs = routableLegs + tailLegs

        val fromHomeLeg = home?.let { matrix.leg(-1, orderIndices.first()) }
        // Home returns from the last routable stop — the un-routable tail
        // can't tell us where the walker really ends.
        val toHomeLeg = home?.let { matrix.leg(orderIndices.last(), -1) }

        val totalSeconds = sequenceOf(fromHomeLeg, toHomeLeg).filterNotNull()
            .sumOf { it.estimate?.durationSeconds ?: 0 } +
            legs.sumOf { it.estimate?.durationSeconds ?: 0 }

        return trip.copy(
            walks = orderedWalks,
            legs = legs,
            fromHomeLeg = fromHomeLeg,
            toHomeLeg = toHomeLeg,
            totalTravelSeconds = totalSeconds,
        )
    }

    private suspend fun buildCostMatrix(stops: List<GeoPoint>, home: GeoPoint?): CostMatrix {
        // Index -1 = home, 0..n-1 = stops. Estimates are symmetric for
        // our purposes; for one-way streets the difference is small in
        // total travel time and matters less than the order itself.
        val n = stops.size
        val toIndex: (Int) -> GeoPoint = { idx -> if (idx == -1) home!! else stops[idx] }
        val cache = HashMap<Long, RouteEstimate?>()

        suspend fun estimate(from: Int, to: Int): RouteEstimate? {
            if (from == to) return RouteEstimate(distanceMeters = 0, durationSeconds = 0)
            val key = pairKey(from, to)
            cache[key]?.let { return it }
            val reverseKey = pairKey(to, from)
            cache[reverseKey]?.let { return it }
            val a = toIndex(from)
            val b = toIndex(to)
            val result = routingProvider.route(a.latitude, a.longitude, b.latitude, b.longitude)
            cache[key] = result
            return result
        }

        val allIndices = if (home != null) listOf(-1) + (0 until n) else (0 until n).toList()
        for (i in allIndices) {
            for (j in allIndices) {
                if (i != j) estimate(i, j)
            }
        }
        return CostMatrix(cache)
    }

    private class CostMatrix(private val cache: Map<Long, RouteEstimate?>) {
        fun leg(from: Int, to: Int): RouteLeg = RouteLeg(lookup(from, to))

        fun duration(from: Int, to: Int): Int =
            lookup(from, to)?.durationSeconds ?: Int.MAX_VALUE

        private fun lookup(from: Int, to: Int): RouteEstimate? {
            val direct = cache[pairKey(from, to)]
            if (direct != null) return direct
            return cache[pairKey(to, from)]
        }
    }

    private companion object {
        // Pack two ints (each in [-1, ...]) into a Long for cache keying.
        fun pairKey(from: Int, to: Int): Long = (from.toLong() shl 32) or (to.toLong() and 0xFFFFFFFFL)
    }
}

private fun PlannedWalk.geoPoint(): GeoPoint? {
    val lat = dog.latitude ?: return null
    val lon = dog.longitude ?: return null
    return GeoPoint(lat, lon)
}
