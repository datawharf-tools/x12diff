package tools.datawharf.x12diff

import java.io.BufferedWriter
import java.io.IOException
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.file.Files

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

private fun showOpt(o: Opts): Int {
    val v = o.flags["--show"] ?: return 20
    return v.toIntOrNull()?.takeIf { it >= 0 } ?: throw X12Exception("--show needs a number of 0 or more")
}

private fun profileOpt(o: Opts, out: PrintStream, err: PrintStream): Profile {
    val p = o.flags["--profile"]?.let { Profile.load(safePath(it)) } ?: Profile.default()
    for (u in p.unknownKeys) out.println("WARNING: profile key $u")
    return p
}

/** Prints envelope problems loudly. Returns true when they must block a normal exit (no --allow-incomplete). */
private fun envelopeProblems(log: EnvelopeLog, out: PrintStream, err: PrintStream, allow: Boolean): Boolean {
    if (!log.any) return false
    val head = "INPUT INCOMPLETE OR INCONSISTENT: ${fmt(log.total.toLong())} envelope problem(s); the numbers in this report may be wrong" + (if (allow) " (accepted by --allow-incomplete)" else "")
    out.println(head)
    err.println(head)
    for (x in log.items.take(10)) { out.println("  ENVELOPE: $x"); err.println("  ENVELOPE: $x") }
    if (log.total > 10) out.println("  ... ${fmt((log.total - 10).toLong())} more")
    if (!allow) err.println("error: input is incomplete or inconsistent (for example a truncated transfer); fix the file or rerun with --allow-incomplete to accept it")
    return !allow
}

private fun skippedNote(skipped: List<String>, out: PrintStream) {
    if (skipped.isNotEmpty()) out.println("Skipped (not X12): " + skipped.joinToString(", "))
}

internal fun cmdDiff(a: List<String>, out: PrintStream, err: PrintStream): Int {
    val o = parseOpts(a, setOf("--csv", "--profile", "--show", "--mode"), setOf("--stats", "--allow-incomplete"))
    if (o.pos.size != 2) throw X12Exception("Usage: x12diff diff OLD NEW [--csv out.csv] [--profile p.properties] [--mode full|change]")
    val showN = showOpt(o)
    val mode = o.flags["--mode"]?.also { if (it != "full" && it != "change") throw X12Exception("--mode must be full or change") }
    val profile = profileOpt(o, out, err)
    val oldP = safePath(o.pos[0])
    val newP = safePath(o.pos[1])
    for (p in listOf(oldP to o.pos[0], newP to o.pos[1])) {
        if (Files.isDirectory(p.first)) throw X12Exception("'${p.second}' is a folder; diff takes two files")
        if (!Files.isRegularFile(p.first)) throw X12Exception("File not found: ${p.second}")
    }
    val csvPath = o.flags["--csv"]
    requireDistinct("--csv" to csvPath, "old file" to o.pos[0])
    requireDistinct("--csv" to csvPath, "new file" to o.pos[1])

    val t0 = System.nanoTime()
    var shown = 0
    var of: OutFile? = null
    try {
        if (csvPath != null) {
            of = Csv.open(csvPath)
            of.w.write(Csv.line(listOf("change_type", "member_key", "segment", "element", "label", "old_value", "new_value", "detail")))
        }
        val w = of?.w
        val sum = Differ.diff(oldP, newP, profile, mode) { r ->
            w?.write(Csv.line(listOf(r.type, r.key, r.segment, r.element, r.label, r.oldValue, r.newValue, r.detail)))
            if (shown < showN) {
                shown++
                val what = if (r.element.isNotEmpty()) "${r.segment} ${r.element}: '${r.oldValue}' -> '${r.newValue}'"
                else listOf(r.segment, r.detail).filter { it.isNotEmpty() }.joinToString(" ")
                out.println("  ${r.type.padEnd(10)} ${Csv.fixUtf8(r.key)}  ${Csv.fixUtf8(what)}")
            }
        }
        of?.commit()
        out.println("Old: ${o.pos[0]}  members: ${fmt(sum.oldMembers)}  (BGN08 ${sum.oldBgn}: ${sum.oldKind})")
        out.println("New: ${o.pos[1]}  members: ${fmt(sum.newMembers)}  (BGN08 ${sum.newBgn}: ${sum.newKind})")
        out.println("Mode: ${sum.mode}" + if (sum.mode == "change") " (a member missing from the new file is not termed)" else " (a member missing from the new file is termed)")
        for (n in sum.notes) { out.println(n); if (n.startsWith("WARNING")) err.println(n) }
        out.println("ADDED:      ${fmt(sum.added)}")
        out.println("TERMED:     ${fmt(sum.termed)}  (${fmt(sum.termedAbsent)} absent from new, ${fmt(sum.termedFlagged)} newly flagged)")
        if (sum.mode == "change") out.println("NOT_IN_NEW: ${fmt(sum.absentNotTermed)}  (change files: absent members are unchanged, not terminated)")
        out.println("CHANGED:    ${fmt(sum.changed)}")
        out.println("UNCHANGED:  ${fmt(sum.unchanged)}")
        if (sum.duplicateKeys > 0) out.println("WARNING: ${fmt(sum.duplicateKeys)} duplicate member key(s) in the old file; review your profile key.N lines")
        if (sum.ambiguousMembers > 0) out.println("WARNING: ${fmt(sum.ambiguousMembers)} member(s) share a key with another member; their rows say 'ambiguous key' and are unreliable")
        if (sum.emptyKeys > 0) out.println("WARNING: ${fmt(sum.emptyKeys)} member(s) in the old file produced an empty key; review your profile key.N lines")
        if (csvPath != null) out.println("CSV written: $csvPath")
        if (o.flags.containsKey("--stats")) {
            val secs = (System.nanoTime() - t0) / 1e9
            val peak = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }.sumOf { it.peakUsage.used }
            out.println(String.format("Elapsed: %.1f s   Peak heap (sum of pool peaks): %d MB", secs, peak / (1024 * 1024)))
        }
        val allow = o.flags.containsKey("--allow-incomplete")
        val empty = zeroMembers(sum, o.pos[0], o.pos[1], out, err, allow)
        if (envelopeProblems(sum.envelope, out, err, allow) || empty) return 3
        return if (sum.hasDifferences) 1 else 0
    } finally {
        of?.abort()
    }
}

