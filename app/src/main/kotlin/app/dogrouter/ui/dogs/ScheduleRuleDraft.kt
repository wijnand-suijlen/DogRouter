package app.dogrouter.ui.dogs

import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.data.entity.maskFromWeekdays
import app.dogrouter.data.entity.weekdaysFromMask
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

/**
 * Editor-side representation of one walk rule. The `id` is stable across
 * edits in the same session and doubles as the persisted primary key.
 */
data class ScheduleRuleDraft(
    val id: String = UUID.randomUUID().toString(),
    val weekdays: Set<DayOfWeek> = emptySet(),
    val earliestStart: LocalTime? = null,
    val latestEnd: LocalTime? = null,
    val durationMinutes: Int = 60,
)

fun DogScheduleRule.toDraft(): ScheduleRuleDraft = ScheduleRuleDraft(
    id = id,
    weekdays = weekdaysFromMask(weekdaysMask),
    earliestStart = earliestStart,
    latestEnd = latestEnd,
    durationMinutes = durationMinutes,
)

fun ScheduleRuleDraft.toEntity(dogId: String): DogScheduleRule = DogScheduleRule(
    id = id,
    dogId = dogId,
    weekdaysMask = maskFromWeekdays(weekdays),
    earliestStart = earliestStart,
    latestEnd = latestEnd,
    durationMinutes = durationMinutes,
)
