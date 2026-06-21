package app.dogrouter.domain.dayplan

import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.data.entity.TransportState
import app.dogrouter.domain.dayplan.constraints.CapacityConstraint
import app.dogrouter.domain.dayplan.constraints.GroupSizeConstraint
import app.dogrouter.domain.dayplan.constraints.IncompatibilityConstraint
import app.dogrouter.domain.dayplan.constraints.NoDogLeftBehindConstraint
import app.dogrouter.domain.dayplan.constraints.TimeWindowConstraint
import app.dogrouter.domain.dayplan.constraints.WalkDurationConstraint
import app.dogrouter.domain.planner.PlannedWalk
import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.domain.routing.RouteEstimate
import app.dogrouter.domain.routing.RoutingProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The LNS remove operator (`DayPlanner.remove`). Builds a feasible plan over
 * a clustered instance (bike overhead on, so the planner groups dogs and
 * walks some legs on foot), then removes each placed option in turn and
 * checks the invariants the LNS destroy step relies on.
 */
class RemoveOperatorTest {

    // Synthetic coordinates: the cluster is rotated in longitude (an isometry,
    // so distances are preserved exactly) and is not a real location.
    private val home = GeoPoint(48.8130, 102.2350)

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

    private fun dog(id: String, name: String, lat: Double, lon: Double) = Dog(
        id = id, name = name, breed = null, weightKg = 12f, photoUri = null,
        ownerName = "", ownerPhone = null, address = "", latitude = lat, longitude = lon,
        stopNotes = null, notes = null, inCargoBike = TransportState.Yes,
    )

    private fun option(dog: Dog, minutes: Int) = WalkOption(
        listOf(
            PlannedWalk(
                dog,
                DogScheduleRule(
                    id = "${dog.id}-r", dogId = dog.id, weekdaysMask = 0,
                    earliestStart = LocalTime.of(9, 0), latestStart = null,
                    latestEnd = LocalTime.of(18, 0), durationMinutes = minutes,
                ),
            ),
        ),
    )

    private fun constraints() = listOf(
        CapacityConstraint(70f),
        TimeWindowConstraint(),
        WalkDurationConstraint(),
        IncompatibilityConstraint(emptySet()),
        NoDogLeftBehindConstraint(),
        GroupSizeConstraint(4),
    )

    @Test
    fun removeDropsTheSpanAndKeepsThePlanFeasible() = runBlocking {
        val planner = DayPlanner(
            routingProvider = FakeRouting(),
            home = home,
            capacityKg = 70f,
            stopBufferSeconds = 0,
            cyclingSpeedKmh = 15f,
            incompatibilities = emptySet(),
            walkingSpeedKmh = 3f,
            bikeOverheadSeconds = 600,
        )
        // Five clustered dogs with wide windows — easily co-walkable, so the
        // build produces shared walks and on-foot legs to exercise removal.
        val options = listOf(
            option(dog("alfa", "Alfa", 48.8145, 102.2360), 60),
            option(dog("bravo", "Bravo", 48.8120, 102.2300), 60),
            option(dog("yankee", "Yankee", 48.8100, 102.2400), 45),
            option(dog("delta", "Delta", 48.8160, 102.2450), 60),
            option(dog("echo", "Echo", 48.8180, 102.2280), 30),
        )
        val points = (options.map { GeoPoint(it.dog.latitude!!, it.dog.longitude!!) } + home).toSet()
        val matrix = DistanceMatrix.build(points, FakeRouting())
        val constraints = constraints()

        // A single greedy pass (no multi-start) need not place everything;
        // remove is exercised on whatever it did place.
        val solution = planner.buildOnce(options, matrix, constraints)
        assertTrue("expected several placed options to remove", solution.placed.size >= 2)

        for (target in solution.placed) {
            val reduced = planner.remove(solution, target, matrix)
                ?: throw AssertionError("remove returned null for placed ${target.dog.name}")

            // One fewer option placed, and it is the target that left.
            assertEquals(solution.placed.size - 1, reduced.placed.size)
            assertFalse(reduced.placed.any { it === target })

            // The target's pickup/dropoff occurrence is gone from the events.
            val ruleIds = target.alternatives.mapTo(HashSet()) { it.rule.id }
            val pickupStillThere = reduced.events.any { e ->
                e is RouteEvent.Pickup && e.dog.id == target.dog.id && e.rule.id in ruleIds
            }
            val dropoffStillThere = reduced.events.any { e ->
                e is RouteEvent.Dropoff && e.dog.id == target.dog.id
            }
            assertFalse("removed dog's pickup should be gone", pickupStillThere)
            assertFalse("removed dog's dropoff should be gone", dropoffStillThere)

            // The remaining plan is still feasible (every other dog still walks
            // at least its required minutes, capacity holds, etc.).
            for (c in constraints) {
                assertNull(
                    "constraint ${c::class.simpleName} violated after removing ${target.dog.name}",
                    c.violation(reduced.events),
                )
            }
        }
    }
}
