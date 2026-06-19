package app.dogrouter.domain.dayplan.constraints

import app.dogrouter.domain.dayplan.PlanningConstraint
import app.dogrouter.domain.dayplan.RouteEvent

/**
 * No dog may sit in the cargo bike while a walk happens without taking
 * part: at every [RouteEvent.Walk], every dog currently aboard (picked up,
 * not yet dropped off) must be in the walk. The walker never leaves a dog
 * in the bike while walking the others.
 */
class NoDogLeftBehindConstraint : PlanningConstraint {
    override fun violation(events: List<RouteEvent>): String? {
        val inBag = LinkedHashMap<String, String>() // dog id -> name, insertion-ordered
        for (event in events) {
            when (event) {
                is RouteEvent.Pickup -> inBag[event.dog.id] = event.dog.name
                is RouteEvent.Dropoff -> inBag.remove(event.dog.id)
                is RouteEvent.Walk -> {
                    val walking = event.dogs.mapTo(HashSet()) { it.id }
                    val left = inBag.entries.firstOrNull { it.key !in walking }
                    if (left != null) {
                        return "${left.value} is in the bike but not in the walk"
                    }
                }
                else -> Unit
            }
        }
        return null
    }
}
