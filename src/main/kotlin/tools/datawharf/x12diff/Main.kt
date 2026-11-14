package tools.datawharf.x12diff

import java.io.BufferedWriter
import java.io.IOException
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

const val VERSION = "0.2.0"

private val USAGE = """
x12diff $VERSION - offline X12 inspector and 834 member diff (Datawharf Tools)

Usage:
  x12diff parse FILE
  x12diff diff OLD NEW [--csv out.csv] [--profile p.properties] [--show N] [--stats]
  x12diff remit FILE_OR_DIR... [--csv lines.csv] [--rollup adjustments.csv] [--profile p.properties] [--show N]
  x12diff recon FILE_OR_DIR... [--csv claims.csv] [--profile p.properties] [--asof YYYYMMDD] [--show N] [--stats]
  x12diff gen --members N --old OLD --new NEW [--seed S] [--newlines] [--sets K]
  x12diff gen --kind claims --claims N --dir DIR [--seed S] [--newlines] [--set-size K]
  x12diff profile            print the built-in profile (redirect to a file to customise)
  x12diff --version | --help

Exit codes: 0 ok (diff: no differences), 1 diff found differences, 2 error.
Runs entirely on your machine; makes no network connections.
""".trimIndent()

internal fun fmt(n: Long) = String.format("%,d", n)

private fun show(c: Char): String = when (c) {
    '\n' -> "\\n (LF)"
    '\r' -> "\\r (CR)"
    '\t' -> "\\t"
    else -> "'$c'"
}

fun main(args: Array<String>) {
    exitProcess(run(args, System.out, System.err))
}

fun run(args: Array<String>, out: PrintStream, err: PrintStream): Int {
    try {
        if (args.isEmpty() || args[0] == "--help" || args[0] == "-h" || args[0] == "help") {
            out.println(USAGE)
            return if (args.isEmpty()) 2 else 0
        }
        return when (args[0]) {
            "--version" -> { out.println("x12diff $VERSION"); 0 }
            "parse" -> cmdParse(args.drop(1), out)
            "diff" -> cmdDiff(args.drop(1), out)
            "remit" -> cmdRemit(args.drop(1), out)
            "recon" -> cmdRecon(args.drop(1), out)
            "gen" -> cmdGen(args.drop(1), out)
            "profile" -> { out.print(Profile.DEFAULT_TEXT); 0 }
            else -> throw X12Exception("Unknown command '${args[0]}'. Run 'x12diff --help'.")
        }
    } catch (e: X12Exception) {
        err.println("error: ${e.message}")
        return 2
    } catch (e: NoSuchFileException) {
        err.println("error: file not found: ${e.file}")
        return 2
    } catch (e: IOException) {
        err.println("error: cannot read or write file: ${e.message}")
        return 2
    } catch (e: OutOfMemoryError) {
        err.println("error: out of memory; try a larger heap, e.g. java -Xmx2g -jar x12diff.jar ...")
        return 2
    }
}

private fun cmdParse(a: List<String>, out: PrintStream): Int {
    if (a.size != 1 || a[0].startsWith("--")) throw X12Exception("Usage: x12diff parse FILE")
    val path = Paths.get(a[0])
    val checker = EnvelopeChecker()
    var segs = 0L
    Files.newInputStream(path).use { ins ->
        val r = X12Reader(ins)
        while (true) {
            val s = r.next() ?: break
            checker.accept(s)
            segs++
        }
        checker.delimiters = r.delimiters
    }
    checker.finish()
    out.println("File: ${path.fileName} (${fmt(Files.size(path))} bytes)")
    val d = checker.delimiters
    if (d != null) {
        out.println(
            "Delimiters: element ${show(d.element)}  sub-element ${show(d.sub)}  repetition " +
                (d.repetition?.let { show(it) } ?: "none") + "  segment terminator ${show(d.terminator)}"
        )
    }
    out.println("Interchanges (ISA): ${fmt(checker.interchanges.toLong())}")
    out.println("Functional groups (GS): ${fmt(checker.groups.toLong())}")
    out.println("Transaction sets (ST): ${fmt(checker.sets.toLong())}")
    for ((t, n) in checker.setTypes) out.println("  $t: ${fmt(n.toLong())}")
    out.println("Segments: ${fmt(segs)}")
    for ((id, n) in checker.segmentCounts) out.println("  $id: ${fmt(n)}")
    val w = checker.warnings
    out.println("Warnings: ${w.size + checker.suppressed}")
    for (x in w) out.println("  WARNING: $x")
    if (checker.suppressed > 0) out.println("  ... and ${fmt(checker.suppressed.toLong())} more not shown")
    return 0
}

