package app.dogrouter.domain.dayplan

import app.dogrouter.data.backup.BackupFile
import app.dogrouter.data.backup.toAppSettings
import app.dogrouter.data.backup.toEntity
import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogIncompatibility
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.data.prefs.AppSettings
import app.dogrouter.domain.planner.PlannedWalk
import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.domain.routing.RouteEstimate
import app.dogrouter.domain.routing.RoutingProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.system.measureNanoTime
import java.util.Locale

/**
 * Off-device solver harness — the laptop runner described in docs/STATUS.md.
 *
 * Loads the real data from `dogrouter-backup.json` (repo root, private),
 * runs [DayPlanner] for each weekday, and prints BOTH the full plan timeline
 * (so we can read it and judge whether it makes sense) AND the quality
 * metrics we want to optimise. Distances are straight-line haversine for now
 * (a pluggable [RoutingProvider]) — instant, good enough to iterate on solver
 * logic before wiring in real BRouter distances.
 *
 * Run it from the terminal:
 *
 *   JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
 *     ./gradlew :app:testDebugUnitTest --tests "*SolverHarness" -PsolverOutput
 *
 * The full report is printed to stdout (with -PsolverOutput) and always
 * written to app/build/solver-report.txt (gitignored — keeps the private
 * plan off git). Optional knobs (system properties):
 *   -Dsolver.day=MONDAY      restrict to one weekday (default: all with walks)
 *   -Dsolver.restarts=200    multi-start count (default: planner's 60)
 *   -Dsolver.seed=0          solver seed
 */
class SolverHarness {

    @Test
    fun runSolverOnRealData() = runBlocking {
        val backup = loadBackup()
        val settings = backup.settings.toAppSettings()
        val dogs = backup.dogs.map { it.toEntity() }
        val rules = backup.scheduleRules.map { it.toEntity() }
        val incompatibilities = backup.incompatibilities.map { it.toEntity() }

        val report = StringBuilder()
        report.appendLine("DogRouter solver harness — straight-line (haversine) distances")
        report.appendLine("Data: ${dogs.size} dogs, ${rules.size} schedule rules, " +
            "${incompatibilities.size} incompatibilities")
        report.appendLine(settingsLine(settings))
        report.appendLine()

        val onlyDay = System.getProperty("solver.day")?.let { DayOfWeek.valueOf(it.uppercase()) }
        val restarts = System.getProperty("solver.restarts")?.toIntOrNull() ?: 60
        val seed = System.getProperty("solver.seed")?.toLongOrNull() ?: 0L

        // A reference week: 2026-06-22 is a Monday, so day N is +N days.
        val monday = LocalDate.of(2026, 6, 22)
        val pairs = incompatibilities.map { canonicalPair(it.dogIdA, it.dogIdB) }.toSet()
        val days = onlyDay?.let { listOf(it) } ?: daysWithRules(rules)

        for (dow in days) {
            val date = monday.plusDays((dow.value - 1).toLong())
            val options = buildOptions(date, dogs, rules)
            val planner = plannerFor(settings, pairs, restarts)

            lateinit var route: DayRoute
            val nanos = measureNanoTime { route = planner.plan(date, options, seed) }

            report.appendLine("=".repeat(72))
            report.appendLine("${dayName(dow)}  ($date)  —  ${options.size} walk option(s)")
            report.appendLine("=".repeat(72))
            appendTimeline(report, route, settings)
            report.appendLine()
            appendMetrics(report, route, settings, nanos)
            report.appendLine()
        }

        val text = report.toString()
        if (System.getProperty("solver.print", "true").toBoolean()) println(text)

        val out = File("build/solver-report.txt")
        out.parentFile?.mkdirs()
        out.writeText(text)
        println("Full report written to ${out.absolutePath}")
    }

