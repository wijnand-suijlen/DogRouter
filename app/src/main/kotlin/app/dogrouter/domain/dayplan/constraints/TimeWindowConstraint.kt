package app.dogrouter.domain.dayplan.constraints

import app.dogrouter.domain.dayplan.PlanningConstraint
import app.dogrouter.domain.dayplan.RouteEvent
import app.dogrouter.domain.dayplan.walkSpans

/**
 * For each scheduled walk the pickup must be no earlier than the rule's
 * `earliestStart`, and the dropoff no later than the rule's `latestEnd`.
 * Works per pickup→dropoff occurrence (see [walkSpans]), so a dog with two
 * rules in a day has each of its walks checked against its own window.
 */
class TimeWindowConstraint : PlanningConstraint {
    override fun violation(events: List<RouteEvent>): String? {
        for (span in events.walkSpans()) {
            val pickup = span.pickup
            val earliest = pickup.rule.earliestStart
            if (earliest != null && pickup.timeSeconds < earliest.toSecondOfDay()) {
                return "${pickup.dog.name} pickup at ${pickup.timeSeconds.fmtTime()} is before earliest $earliest"
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
