package tools.datawharf.x12diff

import java.io.InputStream

/** A user-facing error (bad input, bad profile, bad arguments). Printed without a stack trace. */
class X12Exception(message: String) : Exception(message)

data class Delimiters(val element: Char, val sub: Char, val repetition: Char?, val terminator: Char) {
    val newlineTerminated: Boolean get() = terminator == '\n' || terminator == '\r'
}

/** One segment. [elements] excludes the segment ID; element 01 is index 0. */
class Segment(val id: String, val elements: List<String>, val offset: Long, val sub: Char = ':') {
    fun el(pos: Int): String = if (pos >= 1 && pos <= elements.size) elements[pos - 1] else ""

    fun raw(sep: Char): String {
        var n = elements.size
        while (n > 0 && elements[n - 1].isEmpty()) n--
        val sb = StringBuilder(id)
        for (i in 0 until n) sb.append(sep).append(elements[i])
        return sb.toString()
    }
}

/**
 * Streaming X12 segment reader. Delimiters come from each ISA header (fixed positions),
 * so a file may contain several interchanges with different delimiters.
 * CR/LF and spaces between segments are ignored; if the segment terminator is itself
 * a CR or LF, either one ends a segment.
 */
class X12Reader(
    private val input: InputStream,
    initial: Delimiters? = null,
    startOffset: Long = 0,
    bufSize: Int = 1 shl 16,
) {
    private val buf = ByteArray(bufSize)
    private var pos = 0
    private var lim = 0
    private var consumed = startOffset
    private val firstOffset = startOffset
    private val sb = StringBuilder(64)

    var delimiters: Delimiters? = initial
        private set

    private fun read(): Int {
        if (pos >= lim) {
            consumed += lim
            pos = 0
            lim = input.read(buf, 0, buf.size)
            if (lim <= 0) {
                lim = 0
                return -1
            }
        }
        return buf[pos++].toInt() and 0xFF
    }

    private fun offset(): Long = consumed + pos

    /** Next segment, or null at end of input. Throws [X12Exception] on malformed input. */
    fun next(): Segment? {
        var c: Int
        while (true) {
            c = read()
            if (c == -1) return null
            if (c == 13 || c == 10 || c == 32 || c == 9) continue
            if ((c == 0xEF || c == 0xBB || c == 0xBF) && offset() <= firstOffset + 3) continue
            break
        }
        val start = offset() - 1
        val d = delimiters
        if (d == null) {
            val c1 = read()
            val c2 = read()
            if (c != 'I'.code || c1 != 'S'.code || c2 != 'A'.code) {
                throw X12Exception(
                    "Not an X12 file: expected an ISA segment at byte offset $start but found '" +
                        printable(c, c1, c2) + "'"
                )
            }
            return readIsa(start)
        }
        val parts = ArrayList<String>(16)
        sb.setLength(0)
        var ch = c
        while (true) {
            if (ch == -1) {
                if (sb.isEmpty() && parts.isEmpty()) return null
                break
            }
            val chc = ch.toChar()
            if (chc == d.terminator) break
            if (chc == '\r' || chc == '\n') {
                if (d.newlineTerminated) break
                ch = read()
                continue
            }
            if (chc == d.element) {
                parts.add(sb.toString())
                sb.setLength(0)
            } else {
                sb.append(chc)
                if (parts.isEmpty() && sb.length == 3 && sb[0] == 'I' && sb[1] == 'S' && sb[2] == 'A') {
                    return readIsa(start)
                }
                if (sb.length > MAX_SEGMENT) {
                    throw X12Exception(
                        "Segment starting at byte offset $start is longer than $MAX_SEGMENT characters; " +
                            "the segment terminator may be wrong or the file is not X12"
                    )
                }
            }
            ch = read()
        }
        parts.add(sb.toString())
        val id = parts[0].trim()
        if (id.isEmpty() || id.length > 3) {
            throw X12Exception("Malformed segment at byte offset $start: missing or invalid segment ID '${id.take(12)}'")
        }
        return Segment(id, parts.subList(1, parts.size), start, d.sub)
    }

    private fun readIsa(start: Long): Segment {
        val raw = CharArray(106)
        raw[0] = 'I'; raw[1] = 'S'; raw[2] = 'A'
        for (i in 3 until 106) {
            val r = read()
            if (r == -1) {
                throw X12Exception(
                    "File ends inside the ISA header that starts at byte offset $start (an ISA header is 106 characters)"
                )
            }
            raw[i] = r.toChar()
        }
        val sep = raw[3]
        val sub = raw[104]
        val term = raw[105]
        if (sep.isLetterOrDigit() || sep == ' ' || sep == '\r' || sep == '\n') {
            throw X12Exception("Invalid element separator '${sep}' in the ISA header at byte offset $start")
        }
        if (term.isLetterOrDigit() || term == ' ' || term == sep) {
            throw X12Exception("Invalid segment terminator '${term}' in the ISA header at byte offset $start")
        }
        if (sub == sep || sub == term) {
            throw X12Exception("Sub-element separator '${sub}' clashes with another delimiter in the ISA header at byte offset $start")
        }
        val parts = String(raw, 0, 105).split(sep)
        if (parts.size != 17) {
            throw X12Exception(
                "ISA header at byte offset $start has ${parts.size - 1} elements instead of 16; " +
                    "check the element separator and the fixed-width ISA fields"
            )
        }
        val rawRep = raw[82]
        val rep = if (rawRep.isLetterOrDigit() || rawRep == ' ' || rawRep == sep || rawRep == sub) null else rawRep
        delimiters = Delimiters(sep, sub, rep, term)
        return Segment("ISA", parts.subList(1, parts.size), start, sub)
    }

    private fun printable(vararg cs: Int): String =
        cs.filter { it >= 0 }.joinToString("") { if (it in 32..126) it.toChar().toString() else "\\x%02X".format(it) }

    companion object {
        const val MAX_SEGMENT = 5_000_000
    }
}
