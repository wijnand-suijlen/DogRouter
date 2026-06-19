package app.dogrouter.domain.dayplan.constraints

import app.dogrouter.domain.dayplan.PlanningConstraint
import app.dogrouter.domain.dayplan.RouteEvent

/**
 * Hard cap on how many dogs may be walked at once. The walker prefers no
 * more than three (a soft preference applied as a planning-cost penalty);
 * this is the absolute ceiling for exceptional days.
 */
class GroupSizeConstraint(private val maxDogs: Int) : PlanningConstraint {
    override fun violation(events: List<RouteEvent>): String? {
        for (event in events) {
            if (event is RouteEvent.Walk && event.dogs.size > maxDogs) {
                return "A walk has ${event.dogs.size} dogs, over the maximum of $maxDogs"
            }
        }
        return null
    }
}