    /**
     * Step 0 baseline: run every weekday across several seeds and record
     * min/median/mean/max per quality metric, so model/solver changes can be
     * tracked and regressions spotted. Deterministic (fixed seeds + haversine)
     * so the written file diffs cleanly; wall-clock solve time goes to stdout
     * only. Gated behind -Dsolver.baseline so a normal test run never rewrites
     * the tracked baseline file. Regenerate with:
     *
     *   ./run_baseline.sh   (or add -Dsolver.baseline to the gradle command)
     */
    @Test
    fun baselineAcrossSeeds() = runBlocking {
        Assume.assumeTrue(
            "Set -Dsolver.baseline to (re)generate docs/solver-baseline.md",
            System.getProperty("solver.baseline") != null,
        )
        val backup = loadBackup()
        val settings = backup.settings.toAppSettings()
        val dogs = backup.dogs.map { it.toEntity() }
        val rules = backup.scheduleRules.map { it.toEntity() }
        val pairs = backup.incompatibilities.map { it.toEntity() }
            .map { canonicalPair(it.dogIdA, it.dogIdB) }.toSet()

        val seedCount = System.getProperty("solver.baselineSeeds")?.toIntOrNull() ?: 10
        val restarts = System.getProperty("solver.restarts")?.toIntOrNull() ?: 60
        val monday = LocalDate.of(2026, 6, 22)
        val days = daysWithRules(rules)

        val md = StringBuilder()
        md.appendLine("# Solver baseline")
        md.appendLine()
        md.appendLine("Quality metrics across $seedCount seeds (0..${seedCount - 1}) per weekday, " +
            "straight-line (haversine) distances, $restarts restarts. Deterministic — " +
            "regenerate with `run_baseline.sh`. Wall-clock solve time is reported to stdout " +
            "only (non-deterministic).")
        md.appendLine()
        md.appendLine("Data: ${dogs.size} dogs, ${rules.size} schedule rules. " +
            "Settings: cycling ${settings.cyclingSpeedKmh}km/h, walking ${settings.walkingSpeedKmh}km/h, " +
            "bike overhead ${settings.bikeOverheadMinutes}min, stop buffer ${settings.stopBufferMinutes}min.")
        md.appendLine()

        val solveTimes = StringBuilder("Solve time per day (non-deterministic):\n")
        for (dow in days) {
            val date = monday.plusDays((dow.value - 1).toLong())
            val options = buildOptions(date, dogs, rules)
            val runs = (0 until seedCount).map { seed ->
                val planner = plannerFor(settings, pairs, restarts)
                lateinit var route: DayRoute
                val nanos = measureNanoTime { route = planner.plan(date, options, seed.toLong()) }
                computeMetrics(route, settings, nanos)
            }
            appendBaselineTable(md, dow, options.size, runs)
            val st = statsOf(runs.map { it.solveMs.roundToInt() })
            solveTimes.appendLine("  ${dayName(dow).padEnd(10)} min ${st.min}  " +
                "median ${fmt0(st.median)}  mean ${fmt0(st.mean)}  max ${st.max}  (ms)")
        }

        val out = File(repoRoot(), "docs/solver-baseline.md")
        out.parentFile?.mkdirs()
        out.writeText(md.toString())
        println(md.toString())
        println(solveTimes.toString())
        println("Baseline written to ${out.absolutePath}")
    }

    private class Stat(val min: Int, val median: Double, val mean: Double, val max: Int)

    private fun statsOf(values: List<Int>): Stat {
        val s = values.sorted()
        val n = s.size
        val median = if (n % 2 == 1) s[n / 2].toDouble() else (s[n / 2 - 1] + s[n / 2]) / 2.0
        return Stat(s.first(), median, values.sum().toDouble() / n, s.last())
    }

    private fun appendBaselineTable(
        md: StringBuilder,
        dow: DayOfWeek,
        optionCount: Int,
        runs: List<PlanMetrics>,
    ) {
        md.appendLine("## ${dayName(dow)} — $optionCount walk options")
        md.appendLine()
        md.appendLine("| metric | min | median | mean | max |")
        md.appendLine("|---|---|---|---|---|")
        md.appendLine(countRow("conflicts", runs.map { it.conflicts }))
        md.appendLine(durationRow("day length", runs.map { it.dayLengthSec }))
        md.appendLine(durationRow("cycling", runs.map { it.cyclingSec }))
        md.appendLine(durationRow("on-foot", runs.map { it.footSec }))
        md.appendLine(countRow("bike mounts", runs.map { it.bikeMounts }))
        md.appendLine(durationRow("dwell walk", runs.map { it.dwellSec }))
        md.appendLine(durationRow("idle", runs.map { it.idleSec }))
        md.appendLine(durationRow("over-walk", runs.map { it.overWalkSec }))
        md.appendLine()
    }

    private fun durationRow(label: String, values: List<Int>): String {
        val s = statsOf(values)
        return "| $label | ${minutesOf(s.min)} | ${minutesOf(s.median.roundToInt())} | " +
            "${minutesOf(s.mean.roundToInt())} | ${minutesOf(s.max)} |"
    }

    private fun countRow(label: String, values: List<Int>): String {
        val s = statsOf(values)
        return "| $label | ${s.min} | ${fmt1(s.median)} | ${fmt1(s.mean)} | ${s.max} |"
    }

    // Locale-independent so the tracked baseline diffs cleanly on any machine.
    private fun fmt1(v: Double) = String.format(Locale.ROOT, "%.1f", v)
    private fun fmt0(v: Double) = String.format(Locale.ROOT, "%.0f", v)

