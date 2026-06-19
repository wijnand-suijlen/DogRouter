package app.dogrouter.domain.dayplan.constraints

import app.dogrouter.domain.dayplan.PlanningConstraint
import app.dogrouter.domain.dayplan.RouteEvent

/**
 * For each scheduled walk the pickup must be no earlier than the
 * rule's `earliestStart`, and the dropoff no later than the rule's
 * `latestEnd`. Picks the rule from the [RouteEvent.Pickup] event;
 * matches the corresponding [RouteEvent.Dropoff] by dog id.
 */
class TimeWindowConstraint : PlanningConstraint {
    override fun violation(events: List<RouteEvent>): String? {
        val pickups = events.filterIsInstance<RouteEvent.Pickup>().associateBy { it.dog.id }
        for (event in events) {
            when (event) {
                is RouteEvent.Pickup -> {
                    val earliest = event.rule.earliestStart
                    if (earliest != null && event.timeSeconds < earliest.toSecondsOfDay()) {
                        return "${event.dog.name} pickup at ${event.timeSeconds.fmtTime()} is before earliest ${earliest}"
                    }
                }
                is RouteEvent.Dropoff -> {
                    val pickup = pickups[event.dog.id] ?: continue
                    val latest = pickup.rule.latestEnd
                    if (latest != null && event.timeSeconds > latest.toSecondsOfDay()) {
                        return "${event.dog.name} dropoff at ${event.timeSeconds.fmtTime()} is after latest ${latest}"
                    }
                    if (event.timeSeconds < pickup.timeSeconds) {
                        return "${event.dog.name} dropoff is before its pickup"
                    }
                }
                else -> Unit
            }
        }
        return null
    }

    private fun java.time.LocalTime.toSecondsOfDay(): Int = toSecondOfDay()
    private fun Int.fmtTime(): String {
        val h = this / 3600
        val m = (this % 3600) / 60
        return "%02d:%02d".format(h, m)
    }
}
