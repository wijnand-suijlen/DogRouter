package app.dogrouter.domain.dayplan

import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.domain.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class PlanReorderTest {

    private val home = GeoPoint(48.8130, 102.2350)
    private fun dog(id: String) = Dog(
        id = id, name = id, breed = null, weightKg = 8f, photoUri = null,
        ownerName = "", ownerPhone = null, address = "", latitude = 48.81, longitude = 102.23,
        stopNotes = null, notes = null,
    )
    private fun rule(id: String) = DogScheduleRule(
        id = "$id-r", dogId = id, weekdaysMask = 0, earliestStart = LocalTime.of(9, 0),
        latestStart = null, latestEnd = null, durationMinutes = 60,
    )

    private val a = dog("a")
    private val b = dog("b")
    private val loc = GeoPoint(48.814, 102.236)

    /** [HomeStart, P(a), W(a), D(a), P(b), W(b), D(b), HomeEnd] — two standalone triplets. */
    private fun twoTriplets(): List<RouteEvent> = listOf(
        RouteEvent.HomeStart(0, home),
        RouteEvent.Pickup(0, loc, a, rule("a")),
        RouteEvent.Walk(0, loc, listOf(a), 3600),
        RouteEvent.Dropoff(0, loc, a),
        RouteEvent.Pickup(0, loc, b, rule("b")),
        RouteEvent.Walk(0, loc, listOf(b), 3600),
        RouteEvent.Dropoff(0, loc, b),
        RouteEvent.HomeEnd(0, home),
    )

    @Test
    fun reorderInfoReflectsAdjacentTriplets() {
        val events = twoTriplets()
        val first = reorderInfo(events, 2)!!  // W(a)
        assertFalse(first.canMoveEarlier)
        assertTrue(first.canMoveLater)
        val second = reorderInfo(events, 5)!! // W(b)
        assertTrue(second.canMoveEarlier)
        assertFalse(second.canMoveLater)
    }

    @Test
    fun movesAStandaloneWalkLater() {
        val moved = moveStandaloneWalk(twoTriplets(), 2, earlier = false)!!
        // b's triplet now comes before a's.
        val dogOrder = moved.filterIsInstance<RouteEvent.Pickup>().map { it.dog.id }
        assertEquals(listOf("b", "a"), dogOrder)
    }

    @Test
    fun movingEarlierMirrorsMovingLater() {
        val moved = moveStandaloneWalk(twoTriplets(), 5, earlier = true)!!
        val dogOrder = moved.filterIsInstance<RouteEvent.Pickup>().map { it.dog.id }
        assertEquals(listOf("b", "a"), dogOrder)
    }

    @Test
    fun aGroupedWalkIsNotReorderable() {
        val events = listOf(
            RouteEvent.HomeStart(0, home),
            RouteEvent.Pickup(0, loc, a, rule("a")),
            RouteEvent.Pickup(0, loc, b, rule("b")),
            RouteEvent.Walk(0, loc, listOf(a, b), 3600),
            RouteEvent.Dropoff(0, loc, a),
            RouteEvent.Dropoff(0, loc, b),
            RouteEvent.HomeEnd(0, home),
        )
        assertNull(reorderInfo(events, 3)) // the 2-dog walk
        assertNull(moveStandaloneWalk(events, 3, earlier = true))
    }

    @Test
    fun fetchBikeEventsAreIgnored() {
        // A display-only FetchBike before b's pickup must not break adjacency.
        val events = twoTriplets().toMutableList().apply {
            add(4, RouteEvent.FetchBike(0, home))
        }
        // W(b) is now at index 6.
        val info = reorderInfo(events, 6)!!
        assertTrue(info.canMoveEarlier)
        val moved = moveStandaloneWalk(events, 6, earlier = true)!!
        assertEquals(listOf("b", "a"), moved.filterIsInstance<RouteEvent.Pickup>().map { it.dog.id })
    }
}
