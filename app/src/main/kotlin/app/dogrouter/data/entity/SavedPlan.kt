package app.dogrouter.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * A pinned day plan for a single [date]: the chosen (possibly hand-edited)
 * plan, kept so it is shown again instead of re-solved, and so it can later
 * feed the billing journal. The plan itself is stored as JSON ([planJson],
 * a `SavedPlanDto`) keyed by dog/rule id; it is rehydrated against the current
 * dogs and rules when loaded. [edited] marks a plan the walker changed by hand
 * (vs a solver result that was merely pinned). One row per date.
 */
@Entity(tableName = "saved_plans")
data class SavedPlan(
    @PrimaryKey val date: LocalDate,
    val planJson: String,
    val edited: Boolean,
    val updatedAt: Long,
)
