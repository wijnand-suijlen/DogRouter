package app.dogrouter.domain.billing

import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.domain.dayplan.RouteEvent
import app.dogrouter.domain.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class BillingServiceTest {

    private val date = LocalDate.of(2026, 6, 22)
    private val loc = GeoPoint(48.81, 2.24)

    private fun dog(id: String, ownerId: String?) = Dog(
        id = id, name = id, breed = null, weightKg = 8f, photoUri = null,
        ownerId = ownerId, ownerName = "", ownerPhone = null, address = "",
        latitude = 48.81, longitude = 2.24, stopNotes = null, notes = null,
    )

    private fun rule(dogId: String, durationMinutes: Int, priceCents: Int? = null) = DogScheduleRule(
        id = "$dogId-r", dogId = dogId, weekdaysMask = 0, earliestStart = null,
        latestStart = null, latestEnd = null, durationMinutes = durationMinutes, priceCents = priceCents,
    )

    private fun pickup(dog: Dog, durationMinutes: Int, priceCents: Int? = null) =
        RouteEvent.Pickup(0, loc, dog, rule(dog.id, durationMinutes, priceCents))

    @Test
    fun singleDogUsesTheDefaultTariffForItsDuration() {
        val a = dog("a", "owner1")
        val events = listOf(
            pickup(a, 60),
            RouteEvent.Walk(0, loc, listOf(a), 3600),
            RouteEvent.Dropoff(0, loc, a),
        )
        val services = buildBillableServices(date, events, now = 0L)
        assertEquals(1, services.size)
        assertEquals(1600, services.single().amountCents) // €16 for 60 min
        assertEquals("owner1", services.single().ownerId)
        assertEquals(false, services.single().paid)
    }

    @Test
    fun rulePriceOverridesTheDefault() {
        val a = dog("a", "owner1")
        val events = listOf(
            pickup(a, 60, priceCents = 1200),
            RouteEvent.Walk(0, loc, listOf(a), 3600),
            RouteEvent.Dropoff(0, loc, a),
        )
        assertEquals(1200, buildBillableServices(date, events, 0L).single().amountCents)
    }

    @Test
    fun secondDogOfSameOwnerInSameWalkIsHalfPrice() {
        val a = dog("a", "owner1")
        val b = dog("b", "owner1")
        val events = listOf(
            pickup(a, 60), // €16
            pickup(b, 60), // €16
            RouteEvent.Walk(0, loc, listOf(a, b), 3600),
            RouteEvent.Dropoff(0, loc, a),
            RouteEvent.Dropoff(0, loc, b),
        )
        val amounts = buildBillableServices(date, events, 0L).associate { it.dogId to it.amountCents }
        assertEquals(setOf(1600, 800), amounts.values.toSet()) // one full, one half
    }

    @Test
    fun twoDogsOfDifferentOwnersAreBothFullPrice() {
        val a = dog("a", "owner1")
        val b = dog("b", "owner2")
        val events = listOf(
            pickup(a, 60),
            pickup(b, 60),
            RouteEvent.Walk(0, loc, listOf(a, b), 3600),
            RouteEvent.Dropoff(0, loc, a),
            RouteEvent.Dropoff(0, loc, b),
        )
        val amounts = buildBillableServices(date, events, 0L).map { it.amountCents }
        assertEquals(listOf(1600, 1600), amounts)
    }

    @Test
    fun aWalkSplitAcrossTwoWalkEventsIsBilledOnce() {
        // Una: a 120-min walk realised as two 60-min segments in one span.
        val una = dog("una", "owner1")
        val events = listOf(
            pickup(una, 120),
            RouteEvent.Walk(0, loc, listOf(una), 3600),
            RouteEvent.Walk(0, loc, listOf(una), 3600),
            RouteEvent.Dropoff(0, loc, una),
        )
        val services = buildBillableServices(date, events, 0L)
        assertEquals(1, services.size)
        assertEquals(2400, services.single().amountCents) // default(120) capped at €24
        assertEquals(120, services.single().durationMinutes)
    }

    @Test
    fun twoSeparatePickupDropoffSpansYieldTwoServices() {
        val a = dog("a", "owner1")
        val events = listOf(
            pickup(a, 30),
            RouteEvent.Walk(0, loc, listOf(a), 1800),
            RouteEvent.Dropoff(0, loc, a),
            pickup(a, 30),
            RouteEvent.Walk(0, loc, listOf(a), 1800),
            RouteEvent.Dropoff(0, loc, a),
        )
        assertEquals(2, buildBillableServices(date, events, 0L).size)
    }
}
