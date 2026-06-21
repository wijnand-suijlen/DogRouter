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

/**
 * On-foot travel the dog actually walks within this span: every on-foot
 * stretch while it is aboard — from just after its pickup through its
 * dropoff. This is a full on-foot leg, plus the walk back to the parked bike
 * at the start of a bike leg ([RouteEvent.onFootSeconds]). The pickup's own
 * incoming leg is excluded: that leg fetches the dog, which is not yet
 * aboard, so it is not the dog's walk time. On-foot travel doubles as walk
 * time (the dog walks the group on a leash), so it counts toward the dog's
 * required duration alongside the in-place dwell walks.
 */
fun WalkSpan.footCreditSeconds(events: List<RouteEvent>): Int {
    val drop = dropoff ?: return 0
    return events
        .filter { it !== pickup && it.timeSeconds in pickup.timeSeconds..drop.timeSeconds }
        .sumOf { it.onFootSeconds }
}

/**
 * Total seconds the dog actually walks in this span: the in-place [RouteEvent.Walk]
 * dwells it joins, plus its on-foot travel ([footCreditSeconds]). This is the
 * single definition of "walked" shared by the [WalkDuration] constraint and the
 * objective's over-walk term, so the two never drift apart. 0 for an open span.
 */
fun WalkSpan.walkedSeconds(events: List<RouteEvent>): Int {
    val drop = dropoff ?: return 0
    val dogId = pickup.dog.id
    val range = pickup.timeSeconds..drop.timeSeconds
    val dwell = events
        .filterIsInstance<RouteEvent.Walk>()
        .filter { it.timeSeconds in range && it.dogs.any { d -> d.id == dogId } }
        .sumOf { it.durationSeconds }
    return dwell + footCreditSeconds(events)
}
