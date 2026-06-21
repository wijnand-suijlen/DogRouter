package app.dogrouter.domain.dayplan

import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.data.entity.TransportState
import app.dogrouter.domain.planner.PlannedWalk
import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.domain.routing.RouteEstimate
import app.dogrouter.domain.routing.RoutingProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Exercises [PlanVerifier]: produced plans are always feasible, and the
 * verifier actually catches infeasible ones (so a green run is not vacuous).
 */
class PlanVerifierTest {

    private val home = GeoPoint(48.8130, 102.2350)
    private val date = LocalDate.of(2026, 6, 22)
    private val capacityKg = 70f
    private val stopBufferSeconds = 120

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
            return RouteEstimate((r * 2 * atan2(sqrt(a), sqrt(1 - a))).toInt(), 0)
        }
    }

    private fun verify(route: DayRoute, incompatibilities: Set<Pair<String, String>> = emptySet()) =
        PlanVerifier.violations(
            route = route,
            capacityKg = capacityKg,
            stopBufferSeconds = stopBufferSeconds,
            incompatibilities = incompatibilities,
        )

    // ---- the verifier passes a genuinely feasible plan ----------------------

    @Test
    fun aFeasiblePlanHasNoViolations() = runBlocking {
        val apple = dog("apple", "Apple", 5f, 48.8140, 102.2360)
        val bo = dog("bo", "Bo", 5f, 48.8120, 102.2340)
        val cy = dog("cy", "Cy", 5f, 48.8150, 102.2370)
        val walks = listOf(
            PlannedWalk(apple, rule("apple1", "apple", "08:00", "14:00", 120)),
            PlannedWalk(bo, rule("bo1", "bo", "09:00", "10:30", 60)),
            PlannedWalk(cy, rule("cy1", "cy", "11:00", "12:30", 60)),
        )
        val route = planner().plan(date, walks.map { WalkOption(listOf(it)) })
        assertEquals("expected a feasible plan with no violations", emptyList<String>(), verify(route))
    }

    // ---- the verifier catches infeasible plans (non-vacuous) ----------------

    @Test
    fun catchesAnOversizedWalk() {
        val dogs = (1..5).map { dog("d$it", "D$it", 5f, 48.81 + it * 0.001, 102.23) }
        val route = DayRoute(
            date = date,
            events = listOf(
                RouteEvent.HomeStart(28_800, home),
                RouteEvent.Walk(29_400, home, dogs, 1_800, incomingTravelSeconds = 600),
                RouteEvent.HomeEnd(31_800, home, incomingTravelSeconds = 600),
            ),
            totalCyclingSeconds = 0, totalWalkingSeconds = 0, conflicts = emptyList(),
        )
        assertTrue("a 5-dog walk must be flagged: ${verify(route)}", verify(route).any { it.contains("GroupSize") })
    }

    @Test
    fun catchesADogThatCannotRideOnABikeLeg() {
        val loner = dog("x", "Xeno", 5f, 48.820, 102.240, TransportState.No, TransportState.No)
        val route = DayRoute(
            date = date,
            events = listOf(
                RouteEvent.HomeStart(28_800, home),
                // Leg into the pickup carries no dog yet — fine.
                RouteEvent.Pickup(29_100, GeoPoint(48.820, 102.240), loner, loner.rule(), incomingTravelSeconds = 300),
                // Bike leg into the dropoff carries a dog that cannot ride.
                RouteEvent.Dropoff(29_520, GeoPoint(48.825, 102.245), loner, incomingTravelSeconds = 300),
                RouteEvent.HomeEnd(29_940, home, incomingTravelSeconds = 300),
            ),
            totalCyclingSeconds = 0, totalWalkingSeconds = 0, conflicts = emptyList(),
        )
        assertTrue("a non-rideable dog on a bike leg must be flagged: ${verify(route)}",
            verify(route).any { it.contains("Transport") })
    }

    @Test
    fun catchesTimeGoingBackwards() {
        val d = dog("d", "Dee", 5f, 48.814, 102.236)
        val route = DayRoute(
            date = date,
            events = listOf(
                RouteEvent.HomeStart(28_800, home),
                RouteEvent.Pickup(28_700, GeoPoint(48.814, 102.236), d, d.rule(), incomingTravelSeconds = 300),
                RouteEvent.HomeEnd(31_000, home, incomingTravelSeconds = 300),
            ),
            totalCyclingSeconds = 0, totalWalkingSeconds = 0, conflicts = emptyList(),
        )
        assertTrue("a backwards timestamp must be flagged: ${verify(route)}", verify(route).any { it.contains("Time") })
    }

    // ---- property test: the solver never emits an infeasible final plan -----

    @Test
    fun randomisedPlansAreAlwaysFeasible() = runBlocking {
        for (seed in 1L..40L) {
            val rng = Random(seed)
            val n = rng.nextInt(3, 8)
            val dogs = (0 until n).map { randomDog(rng, it) }
            val walks = dogs.map { d ->
                val dur = listOf(30, 45, 60, 75).random(rng)
                val earliest = if (rng.nextDouble() < 0.3) randomTime(rng, 8, 11) else "08:00"
                PlannedWalk(d, rule("${d.id}r", d.id, earliest, "19:00", dur))
            }
            // Occasionally make one incompatible pair.
            val pairs = if (n >= 2 && rng.nextDouble() < 0.4) {
                setOf(canonical(dogs[0].id, dogs[1].id))
            } else {
                emptySet()
            }
            val route = planner(pairs).plan(date, walks.map { WalkOption(listOf(it)) }, seed)
            val violations = verify(route, pairs)
            assertTrue(
                "seed $seed produced an infeasible plan:\n" + violations.joinToString("\n"),
                violations.isEmpty(),
            )
        }
    }

    // ---- helpers ------------------------------------------------------------

    private fun planner(pairs: Set<Pair<String, String>> = emptySet()) = DayPlanner(
        routingProvider = FakeRouting(), home = home, capacityKg = capacityKg,
        stopBufferSeconds = stopBufferSeconds, cyclingSpeedKmh = 15f, incompatibilities = pairs,
        walkingSpeedKmh = 4f, bikeOverheadSeconds = 180, restarts = 20, lnsIterations = 50,
    )

    private fun dog(
        id: String, name: String, weight: Float, lat: Double, lon: Double,
        inCargoBike: TransportState = TransportState.Yes,
        inBackpack: TransportState = TransportState.NotTested,
    ) = Dog(
        id = id, name = name, breed = null, weightKg = weight, photoUri = null,
        ownerName = "", ownerPhone = null, address = "", latitude = lat, longitude = lon,
        stopNotes = null, notes = null, inCargoBike = inCargoBike, inBackpack = inBackpack,
    )

    private fun rule(id: String, dogId: String, earliest: String, latestEnd: String, minutes: Int) =
        DogScheduleRule(
            id = id, dogId = dogId, weekdaysMask = 0,
            earliestStart = LocalTime.parse(earliest), latestStart = null,
            latestEnd = LocalTime.parse(latestEnd), durationMinutes = minutes,
        )

    /** A throwaway rule for hand-built routes where the rule content is irrelevant. */
    private fun Dog.rule() = rule("${id}r", id, "08:00", "20:00", 60)

    private fun randomDog(rng: Random, i: Int): Dog {
        // Coordinates within ~1.3 km of home so on-foot fallbacks stay feasible.
        val lat = home.latitude + (rng.nextDouble() - 0.5) * 0.024
        val lon = home.longitude + (rng.nextDouble() - 0.5) * 0.024
        val (cargo, pack) = when {
            rng.nextDouble() < 0.8 -> TransportState.Yes to TransportState.NotTested
            rng.nextDouble() < 0.5 -> TransportState.No to TransportState.Yes
            else -> TransportState.No to TransportState.No
        }
        return dog("d$i", "Dog$i", rng.nextInt(4, 26).toFloat(), lat, lon, cargo, pack)
    }

    private fun randomTime(rng: Random, fromHour: Int, toHour: Int): String {
        val minute = rng.nextInt(fromHour * 60, toHour * 60)
        return "%02d:%02d".format(minute / 60, minute % 60)
    }

    private fun canonical(a: String, b: String): Pair<String, String> = if (a < b) a to b else b to a
}
