package app.dogrouter.domain.routing

import java.util.concurrent.ConcurrentHashMap

/**
 * Memoises [RoutingProvider.routeGeometry] per leg so the same cycling
 * route is not retraced every time a map scrolls back into view or a
 * screen recomposes. Keyed on the exact endpoint coordinates, which is
 * safe because legs always come from the same planned [GeoPoint] values.
 *
 * A failed lookup is not cached: it returns a straight-line fallback and
 * retries next time (e.g. once the routing data finishes installing).
 */
class LegGeometryCache(
    private val routing: RoutingProvider,
) {
    private val cache = ConcurrentHashMap<Key, List<GeoPoint>>()

    suspend fun geometry(from: GeoPoint, to: GeoPoint): List<GeoPoint> {
        val key = Key(from.latitude, from.longitude, to.latitude, to.longitude)
        cache[key]?.let { return it }
        val track = routing.routeGeometry(
            from.latitude, from.longitude, to.latitude, to.longitude,
        )?.takeIf { it.size >= 2 }
        return if (track != null) {
            cache[key] = track
            track
        } else {
            listOf(from, to)
        }
    }

    private data class Key(
        val fromLat: Double,
        val fromLon: Double,
        val toLat: Double,
        val toLon: Double,
    )
}
