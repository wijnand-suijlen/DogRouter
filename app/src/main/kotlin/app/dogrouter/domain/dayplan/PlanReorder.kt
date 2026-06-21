package app.dogrouter.domain.dayplan

/**
 * Pure reorder helpers for manual plan editing (Fase 2a). The only safe,
 * predictable reorder in this grouped/split model is swapping two **adjacent
 * standalone triplets** — a `Pickup(d) → Walk([d]) → Dropoff(d)` for a single
 * dog, with nothing else interleaved. Swapping two such complete blocks never
 * changes who is aboard (each block is self-contained), so it cannot break the
 * capacity / no-dog-left-behind / transport invariants; only the order and the
 * times change (the caller re-times afterwards). Walks that share a group or
 * are split across walks are not reorderable here — that is Fase 2b (regroup).
 *
 * Indices from the UI are into the FULL event list (incl. presentation-only
 * `FetchBike`); these helpers work on a FetchBike-stripped view and return a
 * stripped list for the caller to re-time (which re-adds the fetches).
 */

/** Whether the walk at [fullIndex] can be moved earlier / later in the day. */
data class WalkReorder(val canMoveEarlier: Boolean, val canMoveLater: Boolean)

private fun List<RouteEvent>.core(): List<RouteEvent> = filterNot { it is RouteEvent.FetchBike }

/** If the core event at [ci] is a standalone triplet's walk, its pickup→dropoff
 *  index range `[ci-1, ci+1]`; else null. */
private fun List<RouteEvent>.tripletRangeAt(ci: Int): IntRange? {
    val w = getOrNull(ci) as? RouteEvent.Walk ?: return null
    if (w.dogs.size != 1) return null
    val dogId = w.dogs.first().id
    val p = getOrNull(ci - 1) as? RouteEvent.Pickup ?: return null
    val d = getOrNull(ci + 1) as? RouteEvent.Dropoff ?: return null
    if (p.dog.id != dogId || d.dog.id != dogId) return null
    return (ci - 1)..(ci + 1)
}

/** Reorder availability for the walk at [fullIndex], or null if that walk is
 *  not a movable standalone triplet. */
fun reorderInfo(events: List<RouteEvent>, fullIndex: Int): WalkReorder? {
    val walk = events.getOrNull(fullIndex) as? RouteEvent.Walk ?: return null
    val core = events.core()
    val ci = core.indexOfFirst { it === walk }
    if (ci < 0 || core.tripletRangeAt(ci) == null) return null
    // The previous/next adjacent block is a standalone triplet whose walk sits
    // three positions away (block = pickup, walk, dropoff).
    return WalkReorder(
        canMoveEarlier = core.tripletRangeAt(ci - 3) != null,
        canMoveLater = core.tripletRangeAt(ci + 3) != null,
    )
}

/** Swap the standalone triplet whose walk is at [fullIndex] with the adjacent
 *  standalone triplet ([earlier] = the one before, else the one after).
 *  Returns the new FetchBike-stripped event list, or null if the move is not
 *  possible. */
fun moveStandaloneWalk(events: List<RouteEvent>, fullIndex: Int, earlier: Boolean): List<RouteEvent>? {
    val walk = events.getOrNull(fullIndex) as? RouteEvent.Walk ?: return null
    val core = events.core()
    val ci = core.indexOfFirst { it === walk }
    if (ci < 0) return null
    val mine = core.tripletRangeAt(ci) ?: return null
    val otherWalkCi = if (earlier) ci - 3 else ci + 3
    val other = core.tripletRangeAt(otherWalkCi) ?: return null
    val a = if (earlier) other else mine // the block that comes first
    val b = if (earlier) mine else other // the block that comes second (b.first == a.last + 1)
    return ArrayList<RouteEvent>(core.size).apply {
        addAll(core.subList(0, a.first))
        addAll(core.subList(b.first, b.last + 1))
        addAll(core.subList(a.first, a.last + 1))
        addAll(core.subList(b.last + 1, core.size))
    }
}
