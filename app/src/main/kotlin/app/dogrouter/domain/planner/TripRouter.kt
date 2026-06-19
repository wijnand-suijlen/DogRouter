package app.dogrouter.domain.planner

import app.dogrouter.domain.routing.RoutingProvider

/**
 * Wraps the existing per-trip walk list with computed cycling legs.
 * For each consecutive pair of stops (in the order the packer gave us)
 * a single cycling-route call is issued; a stop without coordinates
 * yields a leg with null [RouteLeg.estimate] instead of failing the
 * whole trip.
 *
 * Routes are computed sequentially on the IO context inside
 * [routingProvider]. Engine state is shared across calls so the
 * router serialises internally; concurrent invocations of [routeAll]
 * would block each other anyway.
 *
 * Stop reordering (nearest-neighbour or otherwise) is a separate
 * concern and lands in a follow-up round.
 */
class TripRouter(
    private val routingProvider: RoutingProvider,
) {
    suspend fun routeAll(trips: List<Trip>): List<Trip> {
        if (!routingProvider.isReady()) return trips
        return trips.map { trip -> route(trip) }
    }

    private suspend fun route(trip: Trip): Trip {
        if (trip.walks.size < 2) {
            return trip.copy(legs = emptyList(), totalTravelSeconds = 0)
        }
        val legs = mutableListOf<RouteLeg>()
        var totalSeconds = 0
        for (i in 0 until trip.walks.size - 1) {
            val from = trip.walks[i]
            val to = trip.walks[i + 1]
            val estimate = if (
                from.dog.latitude != null && from.dog.longitude != null &&
                to.dog.latitude != null && to.dog.longitude != null
            ) {
                routingProvider.route(
                    fromLatitude = from.dog.latitude,
                    fromLongitude = from.dog.longitude,
                    toLatitude = to.dog.latitude,
                    toLongitude = to.dog.longitude,
                )
            } else {
                null
            }
            legs.add(
                RouteLeg(
                    fromDogId = from.dog.id,
                    toDogId = to.dog.id,
                    estimate = estimate,
                ),
            )
            if (estimate != null) totalSeconds += estimate.durationSeconds
        }
        return trip.copy(legs = legs, totalTravelSeconds = totalSeconds)
    }
}
