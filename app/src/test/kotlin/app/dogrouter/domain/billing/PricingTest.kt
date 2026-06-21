package app.dogrouter.domain.billing

import org.junit.Assert.assertEquals
import org.junit.Test

class PricingTest {

    @Test
    fun defaultPriceFollowsTheTariffExamples() {
        assertEquals(1000, Pricing.defaultPriceCents(30)) // €10
        assertEquals(1300, Pricing.defaultPriceCents(45)) // €13
        assertEquals(1600, Pricing.defaultPriceCents(60)) // €16
        assertEquals(1600, Pricing.defaultPriceCents(50)) // rounds up to 60 → €16
        assertEquals(700, Pricing.defaultPriceCents(15)) // €7
    }

    @Test
    fun defaultPriceIsCappedAt24Euro() {
        assertEquals(2400, Pricing.defaultPriceCents(120))
        assertEquals(2400, Pricing.defaultPriceCents(300))
    }

    @Test
    fun defaultPriceIsZeroForNonPositive() {
        assertEquals(0, Pricing.defaultPriceCents(0))
        assertEquals(0, Pricing.defaultPriceCents(-5))
    }

    @Test
    fun secondDogOfSameOwnerIsHalfPrice() {
        // Two dogs at €16 each: most expensive full, the other halved.
        assertEquals(listOf(1600, 800), Pricing.applySecondDogDiscount(listOf(1600, 1600)))
    }

    @Test
    fun discountKeepsTheMostExpensiveDogFull() {
        // €10 + €16: the €16 stays full, the €10 is halved (→ €5).
        assertEquals(listOf(500, 1600), Pricing.applySecondDogDiscount(listOf(1000, 1600)))
    }

    @Test
    fun discountIsANoOpForASingleDog() {
        assertEquals(listOf(1600), Pricing.applySecondDogDiscount(listOf(1600)))
    }

    @Test
    fun thirdDogIsAlsoHalved() {
        assertEquals(listOf(1600, 800, 650), Pricing.applySecondDogDiscount(listOf(1600, 1600, 1300)))
    }
}
