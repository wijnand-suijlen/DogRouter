package app.dogrouter.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.dogrouter.data.entity.BillableService
import kotlinx.coroutines.flow.Flow

@Dao
interface BillableServiceDao {
    @Query("SELECT * FROM billable_services ORDER BY date ASC")
    fun observeAll(): Flow<List<BillableService>>

    /** One-shot snapshot for export. */
    @Query("SELECT * FROM billable_services")
    suspend fun getAll(): List<BillableService>

    @Query("SELECT * FROM billable_services WHERE ownerId = :ownerId ORDER BY date ASC")
    fun observeForOwner(ownerId: String): Flow<List<BillableService>>

    @Query("SELECT * FROM billable_services WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): BillableService?

    @Query("SELECT * FROM billable_services WHERE invoiceNumber = :number")
    suspend fun findByInvoiceNumber(number: String): List<BillableService>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(service: BillableService)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(services: List<BillableService>)

    @Update
    suspend fun update(service: BillableService)

    @Update
    suspend fun updateAll(services: List<BillableService>)

    @Delete
    suspend fun delete(service: BillableService)

    @Query("DELETE FROM billable_services")
    suspend fun deleteAll()
}
