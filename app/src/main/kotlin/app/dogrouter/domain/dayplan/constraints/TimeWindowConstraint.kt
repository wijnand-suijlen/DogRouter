package app.dogrouter.domain.dayplan.constraints

import app.dogrouter.domain.dayplan.PlanningConstraint
import app.dogrouter.domain.dayplan.RouteEvent
import app.dogrouter.domain.dayplan.walkSpans

/**
 * Per pickup→dropoff occurrence (see [walkSpans]):
 *  - the pickup must be no earlier than `earliestStart`;
 *  - the dog's **first walk** must start no later than `latestStart` — the
 *    bound is on the actual walk, not the pickup, so a dog cannot be picked
 *    up in time and then carried around until a much later walk;
 *  - the dropoff must be no later than `latestEnd`.
 *
 * Each bound is optional and independent, so a dog can need "walk starts
 * between 11:00 and 13:00" with no return deadline.
 */
class TimeWindowConstraint : PlanningConstraint {
    override fun violation(events: List<RouteEvent>): String? {
        val walks = events.filterIsInstance<RouteEvent.Walk>()
        for (span in events.walkSpans()) {
            val pickup = span.pickup
            val earliest = pickup.rule.earliestStart
            if (earliest != null && pickup.timeSeconds < earliest.toSecondOfDay()) {
                return "${pickup.dog.name} pickup at ${pickup.timeSeconds.fmtTime()} is before earliest $earliest"
            }
            val latestStart = pickup.rule.latestStart
            if (latestStart != null) {
                val spanEnd = span.dropoff?.timeSeconds ?: Int.MAX_VALUE
                val firstWalk = walks
                    .filter { w ->
                        w.timeSeconds in pickup.timeSeconds..spanEnd &&
                            w.dogs.any { it.id == pickup.dog.id }
                    }
                    .minByOrNull { it.timeSeconds }
                if (firstWalk != null && firstWalk.timeSeconds > latestStart.toSecondOfDay()) {
                    return "${pickup.dog.name} walk starts ${firstWalk.timeSeconds.fmtTime()}, " +
                        "after latest start $latestStart"
                }
            }
            val dropoff = span.dropoff ?: continue
            val latest = pickup.rule.latestEnd
            if (latest != null && dropoff.timeSeconds > latest.toSecondOfDay()) {
                return "${pickup.dog.name} dropoff at ${dropoff.timeSeconds.fmtTime()} is after latest $latest"
            }
            if (dropoff.timeSeconds < pickup.timeSeconds) {
                return "${pickup.dog.name} dropoff is before its pickup"
            }
        }
        return null
    }

    private fun Int.fmtTime(): String {
        val h = this / 3600
        val m = (this % 3600) / 60
        return "%02d:%02d".format(h, m)
    }
}
