package app.dogrouter.domain.dayplan.constraints

import app.dogrouter.domain.dayplan.PlanningConstraint
import app.dogrouter.domain.dayplan.RouteEvent

/**
 * For every dog whose pickup we encounter:
 *  - **Minimum:** the total of every [RouteEvent.Walk] in which the
 *    dog participates, taken across the dog's pickup-dropoff span,
 *    must be at least `rule.durationMinutes * 60` seconds. Otherwise
 *    the dog has not been walked long enough.
 *  - **Maximum:** for dogs with `allowLongerWalk = false` (puppies,
 *    injuries) the total must equal — not exceed — the requested
 *    duration. Walking such a dog longer than asked is a violation.
 */
class WalkDurationConstraint : PlanningConstraint {
    override fun violation(events: List<RouteEvent>): String? {
        val pickups = events.filterIsInstance<RouteEvent.Pickup>().associateBy { it.dog.id }
        val dropoffs = events.filterIsInstance<RouteEvent.Dropoff>().associateBy { it.dog.id }
        val walks = events.filterIsInstance<RouteEvent.Walk>()

        for ((dogId, pickup) in pickups) {
            val dropoff = dropoffs[dogId] ?: return "${pickup.dog.name} has no dropoff"
            val span = pickup.timeSeconds..dropoff.timeSeconds

            val totalWalked = walks
                .filter { walk -> walk.timeSeconds in span && walk.dogs.any { it.id == dogId } }
                .sumOf { it.durationSeconds }

            val required = pickup.rule.durationMinutes * 60
            if (totalWalked < required) {
                return "${pickup.dog.name} walked ${totalWalked / 60} min, needs $required / 60 min"
            }
            if (!pickup.dog.allowLongerWalk && totalWalked > required) {
                return "${pickup.dog.name} walked ${totalWalked / 60} min but cap is ${required / 60} min"
            }
        }
        return null
    }
}
