package app.dogrouter.domain.dayplan

import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.domain.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class SavedPlanCodecTest {

    private val date = LocalDate.of(2026, 6, 22)
    private val home = GeoPoint(48.8130, 102.2350)

    private fun dog(id: String, name: String) = Dog(
        id = id, name = name, breed = null, weightKg = 8f, photoUri = null,
        ownerName = "", ownerPhone = null, address = "", latitude = 48.81, longitude = 102.23,
        stopNotes = null, notes = null,
    )

    private fun rule(id: String, dogId: String) = DogScheduleRule(
        id = id, dogId = dogId, weekdaysMask = 0,
        earliestStart = LocalTime.of(9, 0), latestStart = null,
        latestEnd = LocalTime.of(17, 0), durationMinutes = 60,
    )

    private val alfa = dog("alfa", "Alfa")
    private val bravo = dog("bravo", "Bravo")
    private val alfaRule = rule("alfa1", "alfa")
    private val bravoRule = rule("bravo1", "bravo")

    private val loc = GeoPoint(48.8140, 102.2360)

    private val route = DayRoute(
        date = date,
        events = listOf(
            RouteEvent.HomeStart(28_800, home),
            RouteEvent.Pickup(29_100, loc, alfa, alfaRule, incomingTravelSeconds = 300),
            RouteEvent.Pickup(29_200, loc, bravo, bravoRule),
            RouteEvent.Walk(29_300, loc, listOf(alfa, bravo), 3_600),
            RouteEvent.Dropoff(33_000, loc, alfa, incomingTravelSeconds = 120),
            RouteEvent.Dropoff(33_100, loc, bravo),
            RouteEvent.HomeEnd(33_500, home, incomingTravelSeconds = 300),
        ),
        totalCyclingSeconds = 720,
        totalWalkingSeconds = 3_600,
        conflicts = emptyList(),
    )

    @Test
    fun roundTripsAPlanThroughJson() {
        val json = SavedPlanCodec.encode(route)
        val decoded = SavedPlanCodec.decode(json, date, listOf(alfa, bravo), listOf(alfaRule, bravoRule))
        assertEquals(route, decoded)
    }

    @Test
    fun returnsNullWhenAReferencedDogIsGone() {
        val json = SavedPlanCodec.encode(route)
        // Bravo deleted since the plan was saved: rehydration must fail so the
        // caller re-solves instead of showing a corrupt plan.
        val decoded = SavedPlanCodec.decode(json, date, listOf(alfa), listOf(alfaRule, bravoRule))
        assertNull(decoded)
    }
}
