package app.dogrouter.domain.dayplan.constraints

import app.dogrouter.domain.dayplan.PlanningConstraint
import app.dogrouter.domain.dayplan.RouteEvent

/**
 * Two dogs that have been marked incompatible may never be in the
 * cargo bike at the same time. [pairs] holds the symmetric set of
 * unordered dog-id pairs.
 */
class IncompatibilityConstraint(
    private val pairs: Set<Pair<String, String>>,
) : PlanningConstraint {
    override fun violation(events: List<RouteEvent>): String? {
        if (pairs.isEmpty()) return null
        val inBag = mutableSetOf<String>()
        val dogNames = mutableMapOf<String, String>()
        for (event in events) {
            when (event) {
                is RouteEvent.Pickup -> {
                    val newId = event.dog.id
                    dogNames[newId] = event.dog.name
                    for (existing in inBag) {
                        if (newId.toCanonicalPair(existing) in pairs) {
                            return "${event.dog.name} and ${dogNames[existing]} cannot share a trip"
                        }
                    }
                    inBag.add(newId)
                }
                is RouteEvent.Dropoff -> inBag.remove(event.dog.id)
                else -> Unit
            }
        }
        return null
    }

    private fun String.toCanonicalPair(other: String): Pair<String, String> =
        if (this < other) this to other else other to this
}
