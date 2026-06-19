package app.dogrouter.data.routing

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

sealed interface SegmentDownloadState {
    data object Idle : SegmentDownloadState
    data object Installed : SegmentDownloadState
    data class Downloading(val bytesRead: Long, val totalBytes: Long?) : SegmentDownloadState
    data class Failed(val message: String) : SegmentDownloadState
}

/**
 * Handles two install-time concerns:
 *   1. Copying the bundled `bakfiets.brf` profile from APK assets into
 *      app-private storage so BRouter can read it as a file.
 *   2. Downloading the Île-de-France segment file from brouter.de on
 *      first use, with progress reporting and atomic file replacement
 *      to avoid leaving a half-written .rd5 behind on cancel/crash.
 */
class RoutingDataInstaller(
    private val context: Context,
    private val paths: RoutingDataPaths,
    private val httpClient: OkHttpClient,
) {
    private val _downloadState = MutableStateFlow<SegmentDownloadState>(
        if (paths.ileDeFranceSegment.exists()) SegmentDownloadState.Installed
        else SegmentDownloadState.Idle,
    )
    val downloadState: StateFlow<SegmentDownloadState> = _downloadState.asStateFlow()

    private val _profileEvents = Channel<Unit>(Channel.CONFLATED)
    val profileInstalls: Flow<Unit> = _profileEvents.receiveAsFlow()

    /**
     * Make sure the cargo-bike profile and its companion lookups.dat exist
     * on disk; copies them from APK assets if missing. Cheap — invoke on
     * every app start.
     */
    suspend fun installProfileIfMissing() = withContext(Dispatchers.IO) {
        paths.ensureDirectories()
        var installed = false
        installed = copyAssetIfMissing("profiles2/${RoutingDataPaths.PROFILE_FILE_NAME}", paths.cargoProfile) || installed
        installed = copyAssetIfMissing("profiles2/${RoutingDataPaths.LOOKUPS_FILE_NAME}", paths.lookupsFile) || installed
        if (installed) _profileEvents.trySend(Unit)
    }

    private fun copyAssetIfMissing(assetPath: String, target: File): Boolean {
        if (target.exists()) return false
        context.assets.open(assetPath).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return true
    }

    /**
     * Download the Île-de-France routing segment. Emits Downloading,
     * Installed or Failed on [downloadState]. No-op if already installed.
     */
    suspend fun downloadSegment() = withContext(Dispatchers.IO) {
        if (paths.ileDeFranceSegment.exists()) {
            _downloadState.value = SegmentDownloadState.Installed
            return@withContext
        }
        paths.ensureDirectories()

        val temp = File(paths.segmentsDir, "${RoutingDataPaths.SEGMENT_FILE_NAME}.part")
        temp.delete()
        val request = Request.Builder().url(paths.ileDeFranceSegmentUrl).get().build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    _downloadState.value = SegmentDownloadState.Failed("HTTP ${response.code}")
                    return@withContext
                }
                val total = response.body?.contentLength()?.takeIf { it > 0 }
                _downloadState.value = SegmentDownloadState.Downloading(0L, total)

                val source = response.body?.byteStream()
                    ?: run {
                        _downloadState.value = SegmentDownloadState.Failed("empty response body")
                        return@withContext
                    }

                temp.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var totalRead = 0L
                    var lastReported = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        totalRead += read
                        // Throttle state-flow churn to once per ~256 KB so the
                        // UI does not get hammered by tiny updates.
                        if (totalRead - lastReported >= 256L * 1024L) {
                            _downloadState.value = SegmentDownloadState.Downloading(totalRead, total)
                            lastReported = totalRead
                        }
                    }
                }
            }
            if (!temp.renameTo(paths.ileDeFranceSegment)) {
                temp.delete()
                _downloadState.value = SegmentDownloadState.Failed("could not finalise file")
                return@withContext
            }
            _downloadState.value = SegmentDownloadState.Installed
        } catch (io: IOException) {
            temp.delete()
            _downloadState.value = SegmentDownloadState.Failed(io.message ?: "network error")
        }
    }

    /** Remove the cached segment file so the walker can re-download cleanly. */
    suspend fun deleteSegment() = withContext(Dispatchers.IO) {
        paths.ileDeFranceSegment.delete()
        _downloadState.value = SegmentDownloadState.Idle
    }
}
