package app.dogrouter.domain.dayplan

import app.dogrouter.data.entity.Dog

/**
 * One walk that the planner could not fit into the day. Surfaced to the
 * UI so the walker knows what was dropped and why.
 */
data class PlanConflict(
    val dog: Dog,
    val reason: String,
)
