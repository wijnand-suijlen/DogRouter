package app.dogrouter.domain.dayplan

import app.dogrouter.data.entity.Dog
import app.dogrouter.domain.planner.PlannedWalk

/**
 * One thing the planner must schedule for a dog on a day. With a single
 * [alternatives] entry it is a plain required walk; with several it is an
 * exclusive choice — the planner places exactly one of them ("end of
 * morning OR end of afternoon"). All alternatives are for the same dog.
 */
data class WalkOption(val alternatives: List<PlannedWalk>) {
    init {
        require(alternatives.isNotEmpty()) { "A WalkOption needs at least one alternative" }
    }

    val dog: Dog get() = alternatives.first().dog
    val isChoice: Boolean get() = alternatives.size > 1
}
