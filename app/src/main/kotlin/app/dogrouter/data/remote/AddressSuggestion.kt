package app.dogrouter.data.remote

import kotlinx.serialization.Serializable

/**
 * A single autocomplete result the walker can pick from the dropdown.
 * [label] is the formatted address ("12 Rue de la Mairie 92190 Meudon");
 * [latitude] / [longitude] are WGS84 coordinates.
 *
 * Serializable so it can ride back through the navigation result channel
 * (NavBackStackEntry.savedStateHandle) as a JSON string.
 */
@Serializable
data class AddressSuggestion(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val postcode: String? = null,
    val city: String? = null,
)
