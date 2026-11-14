package tools.datawharf.x12diff

import java.io.BufferedWriter
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Path parsing that reports bad input as a clean one-line error. */
fun safePath(s: String): Path = try {
    Paths.get(s)
} catch (e: InvalidPathException) {
    throw X12Exception("Invalid file name or path: '$s' (${e.reason ?: "illegal characters"})")
}

/** CSV cell encoding with spreadsheet formula-injection protection. */
object Csv {
    private val NUMBER = Regex("^[+-]?\\d+(\\.\\d+)?$")

    /**
     * Cells starting with = @ TAB or CR get a leading apostrophe. Cells starting with + or - get one too,
     * unless the whole cell is a plain number (digits with an optional decimal part), so -12.50 stays a number.
     * Then RFC 4180 quoting is applied.
     */
    fun cell(s0: String): String {
        var v = fixUtf8(s0)
        if (v.isNotEmpty()) {
            val c = v[0]
            if (c == '=' || c == '@' || c == '\t' || c == '\r' || ((c == '+' || c == '-') && !NUMBER.matches(v))) {
                v = "'$v"
            }
        }
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    fun line(cells: List<String>): String = cells.joinToString(",") { cell(it) } + "\r\n"

    /**
     * The reader maps bytes 1:1 to characters (ISO-8859-1). If the bytes of a value are valid UTF-8, decode them
     * as UTF-8 so names like MUNOZ with a tilde survive; otherwise keep the Latin-1 reading.
     */
    fun fixUtf8(s: String): String {
        var high = false
        for (ch in s) {
            if (ch.code > 0xFF) return s
            if (ch.code >= 0x80) high = true
        }
        if (!high) return s
        return try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(s.toByteArray(StandardCharsets.ISO_8859_1))).toString()
        } catch (e: CharacterCodingException) {
            s
        }
    }

    /** Opens a UTF-8 CSV writer with a byte-order mark so Excel reads accented characters correctly. */
    fun open(path: String): BufferedWriter {
        val p = safePath(path)
        try {
            val w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)
            w.write("﻿")
            return w
        } catch (e: NoSuchFileException) {
            throw X12Exception("Cannot write '$path': the folder does not exist")
        } catch (e: IOException) {
            throw X12Exception("Cannot write '$path': ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

/** Amounts as integer cents. */
object Money {
    fun parse(s: String): Long? {
        val t = s.trim()
        if (t.isEmpty()) return null
        var i = 0
        var neg = false
        if (t[0] == '-' || t[0] == '+') { neg = t[0] == '-'; i = 1 }
        if (i >= t.length) return null
        var whole = 0L
        var digits = 0
        while (i < t.length && t[i].isDigit()) {
            whole = whole * 10 + (t[i] - '0'); i++; digits++
            if (digits > 15) return null
        }
        var frac = 0L
        var fd = 0
        if (i < t.length && t[i] == '.') {
            i++
            while (i < t.length && t[i].isDigit()) {
                if (fd < 2) { frac = frac * 10 + (t[i] - '0'); fd++ } else if (t[i] != '0') return null
                i++
            }
        }
        if (i != t.length || (digits == 0 && fd == 0)) return null
        while (fd < 2) { frac *= 10; fd++ }
        val v = whole * 100 + frac
        return if (neg) -v else v
    }

    fun fmt(c: Long): String {
        val a = Math.abs(c)
        return (if (c < 0) "-" else "") + (a / 100) + "." + (a % 100).toString().padStart(2, '0')
    }
}

object Dates {
    /** Epoch day for a CCYYMMDD string (or the first date of a CCYYMMDD-CCYYMMDD range), or null. */
    fun days(s0: String): Long? {
        val s = if (s0.length > 8) s0.take(8) else s0
        if (s.length != 8 || !s.all { it.isDigit() }) return null
        return try { LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE).toEpochDay() } catch (e: Exception) { null }
    }

    fun today(): String = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
}

/**
 * Expands command-line inputs: directories (their X12 files), and * ? wildcards in the file name.
 * Non-X12 files found in folders are skipped and named in [skipped].
 */
fun expandInputs(args: List<String>, skipped: MutableList<String>? = null): List<Path> {
    val out = ArrayList<Path>()
    for (a in args) {
        val cut = a.lastIndexOfAny(charArrayOf('\\', '/'))
        val namePart = if (cut >= 0) a.substring(cut + 1) else a
        if (namePart.contains('*') || namePart.contains('?')) {
            val dirText = if (cut < 0) "." else if (cut == 0) a.substring(0, 1) else a.substring(0, cut)
            val dir = safePath(dirText)
            if (!Files.isDirectory(dir)) throw X12Exception("Folder not found for pattern '$a': $dirText")
            val rx = Regex(namePart.map { if (it == '*') ".*" else if (it == '?') "." else Regex.escape(it.toString()) }.joinToString(""), RegexOption.IGNORE_CASE)
            val found = Files.list(dir).use { st -> st.filter { Files.isRegularFile(it) && rx.matches(it.fileName.toString()) }.sorted().toList() }
            if (found.isEmpty()) throw X12Exception("No files match: $a")
            out.addAll(found)
            continue
        }
        val p = safePath(a)
        when {
            Files.isDirectory(p) -> Files.list(p).use { st ->
                st.filter { Files.isRegularFile(it) }.sorted().forEach {
                    if (looksLikeX12(it)) out.add(it) else skipped?.add(it.fileName.toString())
                }
            }
            Files.isRegularFile(p) -> out.add(p)
            else -> throw X12Exception("File not found: $a")
        }
    }
    if (out.isEmpty()) throw X12Exception("No X12 input files found")
    return out
}

/** True when the file starts with ISA (after an optional BOM and whitespace). Used to skip non-X12 files in folders. */
fun looksLikeX12(p: Path): Boolean = try {
    Files.newInputStream(p).use { ins ->
        val b = ins.readNBytes(16).map { it.toInt() and 0xFF }.filter { it != 0xEF && it != 0xBB && it != 0xBF && it != 32 && it != 10 && it != 13 && it != 9 }
        b.size >= 3 && b[0] == 'I'.code && b[1] == 'S'.code && b[2] == 'A'.code
    }
} catch (e: IOException) { false }

/** Walks the transaction sets in a file and hands each wanted set's segments to a handler. */
interface TxHandler {
    fun begin(type: String, st: Segment, gs: Segment?, d: Delimiters, file: String)
    fun segment(s: Segment)
    fun end(complete: Boolean)
}

object TxWalker {
    /**
     * Returns the count of transaction sets seen per ST01 type (all types, wanted or not).
     * If [env] is given, every segment is also fed to it and it is finished at end of file.
     */
    fun walk(path: Path, handlers: Map<String, TxHandler>, env: EnvelopeChecker? = null): Map<String, Int> {
        val seen = java.util.TreeMap<String, Int>()
        var open: TxHandler? = null
        var gs: Segment? = null
        val file = path.fileName.toString()
        try {
            Files.newInputStream(path).use { ins ->
                val r = X12Reader(ins)
                while (true) {
                    val s = r.next() ?: break
                    env?.accept(s)
                    when (s.id) {
                        "ST" -> {
                            open?.end(false); open = null
                            val t = s.el(1)
                            seen.merge(t, 1, Int::plus)
                            val h = handlers[t]
                            if (h != null) { open = h; h.begin(t, s, gs, r.delimiters!!, file) }
                        }
                        "SE" -> { open?.end(true); open = null }
                        "GS" -> { open?.end(false); open = null; gs = s }
                        "GE", "IEA", "ISA" -> { open?.end(false); open = null }
                        else -> open?.segment(s)
                    }
                }
                open?.end(false)
                env?.finish()
            }
        } catch (e: X12Exception) {
            throw X12Exception("${path.fileName}: ${e.message}")
        } catch (e: java.nio.file.NoSuchFileException) {
            throw X12Exception("File not found: $path")
        } catch (e: IOException) {
            throw X12Exception("Cannot read '${path.fileName}': ${e.message ?: e.javaClass.simpleName}")
        }
        return seen
    }
}

/** Collects envelope problems (missing SE/GE/IEA, count and control-number mismatches) across input files. */
class EnvelopeLog {
    val items = ArrayList<String>()
    var total = 0

    fun add(file: String, c: EnvelopeChecker) {
        for (w in c.warnings) { total++; if (items.size < 100) items.add("$file: $w") }
        total += c.suppressed
    }

    val any: Boolean get() = total > 0
}
