package app.dogrouter.domain.dayplan

import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.domain.planner.PlannedWalk
import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.domain.routing.RouteEstimate
import app.dogrouter.domain.routing.RoutingProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reproduction of the 19-June "planner says infeasible but it is feasible"
 * report (planningsprobleem-19juni). Uses a fake routing provider with
 * straight-line distances so the planner logic — not BRouter — is under
 * test.
 */
class DayPlannerScenarioTest {

    private val home = GeoPoint(48.8130, 2.2350)

    /** Straight-line distance; duration is unused (planner divides distance by speed). */
    private class FakeRouting : RoutingProvider {
        override suspend fun isReady() = true
        override val lastError: String? = null
        override suspend fun routeGeometry(
            fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double,
        ): List<GeoPoint>? = null

        override suspend fun route(
            fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double,
        ): RouteEstimate {
            val r = 6_371_000.0
            val dLat = Math.toRadians(toLatitude - fromLatitude)
            val dLon = Math.toRadians(toLongitude - fromLongitude)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(fromLatitude)) * cos(Math.toRadians(toLatitude)) *
                sin(dLon / 2) * sin(dLon / 2)
            val meters = (r * 2 * atan2(sqrt(a), sqrt(1 - a))).toInt()
            return RouteEstimate(distanceMeters = meters, durationSeconds = 0)
        }
    }

    private fun dog(id: String, name: String, weight: Float, lat: Double, lon: Double) = Dog(
        id = id, name = name, breed = null, weightKg = weight, photoUri = null,
        ownerName = "", ownerPhone = null, address = "", latitude = lat, longitude = lon,
        stopNotes = null, notes = null,
    )

    private fun rule(id: String, dogId: String, start: String, end: String, minutes: Int) =
        DogScheduleRule(
            id = id, dogId = dogId, weekdaysMask = 0,
            earliestStart = LocalTime.parse(start), latestEnd = LocalTime.parse(end),
            durationMinutes = minutes,
        )

    @Test
    fun mondayScenario() = runBlocking {
        val alfa = dog("alfa", "Alfa", 8f, 48.8145, 2.2360)
        val bravo = dog("bravo", "Bravo", 24f, 48.8120, 2.2300)
        val charlie = dog("charlie", "Charlie", 25f, 48.7970, 2.2600) // elsewhere
        val yankee = dog("yankee", "Yankee", 9f, 48.8100, 2.2400)
        val delta = dog("delta", "Delta", 12f, 48.8160, 2.2450)
        val echo = dog("echo", "Echo", 24f, 48.8180, 2.2280)

        val walks = listOf(
            PlannedWalk(alfa, rule("alfa1", "alfa", "09:30", "16:00", 120)),
            PlannedWalk(bravo, rule("bravo1", "bravo", "09:30", "16:00", 60)),
            PlannedWalk(charlie, rule("charlieA", "charlie", "10:00", "13:00", 45)),
            PlannedWalk(charlie, rule("charlieB", "charlie", "15:00", "18:00", 45)),
            PlannedWalk(yankee, rule("yankee1", "yankee", "11:00", "15:00", 60)),
            PlannedWalk(delta, rule("delta1", "delta", "11:00", "14:00", 60)),
            PlannedWalk(echo, rule("echo1", "echo", "11:30", "15:30", 60)),
        )

        val planner = DayPlanner(
            routingProvider = FakeRouting(),
            home = home,
            capacityKg = 70f,
            stopBufferSeconds = 0,
            cyclingSpeedKmh = 15f,
            incompatibilities = emptySet(),
        )

        val route = planner.plan(LocalDate.of(2026, 6, 22), walks)

        val summary = buildString {
            appendLine()
            appendLine("EVENTS:")
            route.events.forEach { e -> appendLine("  ${fmt(e.timeSeconds)}  ${describe(e)}") }
            appendLine("CONFLICTS (${route.conflicts.size}):")
            route.conflicts.forEach { c -> appendLine("  ${c.dog.name}: ${c.reason}") }
        }
        println(summary)
        assertTrue("Expected a feasible plan but got conflicts:$summary", route.conflicts.isEmpty())
    }

    /**
     * Same scenario, but Charlie's second walk is given a distinct dog id
     * ("charlie2" at the same place). If this now places where the real
     * (same-id) second rule did not, the cause is the constraints keying
     * everything by dog.id — one dog cannot be walked twice in a day.
     */
    @Test
    fun charlieSecondWalkAsSeparateDogPlaces() = runBlocking {
        val alfa = dog("alfa", "Alfa", 8f, 48.8145, 2.2360)
        val bravo = dog("bravo", "Bravo", 24f, 48.8120, 2.2300)
        val charlie = dog("charlie", "Charlie", 25f, 48.7970, 2.2600)
        val charlie2 = dog("charlie2", "Charlie#2", 25f, 48.7970, 2.2600)
        val yankee = dog("yankee", "Yankee", 9f, 48.8100, 2.2400)
        val delta = dog("delta", "Delta", 12f, 48.8160, 2.2450)
        val echo = dog("echo", "Echo", 24f, 48.8180, 2.2280)

        val walks = listOf(
            PlannedWalk(alfa, rule("alfa1", "alfa", "09:30", "16:00", 120)),
            PlannedWalk(bravo, rule("bravo1", "bravo", "09:30", "16:00", 60)),
            PlannedWalk(charlie, rule("charlieA", "charlie", "10:00", "13:00", 45)),
            PlannedWalk(charlie2, rule("charlieB", "charlie2", "15:00", "18:00", 45)),
            PlannedWalk(yankee, rule("yankee1", "yankee", "11:00", "15:00", 60)),
            PlannedWalk(delta, rule("delta1", "delta", "11:00", "14:00", 60)),
            PlannedWalk(echo, rule("echo1", "echo", "11:30", "15:30", 60)),
        )

        val planner = DayPlanner(
            routingProvider = FakeRouting(), home = home, capacityKg = 70f,
            stopBufferSeconds = 0, cyclingSpeedKmh = 15f, incompatibilities = emptySet(),
        )
        val route = planner.plan(LocalDate.of(2026, 6, 22), walks)
        val summary = buildString {
            appendLine()
            route.events.forEach { e -> appendLine("  ${fmt(e.timeSeconds)}  ${describe(e)}") }
            appendLine("CONFLICTS (${route.conflicts.size}): " + route.conflicts.joinToString { "${it.dog.name}=${it.reason}" })
        }
        println(summary)
        assertTrue("conflicts:$summary", route.conflicts.isEmpty())
    }

    /**
     * A 120-min walk should be satisfied by riding along two separate
     * 60-min group walks (split), not by a dedicated 120-min walk. Bo
     * (09:00-10:30) and Cy (11:00-12:30) force two distinct walks; Apple
     * (120 min, wide window) is placed last and should tag along both.
     */
    @Test
    fun longWalkSplitsAcrossTwoGroupWalks() = runBlocking {
        val apple = dog("apple", "Apple", 5f, 48.8140, 2.2360)
        val bo = dog("bo", "Bo", 5f, 48.8120, 2.2340)
        val cy = dog("cy", "Cy", 5f, 48.8150, 2.2370)

        val walks = listOf(
            PlannedWalk(apple, rule("apple1", "apple", "08:00", "14:00", 120)),
            PlannedWalk(bo, rule("bo1", "bo", "09:00", "10:30", 60)),
            PlannedWalk(cy, rule("cy1", "cy", "11:00", "12:30", 60)),
        )
        val planner = DayPlanner(
            routingProvider = FakeRouting(), home = home, capacityKg = 70f,
            stopBufferSeconds = 0, cyclingSpeedKmh = 15f, incompatibilities = emptySet(),
        )
        val route = planner.plan(LocalDate.of(2026, 6, 22), walks)
        val summary = buildString {
            appendLine()
            route.events.forEach { e -> appendLine("  ${fmt(e.timeSeconds)}  ${describe(e)}") }
            appendLine("CONFLICTS (${route.conflicts.size}): " + route.conflicts.joinToString { "${it.dog.name}=${it.reason}" })
        }
        println(summary)
        assertTrue("conflicts:$summary", route.conflicts.isEmpty())
        val appleWalks = route.events.filterIsInstance<RouteEvent.Walk>()
            .filter { w -> w.dogs.any { it.id == "apple" } }
        assertTrue(
            "Apple should ride along 2 walks (split), got ${appleWalks.size}:$summary",
            appleWalks.size == 2,
        )
    }

    private fun describe(e: RouteEvent): String = when (e) {
        is RouteEvent.HomeStart -> "HomeStart"
        is RouteEvent.HomeEnd -> "HomeEnd"
        is RouteEvent.Pickup -> "Pickup ${e.dog.name}"
        is RouteEvent.Dropoff -> "Dropoff ${e.dog.name}"
        is RouteEvent.Walk -> "Walk[${e.dogs.joinToString { it.name }}] ${e.durationSeconds / 60}min"
    }

    private fun fmt(sec: Int) = "%02d:%02d".format(sec / 3600, (sec % 3600) / 60)
}
