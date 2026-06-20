package app.dogrouter.data.backup

import app.dogrouter.data.entity.Appointment
import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogIncompatibility
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.data.entity.TransportState
import app.dogrouter.data.prefs.AppSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
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
    private val appointment = Appointment(
        id = "appt1", date = LocalDate.of(2026, 6, 23),
        startTime = LocalTime.of(14, 0), endTime = LocalTime.of(15, 0),
        label = "Doctor", address = "Clinic", latitude = 48.82, longitude = 2.25,
    )
    private val settings = AppSettings(
        bikeCapacityKg = 65f, stopBufferMinutes = 5, cyclingSpeedKmh = 14f,
        walkingSpeedKmh = 3.5f, bikeOverheadMinutes = 4, cyclingWeight = 1.5f,
        homeAddress = "Home", homeLatitude = 48.81, homeLongitude = 2.23,
        breakWindowStart = LocalTime.of(12, 30), breakWindowEnd = LocalTime.of(15, 30),
        breakDurationMinutes = 25,
        breakLocations = listOf(app.dogrouter.data.prefs.BreakLocation("Café", 48.80, 2.22)),
        homeLunchMinFreeMinutes = 100,
    )

    @Test
    fun fullBackupSurvivesJsonRoundTrip() {
        val original = BackupFile(
            exportedAt = 123L,
            settings = settings.toDto(),
            dogs = listOf(dog.toDto()),
            scheduleRules = listOf(rule.toDto()),
            incompatibilities = listOf(incompatibility.toDto()),
            appointments = listOf(appointment.toDto()),
        )
        val decoded = json.decodeFromString<BackupFile>(json.encodeToString(original))

        assertEquals(dog, decoded.dogs.single().toEntity())
        assertEquals(rule, decoded.scheduleRules.single().toEntity())
        assertEquals(incompatibility, decoded.incompatibilities.single().toEntity())
        assertEquals(appointment, decoded.appointments.single().toEntity())
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
