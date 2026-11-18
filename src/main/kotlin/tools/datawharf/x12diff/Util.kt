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

    /**
     * Opens a UTF-8 CSV writer with a byte-order mark so Excel reads accented characters correctly.
     * The data goes to a temporary file next to the target; [OutFile.commit] renames it into place and
     * [OutFile.abort] deletes it, so a failed run never leaves a half-written (or header-only) file.
     */
    fun open(path: String): OutFile {
        val target = safePath(path)
        if (Files.isDirectory(target)) throw X12Exception("Cannot write '$path': it is a folder, not a file name")
        val dir = target.toAbsolutePath().parent
        if (dir != null && !Files.isDirectory(dir)) throw X12Exception("Cannot write '$path': the folder does not exist")
        // Devices and pipes (NUL, /dev/null, /dev/stdout) cannot be replaced by a rename: write to them directly.
        if ((Files.exists(target) && !Files.isRegularFile(target)) || isWindowsDevice(target)) {
            try {
                val w = Files.newBufferedWriter(target, StandardCharsets.UTF_8)
                w.write("\uFEFF")
                return OutFile(path, target, null, w)
            } catch (e: IOException) {
                throw X12Exception("Cannot write '$path': ${ioReason(e)}")
            }
        }
        val tmp = (dir ?: target.toAbsolutePath()).resolve("." + target.fileName.toString() + ".x12diff-tmp")
        try {
            val w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)
            w.write("﻿")
            return OutFile(path, target, tmp, w)
        } catch (e: IOException) {
            throw X12Exception("Cannot write '$path': ${ioReason(e)}")
        }
    }
}

/** Human wording for an I/O failure (the raw message is often just a path). */
fun ioReason(e: IOException): String = when (e) {
    is java.nio.file.AccessDeniedException -> "access denied (read-only location, or the file is open in another program)"
    is NoSuchFileException -> "the folder does not exist"
    is java.nio.file.FileSystemException -> e.reason ?: e.message ?: e.javaClass.simpleName
    else -> e.message ?: e.javaClass.simpleName
}

/** An output file written via a temporary name; see [Csv.open]. */
class OutFile(private val label: String, private val target: Path, private val tmp: Path?, val w: BufferedWriter) {
    private var done = false

    fun commit() {
        if (done) return
        done = true
        try {
            w.close()
            if (tmp != null) {
                try {
                    Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } catch (e: IOException) {
                    // Windows 10 and older treat names like nul.csv as the NUL device, which a rename cannot
                    // replace: copy into the device instead. Windows 11 treats them as ordinary files.
                    if (!hasReservedBaseName(target) || Files.isRegularFile(target)) throw e
                    Files.newOutputStream(target).use { out -> Files.copy(tmp, out) }
                    Files.deleteIfExists(tmp)
                }
            }
        } catch (e: IOException) {
            try { if (tmp != null) Files.deleteIfExists(tmp) } catch (_: IOException) {}
            throw X12Exception("Cannot write '$label': ${ioReason(e)}")
        }
    }

    /** Closes and deletes the temporary file. Safe to call after [commit]. */
    fun abort() {
        if (done) return
        done = true
        try { w.close() } catch (_: IOException) {}
        try { if (tmp != null) Files.deleteIfExists(tmp) } catch (_: IOException) {}
    }
}

/** Fails when two outputs (or an output and an input) name the same file. */
fun requireDistinct(vararg named: Pair<String, String?>) {
    val seen = ArrayList<Triple<String, String, Path>>()
    for ((label, p) in named) {
        if (p == null) continue
        val path = safePath(p)
        for ((l2, p2, path2) in seen) {
            if (sameFile(path, path2)) throw X12Exception("$label and $l2 name the same file ('$p'${if (p != p2) " and '$p2'" else ""}); use different file names")
        }
        seen.add(Triple(label, p, path))
    }
}

private val RESERVED = Regex("NUL|CON|PRN|AUX|(COM|LPT)[1-9]")

/**
 * Exactly NUL, CON, PRN, AUX, COM1-9 or LPT1-9 (no extension; trailing dots and spaces ignored, as Windows does).
 * Files.exists() is false for these devices on Windows, so they are written directly. A name with an extension,
 * such as nul.csv, is an ordinary file on Windows 11 and keeps the temp-then-rename protection.
 */
internal fun isWindowsDevice(p: Path): Boolean {
    if (!WINDOWS) return false
    val n = (p.fileName?.toString() ?: return false).trimEnd('.', ' ').uppercase()
    return RESERVED.matches(n)
}

/** A reserved device name followed by an extension (nul.csv, Com1.txt): a device on Windows 10 and older. */
internal fun hasReservedBaseName(p: Path): Boolean {
    if (!WINDOWS) return false
    val n = (p.fileName?.toString() ?: return false).substringBefore('.').trim().uppercase()
    return RESERVED.matches(n)
}

private val WINDOWS = System.getProperty("os.name", "").startsWith("Windows")

/** Lookup keys for a path: its absolute normalized form and, when it exists, its real path (lower-cased on Windows). */
private fun pathKeys(p: Path): List<String> {
    val keys = ArrayList<String>(2)
    keys.add(p.toAbsolutePath().normalize().toString())
    try { if (Files.exists(p)) keys.add(p.toRealPath().toString()) } catch (_: IOException) {}
    return if (WINDOWS) keys.map { it.lowercase() } else keys
}

/**
 * For remit and recon: checks the outputs against each other and against the inputs, and refuses an input that is
 * named twice (for example as a file and again inside a folder). One pass over the inputs, so a folder of
 * thousands of files stays fast.
 */
fun checkOutputsAndInputs(outputs: List<Pair<String, String?>>, args: List<String>, files: List<Path>) {
    requireDistinct(*outputs.toTypedArray())
    val outKeys = HashMap<String, String>()
    for ((label, p) in outputs) if (p != null) for (k in pathKeys(safePath(p))) outKeys[k] = label
    val seen = HashMap<String, Path>()
    for (f in files) {
        val keys = pathKeys(f)
        for (k in keys) outKeys[k]?.let { throw X12Exception("$it and the input file '$f' name the same file; use a different output name") }
        val prior = seen[keys.last()]
        if (prior != null) {
            val folder = args.firstOrNull { a ->
                val ap = try { safePath(a) } catch (_: X12Exception) { null }
                ap != null && Files.isDirectory(ap) && pathKeys(ap).last() == pathKeys(f.toAbsolutePath().parent).last()
            }
            throw X12Exception("'$f' is given twice" + (if (folder != null) " (also inside folder '$folder')" else " (for example as a file and by a wildcard or folder)") + "; list each file once")
        }
        seen[keys.last()] = f
    }
}

private fun sameFile(a: Path, b: Path): Boolean {
    try { if (Files.exists(a) && Files.exists(b)) return Files.isSameFile(a, b) } catch (_: IOException) {}
    val x = a.toAbsolutePath().normalize().toString()
    val y = b.toAbsolutePath().normalize().toString()
    return if (System.getProperty("os.name", "").startsWith("Windows")) x.equals(y, ignoreCase = true) else x == y
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
     * [onInterchange] sees every ISA and TA1 segment (TA1 lives outside any transaction set).
     */
    fun walk(path: Path, handlers: Map<String, TxHandler>, env: EnvelopeChecker? = null, onInterchange: ((Segment) -> Unit)? = null): Map<String, Int> {
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
                    if (onInterchange != null && (s.id == "ISA" || s.id == "TA1")) onInterchange(s)
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
