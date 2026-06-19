package app.dogrouter.data.remote

/**
 * A single autocomplete result the walker can pick from the dropdown.
 * [label] is the formatted address ("12 Rue de la Mairie 92190 Meudon");
 * [latitude] / [longitude] are WGS84 coordinates.
 */
data class AddressSuggestion(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val postcode: String?,
    val city: String?,
)
