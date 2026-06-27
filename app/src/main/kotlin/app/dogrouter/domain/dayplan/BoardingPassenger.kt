package app.dogrouter.domain.dayplan

import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogStatus
import app.dogrouter.domain.routing.GeoPoint

/** Where a boarding dog starts or ends a given day (see [BoardingPassenger]). */
enum class BoardingAnchor { OWNER_HOME, WALKER_HOME }

/**
 * The (start, end) anchors of a boarding day status, or null for a non-boarding
 * status (OFF / WALK). Lives here in the domain layer because [BoardingAnchor]
 * is a domain type; [DogStatus] itself stays free of it (data → domain is not
 * an allowed dependency, domain → data is).
 */
fun DogStatus.anchors(): Pair<BoardingAnchor, BoardingAnchor>? = when (this) {
    DogStatus.BOARD_ARRIVE -> BoardingAnchor.OWNER_HOME to BoardingAnchor.WALKER_HOME
    DogStatus.BOARD_STAY -> BoardingAnchor.WALKER_HOME to BoardingAnchor.WALKER_HOME
    DogStatus.BOARD_LEAVE -> BoardingAnchor.WALKER_HOME to BoardingAnchor.OWNER_HOME
    DogStatus.OFF, DogStatus.WALK -> null
}

/**
 * A dog boarded at the walker's home for a day (the "sleepover" feature, see
 * docs/SLEEPOVER_DESIGN.md). Unlike a regular [WalkOption] it is **not** a
 * single walk to schedule but a **passenger present across the day**: the
 * planner seeds it aboard from [startAnchor] to [endAnchor], and
 * [NoDogLeftBehindConstraint] then makes every group walk in that interval
 * include it (riding along — "meenemen"). Parking it for dedicated short walks
 * is the exception the cap forces, carved out of that presence (stage 2).
 *
 * Axes (see the design doc):
 *  - [capSeconds] — SOFT max walk duration knob (per boarding day). null = no
 *    cap = the dog rides along every walk. A low cap makes long ride-alongs
 *    expensive, so the dog gets parked and walked short instead.
 *  - [depot] — where the dog is left between walks: its own home when
 *    [keyAvailable] (the walker holds the key), else the walker's home.
 *  - start/end anchor — first day starts at the owner's, last day ends there;
 *    middle days are walker-home both ends.
 */
data class BoardingPassenger(
    val dog: Dog,
    // The dog's own home (its address) and the walker's home.
    val ownerHome: GeoPoint,
    val walkerHome: GeoPoint,
    val keyAvailable: Boolean,
    val capSeconds: Int?,
    val maxGapSeconds: Int,
    val minWalkSeconds: Int,
    val startAnchor: BoardingAnchor,
    val endAnchor: BoardingAnchor,
) {
    /** Where the dog may be left between walks. */
    val depot: GeoPoint get() = if (keyAvailable) ownerHome else walkerHome

    fun startLocation(): GeoPoint =
        if (startAnchor == BoardingAnchor.OWNER_HOME) ownerHome else walkerHome

    fun endLocation(): GeoPoint =
        if (endAnchor == BoardingAnchor.OWNER_HOME) ownerHome else walkerHome
}
