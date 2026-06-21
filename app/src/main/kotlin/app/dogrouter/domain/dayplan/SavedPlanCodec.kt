package app.dogrouter.domain.dayplan

import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.domain.routing.GeoPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime

/**
 * JSON persistence for a [DayRoute]. Dogs are stored by **id** and rehydrated
 * against the current dogs (so name/address edits show through); if a
 * referenced dog is gone, rehydration fails (returns null) and the caller
 * re-solves rather than show a corrupt plan. Each pickup's schedule **rule is
 * stored inline** — a pinned plan is a snapshot, and this also lets a plan hold
 * an ad-hoc walk whose rule is not in the database (a hand-added walk, a pinned
 * start time).
 *
 * Kept a flat DTO (not a normalized event tree) — simple for now; when billing
 * lands it can be read out or projected into a normalized journal.
 */
object SavedPlanCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(route: DayRoute): String = json.encodeToString(route.toDto())

    /** Decode + rehydrate against current dogs. Null if unparseable or a
     *  referenced dog no longer exists. */
    fun decode(planJson: String, date: LocalDate, dogs: List<Dog>): DayRoute? {
        val dto = runCatching { json.decodeFromString<SavedPlanDto>(planJson) }.getOrNull() ?: return null
        return dto.toDayRoute(date, dogs.associateBy { it.id })
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
    val legMode: String = "AUTO",
    val dogId: String? = null,
    val rule: RuleDto? = null,
    val dogIds: List<String> = emptyList(),
    val durationSeconds: Int = 0,
    val label: String? = null,
    val startSeconds: Int = 0,
    val earliestStartSeconds: Int = 0,
    val atHome: Boolean = false,
)

@Serializable
private data class RuleDto(
    val id: String,
    val dogId: String,
    val weekdaysMask: Int,
    val earliestStart: String? = null,
    val latestStart: String? = null,
    val latestEnd: String? = null,
    val durationMinutes: Int,
    val isAlternative: Boolean = false,
)

private fun DogScheduleRule.toDto() = RuleDto(
    id = id, dogId = dogId, weekdaysMask = weekdaysMask,
    earliestStart = earliestStart?.toString(), latestStart = latestStart?.toString(),
    latestEnd = latestEnd?.toString(), durationMinutes = durationMinutes, isAlternative = isAlternative,
)

private fun RuleDto.toRule() = DogScheduleRule(
    id = id, dogId = dogId, weekdaysMask = weekdaysMask,
    earliestStart = earliestStart?.let(LocalTime::parse), latestStart = latestStart?.let(LocalTime::parse),
    latestEnd = latestEnd?.let(LocalTime::parse), durationMinutes = durationMinutes, isAlternative = isAlternative,
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
        legMode = legMode.name,
    )
    return when (this) {
        is RouteEvent.HomeStart, is RouteEvent.HomeEnd, is RouteEvent.FetchBike -> base
        is RouteEvent.Pickup -> base.copy(dogId = dog.id, rule = rule.toDto())
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
): DayRoute? {
    val rebuilt = ArrayList<RouteEvent>(events.size)
    for (e in events) {
        rebuilt.add(e.toRouteEvent(dogById) ?: return null)
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
): RouteEvent? {
    val loc = GeoPoint(lat, lon)
    val mode = runCatching { LegMode.valueOf(legMode) }.getOrDefault(LegMode.AUTO)
    return when (type) {
        "HOME_START" -> RouteEvent.HomeStart(timeSeconds, loc, arrivedByFoot, incomingTravelSeconds, returnToBikeSeconds, mode)
        "HOME_END" -> RouteEvent.HomeEnd(timeSeconds, loc, arrivedByFoot, incomingTravelSeconds, returnToBikeSeconds, mode)
        "FETCH_BIKE" -> RouteEvent.FetchBike(timeSeconds, loc, arrivedByFoot, incomingTravelSeconds, returnToBikeSeconds, mode)
        "PICKUP" -> {
            val dog = dogById[dogId] ?: return null
            val rule = rule?.toRule() ?: return null
            RouteEvent.Pickup(timeSeconds, loc, dog, rule, arrivedByFoot, incomingTravelSeconds, returnToBikeSeconds, mode)
        }
        "DROPOFF" -> {
            val dog = dogById[dogId] ?: return null
            RouteEvent.Dropoff(timeSeconds, loc, dog, arrivedByFoot, incomingTravelSeconds, returnToBikeSeconds, mode)
        }
        "WALK" -> {
            val dogs = dogIds.map { dogById[it] ?: return null }
            RouteEvent.Walk(timeSeconds, loc, dogs, durationSeconds, arrivedByFoot, incomingTravelSeconds, returnToBikeSeconds, mode)
        }
        "BREAK" -> RouteEvent.Break(
            timeSeconds, loc, durationSeconds, earliestStartSeconds, atHome,
            arrivedByFoot, incomingTravelSeconds, returnToBikeSeconds, mode,
        )
        "APPOINTMENT" -> RouteEvent.Appointment(
            timeSeconds, loc, durationSeconds, startSeconds, label ?: "",
            arrivedByFoot, incomingTravelSeconds, returnToBikeSeconds, mode,
        )
        else -> null
    }
}
