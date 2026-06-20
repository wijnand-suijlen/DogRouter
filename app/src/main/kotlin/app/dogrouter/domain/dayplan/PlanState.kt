package app.dogrouter.domain.dayplan

/** Which phase of the computation a [PlanState.Loading] is reporting. */
enum class PlanPhase { ROUTING, OPTIMISING }

/**
 * What the UI shows for a day: either a plan is being computed (with a
 * 0..1 [Loading.fraction] for a determinate progress bar) or it is [Ready].
 * The work has two known-size phases — building the distance matrix
 * (routing) and the multi-start + LNS solver (optimising) — so progress is
 * a real fraction, not an indeterminate spinner.
 */
sealed interface PlanState {
    data class Loading(val fraction: Float, val phase: PlanPhase) : PlanState
    data class Ready(val route: DayRoute) : PlanState
}
