package app.dogrouter.domain.dayplan.constraints

import app.dogrouter.domain.dayplan.PlanningConstraint
import app.dogrouter.domain.dayplan.RouteEvent

/**
 * Sum of weights of dogs currently in the bag never exceeds the
 * configured cargo bike capacity. A single dog whose weight already
 * exceeds capacity counts as a violation.
 */
class CapacityConstraint(private val capacityKg: Float) : PlanningConstraint {
    override fun violation(events: List<RouteEvent>): String? {
        var weight = 0f
        for (e in events) {
            when (e) {
                is RouteEvent.Pickup -> {
                    weight += e.dog.weightKg
                    if (weight > capacityKg) {
                        return "Capacity exceeded after picking up ${e.dog.name} (${weight.fmt()} > $capacityKg kg)"
                    }
                }
                is RouteEvent.Dropoff -> weight -= e.dog.weightKg
                else -> Unit
            }
        }
        return null
    }

    private fun Float.fmt(): String = if (toInt().toFloat() == this) toInt().toString() else "%.1f".format(this)
}
