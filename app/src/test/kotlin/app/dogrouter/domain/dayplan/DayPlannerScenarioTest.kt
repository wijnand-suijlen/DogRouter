package app.dogrouter.domain.dayplan

import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.domain.planner.PlannedWalk
import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.domain.routing.RouteEstimate
import app.dogrouter.domain.dayplan.constraints.NoDogLeftBehindConstraint
import app.dogrouter.domain.routing.RoutingProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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
            earliestStart = LocalTime.parse(start), latestStart = null,
            latestEnd = LocalTime.parse(end), durationMinutes = minutes,
        )

    /** A start-window rule (start between [earliest] and [latestStart]) with no return deadline. */
    private fun startWindowRule(id: String, dogId: String, earliest: String, latestStart: String, minutes: Int) =
        DogScheduleRule(
            id = id, dogId = dogId, weekdaysMask = 0,
            earliestStart = LocalTime.parse(earliest), latestStart = LocalTime.parse(latestStart),
            latestEnd = null, durationMinutes = minutes,
        )

    /** Each planned walk as its own mandatory option. */
    private fun List<PlannedWalk>.asOptions() = map { WalkOption(listOf(it)) }

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

        val route = planner.plan(LocalDate.of(2026, 6, 22), walks.asOptions())

        val summary = buildString {
            appendLine()
            appendLine("EVENTS:")
            route.events.forEach { e -> appendLine("  ${fmt(e.timeSeconds)}  ${describe(e)}") }
            appendLine("CONFLICTS (${route.conflicts.size}):")
            route.conflicts.forEach { c -> appendLine("  ${c.dog.name}: ${c.reason}") }
        }
        println(summary)
        assertTrue("Expected a feasible plan but got conflicts:$summary", route.conflicts.isEmpty())
        assertNoOneLeftBehind(route, summary)
        assertTrue("A walk has more than 4 dogs:$summary", maxWalkGroup(route) <= 4)
    }

    /** Every dog aboard during a walk must be in that walk. */
    private fun assertNoOneLeftBehind(route: app.dogrouter.domain.dayplan.DayRoute, summary: String) {
        val inBag = mutableSetOf<String>()
        route.events.forEach { e ->
            when (e) {
                is RouteEvent.Pickup -> inBag.add(e.dog.id)
                is RouteEvent.Dropoff -> inBag.remove(e.dog.id)
                is RouteEvent.Walk -> {
                    val walking = e.dogs.mapTo(HashSet()) { it.id }
                    assertTrue(
                        "A dog is left in the bike during the walk at ${fmt(e.timeSeconds)}:$summary",
                        inBag.all { it in walking },
                    )
                }
                else -> Unit
            }
        }
    }

    private fun maxWalkGroup(route: app.dogrouter.domain.dayplan.DayRoute): Int =
        route.events.filterIsInstance<RouteEvent.Walk>().maxOfOrNull { it.dogs.size } ?: 0

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
        val route = planner.plan(LocalDate.of(2026, 6, 22), walks.asOptions())
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
        val route = planner.plan(LocalDate.of(2026, 6, 22), walks.asOptions())
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

    /**
     * The same seed and inputs must produce the identical plan — the
     * invariant the plan cache relies on.
     */
    @Test
    fun sameSeedIsDeterministic() = runBlocking {
        val apple = dog("apple", "Apple", 5f, 48.8140, 2.2360)
        val bo = dog("bo", "Bo", 5f, 48.8120, 2.2340)
        val cy = dog("cy", "Cy", 5f, 48.8150, 2.2370)
        val walks = listOf(
            PlannedWalk(apple, rule("apple1", "apple", "08:00", "14:00", 120)),
            PlannedWalk(bo, rule("bo1", "bo", "09:00", "13:00", 60)),
            PlannedWalk(cy, rule("cy1", "cy", "10:00", "14:00", 60)),
        )
        fun planner() = DayPlanner(
            routingProvider = FakeRouting(), home = home, capacityKg = 70f,
            stopBufferSeconds = 0, cyclingSpeedKmh = 15f, incompatibilities = emptySet(),
        )
        val a = planner().plan(LocalDate.of(2026, 6, 22), walks.asOptions(), seed = 7L)
        val b = planner().plan(LocalDate.of(2026, 6, 22), walks.asOptions(), seed = 7L)
        assertEquals(a.events.map { "${it.timeSeconds}:${describe(it)}" }, b.events.map { "${it.timeSeconds}:${describe(it)}" })
    }

    /**
     * An exclusive choice (Sierra: end of morning OR end of afternoon) must
     * schedule exactly one of its alternatives, never both.
     */
    @Test
    fun exclusiveChoicePlacesExactlyOneAlternative() = runBlocking {
        val sierra = dog("sierra", "Sierra", 8f, 48.8178, 2.2311)
        val option = WalkOption(
            listOf(
                PlannedWalk(sierra, rule("sierraAM", "sierra", "10:00", "12:00", 60)),
                PlannedWalk(sierra, rule("sierraPM", "sierra", "15:00", "17:00", 60)),
            ),
        )
        val planner = DayPlanner(
            routingProvider = FakeRouting(), home = home, capacityKg = 70f,
            stopBufferSeconds = 0, cyclingSpeedKmh = 15f, incompatibilities = emptySet(),
        )
        val route = planner.plan(LocalDate.of(2026, 6, 22), listOf(option))
        val summary = buildString {
            appendLine()
            route.events.forEach { e -> appendLine("  ${fmt(e.timeSeconds)}  ${describe(e)}") }
            appendLine("CONFLICTS (${route.conflicts.size})")
        }
        println(summary)
        assertTrue("conflicts:$summary", route.conflicts.isEmpty())
        val sierraWalks = route.events.filterIsInstance<RouteEvent.Walk>()
            .filter { w -> w.dogs.any { it.id == "sierra" } }
        assertEquals("Sierra must be walked exactly once:$summary", 1, sierraWalks.size)
    }

    /**
     * A latest-start bound is enforced and is independent of any return
     * deadline: Yankee must start between 11:00 and 13:00, with no fixed end.
     */
    @Test
    fun latestStartIsEnforced() = runBlocking {
        val yankee = dog("yankee", "Yankee", 9f, 48.8159, 2.2317)
        val planner = DayPlanner(
            routingProvider = FakeRouting(), home = home, capacityKg = 70f,
            stopBufferSeconds = 0, cyclingSpeedKmh = 15f, incompatibilities = emptySet(),
        )

        val feasible = planner.plan(
            LocalDate.of(2026, 6, 22),
            listOf(WalkOption(listOf(PlannedWalk(yankee, startWindowRule("y1", "yankee", "11:00", "13:00", 60))))),
        )
        assertTrue(feasible.conflicts.isEmpty())
        assertTrue(
            "Yankee's walk must start in 11:00–13:00",
            yankeeFirstWalkSeconds(feasible) in 11 * 3600..13 * 3600,
        )

        // earliest 14:00 but must start by 13:00 → impossible.
        val impossible = planner.plan(
            LocalDate.of(2026, 6, 22),
            listOf(WalkOption(listOf(PlannedWalk(yankee, startWindowRule("y2", "yankee", "14:00", "13:00", 60))))),
        )
        assertEquals(1, impossible.conflicts.size)
    }

    /**
     * Regression: the latest-start bound is on the walk, not the pickup.
     * With a late walk available (the other dog can only walk 15:00–16:00),
     * Yankee must not be picked up in time and then carried to that late walk
     * — his own walk must still start by 13:00.
     */
    @Test
    fun latestStartBoundsTheWalkNotThePickup() = runBlocking {
        val yankee = dog("yankee", "Yankee", 9f, 48.8159, 2.2317)
        val late = dog("late", "Late", 9f, 48.8150, 2.2360)
        val walks = listOf(
            PlannedWalk(yankee, startWindowRule("y1", "yankee", "11:00", "13:00", 60)),
            PlannedWalk(late, rule("late1", "late", "15:00", "16:00", 60)),
        )
        val planner = DayPlanner(
            routingProvider = FakeRouting(), home = home, capacityKg = 70f,
            stopBufferSeconds = 0, cyclingSpeedKmh = 15f, incompatibilities = emptySet(),
        )
        val route = planner.plan(LocalDate.of(2026, 6, 22), walks.asOptions())
        val summary = buildString {
            appendLine()
            route.events.forEach { e -> appendLine("  ${fmt(e.timeSeconds)}  ${describe(e)}") }
            appendLine("CONFLICTS (${route.conflicts.size}): " + route.conflicts.joinToString { "${it.dog.name}=${it.reason}" })
        }
        println(summary)
        assertTrue("Yankee should be placed:$summary", route.conflicts.none { it.dog.id == "yankee" })
        assertTrue(
            "Yankee's walk must start by 13:00:$summary",
            yankeeFirstWalkSeconds(route) <= 13 * 3600,
        )
    }

    private fun yankeeFirstWalkSeconds(route: app.dogrouter.domain.dayplan.DayRoute): Int =
        route.events.filterIsInstance<RouteEvent.Walk>()
            .filter { w -> w.dogs.any { it.id == "yankee" } }
            .minOf { it.timeSeconds }

    @Test
    fun noDogLeftBehindConstraintFlagsAnIdleDog() {
        val a = dog("a", "A", 5f, 48.81, 2.23)
        val b = dog("b", "B", 5f, 48.82, 2.24)
        val loc = GeoPoint(48.81, 2.23)
        val ra = rule("ra", "a", "10:00", "12:00", 60)
        val rb = rule("rb", "b", "10:00", "12:00", 60)
        val constraint = NoDogLeftBehindConstraint()

        // B is aboard while only A is walked -> violation.
        val leftBehind = listOf(
            RouteEvent.Pickup(0, loc, a, ra),
            RouteEvent.Pickup(0, loc, b, rb),
            RouteEvent.Walk(0, loc, listOf(a), 3600),
            RouteEvent.Dropoff(0, loc, a),
            RouteEvent.Dropoff(0, loc, b),
        )
        assertNotNull(constraint.violation(leftBehind))

        // Both walked together -> fine.
        val together = listOf(
            RouteEvent.Pickup(0, loc, a, ra),
            RouteEvent.Pickup(0, loc, b, rb),
            RouteEvent.Walk(0, loc, listOf(a, b), 3600),
            RouteEvent.Dropoff(0, loc, a),
            RouteEvent.Dropoff(0, loc, b),
        )
        assertNull(constraint.violation(together))
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