private fun cmdDiff(a: List<String>, out: PrintStream): Int {
    val pos = ArrayList<String>()
    var csvPath: String? = null
    var profilePath: String? = null
    var showN = 20
    var stats = false
    var i = 0
    while (i < a.size) {
        val x = a[i]
        fun value(): String {
            if (i + 1 >= a.size) throw X12Exception("Option $x needs a value")
            return a[++i]
        }
        when {
            x == "--csv" -> csvPath = value()
            x == "--profile" -> profilePath = value()
            x == "--show" -> showN = value().toIntOrNull()?.takeIf { it >= 0 } ?: throw X12Exception("--show needs a number")
            x == "--stats" -> stats = true
            x.startsWith("--") -> throw X12Exception("Unknown option '$x'")
            else -> pos.add(x)
        }
        i++
    }
    if (pos.size != 2) throw X12Exception("Usage: x12diff diff OLD NEW [--csv out.csv] [--profile p.properties]")
    val profile = if (profilePath != null) Profile.load(Paths.get(profilePath)) else Profile.default()
    val oldP = Paths.get(pos[0])
    val newP = Paths.get(pos[1])
    if (!Files.isRegularFile(oldP)) throw X12Exception("File not found: ${pos[0]}")
    if (!Files.isRegularFile(newP)) throw X12Exception("File not found: ${pos[1]}")

    val t0 = System.nanoTime()
    var shown = 0
    var w: BufferedWriter? = null
    try {
        if (csvPath != null) {
            val cp: Path = Paths.get(csvPath)
            w = Files.newBufferedWriter(cp, StandardCharsets.UTF_8)
            w.write("change_type,member_key,segment,element,label,old_value,new_value,detail\r\n")
        }
        val sum = Differ.diff(oldP, newP, profile) { r ->
            w?.let {
                it.write(
                    Csv.line(listOf(r.type, r.key, r.segment, r.element, r.label, r.oldValue, r.newValue, r.detail))
                )
            }
            if (shown < showN) {
                shown++
                val what = if (r.element.isNotEmpty()) "${r.segment} ${r.element}: '${r.oldValue}' -> '${r.newValue}'"
                else listOf(r.segment, r.detail).filter { it.isNotEmpty() }.joinToString(" ")
                out.println("  ${r.type.padEnd(7)} ${r.key}  $what")
            }
        }
        w?.close()
        w = null
        out.println("Old: ${pos[0]}  members: ${fmt(sum.oldMembers)}")
        out.println("New: ${pos[1]}  members: ${fmt(sum.newMembers)}")
        out.println("ADDED:     ${fmt(sum.added)}")
        out.println("TERMED:    ${fmt(sum.termed)}  (${fmt(sum.termedAbsent)} absent from new, ${fmt(sum.termedFlagged)} newly flagged)")
        out.println("CHANGED:   ${fmt(sum.changed)}")
        out.println("UNCHANGED: ${fmt(sum.unchanged)}")
        if (sum.duplicateKeys > 0) out.println("WARNING: ${fmt(sum.duplicateKeys)} duplicate member key(s) in the old file; review your profile key.N lines")
        if (sum.emptyKeys > 0) out.println("WARNING: ${fmt(sum.emptyKeys)} member(s) in the old file produced an empty key; review your profile key.N lines")
        if (csvPath != null) out.println("CSV written: $csvPath")
        if (stats) {
            val secs = (System.nanoTime() - t0) / 1e9
            val peak = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }.sumOf { it.peakUsage.used }
            out.println(String.format("Elapsed: %.1f s   Peak heap (sum of pool peaks): %d MB", secs, peak / (1024 * 1024)))
        }
        return if (sum.hasDifferences) 1 else 0
    } finally {
        try { w?.close() } catch (_: IOException) {}
    }
}

