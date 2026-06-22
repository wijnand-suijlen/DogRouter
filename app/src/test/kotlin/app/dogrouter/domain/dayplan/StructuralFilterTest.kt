package app.dogrouter.domain.dayplan

import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.data.entity.TransportState
import app.dogrouter.domain.dayplan.constraints.CapacityConstraint
import app.dogrouter.domain.dayplan.constraints.GroupSizeConstraint
import app.dogrouter.domain.dayplan.constraints.IncompatibilityConstraint
import app.dogrouter.domain.dayplan.constraints.NoDogLeftBehindConstraint
import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.domain.routing.RouteEstimate
import app.dogrouter.domain.routing.RoutingProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime
import kotlin.random.Random

/**
 * [DayPlanner.structurallyFeasible] is the allocation-free pre-filter that
 * replaces the four time-independent constraint objects in insertion search.
 * It must be EXACTLY equivalent to their combined verdict (else the byte-
 * identical baseline would silently drift), so this cross-checks it against the
 * real constraints on thousands of random event sequences plus targeted edges.
 */
class StructuralFilterTest {

    private val capacityKg = 70f
    private val maxGroup = 4
    private val home = GeoPoint(0.0, 0.0)

    // Incompatible: alfa<->bravo (canonical pair).
    private val pairs = setOf("alfa" to "bravo")

    private class NoRouting : RoutingProvider {
        override suspend fun isReady() = true
        override val lastError: String? = null
        override suspend fun routeGeometry(
            fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double,
        ): List<GeoPoint>? = null
        override suspend fun route(
            fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double,
        ): RouteEstimate? = null
    }

    private val planner = DayPlanner(
        routingProvider = NoRouting(),
        home = home,
        capacityKg = capacityKg,
        stopBufferSeconds = 0,
        cyclingSpeedKmh = 15f,
        incompatibilities = pairs,
        maxGroupSize = maxGroup,
    )

    private val realConstraints = listOf(
        CapacityConstraint(capacityKg),
        GroupSizeConstraint(maxGroup),
        IncompatibilityConstraint(pairs),
        NoDogLeftBehindConstraint(),
    )

    private fun realFeasible(events: List<RouteEvent>): Boolean =
        realConstraints.none { it.violation(events) != null }

    // Six dogs with varied weights so capacity (sums) and group size (>4 distinct)
    // can both trigger; alfa/bravo are the incompatible pair.
    private val dogs = listOf(
        dog("alfa", 30f), dog("bravo", 30f), dog("charlie", 20f),
        dog("delta", 40f), dog("echo", 10f), dog("foxtrot", 25f),
    )

    private fun dog(id: String, weight: Float) = Dog(
        id = id, name = id, breed = null, weightKg = weight, photoUri = null,
        ownerName = "", ownerPhone = null, address = "", latitude = 0.0, longitude = 0.0,
        stopNotes = null, notes = null, inCargoBike = TransportState.Yes,
    )

    private val rule = DogScheduleRule(
        id = "r", dogId = "", weekdaysMask = 0,
        earliestStart = LocalTime.of(9, 0), latestStart = null,
        latestEnd = null, durationMinutes = 30,
    )

    private fun pickup(d: Dog) = RouteEvent.Pickup(0, home, d, rule)
    private fun dropoff(d: Dog) = RouteEvent.Dropoff(0, home, d)
    private fun walk(ds: List<Dog>) = RouteEvent.Walk(0, home, ds, 1800)

    @Test
    fun matchesRealConstraintsOnRandomSequences() {
        val rng = Random(20260622)
        var feasibleSeen = 0
        var infeasibleSeen = 0
        repeat(20_000) {
            val len = rng.nextInt(1, 12)
            val events = buildList {
                repeat(len) {
                    when (rng.nextInt(3)) {
                        0 -> add(pickup(dogs[rng.nextInt(dogs.size)]))
                        1 -> add(dropoff(dogs[rng.nextInt(dogs.size)]))
                        else -> {
                            // a walk with a random non-empty subset of the dogs
                            val subset = dogs.filter { rng.nextBoolean() }.ifEmpty { listOf(dogs[0]) }
                            add(walk(subset))
                        }
                    }
                }
            }
            val expected = realFeasible(events)
            if (expected) feasibleSeen++ else infeasibleSeen++
            assertEquals(
                "disagreement on: " + events.joinToString { describe(it) },
                expected,
                planner.structurallyFeasible(events),
            )
        }
        // Sanity: the random generator actually exercised both verdicts.
        assert(feasibleSeen > 0 && infeasibleSeen > 0)
    }

    @Test
    fun edgeCases() {
        // Same dog aboard twice then dropped once: capacity counts the weight
        // twice (multiset), group/incompat/no-left-behind dedupe by id.
        val a = dogs[0]
        check(listOf(pickup(a), pickup(a), walk(listOf(a)), dropoff(a)))
        // Capacity overflow: delta(40) + alfa(30) + charlie(20) = 90 > 70.
        check(listOf(pickup(dogs[3]), pickup(dogs[0]), pickup(dogs[2])))
        // Group overflow: 5 distinct dogs aboard.
        check(dogs.take(5).map { pickup(it) })
        // Incompatible pair aboard together.
        check(listOf(pickup(dogs[0]), pickup(dogs[1])))
        // Dog left behind: bravo aboard but not in the walk.
        check(listOf(pickup(dogs[0]), pickup(dogs[1]), walk(listOf(dogs[0]))))
        // A clean feasible plan.
        check(listOf(pickup(dogs[2]), walk(listOf(dogs[2])), dropoff(dogs[2])))
    }

    private fun check(events: List<RouteEvent>) {
        assertEquals(
            "disagreement on: " + events.joinToString { describe(it) },
            realFeasible(events),
            planner.structurallyFeasible(events),
        )
    }

    private fun describe(e: RouteEvent): String = when (e) {
        is RouteEvent.Pickup -> "P(${e.dog.id})"
        is RouteEvent.Dropoff -> "D(${e.dog.id})"
        is RouteEvent.Walk -> "W(${e.dogs.joinToString("+") { it.id }})"
        else -> e::class.simpleName ?: "?"
    }
}
