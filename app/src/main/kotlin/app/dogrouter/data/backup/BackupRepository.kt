package app.dogrouter.data.backup

import androidx.room.withTransaction
import app.dogrouter.data.db.AppDatabase
import app.dogrouter.data.db.AppointmentDao
import app.dogrouter.data.db.DogDao
import app.dogrouter.data.db.DogIncompatibilityDao
import app.dogrouter.data.db.DogScheduleDao
import app.dogrouter.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Counts in an exported or imported backup, for user-facing feedback. */
data class BackupSummary(val dogs: Int, val scheduleRules: Int, val incompatibilities: Int)

/**
 * Exports all walker-entered data to a JSON string and imports it back,
 * replacing the current data. Import runs in one DB transaction so a bad
 * file leaves the database untouched (settings are written after, since
 * they live in DataStore, not Room).
 */
class BackupRepository(
    private val db: AppDatabase,
    private val dogDao: DogDao,
    private val scheduleDao: DogScheduleDao,
    private val incompatibilityDao: DogIncompatibilityDao,
    private val appointmentDao: AppointmentDao,
    private val settingsRepo: SettingsRepository,
    private val json: Json,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun exportToJson(): String {
        val file = BackupFile(
            exportedAt = now(),
            settings = settingsRepo.settings.first().toDto(),
            dogs = dogDao.getAll().map { it.toDto() },
            scheduleRules = scheduleDao.getAll().map { it.toDto() },
            incompatibilities = incompatibilityDao.getAll().map { it.toDto() },
            appointments = appointmentDao.getAll().map { it.toDto() },
        )
        return json.encodeToString(file)
    }

    /**
     * Parse [text] and replace all current data with it. Throws
     * [BackupException] on a malformed or unsupported file (callers should
     * surface its message); the database is only touched once parsing and
     * mapping have succeeded.
     */
    suspend fun importFromJson(text: String): BackupSummary {
        val file = try {
            json.decodeFromString<BackupFile>(text)
        } catch (e: Exception) {
            throw BackupException("This file is not a valid DogRouter backup.", e)
        }
        if (file.version > BACKUP_VERSION) {
            throw BackupException("This backup was made by a newer app version (v${file.version}).")
        }

        // Map outside the transaction so a bad value (e.g. a malformed time)
        // fails before anything is deleted.
        val dogs = try {
            file.dogs.map { it.toEntity() }
        } catch (e: Exception) {
            throw BackupException("A dog entry in the backup is malformed.", e)
        }
        val rules = try {
            file.scheduleRules.map { it.toEntity() }
        } catch (e: Exception) {
            throw BackupException("A schedule rule in the backup is malformed.", e)
        }
        val incompatibilities = file.incompatibilities.map { it.toEntity() }
        val appointments = try {
            file.appointments.map { it.toEntity() }
        } catch (e: Exception) {
            throw BackupException("An appointment in the backup is malformed.", e)
        }

        db.withTransaction {
            dogDao.deleteAll() // cascades to rules and incompatibilities
            dogDao.insertAll(dogs)
            scheduleDao.insertAll(rules)
            incompatibilityDao.insertAll(incompatibilities)
            appointmentDao.deleteAll()
            appointmentDao.insertAll(appointments)
        }
        settingsRepo.replaceAll(file.settings.toAppSettings())

        return BackupSummary(dogs.size, rules.size, incompatibilities.size)
    }
}

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)
