package app.dogrouter.domain.routing

/** WGS84 coordinate pair used wherever a single location identifies a routing endpoint. */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)
