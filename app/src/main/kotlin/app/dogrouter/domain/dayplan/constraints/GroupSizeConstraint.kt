package app.dogrouter.domain.dayplan.constraints

import app.dogrouter.domain.dayplan.PlanningConstraint
import app.dogrouter.domain.dayplan.RouteEvent

/**
 * Hard cap on how many dogs are in the walker's care at once — both in any
 * single walk AND aboard at any moment between walks (picking another dog up,
 * or carrying some back, never leaves the others behind: they all come along).
 * The cap holds in every mode: should the bike break down the whole group has
 * to be walked, so it can never exceed what the walker can handle on foot.
 *
 * The walker prefers no more than three (a soft preference applied as a
 * planning-cost penalty); this is the absolute ceiling for exceptional days.
 * The aboard cap implies the per-walk cap (a walk's dogs are all aboard), but
 * both are checked so the violation message points at the right spot.
 */
class GroupSizeConstraint(private val maxDogs: Int) : PlanningConstraint {
    override fun violation(events: List<RouteEvent>): String? {
        val aboard = LinkedHashMap<String, String>() // dog id -> name
        for (event in events) {
            when (event) {
                is RouteEvent.Pickup -> {
                    aboard[event.dog.id] = event.dog.name
                    if (aboard.size > maxDogs) {
                        return "${aboard.size} dogs aboard after picking up ${event.dog.name}, " +
                            "over the maximum of $maxDogs"
                    }
                }
                is RouteEvent.Dropoff -> aboard.remove(event.dog.id)
                is RouteEvent.Walk -> if (event.dogs.size > maxDogs) {
                    return "A walk has ${event.dogs.size} dogs, over the maximum of $maxDogs"
                }
                else -> Unit
            }
        }
        return null
    }
}
