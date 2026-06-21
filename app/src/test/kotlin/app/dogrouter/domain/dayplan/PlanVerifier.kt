package app.dogrouter.domain.dayplan

import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.TransportState
import app.dogrouter.domain.dayplan.constraints.AppointmentConstraint
import app.dogrouter.domain.dayplan.constraints.CapacityConstraint
import app.dogrouter.domain.dayplan.constraints.GroupSizeConstraint
import app.dogrouter.domain.dayplan.constraints.IncompatibilityConstraint
import app.dogrouter.domain.dayplan.constraints.NoDogLeftBehindConstraint
import app.dogrouter.domain.dayplan.constraints.TimeWindowConstraint
import app.dogrouter.domain.dayplan.constraints.WalkDurationConstraint

/**
 * Independent feasibility check on a FINAL produced [DayRoute] — the plan after
 * `DayPlanner.toDayRoute` + `withBikeFetches`, which the in-search
 * [PlanningConstraint]s never see (they run on candidate event lists during
 * insertion). Mirrors `docs/CSP_MODEL.md`:
 *
 *  - **C1–C8** reuse the existing constraint objects, run here on the final
 *    plan, so the value added is re-checking the presentation pass (the
 *    `FetchBike` splitting and retiming), not the constraint logic itself.
 *  - **C9–C11** are implemented independently here because they live inside
 *    `retimeAndCost` (a `null` return), not in a `PlanningConstraint`. C11 is
 *    deliberately pragmatic (consistency, not a full re-derivation of the
 *    timer — that would just duplicate the solver).
 *
 * Returns one message per violated constraint; an empty list means feasible.
 *
 * **Walk-back caveat (C9):** a `FetchBike` is on foot but is the walk-back
 * portion of a bike leg, which the solver does NOT subject to the on-foot
 * group cap (`canFoot` only governs legs chosen as foot travel). To stay a
 * faithful guard of the *implemented* model, the foot-cap check below excludes
 * `FetchBike`. Whether the walk-back should also be capped is an open model
 * question (see the report accompanying this change / `CSP_MODEL.md` §5).
 */
object PlanVerifier {

    fun violations(
        route: DayRoute,
        capacityKg: Float,
        stopBufferSeconds: Int,
        maxGroupSize: Int = 4,
        incompatibilities: Set<Pair<String, String>> = emptySet(),
        dayEndSeconds: Int = 20 * 3600,
    ): List<String> {
        val events = route.events
        val out = mutableListOf<String>()

        // C1–C8 — reuse the in-search constraints, now on the final plan.
        val reused = listOf(
            CapacityConstraint(capacityKg),
            TimeWindowConstraint(),
            WalkDurationConstraint(),
            IncompatibilityConstraint(incompatibilities),
            NoDogLeftBehindConstraint(),
            GroupSizeConstraint(maxGroupSize),
            AppointmentConstraint(),
        )
        for (c in reused) c.violation(events)?.let { out.add("${c::class.simpleName}: $it") }

        // C9 — transport mode feasibility (independent oracle).
        out += transportViolations(events, maxGroupSize)

        // C10 — day-end cutoff.
        events.firstOrNull { it.timeSeconds > dayEndSeconds }?.let {
            out.add("DayEnd: ${label(it)} at ${hms(it.timeSeconds)} is past ${hms(dayEndSeconds)}")
        }

        // C11 — pragmatic time consistency.
        out += timeConsistencyViolations(events, stopBufferSeconds)

        return out
    }

    /** C9: each bike leg carries only rideable dogs; each genuine foot-travel
     *  leg stays within the on-foot group cap (FetchBike excluded; see KDoc). */
    private fun transportViolations(events: List<RouteEvent>, maxGroupSize: Int): List<String> {
        val out = mutableListOf<String>()
        val aboard = ArrayList<Dog>()
        for (i in events.indices) {
            val e = events[i]
            if (i > 0 && e.incomingTravelSeconds > 0) {
                when {
                    !e.arrivedByFoot ->
                        if (!canRideBike(aboard)) {
                            out.add(
                                "Transport: bike leg into ${label(e)} carries a dog that cannot ride " +
                                    "(aboard: ${aboard.joinToString { it.name }})",
                            )
                        }
                    e !is RouteEvent.FetchBike && aboard.size > maxGroupSize ->
                        out.add("Transport: ${aboard.size} dogs walked on foot into ${label(e)}, over $maxGroupSize")
                }
            }
            when (e) {
                is RouteEvent.Pickup -> aboard.add(e.dog)
                is RouteEvent.Dropoff -> aboard.removeAll { it.id == e.dog.id }
                else -> Unit
            }
        }
        return out
    }

    /** Independent copy of `DayPlanner.canRideBike` — the C9 oracle. */
    private fun canRideBike(aboard: List<Dog>): Boolean {
        var backpack = 0
        for (d in aboard) when {
            d.inCargoBike == TransportState.Yes -> Unit
            d.inBackpack == TransportState.Yes -> backpack++
            else -> return false
        }
        return backpack <= 1
    }

    /** C11 (pragmatic): times never go backwards, and no event starts before
     *  the walker could possibly arrive (previous stop's service + this leg's
     *  travel). Window lower bounds are already covered by reusing C3. */
    private fun timeConsistencyViolations(events: List<RouteEvent>, stopBufferSeconds: Int): List<String> {
        val out = mutableListOf<String>()
        for (i in 1 until events.size) {
            val prev = events[i - 1]
            val cur = events[i]
            if (cur.timeSeconds < prev.timeSeconds) {
                out.add("Time: ${label(cur)} at ${hms(cur.timeSeconds)} is before ${label(prev)} at ${hms(prev.timeSeconds)}")
            }
            val earliestArrival = prev.timeSeconds + prev.durationAtSeconds(stopBufferSeconds) + cur.incomingTravelSeconds
            if (cur.timeSeconds < earliestArrival) {
                out.add(
                    "Time: ${label(cur)} at ${hms(cur.timeSeconds)} starts before the walker could arrive " +
                        "(${hms(earliestArrival)})",
                )
            }
        }
        return out
    }

    private fun label(e: RouteEvent): String = when (e) {
        is RouteEvent.HomeStart -> "HomeStart"
        is RouteEvent.HomeEnd -> "HomeEnd"
        is RouteEvent.Pickup -> "pickup ${e.dog.name}"
        is RouteEvent.Dropoff -> "dropoff ${e.dog.name}"
        is RouteEvent.Walk -> "walk[${e.dogs.joinToString { it.name }}]"
        is RouteEvent.Break -> "Break"
        is RouteEvent.Appointment -> "appointment ${e.label}"
        is RouteEvent.FetchBike -> "FetchBike"
    }

    private fun hms(s: Int) = "%02d:%02d".format(s / 3600, (s % 3600) / 60)
}
