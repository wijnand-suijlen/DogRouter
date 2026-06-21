package app.dogrouter.domain.dayplan

/**
 * Pure transformations behind the drag-and-drop plan editor. The editor's
 * guiding rule is **position = execution order**: the chips the walker sees are
 * the event list (FetchBike stripped, shared walks split per dog), and the order
 * they are dragged into is exactly the order `DayPlanner.retime` will time.
 *
 * Two representations round-trip:
 *  - **exploded** (what the UI shows): every [RouteEvent.Walk] holds a single
 *    dog. Adjacent walk chips imply a shared walk.
 *  - **merged** (what is timed and persisted): runs of adjacent walk chips are
 *    collapsed into one [RouteEvent.Walk] with all their dogs, exactly the shape
 *    the solver and [SavedPlanCodec] already use.
 *
 * The only invariant the editor enforces on drops is physical per dog:
 * `pickup ≤ walk(s) ≤ dropoff`. Everything else (capacity, group size, windows,
 * incompatibility, day overrun) is left to retime + [PlanVerifier] as warnings.
 */

/** Split shared walks into one chip per dog and drop presentation-only
 *  [RouteEvent.FetchBike]; the inverse of [mergeAdjacentWalks] for display. */
fun explodeForEditing(events: List<RouteEvent>): List<RouteEvent> {
    val out = ArrayList<RouteEvent>(events.size)
    for (e in events) {
        when {
            e is RouteEvent.FetchBike -> Unit
            e is RouteEvent.Walk && e.dogs.size > 1 -> e.dogs.forEach { out.add(e.copy(dogs = listOf(it))) }
            else -> out.add(e)
        }
    }
    return out
}

/**
 * Collapse maximal runs of adjacent [RouteEvent.Walk] chips into one shared
 * walk (dogs concatenated in order, duration = the longest of the run). A dog
 * that would repeat within a run breaks it, so two back-to-back walks of the
 * same dog stay two sequential walks rather than an impossible self-pairing.
 * Used both before retime and at finalisation.
 */
fun mergeAdjacentWalks(events: List<RouteEvent>): List<RouteEvent> {
    val out = ArrayList<RouteEvent>(events.size)
    var i = 0
    while (i < events.size) {
        val first = events[i]
        if (first !is RouteEvent.Walk) {
            out.add(first)
            i++
            continue
        }
        val dogs = ArrayList(first.dogs)
        val ids = HashSet(first.dogs.map { it.id })
        var dur = first.durationSeconds
        var j = i + 1
        while (j < events.size) {
            val next = events[j] as? RouteEvent.Walk ?: break
            if (next.dogs.any { it.id in ids }) break // same dog twice => separate walk
            dogs.addAll(next.dogs)
            ids.addAll(next.dogs.map { it.id })
            dur = maxOf(dur, next.durationSeconds)
            j++
        }
        out.add(first.copy(dogs = dogs, durationSeconds = dur))
        i = j
    }
    return out
}

/**
 * Whether [events] is a physically valid order: it starts at home and ends at
 * home, and every dog is walked only while aboard (picked up before, dropped
 * off after, never walked twice in one span). This is the one hard rule the
 * editor enforces on a drop.
 */
fun isValidOrder(events: List<RouteEvent>): Boolean {
    if (events.isEmpty()) return true
    if (events.first() !is RouteEvent.HomeStart) return false
    if (events.last() !is RouteEvent.HomeEnd) return false
    val aboard = HashSet<String>()
    for (e in events) {
        when (e) {
            is RouteEvent.Pickup -> if (!aboard.add(e.dog.id)) return false
            is RouteEvent.Dropoff -> if (!aboard.remove(e.dog.id)) return false
            is RouteEvent.Walk -> if (e.dogs.any { it.id !in aboard }) return false
            else -> Unit
        }
    }
    return aboard.isEmpty()
}

