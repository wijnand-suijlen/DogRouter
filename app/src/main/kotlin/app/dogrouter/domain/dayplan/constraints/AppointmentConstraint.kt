package app.dogrouter.domain.dayplan.constraints

import app.dogrouter.domain.dayplan.PlanningConstraint
import app.dogrouter.domain.dayplan.RouteEvent

/**
 * For each fixed [RouteEvent.Appointment]: the walker must reach it by its
 * start (the retimer waits when early, so a later assigned time means a late
 * arrival), and no dog may be aboard during it.
 */
class AppointmentConstraint : PlanningConstraint {
    override fun violation(events: List<RouteEvent>): String? {
        var aboard = 0
        for (e in events) {
            when (e) {
                is RouteEvent.Pickup -> aboard++
                is RouteEvent.Dropoff -> aboard--
                is RouteEvent.Appointment -> {
                    if (e.timeSeconds > e.startSeconds) {
                        return "${e.label} would start late (${fmt(e.timeSeconds)} after ${fmt(e.startSeconds)})"
                    }
                    if (aboard > 0) return "$aboard dog(s) aboard during ${e.label}"
                }
                else -> Unit
            }
        }
        return null
    }

    private fun fmt(s: Int) = "%02d:%02d".format(s / 3600, (s % 3600) / 60)
}
