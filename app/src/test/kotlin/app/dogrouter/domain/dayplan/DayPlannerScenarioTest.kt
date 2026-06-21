package app.dogrouter.domain.dayplan

import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.data.entity.TransportState
import app.dogrouter.domain.planner.PlannedWalk
import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.domain.routing.RouteEstimate
import app.dogrouter.domain.dayplan.constraints.NoDogLeftBehindConstraint
import app.dogrouter.domain.routing.RoutingProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Reproduction of the "planner says infeasible but it is feasible" report.
 * Uses a fake routing provider with straight-line distances so the planner
 * logic — not BRouter — is under test.
 *
 * Coordinates are synthetic: the real cluster has been rotated in longitude
 * (an isometry, so every distance is preserved exactly) and bears no relation
 * to any real address. Names are fictional placeholders.
 */
class DayPlannerScenarioTest {

    private val home = GeoPoint(48.8130, 102.2350)

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

    private fun dog(
        id: String,
        name: String,
        weight: Float,
        lat: Double,
        lon: Double,
        inCargoBike: TransportState = TransportState.Yes,
        inBackpack: TransportState = TransportState.NotTested,
    ) = Dog(
        id = id, name = name, breed = null, weightKg = weight, photoUri = null,
        ownerName = "", ownerPhone = null, address = "", latitude = lat, longitude = lon,
        stopNotes = null, notes = null, inCargoBike = inCargoBike, inBackpack = inBackpack,
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
        val alfa = dog("alfa", "Alfa", 8f, 48.8145, 102.2360)
        val bravo = dog("bravo", "Bravo", 24f, 48.8120, 102.2300)
        val charlie = dog("charlie", "Charlie", 25f, 48.7970, 102.2600) // farther out
        val yankee = dog("yankee", "Yankee", 9f, 48.8100, 102.2400)
        val delta = dog("delta", "Delta", 12f, 48.8160, 102.2450)
        val echo = dog("echo", "Echo", 24f, 48.8180, 102.2280)

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
            lnsIterations = 0, // pin: this test exercises construction + constraints, not LNS
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
     * Same scenario, but the second walk of the twice-walked dog is given a
     * distinct dog id (at the same place). If this now places where the real
     * (same-id) second rule did not, the cause is the constraints keying
     * everything by dog.id — one dog cannot be walked twice in a day.
     */
    @Test
    fun secondWalkAsSeparateDogPlaces() = runBlocking {
        val alfa = dog("alfa", "Alfa", 8f, 48.8145, 102.2360)
        val bravo = dog("bravo", "Bravo", 24f, 48.8120, 102.2300)
        val charlie = dog("charlie", "Charlie", 25f, 48.7970, 102.2600)
        val charlie2 = dog("charlie2", "Charlie#2", 25f, 48.7970, 102.2600)
        val yankee = dog("yankee", "Yankee", 9f, 48.8100, 102.2400)
        val delta = dog("delta", "Delta", 12f, 48.8160, 102.2450)
        val echo = dog("echo", "Echo", 24f, 48.8180, 102.2280)

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
            lnsIterations = 0, // pin: these tests exercise construction + constraints, not LNS
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
        val apple = dog("apple", "Apple", 5f, 48.8140, 102.2360)
        val bo = dog("bo", "Bo", 5f, 48.8120, 102.2340)
        val cy = dog("cy", "Cy", 5f, 48.8150, 102.2370)

        val walks = listOf(
            PlannedWalk(apple, rule("apple1", "apple", "08:00", "14:00", 120)),
            PlannedWalk(bo, rule("bo1", "bo", "09:00", "10:30", 60)),
            PlannedWalk(cy, rule("cy1", "cy", "11:00", "12:30", 60)),
        )
        val planner = DayPlanner(
            routingProvider = FakeRouting(), home = home, capacityKg = 70f,
            stopBufferSeconds = 0, cyclingSpeedKmh = 15f, incompatibilities = emptySet(),
            lnsIterations = 0, // pin: these tests exercise construction + constraints, not LNS
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
        val apple = dog("apple", "Apple", 5f, 48.8140, 102.2360)
        val bo = dog("bo", "Bo", 5f, 48.8120, 102.2340)
        val cy = dog("cy", "Cy", 5f, 48.8150, 102.2370)
        val walks = listOf(
            PlannedWalk(apple, rule("apple1", "apple", "08:00", "14:00", 120)),
            PlannedWalk(bo, rule("bo1", "bo", "09:00", "13:00", 60)),
            PlannedWalk(cy, rule("cy1", "cy", "10:00", "14:00", 60)),
        )
        fun planner() = DayPlanner(
            routingProvider = FakeRouting(), home = home, capacityKg = 70f,
            stopBufferSeconds = 0, cyclingSpeedKmh = 15f, incompatibilities = emptySet(),
            lnsIterations = 0, // pin: these tests exercise construction + constraints, not LNS
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
        val sierra = dog("sierra", "Sierra", 8f, 48.8178, 102.2311)
        val option = WalkOption(
            listOf(
                PlannedWalk(sierra, rule("sierraAM", "sierra", "10:00", "12:00", 60)),
                PlannedWalk(sierra, rule("sierraPM", "sierra", "15:00", "17:00", 60)),
            ),
        )
        val planner = DayPlanner(
            routingProvider = FakeRouting(), home = home, capacityKg = 70f,
            stopBufferSeconds = 0, cyclingSpeedKmh = 15f, incompatibilities = emptySet(),
            lnsIterations = 0, // pin: these tests exercise construction + constraints, not LNS
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
        val yankee = dog("yankee", "Yankee", 9f, 48.8159, 102.2317)
        val planner = DayPlanner(
            routingProvider = FakeRouting(), home = home, capacityKg = 70f,
            stopBufferSeconds = 0, cyclingSpeedKmh = 15f, incompatibilities = emptySet(),
            lnsIterations = 0, // pin: these tests exercise construction + constraints, not LNS
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
        val yankee = dog("yankee", "Yankee", 9f, 48.8159, 102.2317)
        val late = dog("late", "Late", 9f, 48.8150, 102.2360)
        val walks = listOf(
            PlannedWalk(yankee, startWindowRule("y1", "yankee", "11:00", "13:00", 60)),
            PlannedWalk(late, rule("late1", "late", "15:00", "16:00", 60)),
        )
        val planner = DayPlanner(
            routingProvider = FakeRouting(), home = home, capacityKg = 70f,
            stopBufferSeconds = 0, cyclingSpeedKmh = 15f, incompatibilities = emptySet(),
            lnsIterations = 0, // pin: these tests exercise construction + constraints, not LNS
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
        val a = dog("a", "A", 5f, 48.81, 102.23)
        val b = dog("b", "B", 5f, 48.82, 102.24)
        val loc = GeoPoint(48.81, 102.23)
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

    /**
     * Two dogs at the same address (Sierra & Tango) must share one bike
     * ride: the second one adds no mount/dismount overhead, because there is
     * no ride to it — the walker just steps over. With a large overhead, the
     * extra dog should add far less than one overhead to the day.
     */
    @Test
    fun sameAddressDogAddsNoBikeOverhead() = runBlocking {
        val overhead = 600 // 10 min
        fun planner() = DayPlanner(
            routingProvider = FakeRouting(), home = home, capacityKg = 70f,
            stopBufferSeconds = 0, cyclingSpeedKmh = 15f, incompatibilities = emptySet(),
            lnsIterations = 0, // pin: these tests exercise construction + constraints, not LNS
            walkingSpeedKmh = 3f, bikeOverheadSeconds = overhead,
        )
        val sierra = dog("sierra", "Sierra", 8f, 48.8179, 102.2311)
        val tango = dog("tango", "Tango", 10f, 48.8179, 102.2311) // same coords

        val solo = planner().plan(
            LocalDate.of(2026, 6, 22),
            listOf(PlannedWalk(sierra, rule("s", "sierra", "09:00", "17:00", 60))).asOptions(),
        )
        val pair = planner().plan(
            LocalDate.of(2026, 6, 22),
            listOf(
                PlannedWalk(sierra, rule("s", "sierra", "09:00", "17:00", 60)),
                PlannedWalk(tango, rule("b", "tango", "09:00", "17:00", 60)),
            ).asOptions(),
        )
        assertTrue(pair.conflicts.isEmpty())
        val added = elapsed(pair) - elapsed(solo)
        assertTrue("Same-address dog added $added s, near a full overhead", added < overhead / 2)
    }

    private fun elapsed(route: app.dogrouter.domain.dayplan.DayRoute): Int =
        if (route.events.size >= 2) route.events.last().timeSeconds - route.events.first().timeSeconds else 0

    /**
     * Two dogs at nearby but different addresses, with a large bike
     * overhead: the planner should walk the group between them on foot
     * (a foot leg) rather than pay the overhead to bike the short hop.
     */
    @Test
    fun nearbyDogsAreWalkedBetweenOnFoot() = runBlocking {
        val a = dog("a", "A", 8f, 48.8145, 102.2360)
        val b = dog("b", "B", 9f, 48.8150, 102.2362) // ~60 m from A
        val planner = DayPlanner(
            routingProvider = FakeRouting(), home = home, capacityKg = 70f,
            stopBufferSeconds = 0, cyclingSpeedKmh = 15f, incompatibilities = emptySet(),
            lnsIterations = 0, // pin: these tests exercise construction + constraints, not LNS
            walkingSpeedKmh = 3f, bikeOverheadSeconds = 600,
        )
        val walks = listOf(
            PlannedWalk(a, rule("a1", "a", "09:00", "17:00", 60)),
            PlannedWalk(b, rule("b1", "b", "09:00", "17:00", 60)),
        )
        val route = planner.plan(LocalDate.of(2026, 6, 22), walks.asOptions())
        val summary = buildString {
            appendLine()
            route.events.forEach { e ->
                val mode = if (e.arrivedByFoot) "foot" else "bike"
                appendLine("  ${fmt(e.timeSeconds)}  [$mode ${e.incomingTravelSeconds}s]  ${describe(e)}")
            }
            appendLine("CONFLICTS (${route.conflicts.size})")
        }
        println(summary)
        assertTrue("Expected a feasible plan:$summary", route.conflicts.isEmpty())
        assertTrue(
            "Expected at least one on-foot leg between the nearby dogs:$summary",
            route.events.any { it.arrivedByFoot && it.incomingTravelSeconds > 0 },
        )
    }

    /**
     * A manual plan edit re-times with recomputeDwells = false so a hand-set
     * walk duration survives (whereas the default recompute would reset it to
     * the dwell the solver computes).
     */
    @Test
    fun retimeKeepsAManualWalkDurationWhenNotRecomputing() = runBlocking {
        val alfa = dog("alfa", "Alfa", 8f, 48.8145, 102.2360)
        val planner = DayPlanner(
            routingProvider = FakeRouting(), home = home, capacityKg = 70f,
            stopBufferSeconds = 0, cyclingSpeedKmh = 15f, incompatibilities = emptySet(),
            lnsIterations = 0,
        )
        val date = LocalDate.of(2026, 6, 22)
        val route = planner.plan(date, listOf(PlannedWalk(alfa, rule("alfa1", "alfa", "09:00", "17:00", 60))).asOptions())

        // Hand-set the walk to 25 minutes.
        val edited = route.events.map { e ->
            if (e is RouteEvent.Walk) e.copy(durationSeconds = 25 * 60) else e
        }

        val kept = planner.retime(date, edited, recomputeDwells = false)!!
        val keptWalk = kept.events.filterIsInstance<RouteEvent.Walk>().single()
        assertEquals("manual duration must survive", 25 * 60, keptWalk.durationSeconds)

        val recomputed = planner.retime(date, edited, recomputeDwells = true)!!
        val recomputedWalk = recomputed.events.filterIsInstance<RouteEvent.Walk>().single()
        assertEquals("default recompute restores the required 60 min", 60 * 60, recomputedWalk.durationSeconds)
    }

    @Test
    fun fitsALunchBreakInAnEmptyMidDayGap() = runBlocking {
        // Alfa in the morning, Bravo in the afternoon — an empty gap between.
        val alfa = dog("alfa", "Alfa", 8f, 48.8145, 102.2360)
        val bravo = dog("bravo", "Bravo", 24f, 48.8120, 102.2300)
        val walks = listOf(
            PlannedWalk(alfa, startWindowRule("alfa1", "alfa", "09:30", "10:00", 60)),
            PlannedWalk(bravo, startWindowRule("bravo1", "bravo", "15:00", "15:30", 60)),
        )
        val spec = BreakSpec(
            locations = listOf(GeoPoint(48.8130, 102.2330)),
            windowStartSeconds = 12 * 3600, windowEndSeconds = 16 * 3600,
            durationSeconds = 30 * 60,
            homeLunchMinFreeSeconds = 0, // home lunch off: exercise the café path
        )
        val planner = DayPlanner(
            routingProvider = FakeRouting(), home = home, capacityKg = 70f,
            stopBufferSeconds = 0, cyclingSpeedKmh = 15f, incompatibilities = emptySet(),
            lnsIterations = 0,
        )

        val route = planner.plan(LocalDate.of(2026, 6, 22), walks.asOptions(), breakSpec = spec)
        val breaks = route.events.filterIsInstance<RouteEvent.Break>()
        assertEquals("exactly one break", 1, breaks.size)
        assertTrue(
            "break starts within the window",
            breaks.first().timeSeconds in spec.windowStartSeconds..spec.windowEndSeconds,
        )
        // No dog is aboard when the break happens.
        var aboard = 0
        for (e in route.events) {
            when (e) {
                is RouteEvent.Pickup -> aboard++
                is RouteEvent.Dropoff -> aboard--
                is RouteEvent.Break -> assertEquals("no dog aboard at the break", 0, aboard)
                else -> Unit
            }
        }
        assertTrue("plan stays feasible", route.conflicts.isEmpty())
    }

    @Test
    fun lunchesAtHomeWhenTheMidDayGapIsLong() = runBlocking {
        // ~4.5 h of free time between the morning and afternoon dogs.
        val alfa = dog("alfa", "Alfa", 8f, 48.8145, 102.2360)
        val bravo = dog("bravo", "Bravo", 24f, 48.8120, 102.2300)
        val walks = listOf(
            PlannedWalk(alfa, startWindowRule("alfa1", "alfa", "09:30", "10:00", 60)),
            PlannedWalk(bravo, startWindowRule("bravo1", "bravo", "15:00", "15:30", 60)),
        )
        val spec = BreakSpec(
            locations = emptyList(), // home-only: no café locations set
            windowStartSeconds = 12 * 3600, windowEndSeconds = 16 * 3600,
            durationSeconds = 30 * 60,
            homeLunchMinFreeSeconds = 120 * 60,
        )
        val planner = DayPlanner(
            routingProvider = FakeRouting(), home = home, capacityKg = 70f,
            stopBufferSeconds = 0, cyclingSpeedKmh = 15f, incompatibilities = emptySet(),
            lnsIterations = 0,
        )

        val route = planner.plan(LocalDate.of(2026, 6, 22), walks.asOptions(), breakSpec = spec)
        val breaks = route.events.filterIsInstance<RouteEvent.Break>()
        assertEquals("one break", 1, breaks.size)
        val lunch = breaks.first()
        assertTrue("lunch is at home", lunch.atHome)
        assertEquals("lunch is at the home location", home, lunch.location)
        assertTrue("home lunch absorbs the idle (longer than the minimum)", lunch.durationSeconds > 30 * 60)
        assertFalse("not flagged unavailable", route.breakUnavailable)
        assertTrue(route.conflicts.isEmpty())
    }

    @Test
    fun schedulesDogsAroundAFixedAppointment() = runBlocking {
        val alfa = dog("alfa", "Alfa", 8f, 48.8145, 102.2360)
        val bravo = dog("bravo", "Bravo", 24f, 48.8120, 102.2300)
        val walks = listOf(
            PlannedWalk(alfa, startWindowRule("alfa1", "alfa", "09:30", "11:00", 60)),
            PlannedWalk(bravo, startWindowRule("bravo1", "bravo", "13:00", "15:00", 60)),
        )
        val appointment = RouteEvent.Appointment(
            timeSeconds = 0, location = GeoPoint(48.8130, 102.2350),
            durationSeconds = 60 * 60, startSeconds = 12 * 3600, label = "Doctor",
        )
        val planner = DayPlanner(
            routingProvider = FakeRouting(), home = home, capacityKg = 70f,
            stopBufferSeconds = 0, cyclingSpeedKmh = 15f, incompatibilities = emptySet(),
            lnsIterations = 0,
        )

        val route = planner.plan(
            LocalDate.of(2026, 6, 22), walks.asOptions(), appointments = listOf(appointment),
        )
        val appts = route.events.filterIsInstance<RouteEvent.Appointment>()
        assertEquals("appointment is in the plan", 1, appts.size)
        assertEquals("appointment at its fixed start", 12 * 3600, appts.first().timeSeconds)
        // No dog aboard during the appointment, and everything still placed.
        var aboard = 0
        for (e in route.events) {
            when (e) {
                is RouteEvent.Pickup -> aboard++
                is RouteEvent.Dropoff -> aboard--
                is RouteEvent.Appointment -> assertEquals("no dog aboard during appointment", 0, aboard)
                else -> Unit
            }
        }
        assertTrue("plan stays feasible", route.conflicts.isEmpty())
    }

    /**
     * For each travelling leg in [route], whether it was on foot and which dog
     * ids were aboard. A dog is aboard on the leg that delivers it (its
     * dropoff) but not on the leg that fetches it (its pickup), mirroring the
     * planner's own bookkeeping.
     */
    private fun legsWithAboard(route: DayRoute): List<Triple<Boolean, Int, Set<String>>> {
        val aboard = LinkedHashSet<String>()
        val out = mutableListOf<Triple<Boolean, Int, Set<String>>>()
        for (e in route.events) {
            if (e.incomingTravelSeconds > 0) out.add(Triple(e.arrivedByFoot, e.incomingTravelSeconds, aboard.toSet()))
            when (e) {
                is RouteEvent.Pickup -> aboard.add(e.dog.id)
                is RouteEvent.Dropoff -> aboard.remove(e.dog.id)
                else -> Unit
            }
        }
        return out
    }

    private fun transportScenario(apple: Dog): app.dogrouter.domain.dayplan.DayRoute {
        val bo = dog("bo", "Bo", 5f, 48.8120, 102.2340)
        val cy = dog("cy", "Cy", 5f, 48.8150, 102.2370)
        val walks = listOf(
            PlannedWalk(apple, rule("apple1", "apple", "08:00", "14:00", 120)),
            PlannedWalk(bo, rule("bo1", "bo", "09:00", "10:30", 60)),
            PlannedWalk(cy, rule("cy1", "cy", "11:00", "12:30", 60)),
        )
        val planner = DayPlanner(
            routingProvider = FakeRouting(), home = home, capacityKg = 70f,
            stopBufferSeconds = 0, cyclingSpeedKmh = 15f, incompatibilities = emptySet(),
            lnsIterations = 0, // pin: construction + constraints, not LNS
        )
        return runBlocking { planner.plan(LocalDate.of(2026, 6, 22), walks.asOptions()) }
    }

    /**
     * A dog whose inCargoBike is No (and that has no backpack) cannot ride in
     * the box, so the planner must carry it on foot — it may never be aboard
     * on a bike leg. The control run (the same dog allowed in the box) proves
     * the scenario really does transport Apple by bike when permitted, so the
     * restricted run is a genuine behaviour change, not a vacuous pass.
     */
    @Test
    fun aDogThatCannotRideIsNeverCarriedOnABikeLeg() = runBlocking {
        val control = transportScenario(
            dog("apple", "Apple", 5f, 48.8140, 102.2360, inCargoBike = TransportState.Yes),
        )
        val restricted = transportScenario(
            dog("apple", "Apple", 5f, 48.8140, 102.2360,
                inCargoBike = TransportState.No, inBackpack = TransportState.No),
        )
        assertTrue(
            "Control: Apple should be carried by bike when allowed in the box",
            legsWithAboard(control).any { (foot, _, dogs) -> !foot && "apple" in dogs },
        )
        assertTrue("Restricted plan stays feasible", restricted.conflicts.isEmpty())
        assertFalse(
            "Apple cannot ride in the box, so no bike leg may carry it",
            legsWithAboard(restricted).any { (foot, _, dogs) -> !foot && "apple" in dogs },
        )
    }

    /**
     * A dog that cannot use the box but fits the backpack (inBackpack = Yes)
     * may ride — it is the lone backpack dog while Bo and Cy travel in the box.
     */
    @Test
    fun aBackpackDogMayRideInTheBackpack() = runBlocking {
        val route = transportScenario(
            dog("apple", "Apple", 5f, 48.8140, 102.2360,
                inCargoBike = TransportState.No, inBackpack = TransportState.Yes),
        )
        assertTrue("Plan stays feasible", route.conflicts.isEmpty())
        assertTrue(
            "Apple should be allowed to ride along in the backpack",
            legsWithAboard(route).any { (foot, _, dogs) -> !foot && "apple" in dogs },
        )
    }

    private fun describe(e: RouteEvent): String = when (e) {
        is RouteEvent.HomeStart -> "HomeStart"
        is RouteEvent.HomeEnd -> "HomeEnd"
        is RouteEvent.Pickup -> "Pickup ${e.dog.name}"
        is RouteEvent.Dropoff -> "Dropoff ${e.dog.name}"
        is RouteEvent.Walk -> "Walk[${e.dogs.joinToString { it.name }}] ${e.durationSeconds / 60}min"
        is RouteEvent.Break -> "Break ${e.durationSeconds / 60}min"
        is RouteEvent.Appointment -> "Appointment[${e.label}] ${e.durationSeconds / 60}min"
        is RouteEvent.FetchBike -> "FetchBike"
    }

    private fun fmt(sec: Int) = "%02d:%02d".format(sec / 3600, (sec % 3600) / 60)
}
