package app.dogrouter.domain.dayplan

/**
 * One pickup→dropoff span for a single walk occurrence. A dog with two
 * schedule rules in a day produces two spans; [dropoff] is null only for a
 * pickup that has no matching dropoff yet (an incomplete candidate route).
 */
data class WalkSpan(
    val pickup: RouteEvent.Pickup,
    val dropoff: RouteEvent.Dropoff?,
)

/**
 * Pairs every [RouteEvent.Pickup] with its matching [RouteEvent.Dropoff] by
 * occurrence (FIFO per dog), so the same dog walked twice in a day yields
 * two distinct spans. This replaces keying by `dog.id`, which collapsed
 * multiple walks of one dog and made the second one impossible to place.
 *
 * A dog is never carried twice at once, so per dog the pickups and dropoffs
 * are already sequential and FIFO pairing is unambiguous.
 */
fun List<RouteEvent>.walkSpans(): List<WalkSpan> {
    val open = HashMap<String, ArrayDeque<RouteEvent.Pickup>>()
    val spans = mutableListOf<WalkSpan>()
    for (event in this) {
        when (event) {
            is RouteEvent.Pickup ->
                open.getOrPut(event.dog.id) { ArrayDeque() }.addLast(event)
            is RouteEvent.Dropoff ->
                open[event.dog.id]?.removeFirstOrNull()?.let { spans.add(WalkSpan(it, event)) }
            else -> Unit
        }
    }
    // Pickups still open had no dropoff in this (candidate) route.
    open.values.forEach { queue -> queue.forEach { spans.add(WalkSpan(it, null)) } }
    return spans
}