/**
 * A file with no members (empty, not an 834, lowercase segment IDs, wrong member-start segment) would otherwise
 * report every member of the other file as ADDED or TERMED. Returns true when that must block a normal exit.
 */
private fun zeroMembers(sum: DiffSummary, oldName: String, newName: String, out: PrintStream, err: PrintStream, allow: Boolean): Boolean {
    val bad = ArrayList<String>()
    if (sum.oldMembers == 0L) bad.add("old file has 0 members ($oldName)")
    if (sum.newMembers == 0L) bad.add("new file has 0 members ($newName)")
    if (bad.isEmpty()) return false
    val tail = if (allow) " (accepted by --allow-incomplete)" else ""
    for (b in bad) {
        val m = "EMPTY INPUT: $b; the differences above are not meaningful (empty file, not an 834, lowercase segment IDs, or a wrong member-start segment in the profile?)$tail"
        out.println(m); err.println(m)
    }
    if (!allow) err.println("error: a file has no members; fix the input or rerun with --allow-incomplete to accept it")
    return !allow
}

internal fun cmdRemit(a: List<String>, out: PrintStream, err: PrintStream): Int {
    val o = parseOpts(a, setOf("--csv", "--rollup", "--claims", "--adjustments", "--profile", "--show"), setOf("--allow-incomplete"))
    if (o.pos.isEmpty()) throw X12Exception("Usage: x12diff remit FILE_OR_DIR... [--csv lines.csv] [--rollup rollup.csv] [--claims claims.csv] [--adjustments adjustments.csv] [--profile p.properties]")
    val profile = profileOpt(o, out, err)
    val showN = showOpt(o)
    val skipped = ArrayList<String>()
    val files = expandInputs(o.pos, skipped)
    checkOutputsAndInputs(
        listOf("--csv" to o.flags["--csv"], "--rollup" to o.flags["--rollup"], "--claims" to o.flags["--claims"], "--adjustments" to o.flags["--adjustments"]),
        o.pos, files,
    )
    val s = RemitCmd.run(files, profile, o.flags["--csv"], o.flags["--rollup"], o.flags["--claims"], o.flags["--adjustments"])
    skippedNote(skipped, out)
    out.println("Files: ${fmt(s.files.toLong())}   835 checks: ${fmt(s.checks)}")
    out.println("Claims: ${fmt(s.claims)}   Service lines: ${fmt(s.lines)}")
    val pct = if (s.charged != 0L) String.format("%.1f%%", s.paid * 100.0 / s.charged) else "n/a"
    out.println("Charged: ${Money.fmt(s.charged)}   Paid: ${Money.fmt(s.paid)}   Paid/charged: $pct   Patient responsibility: ${Money.fmt(s.patientResp)}")
    if (s.invalidAmounts > 0) out.println("WARNING: ${fmt(s.invalidAmounts)} invalid amount(s) were left out of these totals; the CSV keeps their raw text")
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
    o.flags["--claims"]?.let { out.println("Claims written: $it") }
    o.flags["--adjustments"]?.let { out.println("Adjustments written: $it") }
    return if (envelopeProblems(s.envelope, out, err, o.flags.containsKey("--allow-incomplete"))) 3 else 0
}

