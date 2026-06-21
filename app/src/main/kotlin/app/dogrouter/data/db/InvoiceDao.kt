package app.dogrouter.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.dogrouter.data.entity.Invoice
import kotlinx.coroutines.flow.Flow

@Dao
interface InvoiceDao {
    @Query("SELECT * FROM invoices ORDER BY date DESC")
    fun observeAll(): Flow<List<Invoice>>

    /** One-shot snapshot for export. */
    @Query("SELECT * FROM invoices")
    suspend fun getAll(): List<Invoice>

    @Query("SELECT * FROM invoices WHERE ownerId = :ownerId ORDER BY date DESC")
    fun observeForOwner(ownerId: String): Flow<List<Invoice>>

    @Query("SELECT * FROM invoices WHERE number = :number LIMIT 1")
    suspend fun findByNumber(number: String): Invoice?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(invoice: Invoice)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(invoices: List<Invoice>)

    @Update
    suspend fun update(invoice: Invoice)

    @Query("DELETE FROM invoices")
    suspend fun deleteAll()
}
