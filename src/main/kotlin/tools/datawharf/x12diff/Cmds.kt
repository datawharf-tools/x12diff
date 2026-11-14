package tools.datawharf.x12diff

import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.file.Paths

private class Opts(val pos: List<String>, val flags: Map<String, String>)

private fun parseOpts(a: List<String>, withValue: Set<String>, boolean: Set<String>): Opts {
    val pos = ArrayList<String>()
    val flags = HashMap<String, String>()
    var i = 0
    while (i < a.size) {
        val x = a[i]
        when {
            x in withValue -> {
                if (i + 1 >= a.size) throw X12Exception("Option $x needs a value")
                flags[x] = a[++i]
            }
            x in boolean -> flags[x] = "1"
            x.startsWith("--") -> throw X12Exception("Unknown option '$x'")
            else -> pos.add(x)
        }
        i++
    }
    return Opts(pos, flags)
}

internal fun cmdRemit(a: List<String>, out: PrintStream): Int {
    val o = parseOpts(a, setOf("--csv", "--rollup", "--profile", "--show"), emptySet())
    if (o.pos.isEmpty()) throw X12Exception("Usage: x12diff remit FILE_OR_DIR... [--csv lines.csv] [--rollup adjustments.csv] [--profile p.properties]")
    val profile = o.flags["--profile"]?.let { Profile.load(Paths.get(it)) } ?: Profile.default()
    val showN = o.flags["--show"]?.let { it.toIntOrNull() ?: throw X12Exception("--show needs a number") } ?: 20
    val files = expandInputs(o.pos)
    val s = RemitCmd.run(files, profile, o.flags["--csv"], o.flags["--rollup"])
    out.println("Files: ${fmt(s.files.toLong())}   835 checks: ${fmt(s.checks)}")
    out.println("Claims: ${fmt(s.claims)}   Service lines: ${fmt(s.lines)}")
    val pct = if (s.charged != 0L) String.format("%.1f%%", s.paid * 100.0 / s.charged) else "n/a"
    out.println("Charged: ${Money.fmt(s.charged)}   Paid: ${Money.fmt(s.paid)}   Paid/charged: $pct   Patient responsibility: ${Money.fmt(s.patientResp)}")
    out.println("Adjustments by group-reason (count, amount):")
    if (s.rollup.isEmpty()) out.println("  (none)")
    for ((k, v) in s.rollup.entries.sortedByDescending { Math.abs(it.value[1]) }.take(30)) {
        val lab = profile.adjLabel(k.substringBefore('-'), k.substringAfter('-'))
        out.println("  ${k.padEnd(8)} ${fmt(v[0]).padStart(10)}  ${Money.fmt(v[1]).padStart(16)}" + if (lab.isNotEmpty()) "  $lab" else "")
    }
    if (s.rollup.size > 30) out.println("  ... ${s.rollup.size - 30} more (use --rollup to write all)")
    out.println("Warnings: ${fmt(s.warningCount)}")
    for (w in s.warnings.take(showN)) out.println("  WARNING: $w")
    if (s.warningCount > showN) out.println("  ... ${fmt(s.warningCount - showN)} more not shown")
    o.flags["--csv"]?.let { out.println("CSV written: $it") }
    o.flags["--rollup"]?.let { out.println("Rollup written: $it") }
    return 0
}

internal fun cmdRecon(a: List<String>, out: PrintStream): Int {
    val o = parseOpts(a, setOf("--csv", "--profile", "--asof", "--show"), setOf("--stats"))
    if (o.pos.isEmpty()) throw X12Exception("Usage: x12diff recon FILE_OR_DIR... [--csv claims.csv] [--profile p.properties] [--asof YYYYMMDD]")
    val profile = o.flags["--profile"]?.let { Profile.load(Paths.get(it)) } ?: Profile.default()
    val showN = o.flags["--show"]?.let { it.toIntOrNull() ?: throw X12Exception("--show needs a number") } ?: 20
    val files = expandInputs(o.pos)
    val t0 = System.nanoTime()
    val s = ReconCmd.run(files, profile, o.flags["--csv"], o.flags["--asof"] ?: Dates.today(), showN)
    out.println("Files: ${fmt(files.size.toLong())}   837 claims: ${fmt(s.claims)} in ${fmt(s.sets837.toLong())} sets   835 claim entries: ${fmt(s.remitEntries)} on ${fmt(s.remitChecks)} checks")
    out.println("Acknowledgement sets: ${fmt(s.ackSets.toLong())} (${fmt(s.rejectedSets.toLong())} 837 sets rejected)   277CA claim statuses: ${fmt(s.statusEntries277)}")
    for (k in listOf("PAID", "PARTIAL", "DENIED", "REVERSED", "ADJUSTED", "UNMATCHED", "REJECTED", "ORPHAN")) {
        out.println("${(k + ":").padEnd(11)}${fmt(s.counts[k] ?: 0L)}")
    }
    out.println("Charged (837): ${Money.fmt(s.chargedTotal)}   Paid (matched 835s, net): ${Money.fmt(s.paidTotal)}")
    out.println("UNMATCHED aging (days since claim date): " + s.aging.entries.joinToString("  ") { "${it.key}: ${fmt(it.value)}" })
    if (s.firstRows.isNotEmpty()) {
        out.println("First ${s.firstRows.size} non-PAID rows:")
        for (r in s.firstRows) {
            out.println(
                "  ${r[0].padEnd(9)} ${r[1]}  charge ${r[2]}  paid ${r[4]}" +
                    (if (r[8].isNotEmpty()) "  ${r[8]}" else "") + (if (r[9].isNotEmpty()) "  ${r[9]}" else "")
            )
        }
    }
    out.println("Warnings: ${fmt(s.warningCount)}")
    for (w in s.warnings.take(showN)) out.println("  WARNING: $w")
    o.flags["--csv"]?.let { out.println("CSV written: $it") }
    if ("--stats" in o.flags) {
        val secs = (System.nanoTime() - t0) / 1e9
        val peak = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }.sumOf { it.peakUsage.used }
        out.println(String.format("Elapsed: %.1f s   Peak heap (sum of pool peaks): %d MB", secs, peak / (1024 * 1024)))
    }
    return 0
}
