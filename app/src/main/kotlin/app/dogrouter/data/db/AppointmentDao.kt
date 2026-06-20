package app.dogrouter.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.dogrouter.data.entity.Appointment
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface AppointmentDao {
    @Query("SELECT * FROM appointments ORDER BY date ASC, startTime ASC")
    fun observeAll(): Flow<List<Appointment>>

    /** One-shot snapshot for export. */
    @Query("SELECT * FROM appointments")
    suspend fun getAll(): List<Appointment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(appointment: Appointment)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(appointments: List<Appointment>)

    @Delete
    suspend fun delete(appointment: Appointment)

    @Query("DELETE FROM appointments")
    suspend fun deleteAll()
}
