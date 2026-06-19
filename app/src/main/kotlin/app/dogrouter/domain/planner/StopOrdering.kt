package app.dogrouter.domain.planner

/**
 * Picks the best traversal order for a set of stops anchored by an
 * optional fixed start and end point (the walker's home).
 *
 * Strategy:
 *  - Up to [BRUTE_FORCE_LIMIT] stops: enumerate every permutation and
 *    pick the one with the lowest total duration. Optimal, instant on
 *    this scale (7! = 5040 evaluations, each a handful of int adds).
 *  - More than that: nearest-neighbour from each possible starting
 *    stop, pick the best run. Suboptimal but still produces sensible
 *    routes in O(N³).
 *
 * Stops without coordinates cannot be reasoned about by the algorithm.
 * Callers are expected to filter them out before calling and append
 * them in source order afterwards.
 *
 * The [cost] function returns travel time in seconds between two
 * indices in the [stops] list, with index -1 reserved for [home].
 * Returning [Int.MAX_VALUE] indicates "no route available"; the
 * algorithm treats that as infinitely expensive and so avoids it where
 * possible, but still emits an order even if all edges are missing
 * (the result is just the input order).
 */
object StopOrdering {
    private const val BRUTE_FORCE_LIMIT = 7

    fun order(
        homePresent: Boolean,
        stops: List<Int>,
        cost: (from: Int, to: Int) -> Int,
    ): List<Int> {
        if (stops.size <= 1) return stops
        return if (stops.size <= BRUTE_FORCE_LIMIT) {
            bruteForce(homePresent, stops, cost)
        } else {
            nearestNeighbourMultiStart(homePresent, stops, cost)
        }
    }

    private fun bruteForce(
        homePresent: Boolean,
        stops: List<Int>,
        cost: (Int, Int) -> Int,
    ): List<Int> {
        var bestOrder: List<Int> = stops
        var bestTotal = Long.MAX_VALUE
        permutations(stops) { permutation ->
            val snapshot = permutation.toList()
            val total = totalCost(homePresent, snapshot, cost)
            if (total < bestTotal) {
                bestTotal = total
                bestOrder = snapshot
            }
        }
        return bestOrder
    }

    private fun nearestNeighbourMultiStart(
        homePresent: Boolean,
        stops: List<Int>,
        cost: (Int, Int) -> Int,
    ): List<Int> {
        // When home is fixed the start node is always -1; otherwise try
        // every possible first stop and keep the best resulting tour.
        val startCandidates = if (homePresent) listOf(-1) else stops
        var bestOrder: List<Int> = stops
        var bestTotal = Long.MAX_VALUE
        for (start in startCandidates) {
            val ordered = nearestNeighbourFrom(start, stops, cost)
            val total = totalCost(homePresent, ordered, cost)
            if (total < bestTotal) {
                bestTotal = total
                bestOrder = ordered
            }
        }
        return bestOrder
    }

    private fun nearestNeighbourFrom(
        start: Int,
        stops: List<Int>,
        cost: (Int, Int) -> Int,
    ): List<Int> {
        val remaining = stops.toMutableList()
        val ordered = mutableListOf<Int>()
        var current = start
        if (start in remaining) {
            remaining.remove(start)
            ordered.add(start)
        }
        while (remaining.isNotEmpty()) {
            val next = remaining.minBy { cost(current, it) }
            ordered.add(next)
            remaining.remove(next)
            current = next
        }
        return ordered
    }

    private fun totalCost(
        homePresent: Boolean,
        order: List<Int>,
        cost: (Int, Int) -> Int,
    ): Long {
        if (order.isEmpty()) return 0L
        var total = 0L
        if (homePresent) total += cost(-1, order.first()).toLongSafe()
        for (i in 0 until order.size - 1) {
            total += cost(order[i], order[i + 1]).toLongSafe()
        }
        if (homePresent) total += cost(order.last(), -1).toLongSafe()
        return total
    }

    private fun Int.toLongSafe(): Long = if (this == Int.MAX_VALUE) Long.MAX_VALUE / 2 else toLong()

    /**
     * Heap's algorithm — emits every permutation in-place via [visit].
     * Allocates one swap buffer rather than a fresh list per permutation.
     */
    private inline fun permutations(input: List<Int>, visit: (IntArray) -> Unit) {
        val arr = input.toIntArray()
        heapsPermute(arr, arr.size, visit)
    }

    private inline fun heapsPermute(arr: IntArray, n: Int, visit: (IntArray) -> Unit) {
        val c = IntArray(n)
        visit(arr)
        var i = 0
        while (i < n) {
            if (c[i] < i) {
                val swapIndex = if (i % 2 == 0) 0 else c[i]
                val tmp = arr[swapIndex]
                arr[swapIndex] = arr[i]
                arr[i] = tmp
                visit(arr)
                c[i] += 1
                i = 0
            } else {
                c[i] = 0
                i += 1
            }
        }
    }
}
