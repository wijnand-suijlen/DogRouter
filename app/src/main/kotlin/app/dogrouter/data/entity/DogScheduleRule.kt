package app.dogrouter.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * One walk pattern for a dog: on each of the [weekdaysMask] days, walk for
 * [durationMinutes] sometime between [earliestStart] and [latestEnd].
 *
 * A walker who works "Mon–Fri 11:00–13:00, 60 min walk" stores a single
 * rule; an extra rule per dog covers each additional walk pattern
 * (e.g. a separate evening walk).
 *
 * Days are packed into one int: bit 0 = Monday (`DayOfWeek.value` 1),
 * bit 6 = Sunday (`DayOfWeek.value` 7). See [maskFromWeekdays] /
 * [weekdaysFromMask] for conversion.
 */
@Entity(
    tableName = "dog_schedule_rules",
    foreignKeys = [
        ForeignKey(
            entity = Dog::class,
            parentColumns = ["id"],
            childColumns = ["dogId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("dogId")],
)
data class DogScheduleRule(
    @PrimaryKey val id: String,
    val dogId: String,
    val weekdaysMask: Int,
    val earliestStart: LocalTime?,
    val latestEnd: LocalTime?,
    val durationMinutes: Int,
    // When true this rule is one of a dog's mutually-exclusive
    // alternatives: the planner walks exactly one of the dog's alternative
    // rules per day (e.g. "end of morning OR end of afternoon"), not all of
    // them. Non-alternative rules are each walked as usual.
    val isAlternative: Boolean = false,
)

fun maskFromWeekdays(days: Set<DayOfWeek>): Int =
    days.fold(0) { acc, d -> acc or (1 shl (d.value - 1)) }

fun weekdaysFromMask(mask: Int): Set<DayOfWeek> =
    DayOfWeek.entries.filterTo(LinkedHashSet()) { (mask shr (it.value - 1)) and 1 == 1 }
