package app.dogrouter.domain.dayplan.constraints

import app.dogrouter.domain.dayplan.PlanningConstraint
import app.dogrouter.domain.dayplan.RouteEvent
import app.dogrouter.domain.dayplan.walkSpans

/**
 * For every pickup→dropoff occurrence (see [walkSpans]):
 *  - **Minimum:** the total of every [RouteEvent.Walk] in which the dog
 *    participates within that span must be at least
 *    `rule.durationMinutes * 60` seconds. Summing across walks in the span
 *    is what lets one walk be split into several shorter ones.
 *  - **Maximum:** for dogs with `allowLongerWalk = false` (puppies,
 *    injuries) the total must equal — not exceed — the requested duration.
 *
 * Working per occurrence (not per dog id) means a dog with two rules in a
 * day has each walk's duration validated against its own rule.
 */
class WalkDurationConstraint : PlanningConstraint {
    override fun violation(events: List<RouteEvent>): String? {
        val walks = events.filterIsInstance<RouteEvent.Walk>()

        for (span in events.walkSpans()) {
            val pickup = span.pickup
            val dropoff = span.dropoff ?: return "${pickup.dog.name} has no dropoff"
            val dogId = pickup.dog.id
            val range = pickup.timeSeconds..dropoff.timeSeconds

            val totalWalked = walks
                .filter { walk -> walk.timeSeconds in range && walk.dogs.any { it.id == dogId } }
                .sumOf { it.durationSeconds }

            val required = pickup.rule.durationMinutes * 60
            if (totalWalked < required) {
                return "${pickup.dog.name} walked ${totalWalked / 60} min, needs ${required / 60} min"
            }
            if (!pickup.dog.allowLongerWalk && totalWalked > required) {
                return "${pickup.dog.name} walked ${totalWalked / 60} min but cap is ${required / 60} min"
            }
        }
        return null
    }
}
