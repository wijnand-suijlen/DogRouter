package app.dogrouter.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.dogrouter.data.entity.CommittedDay
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface CommittedDayDao {
    @Query("SELECT * FROM committed_days ORDER BY date DESC")
    fun observeAll(): Flow<List<CommittedDay>>

    /** One-shot snapshot for export. */
    @Query("SELECT * FROM committed_days")
    suspend fun getAll(): List<CommittedDay>

    @Query("SELECT * FROM committed_days WHERE date = :date")
    fun observeForDate(date: LocalDate): Flow<CommittedDay?>

    @Query("SELECT * FROM committed_days WHERE date = :date LIMIT 1")
    suspend fun getForDate(date: LocalDate): CommittedDay?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(day: CommittedDay)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(days: List<CommittedDay>)

    @Query("DELETE FROM committed_days")
    suspend fun deleteAll()
}