private fun cmdGen(a: List<String>, out: PrintStream): Int {
    var members = 0L
    var oldP: String? = null
    var newP: String? = null
    var seed = 1L
    var lines = false
    var sets = 1
    var kind = "834"
    var claimsN = 0L
    var dirP: String? = null
    var setSize = 40
    var i = 0
    while (i < a.size) {
        val x = a[i]
        fun value(): String {
            if (i + 1 >= a.size) throw X12Exception("Option $x needs a value")
            return a[++i]
        }
        when (x) {
            "--members" -> members = value().toLongOrNull() ?: throw X12Exception("--members needs a number")
            "--old" -> oldP = value()
            "--new" -> newP = value()
            "--seed" -> seed = value().toLongOrNull() ?: throw X12Exception("--seed needs a number")
            "--sets" -> sets = value().toIntOrNull() ?: throw X12Exception("--sets needs a number")
            "--newlines" -> lines = true
            "--kind" -> kind = value()
            "--claims" -> claimsN = value().toLongOrNull() ?: throw X12Exception("--claims needs a number")
            "--dir" -> dirP = value()
            "--set-size" -> setSize = value().toIntOrNull() ?: throw X12Exception("--set-size needs a number")
            else -> throw X12Exception("Unknown option '$x'")
        }
        i++
    }
    if (kind == "claims") {
        if (claimsN < 1 || dirP == null) throw X12Exception("Usage: x12diff gen --kind claims --claims N --dir DIR [--seed S] [--newlines] [--set-size K]")
        val r = GenClaims.generate(claimsN, Paths.get(dirP), seed, lines, setSize)
        out.println("Wrote synthetic claims.837, remits_1.835, remits_2.835, ack.999, status.277 to $dirP (fake data only)")
        out.println("Expected recon: PAID ${fmt(r.paid)}, PARTIAL ${fmt(r.partial)}, DENIED ${fmt(r.denied)}, REVERSED ${fmt(r.reversed)}, ADJUSTED ${fmt(r.adjusted)}, UNMATCHED ${fmt(r.unmatched)}, REJECTED ${fmt(r.rejected)} (${fmt(r.rejectedBySet)} by 999, ${fmt(r.rejectedBy277)} by 277CA), ORPHAN ${fmt(r.orphans)}")
        out.println("Expected remit: ${fmt(r.remitEntries)} claim entries on ${fmt(r.remitChecks)} checks")
        return 0
    }
    if (kind != "834") throw X12Exception("--kind must be 834 or claims")
    if (members < 1 || oldP == null || newP == null) throw X12Exception("Usage: x12diff gen --members N --old OLD --new NEW [--seed S] [--newlines] [--sets K]")
    val r = Gen.generate(members, Paths.get(oldP), Paths.get(newP), seed, lines, sets)
    out.println("Wrote synthetic files (fake data only):")
    out.println("  $oldP  members: ${fmt(r.oldMembers)}  size: ${fmt(Files.size(Paths.get(oldP)))} bytes")
    out.println("  $newP  members: ${fmt(r.newMembers)}  size: ${fmt(Files.size(Paths.get(newP)))} bytes")
    out.println("Expected diff: added ${fmt(r.added)}, termed ${fmt(r.removed + r.termFlagged)} (${fmt(r.removed)} absent + ${fmt(r.termFlagged)} flagged), changed ${fmt(r.changed)}")
    return 0
}
