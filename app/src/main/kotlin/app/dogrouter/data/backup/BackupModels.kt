package app.dogrouter.data.backup

import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogIncompatibility
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.data.entity.TransportState
import app.dogrouter.data.prefs.AppSettings
import kotlinx.serialization.Serializable
import java.time.LocalTime

/** Current on-disk format version. Bump when the shape changes incompatibly. */
const val BACKUP_VERSION = 1

/**
 * Self-contained snapshot of everything the walker enters: dogs (with
 * owner, address, quirks, transport, schedule), incompatibilities, and the
 * planning settings. Serialized to JSON for moving data between phones.
 *
 * Stored as plain DTOs rather than the Room entities so the file format is
 * decoupled from the database schema (and so `LocalTime`/enums become
 * stable strings).
 */
@Serializable
data class BackupFile(
    val version: Int = BACKUP_VERSION,
    val exportedAt: Long,
    val settings: SettingsDto,
    val dogs: List<DogDto>,
    val scheduleRules: List<ScheduleRuleDto>,
    val incompatibilities: List<IncompatibilityDto>,
)

@Serializable
data class DogDto(
    val id: String,
    val name: String,
    val breed: String? = null,
    val weightKg: Float,
    val photoUri: String? = null,
    val ownerName: String,
    val ownerPhone: String? = null,
    val address: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val stopNotes: String? = null,
    val stopAdjustmentMinutes: Int = 0,
    val inCargoBike: String = TransportState.NotTested.name,
    val inBackpack: String = TransportState.NotTested.name,
    val allowLongerWalk: Boolean = true,
    val notes: String? = null,
    val createdAt: Long,
)

@Serializable
data class ScheduleRuleDto(
    val id: String,
    val dogId: String,
    val weekdaysMask: Int,
    val earliestStart: String? = null,
    val latestEnd: String? = null,
    val durationMinutes: Int,
)

@Serializable
data class IncompatibilityDto(
    val dogIdA: String,
    val dogIdB: String,
)

@Serializable
data class SettingsDto(
    val bikeCapacityKg: Float,
    val stopBufferMinutes: Int,
    val cyclingSpeedKmh: Float,
    val homeAddress: String,
    val homeLatitude: Double? = null,
    val homeLongitude: Double? = null,
)

// ---- mapping: entity/domain -> DTO ----

fun Dog.toDto() = DogDto(
    id = id, name = name, breed = breed, weightKg = weightKg, photoUri = photoUri,
    ownerName = ownerName, ownerPhone = ownerPhone, address = address,
    latitude = latitude, longitude = longitude, stopNotes = stopNotes,
    stopAdjustmentMinutes = stopAdjustmentMinutes,
    inCargoBike = inCargoBike.name, inBackpack = inBackpack.name,
    allowLongerWalk = allowLongerWalk, notes = notes, createdAt = createdAt,
)

fun DogScheduleRule.toDto() = ScheduleRuleDto(
    id = id, dogId = dogId, weekdaysMask = weekdaysMask,
    earliestStart = earliestStart?.toString(), latestEnd = latestEnd?.toString(),
    durationMinutes = durationMinutes,
)

fun DogIncompatibility.toDto() = IncompatibilityDto(dogIdA, dogIdB)

fun AppSettings.toDto() = SettingsDto(
    bikeCapacityKg = bikeCapacityKg, stopBufferMinutes = stopBufferMinutes,
    cyclingSpeedKmh = cyclingSpeedKmh, homeAddress = homeAddress,
    homeLatitude = homeLatitude, homeLongitude = homeLongitude,
)

// ---- mapping: DTO -> entity/domain ----

fun DogDto.toEntity() = Dog(
    id = id, name = name, breed = breed, weightKg = weightKg, photoUri = photoUri,
    ownerName = ownerName, ownerPhone = ownerPhone, address = address,
    latitude = latitude, longitude = longitude, stopNotes = stopNotes,
    stopAdjustmentMinutes = stopAdjustmentMinutes,
    inCargoBike = transportStateOf(inCargoBike), inBackpack = transportStateOf(inBackpack),
    allowLongerWalk = allowLongerWalk, notes = notes, createdAt = createdAt,
)

fun ScheduleRuleDto.toEntity() = DogScheduleRule(
    id = id, dogId = dogId, weekdaysMask = weekdaysMask,
    earliestStart = earliestStart?.let { LocalTime.parse(it) },
    latestEnd = latestEnd?.let { LocalTime.parse(it) },
    durationMinutes = durationMinutes,
)

fun IncompatibilityDto.toEntity() = DogIncompatibility(dogIdA, dogIdB)

fun SettingsDto.toAppSettings() = AppSettings(
    bikeCapacityKg = bikeCapacityKg, stopBufferMinutes = stopBufferMinutes,
    cyclingSpeedKmh = cyclingSpeedKmh, homeAddress = homeAddress,
    homeLatitude = homeLatitude, homeLongitude = homeLongitude,
)

private fun transportStateOf(name: String): TransportState =
    runCatching { TransportState.valueOf(name) }.getOrDefault(TransportState.NotTested)
