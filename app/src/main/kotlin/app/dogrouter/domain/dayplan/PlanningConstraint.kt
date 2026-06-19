package app.dogrouter.domain.dayplan

/**
 * One planning constraint. Given a candidate sequence of events with
 * times already filled in, returns null when feasible or a short
 * violation reason otherwise.
 *
 * Constraints are pure and stateless after construction; the planner
 * may call [violation] many times during insertion search.
 */
fun interface PlanningConstraint {
    fun violation(events: List<RouteEvent>): String?
}
