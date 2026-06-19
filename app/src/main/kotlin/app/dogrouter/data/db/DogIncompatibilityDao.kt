package app.dogrouter.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.dogrouter.data.entity.DogIncompatibility
import kotlinx.coroutines.flow.Flow

@Dao
interface DogIncompatibilityDao {
    @Query("SELECT * FROM dog_incompatibilities")
    fun observeAll(): Flow<List<DogIncompatibility>>

    /** One-shot snapshot for export. */
    @Query("SELECT * FROM dog_incompatibilities")
    suspend fun getAll(): List<DogIncompatibility>

    @Query(
        "SELECT * FROM dog_incompatibilities " +
            "WHERE dogIdA = :dogId OR dogIdB = :dogId",
    )
    suspend fun findForDog(dogId: String): List<DogIncompatibility>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(pairs: List<DogIncompatibility>)

    @Query("DELETE FROM dog_incompatibilities WHERE dogIdA = :dogId OR dogIdB = :dogId")
    suspend fun deleteForDog(dogId: String)

    /**
     * Replace the set of incompatibility pairs that include [dogId] with
     * the provided list. Pairs are always stored with the smaller dogId
     * as dogIdA to keep them canonical.
     */
    @Transaction
    suspend fun replaceForDog(dogId: String, partnerIds: List<String>) {
        deleteForDog(dogId)
        if (partnerIds.isEmpty()) return
        val pairs = partnerIds.map { partner ->
            if (dogId < partner) DogIncompatibility(dogId, partner)
            else DogIncompatibility(partner, dogId)
        }
        insertAll(pairs)
    }
}
