package app.dogrouter.domain.routing

import app.dogrouter.domain.dayplan.DistanceMatrix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs

class RouteDistanceCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    /** Counts route() calls and returns a deterministic distance (or null). */
    private class CountingRouting(val fail: Boolean = false) : RoutingProvider {
        var calls = 0
        override suspend fun isReady() = true
        override val lastError: String? = null
        override suspend fun routeGeometry(
            fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double,
        ): List<GeoPoint>? = null

        override suspend fun route(
            fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double,
        ): RouteEstimate? {
            calls++
            if (fail) return null
            val meters = ((abs(toLatitude - fromLatitude) + abs(toLongitude - fromLongitude)) * 100_000).toInt()
            return RouteEstimate(meters, 0)
        }
    }

    private fun cache(file: File, fp: String = "fp-1") = RouteDistanceCache(
        cacheFile = file,
        json = json,
        fingerprint = { fp },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    private val points = setOf(
        GeoPoint(0.0, 0.0), GeoPoint(0.1, 0.0), GeoPoint(0.0, 0.1), GeoPoint(0.1, 0.1),
    ) // 4 points -> 6 pairs

    @Test
    fun reusesAcrossBuildsWithNoExtraRoutingCalls() = runBlocking {
        val routing = CountingRouting()
        val cache = cache(tmp.newFile("c.json"))

        DistanceMatrix.build(points, routing, routeCache = cache)
        assertEquals("first build routes every pair", 6, routing.calls)

        DistanceMatrix.build(points, routing, routeCache = cache)
        assertEquals("second build is all cache hits", 6, routing.calls)
    }

    @Test
    fun addedPointRoutesOnlyItsOwnPairs() = runBlocking {
        val routing = CountingRouting()
        val cache = cache(tmp.newFile("c.json"))

        DistanceMatrix.build(points, routing, routeCache = cache)
        assertEquals(6, routing.calls)

        // One extra address among the 4 existing: only 4 new pairs (q vs each),
        // not a full 10-pair (5 choose 2) rebuild.
        val withExtra = points + GeoPoint(0.2, 0.2)
        DistanceMatrix.build(withExtra, routing, routeCache = cache)
        assertEquals("only the new point's pairs are routed", 6 + 4, routing.calls)
    }

    @Test
    fun routingFailureIsNotCachedAndRetries() = runBlocking {
        val routing = CountingRouting(fail = true)
        val cache = cache(tmp.newFile("c.json"))

        DistanceMatrix.build(points, routing, routeCache = cache)
        assertEquals(6, routing.calls)

        // Failures aren't stored, so a second build retries every pair.
        DistanceMatrix.build(points, routing, routeCache = cache)
        assertEquals(12, routing.calls)
    }

    @Test
    fun persistsToDiskAndReloads() = runBlocking {
        val file = tmp.newFile("c.json")
        val cache1 = cache(file)
        DistanceMatrix.build(points, CountingRouting(), routeCache = cache1)
        cache1.flush()

        val routing2 = CountingRouting()
        val cache2 = cache(file) // same file, same fingerprint
        DistanceMatrix.build(points, routing2, routeCache = cache2)
        assertEquals("a fresh instance loads from disk — no routing", 0, routing2.calls)
    }

    @Test
    fun staleFingerprintDiscardsPersistedCache() = runBlocking {
        val file = tmp.newFile("c.json")
        val cache1 = cache(file, fp = "fp-old")
        DistanceMatrix.build(points, CountingRouting(), routeCache = cache1)
        cache1.flush()

        val routing2 = CountingRouting()
        val cache2 = cache(file, fp = "fp-new") // routing data changed
        DistanceMatrix.build(points, routing2, routeCache = cache2)
        assertEquals("stale cache ignored — every pair re-routed", 6, routing2.calls)
    }

    @Test
    fun fingerprintChangesWhenAFileChanges() {
        val f = tmp.newFile("seg.rd5").apply { writeText("a") }
        val before = RouteDistanceCache.fingerprintOf(listOf(f))
        f.writeText("aa-longer")
        val after = RouteDistanceCache.fingerprintOf(listOf(f))
        assertNotEquals(before, after)
    }
}
