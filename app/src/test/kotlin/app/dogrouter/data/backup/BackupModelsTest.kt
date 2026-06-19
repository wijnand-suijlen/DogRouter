package app.dogrouter.data.backup

import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogIncompatibility
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.data.entity.TransportState
import app.dogrouter.data.prefs.AppSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class BackupModelsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val dog = Dog(
        id = "alfa", name = "Alfa", breed = "Jack Russell", weightKg = 8f, photoUri = null,
        ownerName = "Owner", ownerPhone = "0600000000", address = "1 Test Street",
        latitude = 48.8145, longitude = 2.2360, stopNotes = "ring bell, wait ~3 min",
        stopAdjustmentMinutes = 2, inCargoBike = TransportState.Yes,
        inBackpack = TransportState.NotTested, allowLongerWalk = false, notes = "puppy",
        createdAt = 1_700_000_000_000L,
    )
    private val rule = DogScheduleRule(
        id = "r1", dogId = "alfa", weekdaysMask = 0b0000001,
        earliestStart = LocalTime.of(9, 30), latestStart = LocalTime.of(13, 0),
        latestEnd = LocalTime.of(16, 0),
        durationMinutes = 120, isAlternative = true,
    )
    private val incompatibility = DogIncompatibility(dogIdA = "delta", dogIdB = "alfa")
    private val settings = AppSettings(
        bikeCapacityKg = 65f, stopBufferMinutes = 5, cyclingSpeedKmh = 14f,
        walkingSpeedKmh = 3.5f, bikeOverheadMinutes = 4,
        homeAddress = "Home", homeLatitude = 48.81, homeLongitude = 2.23,
    )

    @Test
    fun fullBackupSurvivesJsonRoundTrip() {
        val original = BackupFile(
            exportedAt = 123L,
            settings = settings.toDto(),
            dogs = listOf(dog.toDto()),
            scheduleRules = listOf(rule.toDto()),
            incompatibilities = listOf(incompatibility.toDto()),
        )
        val decoded = json.decodeFromString<BackupFile>(json.encodeToString(original))

        assertEquals(dog, decoded.dogs.single().toEntity())
        assertEquals(rule, decoded.scheduleRules.single().toEntity())
        assertEquals(incompatibility, decoded.incompatibilities.single().toEntity())
        assertEquals(settings, decoded.settings.toAppSettings())
        assertEquals(BACKUP_VERSION, decoded.version)
    }

    @Test
    fun nullTimesAndUnknownEnumDegradeGracefully() {
        val ruleNoWindow = rule.copy(earliestStart = null, latestEnd = null)
        assertEquals(ruleNoWindow, ruleNoWindow.toDto().toEntity())

        // A transport state the app no longer knows falls back to NotTested.
        val dto = dog.toDto().copy(inCargoBike = "SomethingNew")
        assertEquals(TransportState.NotTested, dto.toEntity().inCargoBike)
    }
}