    // ---- shared solver setup ---------------------------------------------

    private fun plannerFor(
        settings: AppSettings,
        pairs: Set<Pair<String, String>>,
        restarts: Int,
    ) = DayPlanner(
        routingProvider = HaversineRouting(),
        home = settings.homeGeoPoint(),
        capacityKg = settings.bikeCapacityKg,
        stopBufferSeconds = settings.stopBufferMinutes * 60,
        cyclingSpeedKmh = settings.cyclingSpeedKmh,
        incompatibilities = pairs,
        walkingSpeedKmh = settings.walkingSpeedKmh,
        bikeOverheadSeconds = settings.bikeOverheadMinutes * 60,
        restarts = restarts,
        // -Dsolver.lns=N to experiment with the LNS pass (0 = pure multi-start);
        // defaults to the planner's adopted value so the baseline reflects it.
        lnsIterations = System.getProperty("solver.lns")?.toIntOrNull()
            ?: DayPlanner.DEFAULT_LNS_ITERATIONS,
    )

    private fun daysWithRules(rules: List<DogScheduleRule>): List<DayOfWeek> =
        DayOfWeek.entries.filter { dow ->
            rules.any { (it.weekdaysMask and (1 shl (dow.value - 1))) != 0 }
        }

    private fun dayName(dow: DayOfWeek): String =
        dow.name.lowercase().replaceFirstChar { it.uppercase() }

    // ---- plan printout ---------------------------------------------------

    private fun appendTimeline(sb: StringBuilder, route: DayRoute, settings: AppSettings) {
        if (route.events.isEmpty()) {
            sb.appendLine("  (no events)")
            return
        }
        val stopBuffer = settings.stopBufferMinutes * 60
        var prev: RouteEvent? = null
        for (e in route.events) {
            val leg = legLabel(prev, e)
            val wait = waitSeconds(prev, e, stopBuffer)
            val waitLabel = if (wait > 0) "  (wait ${minutesOf(wait)})" else ""
            sb.appendLine("  ${clock(e.timeSeconds)}  ${leg.padEnd(12)}  ${describe(e)}$waitLabel")
            prev = e
        }
        if (route.conflicts.isNotEmpty()) {
            sb.appendLine("  CONFLICTS (${route.conflicts.size}):")
            route.conflicts.forEach { sb.appendLine("    ✗ ${it.dog.name}: ${it.reason}") }
        }
    }

    private fun legLabel(prev: RouteEvent?, e: RouteEvent): String {
        if (prev == null) return "(start)"
        if (e.incomingTravelSeconds == 0 && !e.arrivedByFoot) return "·"
        val mode = if (e.arrivedByFoot) "foot" else "bike"
        return "$mode ${minutesOf(e.incomingTravelSeconds)}"
    }

    private fun describe(e: RouteEvent): String = when (e) {
        is RouteEvent.HomeStart -> "HomeStart"
        is RouteEvent.HomeEnd -> "HomeEnd"
        is RouteEvent.Pickup -> "Pickup  ${e.dog.name}"
        is RouteEvent.Dropoff -> "Dropoff ${e.dog.name}"
        is RouteEvent.Walk ->
            "Walk    [${e.dogs.joinToString { it.name }}]  ${e.durationSeconds / 60}min"
        is RouteEvent.Break -> "☕ Break  ${e.durationSeconds / 60}min"
        is RouteEvent.FetchBike -> "↩ Back to the parked bike"
    }

    // ---- quality metrics -------------------------------------------------

    /** The numeric quality metrics for one plan; shared by the detailed
     *  printout and the across-seeds baseline so both measure identically. */
    private data class PlanMetrics(
        val conflicts: Int,
        val dayLengthSec: Int,
        val cyclingSec: Int,
        val footSec: Int,
        val bikeMounts: Int,
        val dwellSec: Int,
        val idleSec: Int,
        val overWalkSec: Int,
        val solveMs: Double,
    )

    private fun computeMetrics(route: DayRoute, settings: AppSettings, solveNanos: Long): PlanMetrics {
        val events = route.events
        return PlanMetrics(
            conflicts = route.conflicts.size,
            dayLengthSec = if (events.size >= 2)
                events.last().timeSeconds - events.first().timeSeconds else 0,
            cyclingSec = events.filter { !it.arrivedByFoot }.sumOf { it.incomingTravelSeconds },
            footSec = events.filter { it.arrivedByFoot }.sumOf { it.incomingTravelSeconds },
            bikeMounts = events.count { !it.arrivedByFoot && it.incomingTravelSeconds > 0 },
            dwellSec = events.filterIsInstance<RouteEvent.Walk>().sumOf { it.durationSeconds },
            idleSec = idleSeconds(events, settings.stopBufferMinutes * 60),
            overWalkSec = walkedVsRequired(events).second,
            solveMs = solveNanos / 1_000_000.0,
        )
    }

