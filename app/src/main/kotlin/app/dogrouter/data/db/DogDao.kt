package app.dogrouter.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.dogrouter.data.entity.Dog
import kotlinx.coroutines.flow.Flow

@Dao
interface DogDao {
    @Query("SELECT * FROM dogs ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Dog>>

    /** One-shot snapshot for export. */
    @Query("SELECT * FROM dogs")
    suspend fun getAll(): List<Dog>

    @Query("SELECT * FROM dogs WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): Dog?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(dog: Dog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(dogs: List<Dog>)

    @Update
    suspend fun update(dog: Dog)

    @Delete
    suspend fun delete(dog: Dog)

    /** Wipe all dogs; cascades to schedule rules and incompatibilities. */
    @Query("DELETE FROM dogs")
    suspend fun deleteAll()
}
