package app.dogrouter.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.dogrouter.data.entity.Owner
import kotlinx.coroutines.flow.Flow

@Dao
interface OwnerDao {
    @Query("SELECT * FROM owners ORDER BY lastName COLLATE NOCASE ASC, firstName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Owner>>

    /** One-shot snapshot for export. */
    @Query("SELECT * FROM owners")
    suspend fun getAll(): List<Owner>

    @Query("SELECT * FROM owners WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): Owner?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(owner: Owner)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(owners: List<Owner>)

    @Update
    suspend fun update(owner: Owner)

    @Delete
    suspend fun delete(owner: Owner)

    @Query("DELETE FROM owners")
    suspend fun deleteAll()
}
