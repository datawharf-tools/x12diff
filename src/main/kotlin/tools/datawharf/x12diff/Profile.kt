package tools.datawharf.x12diff

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Points at a segment (optionally filtered by element 01) and an element position, e.g. `REF*0F:02`. */
class Locator(val seg: String, val qual: String?, val pos: Int) {
    fun matches(s: Segment): Boolean = s.id == seg && (qual == null || s.el(1) == qual)
    fun value(s: Segment): String = s.el(pos)

    override fun toString(): String = buildString {
        append(seg)
        if (qual != null) append('*').append(qual)
        if (pos > 0) append(':').append(pos.toString().padStart(2, '0'))
    }

    companion object {
        fun parse(text: String, context: String): Locator {
            val t = text.trim()
            val colon = t.indexOf(':')
            val head = if (colon >= 0) t.substring(0, colon) else t
            val posText = if (colon >= 0) t.substring(colon + 1).trim() else ""
            val pos = if (posText.isEmpty()) 0 else (posText.toIntOrNull()?.takeIf { it in 1..99 }
                ?: throw X12Exception("Profile: $context: bad element position in '$text' (use e.g. REF*0F:02)"))
            val star = head.indexOf('*')
            val seg = (if (star >= 0) head.substring(0, star) else head).trim()
            val qual = if (star >= 0) head.substring(star + 1).trim() else null
            if (seg.isEmpty() || seg.length > 3) throw X12Exception("Profile: $context: bad segment ID in '$text'")
            return Locator(seg, qual?.takeIf { it.isNotEmpty() }, pos)
        }
    }
}

