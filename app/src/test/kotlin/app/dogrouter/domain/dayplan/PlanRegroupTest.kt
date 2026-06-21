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

class PlanRegroupTest {

    private val home = GeoPoint(48.8130, 102.2350)
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
    private val loc = GeoPoint(48.814, 102.236)

    /** Two solo triplets. */
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

    @Test
    fun groupedDetection() {
        assertTrue(isDogGrouped(grouped(), "a"))
        assertFalse(isDogGrouped(twoSolo(), "a"))
    }

    @Test
    fun groupsTwoSoloDogsIntoOneWalk() {
        // join a (its walk at index 2 is irrelevant) into b's walk at index 5.
        val result = groupDogInto(twoSolo(), "a", 5)!!
        val walks = result.filterIsInstance<RouteEvent.Walk>()
        assertEquals("the two solo walks become one", 1, walks.size)
        assertEquals(setOf("a", "b"), walks.first().dogs.map { it.id }.toSet())
    }

    @Test
    fun splitsAGroupedDogBackToItsOwnWalk() {
        val result = splitDogOut(grouped(), "a")!!
        val walks = result.filterIsInstance<RouteEvent.Walk>()
        // The shared walk keeps only b; a gets its own walk.
        val soloA = walks.filter { w -> w.dogs.map { it.id } == listOf("a") }
        val withB = walks.filter { w -> w.dogs.any { it.id == "b" } }
        assertEquals(1, soloA.size)
        assertEquals(1, withB.size)
        assertTrue(withB.first().dogs.none { it.id == "a" })
    }

    @Test
    fun splitIsNullWhenAlreadyAlone() {
        assertNull(splitDogOut(twoSolo(), "a"))
    }
}
