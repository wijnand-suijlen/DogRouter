package app.dogrouter.domain.routing

import app.dogrouter.domain.dayplan.DistanceMatrix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent, lazy, symmetric cache of road distances (metres) between pairs of
 * [GeoPoint]s — the slow BRouter calls behind [DistanceMatrix]. Because road
 * distance depends only on the two endpoints (not the day or the planner
 * settings), the same pair is reused across every weekday and across app
 * restarts: as long as no dog is added or moved, no pair is routed twice.
 *
 * Mirrors [LegGeometryCache]: keyed on the exact endpoint coordinates (the
 * planned [GeoPoint] values are stable), thread-safe, and a **routing failure
 * is not cached** — it returns a straight-line fallback and retries next time
 * (e.g. once BRouter data finishes installing).
 *
 * Granularity is per unordered pair, so adding/moving one address among N costs
 * ~N new routing calls (the new point vs each existing point), never a full N²
 * rebuild. No eviction: the distinct-point count stays small (dogs + home + a
 * few break/appointment locations), so the file stays well under ~1 MB for
 * years. The only reset is [fingerprint] changing (BRouter data reinstalled).
 *
 * Persistence is a single JSON file written atomically off a debounce, so a
 * burst of new pairs during one matrix build triggers one write.
 */
class RouteDistanceCache(
    private val cacheFile: File,
    private val json: Json,
    // A stable signature of the installed routing data (e.g. BRouter segment +
    // profile file sizes/mtimes). A persisted cache whose fingerprint no longer
    // matches is discarded on load, so a routing-data update invalidates it.
    // Read once per instance (a mid-session reinstall is picked up next launch).
    fingerprint: () -> String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val cache = ConcurrentHashMap<Key, Int>()
    private val fingerprintValue: String by lazy(fingerprint)

    private val loadMutex = Mutex()
    @Volatile private var loaded = false

    private val saveMutex = Mutex()
    private var saveJob: Job? = null

    /**
     * Road distance (metres) between [a] and [b]: from the cache when known,
     * else routed via [routing] and remembered. A failed route falls back to a
     * straight-line estimate and is NOT cached (retried next time). 0 for a
     * point against itself.
     */
    suspend fun distance(a: GeoPoint, b: GeoPoint, routing: RoutingProvider): Int {
        if (a == b) return 0
        ensureLoaded()
        val key = keyOf(a, b)
        cache[key]?.let { return it }
        val estimate = routing.route(a.latitude, a.longitude, b.latitude, b.longitude)
        return if (estimate != null) {
            cache[key] = estimate.distanceMeters
            scheduleSave()
            estimate.distanceMeters
        } else {
            DistanceMatrix.fallbackMeters(a, b)
        }
    }

    /** Persist any pending writes now (cancels the debounce). For tests and a
     *  deliberate flush; normal saves happen automatically off the debounce. */
    suspend fun flush() {
        saveJob?.cancel()
        persist()
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        loadMutex.withLock {
            if (loaded) return
            runCatching {
                if (cacheFile.exists()) {
                    val decoded = json.decodeFromString<CacheFileDto>(cacheFile.readText())
                    if (decoded.formatVersion == FORMAT_VERSION && decoded.fingerprint == fingerprintValue) {
                        decoded.entries.forEach { e ->
                            cache[Key(e.aLat, e.aLon, e.bLat, e.bLon)] = e.meters
                        }
                    }
                    // else: stale (format/fingerprint changed) — start empty.
                }
            }
            loaded = true
        }
    }

    private fun scheduleSave() {
        synchronized(this) {
            saveJob?.cancel()
            saveJob = scope.launch {
                delay(SAVE_DEBOUNCE_MS)
                persist()
            }
        }
    }

    private suspend fun persist() {
        saveMutex.withLock {
            val snapshot = cache.entries.map { (k, m) ->
                EntryDto(k.aLat, k.aLon, k.bLat, k.bLon, m)
            }
            val dto = CacheFileDto(FORMAT_VERSION, fingerprintValue, snapshot)
            runCatching {
                cacheFile.parentFile?.mkdirs()
                val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
                tmp.writeText(json.encodeToString(dto))
                // Atomic replace so a crash mid-write never leaves a half file.
                if (!tmp.renameTo(cacheFile)) {
                    cacheFile.writeText(tmp.readText())
                    tmp.delete()
                }
            }
        }
    }

    /** Canonical unordered key: A→B and B→A share one entry. */
    private fun keyOf(a: GeoPoint, b: GeoPoint): Key {
        val aFirst = a.latitude < b.latitude ||
            (a.latitude == b.latitude && a.longitude <= b.longitude)
        val (p, q) = if (aFirst) a to b else b to a
        return Key(p.latitude, p.longitude, q.latitude, q.longitude)
    }

    private data class Key(val aLat: Double, val aLon: Double, val bLat: Double, val bLon: Double)

    @Serializable
    private data class CacheFileDto(
        val formatVersion: Int,
        val fingerprint: String,
        val entries: List<EntryDto>,
    )

    @Serializable
    private data class EntryDto(
        val aLat: Double,
        val aLon: Double,
        val bLat: Double,
        val bLon: Double,
        val meters: Int,
    )

    companion object {
        /** Bump when the stored distance semantics change (e.g. routing math). */
        private const val FORMAT_VERSION = 1
        private const val SAVE_DEBOUNCE_MS = 750L

        /** Standard on-disk location under the app's private files dir. */
        fun fileIn(filesDir: File): File = File(filesDir, "route_distance_cache.json")

        /**
         * A fingerprint of the installed routing data: name + length + mtime of
         * each file that affects computed distances (segments + profile). Any
         * reinstall/update changes it, invalidating a persisted cache.
         */
        fun fingerprintOf(files: List<File>): String =
            files.sortedBy { it.path }.joinToString("|") { f ->
                "${f.name}:${if (f.exists()) f.length() else -1L}:${if (f.exists()) f.lastModified() else 0L}"
            }
    }
}
