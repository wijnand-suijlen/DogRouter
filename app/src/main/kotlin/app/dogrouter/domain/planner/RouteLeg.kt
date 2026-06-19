package app.dogrouter.domain.planner

import app.dogrouter.domain.routing.RouteEstimate

/**
 * One cycling leg between two consecutive points in a trip. The estimate
 * is null when the leg cannot be computed — either because one endpoint
 * has no coordinates, or because the routing engine could not find a path.
 * The endpoints themselves are implicit by position in the surrounding
 * [Trip].
 */
data class RouteLeg(
    val estimate: RouteEstimate?,
)
