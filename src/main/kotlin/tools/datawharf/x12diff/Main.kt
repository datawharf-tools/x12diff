package tools.datawharf.x12diff

import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import kotlin.system.exitProcess

const val VERSION = "0.2.4"

private val USAGE = """
x12diff $VERSION - offline X12 inspector, 834 member diff, 835 remit and claim reconciliation (Datawharf Tools)

Usage:
  x12diff parse FILE [--allow-incomplete]
  x12diff diff OLD NEW [--csv out.csv] [--profile p.properties] [--mode full|change] [--show N] [--stats] [--allow-incomplete]
  x12diff remit FILE_OR_DIR... [--csv lines.csv] [--rollup adjustments.csv] [--profile p.properties] [--show N] [--allow-incomplete]
  x12diff recon FILE_OR_DIR... [--csv claims.csv] [--profile p.properties] [--asof YYYYMMDD] [--show N] [--stats] [--allow-incomplete]
  x12diff gen --members N --old OLD --new NEW [--seed S] [--newlines] [--sets K]
  x12diff gen --kind claims --claims N --dir DIR [--seed S] [--newlines] [--set-size K] [--reject-set N]...
  x12diff profile            print the built-in profile (redirect to a file to customise)
  x12diff --version | --help

Exit codes: 0 ok (diff: no differences), 1 diff found differences, 2 error,
3 input incomplete or inconsistent (missing SE/GE/IEA, count or control-number mismatch; use --allow-incomplete to accept).
Runs entirely on your machine; makes no network connections.
""".trimIndent()

internal fun fmt(n: Long) = String.format("%,d", n)

private fun show(c: Char): String = when (c) {
    '\n' -> "\\n (LF)"
    '\r' -> "\\r (CR)"
    '\t' -> "\\t"
    else -> "'$c'"
}

/**
 * Console output must show non-ASCII file names readably. On an interactive console use the console's own code
 * page; when output is redirected (pipe or file) write UTF-8.
 */
private fun textStream(fd: java.io.FileDescriptor): PrintStream {
    val cs = try { System.console()?.charset() } catch (e: Throwable) { null } ?: java.nio.charset.StandardCharsets.UTF_8
    return PrintStream(java.io.FileOutputStream(fd), true, cs)
}

fun main(args: Array<String>) {
    val out = textStream(java.io.FileDescriptor.out)
    val err = textStream(java.io.FileDescriptor.err)
    val rc = run(args, out, err)
    out.flush(); err.flush()
    exitProcess(rc)
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
            "diff" -> cmdDiff(args.drop(1), out, err)
            "remit" -> cmdRemit(args.drop(1), out, err)
            "recon" -> cmdRecon(args.drop(1), out, err)
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
        err.println("error: cannot read or write file: ${ioReason(e)}")
        return 2
    } catch (e: OutOfMemoryError) {
        err.println("error: out of memory; try a larger heap, e.g. java -Xmx2g -jar x12diff.jar ...")
        return 2
    } catch (e: RuntimeException) {
        err.println("error: internal error: ${e.message ?: e.javaClass.simpleName}")
        return 2
    }
}

private fun cmdParse(a0: List<String>, out: PrintStream): Int {
    val allow = "--allow-incomplete" in a0
    val a = a0.filter { it != "--allow-incomplete" }
    if (a.size != 1 || a[0].startsWith("--")) throw X12Exception("Usage: x12diff parse FILE [--allow-incomplete]")
    val path = safePath(a[0])
    if (Files.isDirectory(path)) throw X12Exception("'${a[0]}' is a folder; parse takes one file (remit and recon accept folders)")
    if (!Files.isRegularFile(path)) throw X12Exception("File not found: ${a[0]}")
    val checker = EnvelopeChecker()
    var segs = 0L
    val delims = ArrayList<Delimiters>()
    Files.newInputStream(path).use { ins ->
        val r = X12Reader(ins)
        while (true) {
            val s = r.next() ?: break
            if (s.id == "ISA") r.delimiters?.let { if (delims.isEmpty() || delims.last() != it) delims.add(it) }
            checker.accept(s)
            segs++
        }
    }
    checker.finish()
    if (segs == 0L) checker.warnings.add("The file is empty or contains no X12 segments")
    out.println("File: ${path.fileName} (${fmt(Files.size(path))} bytes)")
    for ((i, d) in delims.withIndex()) {
        out.println(
            "Delimiters" + (if (delims.size > 1) " (from interchange ${i + 1})" else "") +
                ": element ${show(d.element)}  sub-element ${show(d.sub)}  repetition " +
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
    // Like diff, remit and recon: an empty, truncated or inconsistent file exits with 3 unless --allow-incomplete is given.
    return if ((w.isNotEmpty() || checker.suppressed > 0) && !allow) 3 else 0
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
    val rejectSets = ArrayList<Int>()
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
            "--reject-set" -> rejectSets.add(value().toIntOrNull() ?: throw X12Exception("--reject-set needs a set number (1 is the first 837 set)"))
            "--set-size" -> setSize = value().toIntOrNull() ?: throw X12Exception("--set-size needs a number")
            else -> throw X12Exception("Unknown option '$x'")
        }
        i++
    }
    if (kind == "claims") {
        if (claimsN < 1 || dirP == null) throw X12Exception("Usage: x12diff gen --kind claims --claims N --dir DIR [--seed S] [--newlines] [--set-size K] [--reject-set N]...")
        val r = GenClaims.generate(claimsN, safePath(dirP), seed, lines, setSize, rejectSets = rejectSets.toSet().takeIf { rejectSets.isNotEmpty() })
        out.println("Wrote synthetic claims.837, remits_1.835, remits_2.835, ack.999, status.277 to $dirP (fake data only)")
        out.println(
            "Expected recon: PAID ${fmt(r.paid)}, PARTIAL ${fmt(r.partial)}, DENIED ${fmt(r.denied)}, PATIENT_RESP ${fmt(r.patientResp)}, " +
                "REVERSED ${fmt(r.reversed)}, ADJUSTED ${fmt(r.adjusted)}, UNMATCHED ${fmt(r.unmatched)}, " +
                "REJECTED ${fmt(r.rejected)} (${fmt(r.rejectedBySet)} by 999, ${fmt(r.rejectedBy277)} by 277CA), ORPHAN ${fmt(r.orphans)}"
        )
        out.println("Expected remit: ${fmt(r.remitEntries)} claim entries on ${fmt(r.remitChecks)} checks")
        return 0
    }
    if (kind != "834") throw X12Exception("--kind must be 834 or claims")
    if (members < 1 || oldP == null || newP == null) throw X12Exception("Usage: x12diff gen --members N --old OLD --new NEW [--seed S] [--newlines] [--sets K]")
    requireDistinct("--old" to oldP, "--new" to newP)
    val op = safePath(oldP)
    val np = safePath(newP)
    val r = Gen.generate(members, op, np, seed, lines, sets)
    out.println("Wrote synthetic files (fake data only):")
    out.println("  $oldP  members: ${fmt(r.oldMembers)}  size: ${fmt(Files.size(op))} bytes")
    out.println("  $newP  members: ${fmt(r.newMembers)}  size: ${fmt(Files.size(np))} bytes")
    out.println("Expected diff: added ${fmt(r.added)}, termed ${fmt(r.removed + r.termFlagged)} (${fmt(r.removed)} absent + ${fmt(r.termFlagged)} flagged), changed ${fmt(r.changed)}")
    return 0
}
