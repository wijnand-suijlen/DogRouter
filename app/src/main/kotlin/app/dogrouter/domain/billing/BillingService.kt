package app.dogrouter.domain.billing

import app.dogrouter.data.db.BillableServiceDao
import app.dogrouter.data.db.CommittedDayDao
import app.dogrouter.data.entity.BillableService
import app.dogrouter.data.entity.CommittedDay
import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogScheduleRule
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

/** One walk occurrence: a dog's pickup→dropoff span and the walk events it
 *  covers. Billed once, whatever number of (split) walk events it spans. */
private data class WalkSpanBill(
    val dog: Dog,
    val rule: DogScheduleRule,
    val walkIndices: Set<Int>,
    val durationMinutes: Int,
)

/**
 * Pure mapping from a day's events to billable services — **one per dog per
 * pickup→dropoff span** (a dog whose walk is split across two walk events is
 * billed once, not twice). Each dog's full price comes from its schedule rule
 * (the rule's `priceCents`, or the default tariff for the rule's duration).
 *
 * The second-dog discount applies between dogs of the **same owner** who are
 * genuinely walked together (their spans share a walk event): the most expensive
 * keeps full price, the rest are halved.
 */
fun buildBillableServices(
    date: LocalDate,
    events: List<RouteEvent>,
    now: Long,
): List<BillableService> {
    val spans = walkSpanBills(events)
    val full = spans.map { it.rule.priceCents ?: Pricing.defaultPriceCents(it.rule.durationMinutes) }
    val amount = full.toMutableList()

    // Per owner, halve every span that is walked together with a kept-full span
    // (shares a walk event). Spans of the same owner walked at different times
    // each stay full.
    spans.indices.groupBy { spans[it].dog.ownerId }.forEach { (ownerId, idxs) ->
        if (ownerId == null) return@forEach
        val keptFull = mutableListOf<Int>()
        for (idx in idxs.sortedByDescending { full[it] }) {
            val togetherWithFull = keptFull.any { spans[it].walkIndices.intersect(spans[idx].walkIndices).isNotEmpty() }
            if (togetherWithFull) amount[idx] = (full[idx] + 1) / 2 else keptFull.add(idx)
        }
    }

    return spans.mapIndexed { idx, span ->
        BillableService(
            id = UUID.randomUUID().toString(),
            ownerId = span.dog.ownerId,
            date = date,
            dogId = span.dog.id,
            description = "${span.dog.name} — ${span.durationMinutes} min",
            amountCents = amount[idx],
            durationMinutes = span.durationMinutes,
            committedAt = now,
        )
    }
}

/** Pair each dog's pickup→dropoff (FIFO), collecting the walk events in between
 *  and their total walked minutes. */
private fun walkSpanBills(events: List<RouteEvent>): List<WalkSpanBill> {
    val openPickup = HashMap<String, RouteEvent.Pickup>()
    val openWalks = HashMap<String, MutableSet<Int>>()
    val out = ArrayList<WalkSpanBill>()

    fun close(dogId: String) {
        val pickup = openPickup.remove(dogId) ?: return
        val walks = openWalks.remove(dogId) ?: mutableSetOf()
        val minutes = walks.sumOf { (events[it] as RouteEvent.Walk).durationSeconds } / 60
        out.add(WalkSpanBill(pickup.dog, pickup.rule, walks, minutes))
    }

    events.forEachIndexed { i, e ->
        when (e) {
            is RouteEvent.Pickup -> {
                openPickup[e.dog.id] = e
                openWalks[e.dog.id] = mutableSetOf()
            }
            is RouteEvent.Walk -> e.dogs.forEach { openWalks[it.id]?.add(i) }
            is RouteEvent.Dropoff -> close(e.dog.id)
            else -> Unit
        }
    }
    // Any pickup without a dropoff still bills as a span.
    openPickup.keys.toList().forEach { close(it) }
    return out
}
