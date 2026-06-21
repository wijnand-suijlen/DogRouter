package app.dogrouter.domain.billing

import kotlin.math.abs
import kotlin.math.roundToInt

/** Euro-cent formatting/parsing shared by the billing UI. */

/** Editable text for a price field: whole euros when round, else two decimals
 *  (e.g. 1600 → "16", 1650 → "16.50"). */
fun euroText(cents: Int): String {
    val sign = if (cents < 0) "-" else ""
    val a = abs(cents)
    return if (a % 100 == 0) "$sign${a / 100}" else "$sign${a / 100}.%02d".format(a % 100)
}

/** Parse a euro amount ("16", "16.50", "16,50") to cents, or null if blank/invalid. */
fun parseEuroToCents(text: String): Int? {
    val t = text.trim().replace(',', '.')
    if (t.isBlank()) return null
    val euros = t.toDoubleOrNull() ?: return null
    return (euros * 100).roundToInt()
}

/** Display string with the euro sign and French decimal comma (e.g. "16,00 €"). */
fun formatEuros(cents: Int): String {
    val sign = if (cents < 0) "-" else ""
    val a = abs(cents)
    return "$sign%d,%02d €".format(a / 100, a % 100)
}
