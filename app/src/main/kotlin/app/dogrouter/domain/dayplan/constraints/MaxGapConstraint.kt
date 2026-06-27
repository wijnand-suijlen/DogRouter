package app.dogrouter.domain.dayplan.constraints

import app.dogrouter.domain.dayplan.BoardingPassenger
import app.dogrouter.domain.dayplan.PlanningConstraint
import app.dogrouter.domain.dayplan.RouteEvent

/**
 * For every boarding dog (see [BoardingPassenger]): no interval longer than its
 * `maxGapSeconds` may pass without a qualifying walk — between the dog's
 * presence start (its first pickup) and its first walk, and between consecutive
 * walks it joins. A qualifying walk is one the dog takes part in lasting at
 * least its `minWalkSeconds`.
 *
 * This is the boarding dog's real requirement (it replaces the per-span
 * `durationMinutes` total a regular dog has — `WalkDurationConstraint` is
 * skipped for boarding dogs). It is time-dependent, so it is evaluated after
 * retiming, like [TimeWindowConstraint].
 *
 * NOTE: during incremental construction a boarding dog's coverage is only
 * complete once all group walks exist, so this is NOT used as a per-insertion
 * reject (that would block the build). It is checked on the finished plan; the
 * parking pass (stage 2) is what actively fills a gap that opens under a cap.
 * The trailing gap from the last walk to the end anchor is left open for now
 * (see the design doc).
 */
class MaxGapConstraint(private val passengers: List<BoardingPassenger>) : PlanningConstraint {
    override fun violation(events: List<RouteEvent>): String? {
        for (p in passengers) {
            val id = p.dog.id
            val presenceStart = events
                .firstOrNull { it is RouteEvent.Pickup && it.dog.id == id }
                ?.timeSeconds ?: continue
            val walks = events
                .filterIsInstance<RouteEvent.Walk>()
                .filter { w -> w.durationSeconds >= p.minWalkSeconds && w.dogs.any { it.id == id } }
                .sortedBy { it.timeSeconds }
            if (walks.isEmpty()) {
                return "${p.dog.name}: no qualifying walk (>= ${p.minWalkSeconds / 60} min) in the day"
            }
            var prevEnd = presenceStart
            for (w in walks) {
                val gap = w.timeSeconds - prevEnd
                if (gap > p.maxGapSeconds) {
                    return "${p.dog.name}: gap of ${gap / 60} min exceeds max ${p.maxGapSeconds / 60} min"
                }
                prevEnd = w.timeSeconds + w.durationSeconds
            }
        }
        return null
    }
}
