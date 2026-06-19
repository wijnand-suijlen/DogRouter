package app.dogrouter.domain.dayplan.constraints

import app.dogrouter.domain.dayplan.PlanningConstraint
import app.dogrouter.domain.dayplan.RouteEvent
import app.dogrouter.domain.dayplan.walkSpans

/**
 * Per pickup→dropoff occurrence (see [walkSpans]): the pickup must fall in
 * the rule's start window — no earlier than `earliestStart`, no later than
 * `latestStart` — and the dropoff no later than `latestEnd`. Each bound is
 * optional and independent, so a dog can need "start between 11:00 and
 * 13:00" with no return deadline.
 */
class TimeWindowConstraint : PlanningConstraint {
    override fun violation(events: List<RouteEvent>): String? {
        for (span in events.walkSpans()) {
            val pickup = span.pickup
            val earliest = pickup.rule.earliestStart
            if (earliest != null && pickup.timeSeconds < earliest.toSecondOfDay()) {
                return "${pickup.dog.name} pickup at ${pickup.timeSeconds.fmtTime()} is before earliest $earliest"
            }
            val latestStart = pickup.rule.latestStart
            if (latestStart != null && pickup.timeSeconds > latestStart.toSecondOfDay()) {
                return "${pickup.dog.name} pickup at ${pickup.timeSeconds.fmtTime()} is after latest start $latestStart"
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
