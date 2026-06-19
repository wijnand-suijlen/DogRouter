package app.dogrouter.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.dogrouter.data.entity.DogScheduleRule
import kotlinx.coroutines.flow.Flow

@Dao
interface DogScheduleDao {
    @Query("SELECT * FROM dog_schedule_rules")
    fun observeAll(): Flow<List<DogScheduleRule>>

    @Query("SELECT * FROM dog_schedule_rules WHERE dogId = :dogId")
    fun observeForDog(dogId: String): Flow<List<DogScheduleRule>>

    @Query("SELECT * FROM dog_schedule_rules WHERE dogId = :dogId")
    suspend fun findForDog(dogId: String): List<DogScheduleRule>

    /**
     * All rules that apply on the given weekday (matched via the bitmask).
     */
    @Query("SELECT * FROM dog_schedule_rules WHERE (weekdaysMask & :weekdayBit) != 0")
    fun observeForWeekday(weekdayBit: Int): Flow<List<DogScheduleRule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<DogScheduleRule>)

    @Query("DELETE FROM dog_schedule_rules WHERE dogId = :dogId")
    suspend fun deleteForDog(dogId: String)

    @Transaction
    suspend fun replaceForDog(dogId: String, rules: List<DogScheduleRule>) {
        deleteForDog(dogId)
        if (rules.isNotEmpty()) insertAll(rules)
    }
}
