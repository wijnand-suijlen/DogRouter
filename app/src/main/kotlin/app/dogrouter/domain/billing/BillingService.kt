package app.dogrouter.domain.billing

import app.dogrouter.data.db.BillableServiceDao
import app.dogrouter.data.db.CommittedDayDao
import app.dogrouter.data.entity.BillableService
import app.dogrouter.data.entity.CommittedDay
import app.dogrouter.domain.dayplan.DayRoute
import app.dogrouter.domain.dayplan.RouteEvent
import app.dogrouter.domain.dayplan.SavedPlanCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.UUID

/**
 * Turns a finished day plan into billable services on the owners' running
 * accounts. Committing a day is one-shot: the per-walk amounts are frozen and
 * the date is recorded as committed (so it can't be billed twice, and it shows
 * in the committed-days list).
 */
class BillingService(
    private val billableServiceDao: BillableServiceDao,
    private val committedDayDao: CommittedDayDao,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    /** Whether [date]'s plan has already been committed to billing. */
    fun observeIsCommitted(date: LocalDate): Flow<Boolean> =
        committedDayDao.observeForDate(date).map { it != null }

    /**
     * Commit [route] (the shown plan for [date]) to billing. Returns false if
     * the day was already committed (no-op); true after a successful commit.
     */
    suspend fun commitDay(date: LocalDate, route: DayRoute): Boolean {
        if (committedDayDao.getForDate(date) != null) return false
        val services = buildBillableServices(date, route.events, now())
        billableServiceDao.insertAll(services)
        committedDayDao.upsert(
            CommittedDay(
                date = date,
                committedAt = now(),
                serviceCount = services.size,
                totalCents = services.sumOf { it.amountCents },
                planJson = SavedPlanCodec.encode(route),
            ),
        )
        return true
    }
}

/**
 * Pure mapping from a day's events to billable services (one per walked dog).
 * Each dog's full price comes from its schedule rule (the rule's `priceCents`,
 * or the default tariff for the rule's duration). Within a single walk, dogs of
 * the same owner get the second-dog discount.
 */
fun buildBillableServices(
    date: LocalDate,
    events: List<RouteEvent>,
    now: Long,
): List<BillableService> {
    val ruleByDog = events.filterIsInstance<RouteEvent.Pickup>().associate { it.dog.id to it.rule }
    val out = ArrayList<BillableService>()
    for (walk in events.filterIsInstance<RouteEvent.Walk>()) {
        val durationMinutes = walk.durationSeconds / 60
        val fullByDog = walk.dogs.associate { dog ->
            val rule = ruleByDog[dog.id]
            val full = rule?.let { it.priceCents ?: Pricing.defaultPriceCents(it.durationMinutes) } ?: 0
            dog.id to full
        }
        // Apply the second-dog discount per owner group (within this one walk).
        val billedByDog = HashMap<String, Int>()
        walk.dogs.groupBy { it.ownerId }.forEach { (ownerId, dogs) ->
            val amounts = dogs.map { fullByDog.getValue(it.id) }
            val billed = if (ownerId == null) amounts else Pricing.applySecondDogDiscount(amounts)
            dogs.forEachIndexed { i, dog -> billedByDog[dog.id] = billed[i] }
        }
        for (dog in walk.dogs) {
            out.add(
                BillableService(
                    id = UUID.randomUUID().toString(),
                    ownerId = dog.ownerId,
                    date = date,
                    dogId = dog.id,
                    description = "${dog.name} — $durationMinutes min",
                    amountCents = billedByDog.getValue(dog.id),
                    durationMinutes = durationMinutes,
                    committedAt = now,
                ),
            )
        }
    }
    return out
}
