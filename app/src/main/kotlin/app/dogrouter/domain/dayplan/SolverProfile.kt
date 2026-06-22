package app.dogrouter.domain.dayplan

/**
 * Opt-in solver profiler. Off by default (zero cost: a single boolean check on
 * the hot path); enabled with `-Dsolver.profile=true` so the off-device harness
 * can report WHERE the planning time goes, not just the total.
 *
 * Single-threaded by construction — [DayPlanner.plan] runs one solve at a time
 * on one thread — so plain mutable fields are safe and need no synchronisation.
 * Counters are exact; the nanosecond accumulators carry the usual `nanoTime`
 * sampling noise but are only used for *relative* attribution.
 *
 * The two phase timers ([restartNanos], [lnsNanos]) partition the total solve
 * time. [retimeNanos] and [constraintNanos] are cross-cutting: they are spent
 * inside those phases (retime first, then the constraint check on its result),
 * so they sum to a fraction of the phase total — the remainder is candidate
 * construction, list allocation and bookkeeping.
 */
internal object SolverProfile {
    /** Read once at the start of a solve from `-Dsolver.profile`. */
    var enabled: Boolean = false

    var restartNanos: Long = 0
    var lnsNanos: Long = 0

    var retimeCalls: Long = 0
    var retimeNanos: Long = 0

    var constraintCalls: Long = 0
    var constraintNanos: Long = 0

    // Insertion candidates evaluated per mode (each is one retimeAndCost call):
    // Mode A new triplet, Mode B join an existing walk, Mode C ride-along.
    var modeA: Long = 0
    var modeB: Long = 0
    var modeC: Long = 0

    /**
     * The exact `Solution.score()` of the winning plan from the last
     * [DayPlanner.plan] call — the value the search actually minimised
     * (day length + cyclingWeight×cycling + overWalkWeight×over-walk + any
     * group-oversize penalty). Set on every solve (one cheap assignment), so
     * the sweep can report the true objective instead of a post-presentation
     * proxy that diverges from it. Not gated by [enabled].
     */
    var lastScoreSec: Long = 0

    fun reset() {
        restartNanos = 0
        lnsNanos = 0
        retimeCalls = 0
        retimeNanos = 0
        constraintCalls = 0
        constraintNanos = 0
        modeA = 0
        modeB = 0
        modeC = 0
    }

    /** Time [block], adding the elapsed nanos to [sink] only when enabled. */
    inline fun <T> measure(sink: (Long) -> Unit, block: () -> T): T {
        if (!enabled) return block()
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            sink(System.nanoTime() - start)
        }
    }

    /** A human-readable breakdown table for the harness report. */
    fun dump(): String {
        val total = restartNanos + lnsNanos
        fun ms(n: Long) = "%,d ms".format(n / 1_000_000)
        fun pct(n: Long) = if (total > 0) "%.1f%%".format(100.0 * n / total) else "n/a"
        fun perCall(nanos: Long, calls: Long) =
            if (calls > 0) "%,d ns/call".format(nanos / calls) else "-"
        val sb = StringBuilder()
        sb.appendLine("--- solver profile ---")
        sb.appendLine("phase     restart : ${ms(restartNanos)} (${pct(restartNanos)})")
        sb.appendLine("phase     LNS     : ${ms(lnsNanos)} (${pct(lnsNanos)})")
        sb.appendLine("phase     total   : ${ms(total)}")
        sb.appendLine(
            "retime            : %,d calls, %s (%s of total), %s"
                .format(retimeCalls, ms(retimeNanos), pct(retimeNanos), perCall(retimeNanos, retimeCalls)),
        )
        sb.appendLine(
            "constraints       : %,d calls, %s (%s of total), %s"
                .format(constraintCalls, ms(constraintNanos), pct(constraintNanos), perCall(constraintNanos, constraintCalls)),
        )
        val modeTotal = modeA + modeB + modeC
        sb.appendLine("insert candidates : %,d total".format(modeTotal))
        sb.appendLine("  Mode A (triplet): %,d".format(modeA))
        sb.appendLine("  Mode B (join)   : %,d".format(modeB))
        sb.appendLine("  Mode C (ride)   : %,d".format(modeC))
        sb.append("----------------------")
        return sb.toString()
    }
}
