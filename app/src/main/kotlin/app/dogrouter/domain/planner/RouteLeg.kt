package app.dogrouter.domain.planner

import app.dogrouter.domain.routing.RouteEstimate

/**
 * One cycling leg between two consecutive stops in a trip. The estimate
 * is null when the leg cannot be computed — either because one of the
 * stops has no coordinates yet, or because the routing engine could not
 * find a path.
 */
data class RouteLeg(
    val fromDogId: String,
    val toDogId: String,
    val estimate: RouteEstimate?,
)