    private fun appendMetrics(
        sb: StringBuilder,
        route: DayRoute,
        settings: AppSettings,
        solveNanos: Long,
    ) {
        val m = computeMetrics(route, settings, solveNanos)
        sb.appendLine("METRICS")
        sb.appendLine("  Conflicts (unplaced) ......... ${m.conflicts}   (target 0)")
        sb.appendLine("  Day length (home→home) ....... ${minutesOf(m.dayLengthSec)}")
        sb.appendLine("  Travel: cycling .............. ${minutesOf(m.cyclingSec)}" +
            "   on-foot ${minutesOf(m.footSec)}")
        sb.appendLine("  Bike mounts (overhead paid) .. ${m.bikeMounts}" +
            "   @ ${settings.bikeOverheadMinutes}min = ${m.bikeMounts * settings.bikeOverheadMinutes}min")
        sb.appendLine("  Dwell walk time (in place) ... ${minutesOf(m.dwellSec)}")
        sb.appendLine("  Idle / waiting ............... ${minutesOf(m.idleSec)}")
        sb.appendLine("  Walk-backs to bike ........... ${bikeFetchSummary(route.events)}")

        val (perDog, _) = walkedVsRequired(route.events)
        sb.appendLine("  Per-dog walked vs required:")
        if (perDog.isEmpty()) {
            sb.appendLine("    (none placed)")
        } else {
            perDog.forEach { sb.appendLine("    $it") }
        }
        sb.appendLine("  Total over-walk .............. ${minutesOf(m.overWalkSec)}")
        sb.appendLine("  Solve time ................... ${"%.1f".format(m.solveMs)} ms")
    }

    /**
     * Breakdown of the walk-back-to-bike legs: how many happen empty-handed
     * (every dog already dropped off) vs with one or more dogs still aboard.
     * The latter is fine — those dogs walk that stretch with us and the time
     * is credited to them in walkedVsRequired; this line just shows how often
     * it happens.
     */
    private fun bikeFetchSummary(events: List<RouteEvent>): String {
        val aboard = HashSet<String>()
        var total = 0
        var carrying = 0
        for (e in events) {
            when (e) {
                is RouteEvent.Pickup -> aboard.add(e.dog.id)
                is RouteEvent.Dropoff -> aboard.remove(e.dog.id)
                is RouteEvent.FetchBike -> {
                    total++
                    if (aboard.isNotEmpty()) carrying++
                }
                else -> Unit
            }
        }
        return when {
            total == 0 -> "0"
            carrying == 0 -> "$total (all empty-handed)"
            else -> "$total ($carrying carried a dog — counted as their walk time)"
        }
    }

    /** Time spent standing still waiting for a window to open, summed over the day. */
    private fun idleSeconds(events: List<RouteEvent>, stopBuffer: Int): Int {
        var idle = 0
        for (i in 1 until events.size) {
            idle += waitSeconds(events[i - 1], events[i], stopBuffer)
        }
        return idle
    }

    /** Idle time at [e]: gap beyond travel between leaving [prev] and arriving at [e]. */
    private fun waitSeconds(prev: RouteEvent?, e: RouteEvent, stopBuffer: Int): Int {
        if (prev == null) return 0
        val expectedArrival = prev.timeSeconds + prev.durationAtSeconds(stopBuffer) + e.incomingTravelSeconds
        return (e.timeSeconds - expectedArrival).coerceAtLeast(0)
    }

