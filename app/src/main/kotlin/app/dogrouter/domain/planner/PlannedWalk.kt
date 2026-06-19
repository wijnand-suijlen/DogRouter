package app.dogrouter.domain.planner

import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogScheduleRule

/**
 * A single dog's walk on a specific weekday, paired with the rule that
 * triggered it (so we can show the time window and duration alongside the
 * dog identity).
 */
data class PlannedWalk(
    val dog: Dog,
    val rule: DogScheduleRule,
)
