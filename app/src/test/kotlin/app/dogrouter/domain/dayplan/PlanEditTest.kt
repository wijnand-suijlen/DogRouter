package app.dogrouter.domain.dayplan

import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.domain.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanEditTest {

    private val home = GeoPoint(48.8130, 102.2350)
    private val loc = GeoPoint(48.814, 102.236)

    private fun dog(id: String) = Dog(
        id = id, name = id, breed = null, weightKg = 8f, photoUri = null,
        ownerName = "", ownerPhone = null, address = "", latitude = 48.81, longitude = 102.23,
        stopNotes = null, notes = null,
    )
    private fun rule(id: String) = DogScheduleRule(
        id = "$id-r", dogId = id, weekdaysMask = 0, earliestStart = null,
        latestStart = null, latestEnd = null, durationMinutes = 60,
    )

    private val a = dog("a")
    private val b = dog("b")

    /** Two standalone triplets: a then b. */
    private fun twoSolo(): List<RouteEvent> = listOf(
        RouteEvent.HomeStart(0, home),
        RouteEvent.Pickup(0, loc, a, rule("a")),
        RouteEvent.Walk(0, loc, listOf(a), 3600),
        RouteEvent.Dropoff(0, loc, a),
        RouteEvent.Pickup(0, loc, b, rule("b")),
        RouteEvent.Walk(0, loc, listOf(b), 3600),
        RouteEvent.Dropoff(0, loc, b),
        RouteEvent.HomeEnd(0, home),
    )

    /** One shared walk holding a and b. */
    private fun grouped(): List<RouteEvent> = listOf(
        RouteEvent.HomeStart(0, home),
        RouteEvent.Pickup(0, loc, a, rule("a")),
        RouteEvent.Pickup(0, loc, b, rule("b")),
        RouteEvent.Walk(0, loc, listOf(a, b), 3600),
        RouteEvent.Dropoff(0, loc, a),
        RouteEvent.Dropoff(0, loc, b),
        RouteEvent.HomeEnd(0, home),
    )

    private fun walkOrder(events: List<RouteEvent>): List<String> =
        events.filterIsInstance<RouteEvent.Walk>().flatMap { w -> w.dogs.map { it.id } }

    // --- explode / merge round-trip ------------------------------------------

    @Test
    fun explodeSplitsSharedWalkPerDog() {
        val chips = explodeForEditing(grouped())
        val walks = chips.filterIsInstance<RouteEvent.Walk>()
        assertEquals("shared walk becomes one chip per dog", 2, walks.size)
        assertTrue(walks.all { it.dogs.size == 1 })
        assertEquals(listOf("a", "b"), walkOrder(chips))
    }

    @Test
    fun explodeDropsFetchBike() {
        val withFetch = grouped().toMutableList().apply {
            add(1, RouteEvent.FetchBike(0, home))
        }
        assertTrue(explodeForEditing(withFetch).none { it is RouteEvent.FetchBike })
    }

    @Test
    fun mergeCollapsesAdjacentWalksIntoOneSharedWalk() {
        val merged = mergeAdjacentWalks(explodeForEditing(grouped()))
        val walks = merged.filterIsInstance<RouteEvent.Walk>()
        assertEquals(1, walks.size)
        assertEquals(setOf("a", "b"), walks.first().dogs.map { it.id }.toSet())
    }

    @Test
    fun mergeKeepsSameDogBackToBackWalksSeparate() {
        // Two sequential walks of dog a must not fold into an impossible a+a walk.
        val chips = listOf(
            RouteEvent.HomeStart(0, home),
            RouteEvent.Pickup(0, loc, a, rule("a")),
            RouteEvent.Walk(0, loc, listOf(a), 1800),
            RouteEvent.Walk(0, loc, listOf(a), 1800),
            RouteEvent.Dropoff(0, loc, a),
            RouteEvent.HomeEnd(0, home),
        )
        val walks = mergeAdjacentWalks(chips).filterIsInstance<RouteEvent.Walk>()
        assertEquals(2, walks.size)
        assertTrue(walks.all { it.dogs.single().id == "a" })
    }

    @Test
    fun mergeTakesLongestDurationOfTheRun() {
        val chips = listOf(
            RouteEvent.HomeStart(0, home),
            RouteEvent.Pickup(0, loc, a, rule("a")),
            RouteEvent.Pickup(0, loc, b, rule("b")),
            RouteEvent.Walk(0, loc, listOf(a), 1800),
            RouteEvent.Walk(0, loc, listOf(b), 3600),
            RouteEvent.Dropoff(0, loc, a),
            RouteEvent.Dropoff(0, loc, b),
            RouteEvent.HomeEnd(0, home),
        )
        val walk = mergeAdjacentWalks(chips).filterIsInstance<RouteEvent.Walk>().single()
        assertEquals(3600, walk.durationSeconds)
    }

    // --- invariant -----------------------------------------------------------

    @Test
    fun validOrderAcceptsAWellFormedPlan() {
        assertTrue(isValidOrder(twoSolo()))
        assertTrue(isValidOrder(grouped()))
    }

    @Test
    fun validOrderRejectsWalkBeforePickup() {
        val bad = listOf(
            RouteEvent.HomeStart(0, home),
            RouteEvent.Walk(0, loc, listOf(a), 3600),
            RouteEvent.Pickup(0, loc, a, rule("a")),
            RouteEvent.Dropoff(0, loc, a),
            RouteEvent.HomeEnd(0, home),
        )
        assertFalse(isValidOrder(bad))
    }

    @Test
    fun validOrderRejectsMisplacedHomeAnchors() {
        val noHomeFirst = twoSolo().drop(1)
        assertFalse(isValidOrder(noHomeFirst))
    }

    // --- moveChip ------------------------------------------------------------

    @Test
    fun moveChipRejectsMovingAWalkBeforeItsPickup() {
        // twoSolo: index 2 is Walk(a), index 1 is Pickup(a). Moving the walk to
        // index 1 puts it before its pickup -> rejected (null).
        assertNull(moveChip(twoSolo(), from = 2, to = 1))
    }

    @Test
    fun moveChipRejectsDisplacingHomeStart() {
        assertNull(moveChip(twoSolo(), from = 0, to = 3))
    }

    @Test
    fun moveChipReordersAWholeSpanToTheFront() {
        // The original bug: a dog walked second should be draggable to first.
        // Move b's triplet (Pickup, Walk, Dropoff) ahead of a's, chip by chip.
        var ev: List<RouteEvent> = twoSolo()
        // Pickup b is at 4 -> move just after HomeStart (1).
        ev = moveChip(ev, from = 4, to = 1)!!
        // Walk b is now at 5 -> move to 2 (right after its pickup).
        ev = moveChip(ev, from = 5, to = 2)!!
        // Dropoff b is now at 6 -> move to 3.
        ev = moveChip(ev, from = 6, to = 3)!!
        assertTrue(isValidOrder(ev))
        assertEquals("b is now walked before a", listOf("b", "a"), walkOrder(ev))
    }

    // --- split / merge walk --------------------------------------------------

    @Test
    fun splitWalkInTwoHalvesAndPreservesTotal() {
        val chips = explodeForEditing(twoSolo())
        val walkIndex = chips.indexOfFirst { it is RouteEvent.Walk }
        val split = splitWalkInTwo(chips, walkIndex)!!
        val firstTwo = split.filterIsInstance<RouteEvent.Walk>().filter { it.dogs.single().id == "a" }
        assertEquals(2, firstTwo.size)
        assertEquals(3600, firstTwo.sumOf { it.durationSeconds })
    }

    @Test
    fun splitThenMergeRoundTrips() {
        val chips = explodeForEditing(twoSolo())
        val walkIndex = chips.indexOfFirst { it is RouteEvent.Walk }
        val split = splitWalkInTwo(chips, walkIndex)!!
        assertTrue(canMergeWalk(split, walkIndex))
        val merged = mergeWalkWithNeighbor(split, walkIndex)!!
        val aWalks = merged.filterIsInstance<RouteEvent.Walk>().filter { it.dogs.single().id == "a" }
        assertEquals(1, aWalks.size)
        assertEquals(3600, aWalks.single().durationSeconds)
    }

    @Test
    fun mergeWalkReturnsNullWithoutSameDogNeighbour() {
        val chips = explodeForEditing(twoSolo())
        val walkIndex = chips.indexOfFirst { it is RouteEvent.Walk }
        assertFalse(canMergeWalk(chips, walkIndex))
        assertNull(mergeWalkWithNeighbor(chips, walkIndex))
    }

    // --- leg mode ------------------------------------------------------------

    @Test
    fun setLegModeOverridesTheEventLeg() {
        val chips = twoSolo()
        val out = setLegMode(chips, index = 2, mode = LegMode.FOOT)
        assertEquals(LegMode.FOOT, out[2].legMode)
        assertEquals("other events untouched", LegMode.AUTO, out[1].legMode)
    }
}