class Profile(
    val memberStart: String,
    val keyParts: List<List<List<Locator>>>,
    val termCodes: Set<String>,
    val termCode: Locator?,
    val termDates: List<Locator>,
    val align: Map<String, Int>,
    val ignore: List<Locator>,
    val labels: Map<String, String>,
    val raw: Map<String, String> = emptyMap(),
) {
    fun list(name: String, default: List<String>): List<String> =
        if (raw.containsKey(name)) raw[name]!!.split(',').map { it.trim() }.filter { it.isNotEmpty() } else default

    fun value(name: String, default: String): String = raw[name]?.takeIf { it.isNotEmpty() } ?: default

    /** Label for an adjustment, keyed like CO-45 (group-reason); empty when the user supplied none. */
    fun adjLabel(group: String, reason: String): String = raw["adjlabel.$group-$reason"] ?: ""

    private val hasIgnore = ignore.isNotEmpty()

    fun segmentIgnored(s: Segment): Boolean = hasIgnore && ignore.any { it.pos == 0 && it.matches(s) }
    fun elementIgnored(s: Segment, pos: Int): Boolean = hasIgnore && ignore.any { it.pos == pos && it.matches(s) }

    /**
     * Member key: for each key part, the first alternative that has a value; parts are joined by '|'.
     * An alternative can combine several locators with '+', which are joined by '/'.
     */
    fun keyOf(segs: List<Segment>): String {
        val sb = StringBuilder()
        for ((i, alts) in keyParts.withIndex()) {
            if (i > 0) sb.append('|')
            var v = ""
            for (alt in alts) {
                val vals = alt.map { loc ->
                    var x = ""
                    for (s in segs) {
                        if (loc.matches(s)) {
                            val y = loc.value(s)
                            if (y.isNotEmpty()) { x = y; break }
                        }
                    }
                    x
                }
                if (vals.any { it.isNotEmpty() }) { v = vals.joinToString("/"); break }
            }
            sb.append(v)
        }
        return sb.toString()
    }

    /** 128-bit content hash of a member (ignored segments/elements and trailing empty elements excluded). */
    fun hash(segs: List<Segment>, out: LongArray) {
        var h1 = -0x340d631b7bdddcdbL // FNV-1a 64 offset
        var h2 = 0x2545F4914F6CDD1DL
        for (s in segs) {
            if (segmentIgnored(s)) continue
            for (ch in s.id) {
                h1 = (h1 xor ch.code.toLong()) * 0x100000001b3L
                h2 = (h2 + ch.code) * -0x61c8864680b583ebL
                h2 = h2 xor (h2 ushr 29)
            }
            var n = s.elements.size
            while (n > 0 && s.elements[n - 1].isEmpty()) n--
            for (i in 0 until n) {
                h1 = (h1 xor 0x1F) * 0x100000001b3L
                h2 = (h2 + 0x1F) * -0x61c8864680b583ebL
                if (hasIgnore && elementIgnored(s, i + 1)) continue
                for (ch0 in s.elements[i]) {
                    val ch = if (ch0 == s.sub) ':' else ch0
                    h1 = (h1 xor ch.code.toLong()) * 0x100000001b3L
                    h2 = (h2 + ch.code) * -0x61c8864680b583ebL
                    h2 = h2 xor (h2 ushr 29)
                }
            }
            h1 = (h1 xor 0x1E) * 0x100000001b3L
            h2 = (h2 + 0x1E) * -0x61c8864680b583ebL
        }
        out[0] = h1
        out[1] = h2
    }

    /** Non-null (with a short reason) when the member carries a termination flag. */
    fun termReason(segs: List<Segment>): String? {
        for (s in segs) {
            if (termCode != null && termCode.matches(s)) {
                val v = termCode.value(s)
                if (v in termCodes) return "$termCode=$v"
            }
            for (l in termDates) if (l.matches(s) && l.value(s).isNotEmpty()) return "$l present"
        }
        return null
    }

    fun label(s: Segment, pos: Int): String {
        val p = pos.toString().padStart(2, '0')
        return labels["${s.id}*${s.el(1)}:$p"] ?: labels["${s.id}:$p"] ?: ""
    }

    companion object {
        const val DEFAULT_TEXT: String = """# x12diff profile (plain text, UTF-8). Lines starting with # are comments.
# Everything here refers to segments by ID and elements by position only.
# Locator syntax:  SEG:NN  or  SEG*Q:NN  (SEG*Q means the segment whose element 01 equals Q).
# Labels are yours to define; x12diff ships no labels from any implementation guide.

# Member records start at this segment and run until the next one (or the end of the transaction set).
member.start = INS

# Member key: key parts are joined with '|'. Each part lists alternatives separated by '|';
# the first alternative with a value is used. Several locators joined by '+' are combined.
key.1 = REF*0F:02
# key.2: the member id if present, otherwise last name + first name + birth date joined with '+'.
key.2 = NM1*IL:09 | NM1*IL:03 + NM1*IL:04 + DMG:02
key.3 = INS:02

# File mode, read from BGN08 of each 834: values in mode.full.codes mean the file lists everyone (so a member
# missing from the new file is TERMED); values in mode.change.codes mean a change file (a missing member is just
# not changed, reported as NOT_IN_NEW). Override with --mode full|change on the command line.
mode.full.codes = 4, RX
mode.change.codes = 2

# A member counts as TERMED when it appears flagged in the new file but was not flagged in the old one.
# Flag = the value at term.code is one of term.codes, or any term.dates locator has a value.
term.code  = INS:03
term.codes = 024
term.dates = DTP*349:03

# Segments that repeat are aligned between files by this element (SEG:NN); otherwise by occurrence order.
align = REF:01, DTP:01, NM1:01, N1:01, AMT:01, HD:03

# Ignored from comparison. SEG or SEG*Q ignores the whole segment, SEG:NN one element.
# Example:  ignore = DTP*303, PER:04
ignore =

# Optional labels for CSV output: label.SEG:NN or label.SEG*Q:NN
label.INS:03 = action code
label.REF*0F:02 = subscriber id
label.REF*1L:02 = group id
label.NM1*IL:09 = member id
label.DTP*348:03 = start date
label.DTP*349:03 = end date
label.HD:03 = line code

# ---- remit / recon (values are codes only; descriptions are yours to add) ----
# Claim status values (CLP02 in an 835) that mark a reversal.
recon.reversal.codes = 22
# Claim status values that mark a denial. A claim with zero paid and one of these is DENIED.
recon.denied.codes = 4
# Zero paid with patient responsibility and no denial status is PATIENT_RESP, not DENIED.
# Adjustments (GROUP-REASON) that are the normal contractual write-off. A paid claim is PAID when the only
# adjustments are these and patient responsibility (PR); any other adjustment makes it PARTIAL.
recon.contractual = CO-45
# Claim date in the 837: the first match inside the claim for each locator, in this order, then the fallback
# (a segment seen before the first claim of the set).
recon.claim.date = DTP*472:03, DTP*434:03
recon.claim.date.fallback = BHT:04
# Acknowledgement status values (IK5/AK5 element 1, AK9 element 1) that count as rejected.
recon.ack.reject.codes = R, M, W, X
# Claim-status category values (first part of STC01 in a 277CA) that reject a claim, and those that clear an
# earlier rejection when they arrive later.
recon.status.reject.categories = A3, A4, A6, A7, A8
recon.status.accept.categories = A0, A1, A2
# Optional labels for the adjustment rollup, written as adjlabel.GROUP-REASON
# adjlabel.CO-45 = your label here
"""

        fun default(): Profile = parse(DEFAULT_TEXT, "built-in default")

        fun load(path: Path): Profile {
            val text = try {
                String(Files.readAllBytes(path), StandardCharsets.UTF_8)
            } catch (e: java.io.IOException) {
                throw X12Exception("Cannot read profile '$path': ${e.message}")
            }
            return parse(text, path.toString())
        }

        fun parse(text: String, source: String): Profile {
            val map = LinkedHashMap<String, String>()
            for ((n, line0) in text.lines().withIndex()) {
                val line = line0.trim().removePrefix("﻿")
                if (line.isEmpty() || line.startsWith("#")) continue
                val eq = line.indexOf('=')
                if (eq <= 0) throw X12Exception("Profile $source line ${n + 1}: expected 'name = value'")
                map[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
            }
            fun list(v: String?, sep: Char = ','): List<String> =
                v.orEmpty().split(sep).map { it.trim() }.filter { it.isNotEmpty() }

            val keyParts = map.keys.filter { it.startsWith("key.") }
                .sortedBy { it.removePrefix("key.").toIntOrNull() ?: throw X12Exception("Profile $source: bad key name '$it' (use key.1, key.2, ...)") }
                .map { k -> list(map[k], '|').map { alt -> alt.split('+').map { Locator.parse(it, k) } }.also { if (it.isEmpty() || it.any { a -> a.any { l -> l.pos == 0 } }) throw X12Exception("Profile $source: $k needs locators with element positions, e.g. REF*0F:02") } }
            if (keyParts.isEmpty()) throw X12Exception("Profile $source: at least one key.N line is required")
            val termCode = map["term.code"]?.takeIf { it.isNotEmpty() }?.let { Locator.parse(it, "term.code") }
            if (termCode != null && termCode.pos == 0) throw X12Exception("Profile $source: term.code needs an element position")
            val termDates = list(map["term.dates"]).map { Locator.parse(it, "term.dates") }
            if (termDates.any { it.pos == 0 }) throw X12Exception("Profile $source: term.dates entries need an element position")
            val align = LinkedHashMap<String, Int>()
            for (a in list(map["align"])) {
                val l = Locator.parse(a, "align")
                if (l.pos == 0) throw X12Exception("Profile $source: align entries need an element position (e.g. REF:01)")
                align[l.seg] = l.pos
            }
            val ignore = list(map["ignore"]).map { Locator.parse(it, "ignore") }
            val labels = HashMap<String, String>()
            for ((k, v) in map) if (k.startsWith("label.")) {
                val loc = Locator.parse(k.removePrefix("label."), k)
                if (loc.pos == 0) throw X12Exception("Profile $source: $k needs an element position")
                labels[(if (loc.qual != null) "${loc.seg}*${loc.qual}" else loc.seg) + ":" + loc.pos.toString().padStart(2, '0')] = v
            }
            val start = map["member.start"]?.trim().orEmpty().ifEmpty { "INS" }
            if (start.length > 3) throw X12Exception("Profile $source: bad member.start '$start'")
            return Profile(start, keyParts, list(map["term.codes"]).toSet(), termCode, termDates, align, ignore, labels, map)
        }
    }
}
