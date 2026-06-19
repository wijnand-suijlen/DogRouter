package app.dogrouter.data.routing

import android.util.Log
import app.dogrouter.domain.routing.RouteEstimate
import app.dogrouter.domain.routing.RoutingProvider
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * BRouter-backed implementation of [RoutingProvider]. Each call spins up
 * a fresh [RoutingEngine] with the cargo-bike profile and the on-device
 * Île-de-France segment directory. Engines are not thread-safe; a Mutex
 * serialises calls so concurrent route requests from the planner do not
 * trip over each other.
 *
 * Returns null when:
 *   - the profile or segment data is not yet installed
 *   - BRouter cannot find a path between the two points
 *   - the underlying engine throws (logged via [System.err])
 */
class BRouterRoutingProvider(
    private val paths: RoutingDataPaths,
) : RoutingProvider {

    private val routingMutex = Mutex()

    @Volatile
    override var lastError: String? = null
        private set

    override suspend fun isReady(): Boolean = paths.isReady()

    override suspend fun route(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
    ): RouteEstimate? = withContext(Dispatchers.IO) {
        if (!paths.isReady()) {
            lastError = "Routing data not installed"
            return@withContext null
        }
        routingMutex.withLock { computeRoute(fromLatitude, fromLongitude, toLatitude, toLongitude) }
    }

    private fun computeRoute(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
    ): RouteEstimate? {
        val context = RoutingContext().apply {
            // BRouter resolves the profile file as raw bytes from this path.
            localFunction = paths.cargoProfile.absolutePath
        }
        val waypoints = listOf(
            waypoint("from", fromLat, fromLon),
            waypoint("to", toLat, toLon),
        )
        return try {
            val engine = RoutingEngine(
                /* outfileBase = */ null,
                /* logfileBase = */ null,
                /* segmentDir = */ paths.segmentsDir,
                /* waypoints = */ waypoints,
                /* rc = */ context,
            )
            engine.doRun(0L)
            val err = engine.errorMessage
            val track = engine.foundTrack
            if (err != null || track == null || track.distance <= 0) {
                val msg = err ?: "Empty track (distance=${track?.distance})"
                Log.w("DogRouter", "BRouter returned no route: $msg")
                lastError = msg
                return null
            }
            lastError = null
            RouteEstimate(
                distanceMeters = track.distance,
                durationSeconds = track.totalSeconds,
            )
        } catch (t: Throwable) {
            Log.e("DogRouter", "BRouter routing threw", t)
            lastError = "${t::class.java.simpleName}: ${t.message}"
            null
        }
    }

    private fun waypoint(name: String, lat: Double, lon: Double): OsmNodeNamed =
        OsmNodeNamed().apply {
            this.name = name
            // BRouter stores coordinates as fixed-point ints offset by 180°.
            this.ilon = ((lon + 180.0) * 1_000_000.0).toInt()
            this.ilat = ((lat + 90.0) * 1_000_000.0).toInt()
        }
}
