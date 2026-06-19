package app.dogrouter.domain.planner

import java.time.Duration
import java.time.LocalTime

/**
 * A closed time-of-day interval `[from, until]`. The packer uses these to
 * decide which walks can share a single bike trip — two walks are
 * compatible only if their windows intersect non-emptily.
 *
 * A null bound on a [DogScheduleRule] (earliestStart or latestEnd) is
 * encoded as [LocalTime.MIN] / [LocalTime.MAX] respectively, so window
 * arithmetic stays uniform.
 */
data class TimeWindow(
    val from: LocalTime,
    val until: LocalTime,
) {
    /**
     * Intersection with another window, or null when the intervals do
     * not overlap.
     */
    fun intersect(other: TimeWindow): TimeWindow? {
        val newFrom = maxOf(from, other.from)
        val newUntil = minOf(until, other.until)
        return if (newFrom <= newUntil) TimeWindow(newFrom, newUntil) else null
    }

    val isUnbounded: Boolean get() = from == LocalTime.MIN && until == LocalTime.MAX

    /** Length of this window in whole minutes, never negative. */
    val durationMinutes: Long
        get() = Duration.between(from, until).toMinutes().coerceAtLeast(0L)

    companion object {
        val UNBOUNDED = TimeWindow(LocalTime.MIN, LocalTime.MAX)
    }
}

fun PlannedWalk.window(): TimeWindow = TimeWindow(
    from = rule.earliestStart ?: LocalTime.MIN,
    until = rule.latestEnd ?: LocalTime.MAX,
)
