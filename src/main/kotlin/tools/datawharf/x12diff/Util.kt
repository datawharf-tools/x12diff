package tools.datawharf.x12diff

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** CSV cell encoding with spreadsheet formula-injection protection. */
object Csv {
    private val NUMBER = Regex("^[+-]?\\d+(\\.\\d+)?$")

    /**
     * Cells starting with = @ TAB or CR get a leading apostrophe. Cells starting with + or - get one too,
     * unless the whole cell is a plain number (digits with an optional decimal part), so -12.50 stays a number.
     * Then RFC 4180 quoting is applied.
     */
    fun cell(s: String): String {
        var v = s
        if (v.isNotEmpty()) {
            val c = v[0]
            if (c == '=' || c == '@' || c == '\t' || c == '\r' || ((c == '+' || c == '-') && !NUMBER.matches(v))) {
                v = "'$v"
            }
        }
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    fun line(cells: List<String>): String = cells.joinToString(",") { cell(it) } + "\r\n"
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
    /** Epoch day for a CCYYMMDD string, or null. */
    fun days(s: String): Long? {
        if (s.length != 8 || !s.all { it.isDigit() }) return null
        return try { LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE).toEpochDay() } catch (e: Exception) { null }
    }

    fun today(): String = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
}

/** Expands command-line inputs: directories (their regular files) and simple * ? wildcards in the file name. */
fun expandInputs(args: List<String>): List<Path> {
    val out = ArrayList<Path>()
    for (a in args) {
        val p = Paths.get(a)
        val name = p.fileName?.toString() ?: a
        when {
            Files.isDirectory(p) -> Files.list(p).use { st -> st.filter { Files.isRegularFile(it) && looksLikeX12(it) }.sorted().forEach { out.add(it) } }
            name.contains('*') || name.contains('?') -> {
                val dir = p.parent ?: Paths.get(".")
                if (!Files.isDirectory(dir)) throw X12Exception("Folder not found for pattern: $a")
                val rx = Regex(name.map { if (it == '*') ".*" else if (it == '?') "." else Regex.escape(it.toString()) }.joinToString(""), RegexOption.IGNORE_CASE)
                val found = Files.list(dir).use { st -> st.filter { Files.isRegularFile(it) && rx.matches(it.fileName.toString()) }.sorted().toList() }
                if (found.isEmpty()) throw X12Exception("No files match: $a")
                out.addAll(found)
            }
            Files.isRegularFile(p) -> out.add(p)
            else -> throw X12Exception("File not found: $a")
        }
    }
    if (out.isEmpty()) throw X12Exception("No input files")
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
    /** Returns the count of transaction sets seen per ST01 type (all types, wanted or not). */
    fun walk(path: Path, handlers: Map<String, TxHandler>): Map<String, Int> {
        val seen = java.util.TreeMap<String, Int>()
        var open: TxHandler? = null
        var gs: Segment? = null
        val file = path.fileName.toString()
        try {
            Files.newInputStream(path).use { ins ->
                val r = X12Reader(ins)
                while (true) {
                    val s = r.next() ?: break
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
            }
        } catch (e: X12Exception) {
            throw X12Exception("${path.fileName}: ${e.message}")
        }
        return seen
    }
}

fun ioMessage(e: IOException): String = e.message ?: e.toString()
