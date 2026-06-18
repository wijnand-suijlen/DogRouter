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

    @Query("SELECT * FROM dogs WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): Dog?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(dog: Dog)

    @Update
    suspend fun update(dog: Dog)

    @Delete
    suspend fun delete(dog: Dog)
}
