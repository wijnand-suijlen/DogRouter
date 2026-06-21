package app.dogrouter.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.dogrouter.data.entity.SavedPlan
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface SavedPlanDao {
    @Query("SELECT * FROM saved_plans WHERE date = :date")
    fun observeForDate(date: LocalDate): Flow<SavedPlan?>

    @Query("SELECT * FROM saved_plans WHERE date = :date")
    suspend fun getForDate(date: LocalDate): SavedPlan?

    /** One-shot snapshot for export. */
    @Query("SELECT * FROM saved_plans")
    suspend fun getAll(): List<SavedPlan>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plan: SavedPlan)

    @Query("DELETE FROM saved_plans WHERE date = :date")
    suspend fun deleteForDate(date: LocalDate)
}
