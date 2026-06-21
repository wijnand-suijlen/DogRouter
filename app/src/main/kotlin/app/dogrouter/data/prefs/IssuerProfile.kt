package app.dogrouter.data.prefs

import kotlinx.serialization.Serializable

/**
 * The walker's own business identity, printed on invoices. Real PII (name,
 * SIRET, address) — entered by the user, stored only locally, never hardcoded.
 *
 * Defaults to the mandatory French mentions for a micro-entrepreneur in BNC
 * who is not liable for VAT (franchise en base); the walker can edit them.
 */
@Serializable
data class IssuerProfile(
    // Your name with the mandatory "EI" / "Entrepreneur Individuel" mention,
    // e.g. "Jane Doe EI". Printed at the top of the invoice.
    val name: String = "",
    val address: String = "",
    val siret: String = "",
    val email: String = "",
    val phone: String = "",
    val legalMentions: String = DEFAULT_LEGAL_MENTIONS,
    // Prefix prepended to the sequential invoice number, e.g. "2026-".
    val invoiceNumberPrefix: String = "",
) {
    companion object {
        const val DEFAULT_LEGAL_MENTIONS: String =
            "TVA non applicable, art. 293 B du CGI\n" +
                "Dispensé d'immatriculation au registre du commerce et des sociétés (RCS) " +
                "et au répertoire des métiers (RM)"

        val DEFAULT = IssuerProfile()
    }
}