    /**
     * Per pickup→dropoff occurrence: minutes actually walked (dwell walks the
     * dog joins + on-foot travel in its span, mirroring WalkDurationConstraint)
     * vs the rule's required minutes. Returns printable lines and the total
     * over-walk across all occurrences.
     */
    private fun walkedVsRequired(events: List<RouteEvent>): Pair<List<String>, Int> {
        val walks = events.filterIsInstance<RouteEvent.Walk>()
        val lines = mutableListOf<String>()
        var totalOver = 0
        for (span in events.walkSpans()) {
            val pickup = span.pickup
            val dropoff = span.dropoff
            if (dropoff == null) {
                lines.add("${pickup.dog.name}: no dropoff (incomplete)")
                continue
            }
            val range = pickup.timeSeconds..dropoff.timeSeconds
            val dwell = walks
                .filter { w -> w.timeSeconds in range && w.dogs.any { it.id == pickup.dog.id } }
                .sumOf { it.durationSeconds }
            // On-foot legs the dog walks while aboard count as walk time
            // (true double-duty), including a walk back to the bike while it
            // is still with us. The pickup's own incoming leg is excluded —
            // that leg fetches the dog, not yet aboard. See footCreditSeconds.
            val walked = dwell + span.footCreditSeconds(events)
            val required = pickup.rule.durationMinutes * 60
            val over = (walked - required).coerceAtLeast(0)
            totalOver += over
            val overLabel = if (over > 0) "  (+${minutesOf(over)} over)" else ""
            val capLabel = if (!pickup.dog.allowLongerWalk) "  [exact-only]" else ""
            lines.add("${pickup.dog.name.padEnd(10)} walked ${minutesOf(walked)}" +
                " / need ${minutesOf(required)}$overLabel$capLabel")
        }
        return lines to totalOver
    }

    // ---- option building (mirrors DayPlanService.computePlan) ------------

    private fun buildOptions(
        date: LocalDate,
        dogs: List<Dog>,
        rules: List<DogScheduleRule>,
    ): List<WalkOption> {
        val bit = 1 shl (date.dayOfWeek.value - 1)
        val rulesForDay = rules.filter { (it.weekdaysMask and bit) != 0 }
        val dogById = dogs.associateBy { it.id }
        return rulesForDay
            .groupBy { it.dogId }
            .flatMap { (dogId, dogRules) ->
                val dog = dogById[dogId] ?: return@flatMap emptyList()
                val (alternatives, mandatory) = dogRules.partition { it.isAlternative }
                buildList {
                    mandatory.forEach { add(WalkOption(listOf(PlannedWalk(dog, it)))) }
                    if (alternatives.isNotEmpty()) {
                        add(WalkOption(alternatives.map { PlannedWalk(dog, it) }))
                    }
                }
            }
    }

    private fun canonicalPair(a: String, b: String): Pair<String, String> =
        if (a < b) a to b else b to a

    private fun AppSettings.homeGeoPoint(): GeoPoint? {
        val lat = homeLatitude ?: return null
        val lon = homeLongitude ?: return null
        return GeoPoint(lat, lon)
    }

    // ---- io & formatting -------------------------------------------------

    private fun loadBackup(): BackupFile {
        val file = findBackupFile()
        val json = Json { ignoreUnknownKeys = true }
        return json.decodeFromString(BackupFile.serializer(), file.readText())
    }

    /** Walk up from the working dir until the backup file is found.
     *  Override the name with -Dsolver.backup=dogrouter-backup-2.json. */
    private fun findBackupFile(): File {
        val name = System.getProperty("solver.backup") ?: "dogrouter-backup.json"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, name)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$name not found (looked up from ${File("").absolutePath})")
    }

    /** Repo root = the directory holding the backup file. */
    private fun repoRoot(): File = findBackupFile().absoluteFile.parentFile
        ?: error("backup file has no parent directory")

    private fun settingsLine(s: AppSettings): String =
        "Settings: home=(${s.homeLatitude}, ${s.homeLongitude}), " +
            "cycling=${s.cyclingSpeedKmh}km/h, walking=${s.walkingSpeedKmh}km/h, " +
            "bikeOverhead=${s.bikeOverheadMinutes}min, stopBuffer=${s.stopBufferMinutes}min, " +
            "capacity=${s.bikeCapacityKg}kg"

    private fun clock(sec: Int) = "%02d:%02d".format(sec / 3600, (sec % 3600) / 60)

    private fun minutesOf(sec: Int): String {
        val m = sec / 60
        return if (m >= 60) "%dh%02dm".format(m / 60, m % 60) else "${m}m"
    }

    /** Straight-line distance; duration unused (planner divides distance by speed). */
    private class HaversineRouting : RoutingProvider {
        override suspend fun isReady() = true
        override val lastError: String? = null
        override suspend fun routeGeometry(
            fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double,
        ): List<GeoPoint>? = null

        override suspend fun route(
            fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double,
        ): RouteEstimate {
            val r = 6_371_000.0
            val dLat = Math.toRadians(toLatitude - fromLatitude)
            val dLon = Math.toRadians(toLongitude - fromLongitude)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(fromLatitude)) * cos(Math.toRadians(toLatitude)) *
                sin(dLon / 2) * sin(dLon / 2)
            val meters = (r * 2 * atan2(sqrt(a), sqrt(1 - a))).toInt()
            return RouteEstimate(distanceMeters = meters, durationSeconds = 0)
        }
    }
}