internal fun cmdRecon(a: List<String>, out: PrintStream, err: PrintStream): Int {
    val o = parseOpts(a, setOf("--csv", "--profile", "--asof", "--show"), setOf("--stats", "--allow-incomplete"))
    if (o.pos.isEmpty()) throw X12Exception("Usage: x12diff recon FILE_OR_DIR... [--csv claims.csv] [--profile p.properties] [--asof YYYYMMDD]")
    val profile = profileOpt(o, out, err)
    val showN = showOpt(o)
    val skipped = ArrayList<String>()
    val files = expandInputs(o.pos, skipped)
    checkOutputsAndInputs(listOf("--csv" to o.flags["--csv"]), o.pos, files)
    val t0 = System.nanoTime()
    val s = ReconCmd.run(files, profile, o.flags["--csv"], o.flags["--asof"] ?: Dates.today(), showN)
    skippedNote(skipped, out)
    out.println("Files: ${fmt(files.size.toLong())}   837 claims: ${fmt(s.claims)} in ${fmt(s.sets837.toLong())} sets   835 claim entries: ${fmt(s.remitEntries)} on ${fmt(s.remitChecks)} checks")
    out.println("Acknowledgement sets: ${fmt(s.ackSets.toLong())} (${fmt(s.rejectedSets.toLong())} 837 sets rejected)   277CA claim statuses: ${fmt(s.statusEntries277)}" +
        if (s.ta1Count > 0) "   TA1: ${fmt(s.ta1Count.toLong())} (${fmt(s.ta1RejectedInterchanges.toLong())} interchanges rejected)" else "")
    for (k in listOf("PAID", "PARTIAL", "DENIED", "PATIENT_RESP", "REVERSED", "ADJUSTED", "UNMATCHED", "REJECTED", "ORPHAN")) {
        out.println("${(k + ":").padEnd(14)}${fmt(s.counts[k] ?: 0L)}")
    }
    out.println("Charged (837): ${Money.fmt(s.chargedTotal)}   Paid (matched 835s, net): ${Money.fmt(s.paidTotal)}")
    out.println("UNMATCHED aging (days since claim date): " + s.aging.entries.joinToString("  ") { "${it.key}: ${fmt(it.value)}" })
    if (s.firstRows.isNotEmpty()) {
        out.println("First ${s.firstRows.size} non-PAID rows:")
        for (r in s.firstRows) {
            out.println(
                "  ${r[0].padEnd(12)} ${Csv.fixUtf8(r[1])}  charge ${r[2]}  paid ${r[4]}" +
                    (if (r[8].isNotEmpty()) "  ${r[8]}" else "") + (if (r[9].isNotEmpty()) "  ${r[9]}" else "")
            )
        }
    }
    out.println("Warnings: ${fmt(s.warningCount)}")
    for (w in s.warnings.take(showN)) out.println("  WARNING: $w")
    if (s.warningCount > showN) out.println("  ... ${fmt(s.warningCount - showN)} more not shown")
    if (s.ambiguousTies > 0) err.println("WARNING: ${fmt(s.ambiguousTies)} acknowledgement or remit match(es) were ambiguous; see the warnings above")
    o.flags["--csv"]?.let { out.println("CSV written: $it") }
    if (o.flags.containsKey("--stats")) {
        val secs = (System.nanoTime() - t0) / 1e9
        val peak = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }.sumOf { it.peakUsage.used }
        out.println(String.format("Elapsed: %.1f s   Peak heap (sum of pool peaks): %d MB", secs, peak / (1024 * 1024)))
    }
    return if (envelopeProblems(s.envelope, out, err, o.flags.containsKey("--allow-incomplete"))) 3 else 0
}