/**
 * Move the chip at [from] to index [to] (indices into the exploded list).
 * Returns the new list, or null if the move would break [isValidOrder] — the
 * UI uses null to clamp the drag (the chip springs back). Moving the home
 * anchors is always rejected this way.
 */
fun moveChip(events: List<RouteEvent>, from: Int, to: Int): List<RouteEvent>? {
    if (from !in events.indices) return null
    val list = events.toMutableList()
    val item = list.removeAt(from)
    list.add(to.coerceIn(0, list.size), item)
    return if (isValidOrder(list)) list else null
}

/**
 * Split the single-dog walk chip at [index] into two consecutive walks of half
 * the duration each, so the walker can drag a half elsewhere or walk the dog in
 * two sessions. Returns null if [index] is not a single-dog walk.
 */
fun splitWalkInTwo(events: List<RouteEvent>, index: Int): List<RouteEvent>? {
    val walk = events.getOrNull(index) as? RouteEvent.Walk ?: return null
    if (walk.dogs.size != 1) return null
    val firstHalf = (walk.durationSeconds / 2).coerceAtLeast(60)
    val secondHalf = (walk.durationSeconds - firstHalf).coerceAtLeast(60)
    val list = events.toMutableList()
    list[index] = walk.copy(durationSeconds = firstHalf)
    list.add(index + 1, walk.copy(durationSeconds = secondHalf))
    return list
}

/** Whether the single-dog walk at [index] has an adjacent walk of the same dog
 *  it can merge with (so the UI offers "merge" rather than "split"). */
fun canMergeWalk(events: List<RouteEvent>, index: Int): Boolean {
    val walk = events.getOrNull(index) as? RouteEvent.Walk ?: return false
    val dogId = walk.dogs.singleOrNull()?.id ?: return false
    val prev = (events.getOrNull(index - 1) as? RouteEvent.Walk)?.dogs?.singleOrNull()?.id
    val next = (events.getOrNull(index + 1) as? RouteEvent.Walk)?.dogs?.singleOrNull()?.id
    return prev == dogId || next == dogId
}

/**
 * Merge the single-dog walk at [index] with an adjacent walk of the same dog
 * (preferring the previous one), summing their durations. Returns null when no
 * such neighbour exists.
 */
fun mergeWalkWithNeighbor(events: List<RouteEvent>, index: Int): List<RouteEvent>? {
    val walk = events.getOrNull(index) as? RouteEvent.Walk ?: return null
    val dogId = walk.dogs.singleOrNull()?.id ?: return null
    val prev = events.getOrNull(index - 1) as? RouteEvent.Walk
    val next = events.getOrNull(index + 1) as? RouteEvent.Walk
    return when {
        prev != null && prev.dogs.singleOrNull()?.id == dogId -> {
            val list = events.toMutableList()
            list[index - 1] = prev.copy(durationSeconds = prev.durationSeconds + walk.durationSeconds)
            list.removeAt(index)
            list
        }
        next != null && next.dogs.singleOrNull()?.id == dogId -> {
            val list = events.toMutableList()
            list[index] = walk.copy(durationSeconds = walk.durationSeconds + next.durationSeconds)
            list.removeAt(index + 1)
            list
        }
        else -> null
    }
}

/** Set the leg-mode override on the event at [index] (the leg that reaches it). */
fun setLegMode(events: List<RouteEvent>, index: Int, mode: LegMode): List<RouteEvent> {
    val e = events.getOrNull(index) ?: return events
    return events.toMutableList().also { it[index] = e.withLegMode(mode) }
}

/** Drop a dog entirely from the exploded chip list: its pickup, its dropoff and
 *  every (single-dog) walk chip of it. Used by "not walked today". */
fun removeDogChips(events: List<RouteEvent>, dogId: String): List<RouteEvent> =
    events.filterNot { e ->
        (e is RouteEvent.Pickup && e.dog.id == dogId) ||
            (e is RouteEvent.Dropoff && e.dog.id == dogId) ||
            (e is RouteEvent.Walk && e.dogs.any { it.id == dogId })
    }
