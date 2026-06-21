package app.dogrouter.domain.dayplan

import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.domain.routing.GeoPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * JSON persistence for a [DayRoute]. Events are stored by dog/rule **id**
 * (plus the fields needed to render and re-time), so a saved plan tracks the
 * current dogs and rules: on load it is rehydrated against them. If any
 * referenced dog or rule is gone, rehydration fails (returns null) and the
 * caller re-solves rather than show a corrupt plan.
 *
 * Kept a flat DTO (not a normalized event tree) — simple for now; when billing
 * lands it can be read out or projected into a normalized journal.
 */
object SavedPlanCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(route: DayRoute): String = json.encodeToString(route.toDto())

    /** Decode + rehydrate against current dogs/rules. Null if unparseable or a
     *  referenced dog/rule no longer exists. */
    fun decode(planJson: String, date: LocalDate, dogs: List<Dog>, rules: List<DogScheduleRule>): DayRoute? {
        val dto = runCatching { json.decodeFromString<SavedPlanDto>(planJson) }.getOrNull() ?: return null
        val dogById = dogs.associateBy { it.id }
        val ruleById = rules.associateBy { it.id }
        return dto.toDayRoute(date, dogById, ruleById)
    }
}

@Serializable
private data class SavedPlanDto(
    val events: List<PlanEventDto>,
    val conflicts: List<PlanConflictDto>,
    val totalCyclingSeconds: Int,
    val totalWalkingSeconds: Int,
    val breakUnavailable: Boolean,
)

@Serializable
private data class PlanEventDto(
    val type: String,
    val timeSeconds: Int,
    val lat: Double,
    val lon: Double,
    val arrivedByFoot: Boolean = false,
    val incomingTravelSeconds: Int = 0,
    val returnToBikeSeconds: Int = 0,
    val dogId: String? = null,
    val ruleId: String? = null,
    val dogIds: List<String> = emptyList(),
    val durationSeconds: Int = 0,
    val label: String? = null,
    val startSeconds: Int = 0,
    val earliestStartSeconds: Int = 0,
    val atHome: Boolean = false,
)

@Serializable
private data class PlanConflictDto(val dogId: String, val reason: String)

private fun DayRoute.toDto() = SavedPlanDto(
    events = events.map { it.toDto() },
    conflicts = conflicts.map { PlanConflictDto(it.dog.id, it.reason) },
    totalCyclingSeconds = totalCyclingSeconds,
    totalWalkingSeconds = totalWalkingSeconds,
    breakUnavailable = breakUnavailable,
)

private fun RouteEvent.toDto(): PlanEventDto {
    val base = PlanEventDto(
        type = typeName(),
        timeSeconds = timeSeconds,
        lat = location.latitude,
        lon = location.longitude,
        arrivedByFoot = arrivedByFoot,
        incomingTravelSeconds = incomingTravelSeconds,
        returnToBikeSeconds = returnToBikeSeconds,
    )
    return when (this) {
        is RouteEvent.HomeStart, is RouteEvent.HomeEnd, is RouteEvent.FetchBike -> base
        is RouteEvent.Pickup -> base.copy(dogId = dog.id, ruleId = rule.id)
        is RouteEvent.Dropoff -> base.copy(dogId = dog.id)
        is RouteEvent.Walk -> base.copy(dogIds = dogs.map { it.id }, durationSeconds = durationSeconds)
        is RouteEvent.Break -> base.copy(
            durationSeconds = durationSeconds, earliestStartSeconds = earliestStartSeconds, atHome = atHome,
        )
        is RouteEvent.Appointment -> base.copy(
            durationSeconds = durationSeconds, startSeconds = startSeconds, label = label,
        )
    }
}

private fun RouteEvent.typeName(): String = when (this) {
    is RouteEvent.HomeStart -> "HOME_START"
    is RouteEvent.HomeEnd -> "HOME_END"
    is RouteEvent.Pickup -> "PICKUP"
    is RouteEvent.Dropoff -> "DROPOFF"
    is RouteEvent.Walk -> "WALK"
    is RouteEvent.Break -> "BREAK"
    is RouteEvent.Appointment -> "APPOINTMENT"
    is RouteEvent.FetchBike -> "FETCH_BIKE"
}

private fun SavedPlanDto.toDayRoute(
    date: LocalDate,
    dogById: Map<String, Dog>,
    ruleById: Map<String, DogScheduleRule>,
): DayRoute? {
    val rebuilt = ArrayList<RouteEvent>(events.size)
    for (e in events) {
        rebuilt.add(e.toRouteEvent(dogById, ruleById) ?: return null)
    }
    val conflictsResolved = conflicts.map { c ->
        val dog = dogById[c.dogId] ?: return null
        PlanConflict(dog, c.reason)
    }
    return DayRoute(
        date = date,
        events = rebuilt,
        totalCyclingSeconds = totalCyclingSeconds,
        totalWalkingSeconds = totalWalkingSeconds,
        conflicts = conflictsResolved,
        breakUnavailable = breakUnavailable,
    )
}

private fun PlanEventDto.toRouteEvent(
    dogById: Map<String, Dog>,
    ruleById: Map<String, DogScheduleRule>,
): RouteEvent? {
    val loc = GeoPoint(lat, lon)
    return when (type) {
        "HOME_START" -> RouteEvent.HomeStart(timeSeconds, loc, arrivedByFoot, incomingTravelSeconds, returnToBikeSeconds)
        "HOME_END" -> RouteEvent.HomeEnd(timeSeconds, loc, arrivedByFoot, incomingTravelSeconds, returnToBikeSeconds)
        "FETCH_BIKE" -> RouteEvent.FetchBike(timeSeconds, loc, arrivedByFoot, incomingTravelSeconds, returnToBikeSeconds)
        "PICKUP" -> {
            val dog = dogById[dogId] ?: return null
            val rule = ruleById[ruleId] ?: return null
            RouteEvent.Pickup(timeSeconds, loc, dog, rule, arrivedByFoot, incomingTravelSeconds, returnToBikeSeconds)
        }
        "DROPOFF" -> {
            val dog = dogById[dogId] ?: return null
            RouteEvent.Dropoff(timeSeconds, loc, dog, arrivedByFoot, incomingTravelSeconds, returnToBikeSeconds)
        }
        "WALK" -> {
            val dogs = dogIds.map { dogById[it] ?: return null }
            RouteEvent.Walk(timeSeconds, loc, dogs, durationSeconds, arrivedByFoot, incomingTravelSeconds, returnToBikeSeconds)
        }
        "BREAK" -> RouteEvent.Break(
            timeSeconds, loc, durationSeconds, earliestStartSeconds, atHome,
            arrivedByFoot, incomingTravelSeconds, returnToBikeSeconds,
        )
        "APPOINTMENT" -> RouteEvent.Appointment(
            timeSeconds, loc, durationSeconds, startSeconds, label ?: "",
            arrivedByFoot, incomingTravelSeconds, returnToBikeSeconds,
        )
        else -> null
    }
}
