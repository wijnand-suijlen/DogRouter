package app.dogrouter.domain.billing

import kotlin.math.ceil

/**
 * The dog-walking tariff, in whole euro cents.
 *
 * Default price of a walk: €4 call-out + €3 per started quarter hour (durations
 * round **up** to the next 15 minutes), capped at €24 — €25 being the full-day
 * dog-sitting rate. So 30 min = €10, 45 = €13, 50 = €16 (rounds to 60), 60 = €16.
 *
 * The default is only a suggestion: each schedule rule (and each ad-hoc walk)
 * carries an editable price. When a day is committed to billing the amounts are
 * frozen onto the services, so later tariff edits never change past accounts.
 */
object Pricing {
    const val CALL_OUT_CENTS = 400
    const val PER_QUARTER_CENTS = 300
    const val MAX_CENTS = 2400

    /** Suggested price (cents) for a walk of [minutes] minutes. */
    fun defaultPriceCents(minutes: Int): Int {
        if (minutes <= 0) return 0
        val quarters = ceil(minutes / 15.0).toInt()
        return minOf(MAX_CENTS, CALL_OUT_CENTS + PER_QUARTER_CENTS * quarters)
    }

    /**
     * Apply the "second dog of the same owner, same walk, half price" rule to a
     * group of per-dog amounts (all dogs of one owner sharing one walk): the
     * most expensive dog stays at full price, every other dog is halved (rounded
     * to the nearest cent). Returns the billed amounts in the input order.
     */
    fun applySecondDogDiscount(amounts: List<Int>): List<Int> {
        if (amounts.size <= 1) return amounts
        val fullIndex = amounts.indices.maxByOrNull { amounts[it] } ?: 0
        return amounts.mapIndexed { i, a -> if (i == fullIndex) a else (a + 1) / 2 }
    }
}
