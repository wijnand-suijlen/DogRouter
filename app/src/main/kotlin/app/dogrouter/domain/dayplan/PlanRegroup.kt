package app.dogrouter.domain.dayplan

/**
 * Pure regroup helpers for manual plan editing (Fase 2b): move a dog out of a
 * shared walk to walk alone, or into another dog's walk to walk together.
 *
 * Both return a FetchBike-stripped event list for the caller to re-time (which
 * re-adds the fetches), or null when the move does not apply. Indices from the
 * UI are into the full event list (incl. presentation-only FetchBike).
 *
 * A regrouped dog keeps its own pickup/dropoff (at its address) and its
 * schedule rule; when it joins a walk that walk grows to the longer of the two
 * required durations. Constraints are not enforced here — the caller validates
 * (warn but allow), e.g. a join that exceeds the group cap shows a warning.
 */

private fun List<RouteEvent>.withoutFetchBike(): List<RouteEvent> =
    filterNot { it is RouteEvent.FetchBike }

/** Whether [dogId] currently shares a walk with another dog or is split across
 *  several walks — i.e. "walk alone" would change something. */
fun isDogGrouped(events: List<RouteEvent>, dogId: String): Boolean {
    val walks = events.filterIsInstance<RouteEvent.Walk>().filter { w -> w.dogs.any { it.id == dogId } }
    return walks.size > 1 || walks.any { it.dogs.size > 1 }
}

/** Drop a dog from every walk it shares and give it its own standalone
 *  pickup→walk→dropoff (at the end of the day), or null if it is not grouped. */
fun splitDogOut(events: List<RouteEvent>, dogId: String): List<RouteEvent>? {
    if (!isDogGrouped(events, dogId)) return null
    val core = events.withoutFetchBike()
    val pickup = core.firstOrNull { it is RouteEvent.Pickup && it.dog.id == dogId } as? RouteEvent.Pickup
        ?: return null
    val dog = pickup.dog
    val rule = pickup.rule
    val loc = pickup.location
    val cleaned = core.removingDog(dogId)
    val insertAt = (cleaned.size - 1).coerceAtLeast(1)
    cleaned.add(insertAt, RouteEvent.Dropoff(0, loc, dog))
    cleaned.add(insertAt, RouteEvent.Walk(0, loc, listOf(dog), rule.durationMinutes * 60))
    cleaned.add(insertAt, RouteEvent.Pickup(0, loc, dog, rule))
    return cleaned
}

/** Move [dogId] into the walk at [targetWalkFullIndex] (ride-along): drop it
 *  from its current walk(s), add it to the target (growing the target's
 *  duration to fit), and bracket the target with the dog's pickup/dropoff.
 *  Null if the target is not a walk, already holds the dog, or the dog is gone. */
fun groupDogInto(events: List<RouteEvent>, dogId: String, targetWalkFullIndex: Int): List<RouteEvent>? {
    val target = events.getOrNull(targetWalkFullIndex) as? RouteEvent.Walk ?: return null
    if (target.dogs.any { it.id == dogId }) return null
    val core = events.withoutFetchBike()
    val pickup = core.firstOrNull { it is RouteEvent.Pickup && it.dog.id == dogId } as? RouteEvent.Pickup
        ?: return null
    val dog = pickup.dog
    val rule = pickup.rule
    val loc = pickup.location
    val cleaned = core.removingDog(dogId)
    val ti = cleaned.indexOfFirst { it === target }
    if (ti < 0) return null
    cleaned[ti] = target.copy(
        dogs = target.dogs + dog,
        durationSeconds = maxOf(target.durationSeconds, rule.durationMinutes * 60),
    )
    cleaned.add(ti + 1, RouteEvent.Dropoff(0, loc, dog))
    cleaned.add(ti, RouteEvent.Pickup(0, loc, dog, rule))
    return cleaned
}

/** A mutable copy of these events with [dogId]'s pickup/dropoff dropped and the
 *  dog removed from every walk (emptied walks dropped). */
private fun List<RouteEvent>.removingDog(dogId: String): MutableList<RouteEvent> =
    mapNotNull { e ->
        when {
            e is RouteEvent.Pickup && e.dog.id == dogId -> null
            e is RouteEvent.Dropoff && e.dog.id == dogId -> null
            e is RouteEvent.Walk && e.dogs.any { it.id == dogId } -> {
                val remaining = e.dogs.filter { it.id != dogId }
                if (remaining.isEmpty()) null else e.copy(dogs = remaining)
            }
            else -> e
        }
    }.toMutableList()
