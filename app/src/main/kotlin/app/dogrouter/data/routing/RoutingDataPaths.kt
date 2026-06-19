package app.dogrouter.data.routing

import android.content.Context
import java.io.File

/**
 * Single source of truth for where on-device routing data lives. The
 * layout mirrors BRouter's own expectations (a profiles2 folder and a
 * segments folder) so we can later swap to the standalone app's storage
 * with minimal churn.
 *
 * Files land under the app's private [Context.getFilesDir] so backup
 * rules and uninstall both behave predictably.
 */
class RoutingDataPaths(private val context: Context) {

    val rootDir: File get() = File(context.filesDir, "brouter")
    val profilesDir: File get() = File(rootDir, "profiles2")
    val segmentsDir: File get() = File(rootDir, "segments")

    /** The single Île-de-France tile (5° × 5° square, SW corner 0°E/45°N). */
    val ileDeFranceSegment: File get() = File(segmentsDir, SEGMENT_FILE_NAME)
    val ileDeFranceSegmentUrl: String = SEGMENT_DOWNLOAD_URL

    /** Custom cargo-bike profile installed from the app's assets on first run. */
    val cargoProfile: File get() = File(profilesDir, PROFILE_FILE_NAME)
    val cargoProfileName: String = PROFILE_NAME

    /**
     * OSM tag-to-bit-mask definitions; BRouter loads this from the same
     * directory as the .brf profile when parsing the profile.
     */
    val lookupsFile: File get() = File(profilesDir, LOOKUPS_FILE_NAME)

    fun ensureDirectories() {
        profilesDir.mkdirs()
        segmentsDir.mkdirs()
    }

    fun isReady(): Boolean =
        cargoProfile.exists() && lookupsFile.exists() && ileDeFranceSegment.exists()

    companion object {
        const val SEGMENT_FILE_NAME = "E0_N45.rd5"
        const val SEGMENT_DOWNLOAD_URL =
            "https://brouter.de/brouter/segments4/E0_N45.rd5"
        const val PROFILE_FILE_NAME = "bakfiets.brf"
        const val PROFILE_NAME = "bakfiets"
        const val LOOKUPS_FILE_NAME = "lookups.dat"

        /** Approximate compressed size shown in the UI before downloading. */
        const val SEGMENT_SIZE_BYTES_APPROX = 125L * 1024L * 1024L
    }
}
