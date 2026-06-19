package app.dogrouter.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Thin client over the French national address base (BAN), a free,
 * key-less public API at api-adresse.data.gouv.fr.
 *
 * Results are biased toward the south-western Paris suburbs (Meudon) by
 * passing the walker's home coordinates as the proximity hint; this is a
 * soft bias, not a hard filter, so other Île-de-France addresses still
 * surface when typed clearly.
 */
class BanApi(
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun search(query: String, limit: Int = 5): List<AddressSuggestion> {
        if (query.length < 3) return emptyList()
        return withContext(Dispatchers.IO) {
            val url = "https://api-adresse.data.gouv.fr/search/".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("autocomplete", "1")
                .addQueryParameter("lat", BIAS_LAT.toString())
                .addQueryParameter("lon", BIAS_LON.toString())
                .build()

            val request = Request.Builder().url(url).get().build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    val body = response.body?.string() ?: return@use emptyList()
                    val parsed = json.decodeFromString<BanResponse>(body)
                    parsed.features.mapNotNull { it.toSuggestion() }
                }
            }.getOrDefault(emptyList())
        }
    }

    /**
     * Reverse-geocode a coordinate to the closest known address.
     * Returns null if BAN has nothing nearby or the call fails.
     */
    suspend fun reverse(latitude: Double, longitude: Double): AddressSuggestion? {
        return withContext(Dispatchers.IO) {
            val url = "https://api-adresse.data.gouv.fr/reverse/".toHttpUrl().newBuilder()
                .addQueryParameter("lat", latitude.toString())
                .addQueryParameter("lon", longitude.toString())
                .addQueryParameter("limit", "1")
                .build()

            val request = Request.Builder().url(url).get().build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    val parsed = json.decodeFromString<BanResponse>(body)
                    parsed.features.firstNotNullOfOrNull { it.toSuggestion() }
                }
            }.getOrNull()
        }
    }

    private companion object {
        // Meudon centre — proximity bias for autocomplete results.
        const val BIAS_LAT = 48.81
        const val BIAS_LON = 2.24
    }
}

private fun BanFeature.toSuggestion(): AddressSuggestion? {
    val coords = geometry.coordinates
    if (coords.size < 2) return null
    return AddressSuggestion(
        label = properties.label,
        longitude = coords[0],
        latitude = coords[1],
        postcode = properties.postcode,
        city = properties.city,
    )
}

@Serializable
private data class BanResponse(
    val features: List<BanFeature> = emptyList(),
)

@Serializable
private data class BanFeature(
    val geometry: BanGeometry,
    val properties: BanProperties,
)

@Serializable
private data class BanGeometry(
    val coordinates: List<Double> = emptyList(),
)

@Serializable
private data class BanProperties(
    val label: String,
    val postcode: String? = null,
    val city: String? = null,
)
