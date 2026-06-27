package app.dogrouter.domain.dayplan.constraints

import app.dogrouter.domain.dayplan.PlanningConstraint
import app.dogrouter.domain.dayplan.RouteEvent
import app.dogrouter.domain.dayplan.walkSpans
import app.dogrouter.domain.dayplan.walkedSeconds

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
 *
 * A **boarding** dog (`dog.status.isBoarding`, the sleepover feature) is exempt:
 * it has no per-span `durationMinutes` requirement — its rule is max-gap
 * coverage ([MaxGapConstraint]) instead — and it legitimately ends the day
 * aboard (an open span with no dropoff: it goes home with the walker, or is
 * returned at its own address as a `Breng` dog). So its spans are not checked
 * here, in the solver and in `PlanVerifier` alike (which is why the test keys on
 * the dog's status, not an externally supplied id set).
 */
class WalkDurationConstraint : PlanningConstraint {
    override fun violation(events: List<RouteEvent>): String? {
        for (span in events.walkSpans()) {
            val pickup = span.pickup
            if (pickup.dog.status.isBoarding) continue
            span.dropoff ?: return "${pickup.dog.name} has no dropoff"

            // Dwell-walk events the dog takes part in, plus on-foot travel
            // legs in its span (while aboard the dog walks with the group).
            val totalWalked = span.walkedSeconds(events)

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
