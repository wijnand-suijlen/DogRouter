package app.dogrouter.domain.routing

/**
 * Abstraction over the routing engine so we can swap implementations
 * (BRouter today, possibly a hosted API later) without touching the
 * planner or UI.
 */
interface RoutingProvider {
    /**
     * True when the provider has everything it needs to answer queries.
     * For BRouter that means: profile present, segment file downloaded.
     */
    suspend fun isReady(): Boolean

    /**
     * Cycling route estimate between two WGS84 points, or null when the
     * engine cannot reach an answer (no path, missing data, error).
     */
    suspend fun route(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
    ): RouteEstimate?

    /**
     * Human-readable reason the most recent route() call returned null,
     * or null when the last call succeeded (or no call has happened yet).
     * Read AFTER awaiting [route] — there is no concurrency guarantee
     * across concurrent callers, but in practice route() is serialised.
     */
    val lastError: String?
}
