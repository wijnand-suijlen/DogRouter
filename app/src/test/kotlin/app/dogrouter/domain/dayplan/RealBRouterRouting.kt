package app.dogrouter.domain.dayplan

import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.domain.routing.RouteEstimate
import app.dogrouter.domain.routing.RoutingProvider
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import java.io.File

/**
 * Real BRouter (the on-device engine) running headless for the harness, so
 * the laptop can plan with the actual cargo-bike road distances instead of
 * straight lines. Mirrors `data/routing/BRouterRoutingProvider` but with
 * plain file paths and no Android. Needs `brouter-data/profiles2/bakfiets.brf`
 * (+ lookups.dat) and `brouter-data/segments/E0_N45.rd5`; enable with
 * -Dsolver.router=brouter.
 */
class RealBRouterRouting(
    private val profileFile: File,
    private val segmentsDir: File,
) : RoutingProvider {
    override suspend fun isReady() = profileFile.exists() && segmentsDir.isDirectory
    override val lastError: String? = null

    override suspend fun routeGeometry(
        fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double,
    ): List<GeoPoint>? = null

    override suspend fun route(
        fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double,
    ): RouteEstimate? {
        val context = RoutingContext().apply { localFunction = profileFile.absolutePath }
        val waypoints = listOf(
            waypoint(fromLatitude, fromLongitude),
            waypoint(toLatitude, toLongitude),
        )
        return try {
            val engine = RoutingEngine(null, null, segmentsDir, waypoints, context)
            engine.doRun(0L)
            val track = engine.foundTrack
            if (engine.errorMessage != null || track == null || track.distance <= 0) {
                null
            } else {
                RouteEstimate(distanceMeters = track.distance, durationSeconds = track.totalSeconds)
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun waypoint(lat: Double, lon: Double) = OsmNodeNamed().apply {
        name = "wp"
        ilon = ((lon + 180.0) * 1_000_000.0).toInt()
        ilat = ((lat + 90.0) * 1_000_000.0).toInt()
    }
}
