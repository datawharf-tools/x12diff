package tools.datawharf.x12diff

import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path

class Row(
    val type: String,
    val key: String,
    val segment: String,
    val element: String,
    val label: String,
    val oldValue: String,
    val newValue: String,
    val detail: String,
)

class DiffSummary {
    var oldMembers = 0L
    var newMembers = 0L
    var added = 0L
    var termedAbsent = 0L
    var termedFlagged = 0L
    var changed = 0L
    var unchanged = 0L
    /** Members absent from the new file in change mode (not counted as terminated). */
    var absentNotTermed = 0L
    var duplicateKeys = 0L
    var emptyKeys = 0L
    /** Members whose rows are marked "ambiguous key". */
    var ambiguousMembers = 0L
    /** TERMED/ADDED pairs that look like one person re-keyed by a name change. */
    var possibleRenames = 0L
    var mode = "full"
    var oldKind = ""
    var newKind = ""
    var oldBgn = ""
    var newBgn = ""
    val notes = ArrayList<String>()
    val envelope = EnvelopeLog()
    val termed: Long get() = termedAbsent + termedFlagged
    val hasDifferences: Boolean get() = added + termed + changed > 0
}

private class Entry(val h1: Long, val h2: Long, val start: Long, val delim: Int) {
    var seen = false
    var dup = false
}

private class ScanInfo(val sets: Int, val bgn: Set<String>, val segments: Long)

object Differ {
    private val BOUNDARY = setOf("SE", "GE", "IEA", "ISA", "GS", "ST")

    /** Streams a file and reports each 834 member record. Also feeds an envelope checker. */
    private fun scan(
        path: Path, profile: Profile, env: EnvelopeLog,
        onMember: (List<Segment>, Long, Delimiters) -> Unit,
    ): ScanInfo {
        var sets = 0
        var segCount = 0L
        val bgn = LinkedHashSet<String>()
        val chk = EnvelopeChecker()
        try {
            Files.newInputStream(path).use { ins ->
                val r = X12Reader(ins)
                var in834 = false
                var cur: ArrayList<Segment>? = null
                var curStart = 0L
                var curDelim: Delimiters? = null
                var curBgn: String? = null
                fun flush() {
                    val c = cur
                    if (c != null) onMember(c, curStart, curDelim!!)
                    cur = null
                }
                fun endSet() {
                    if (in834) bgn.add(curBgn ?: "")
                    in834 = false
                    curBgn = null
                }
                while (true) {
                    val s = r.next() ?: break
                    segCount++
                    chk.accept(s)
                    when (s.id) {
                        "ST" -> {
                            flush(); endSet()
                            in834 = s.el(1) == "834"
                            if (in834) sets++
                        }
                        "SE", "GE", "IEA", "ISA", "GS" -> { flush(); endSet() }
                        "BGN" -> if (in834 && curBgn == null) curBgn = s.el(8)
                        else -> if (in834) {
                            if (s.id == profile.memberStart) {
                                flush()
                                cur = arrayListOf(s)
                                curStart = s.offset
                                curDelim = r.delimiters
                            } else {
                                cur?.add(s)
                            }
                        }
                    }
                }
                flush(); endSet()
                chk.finish()
            }
        } catch (e: X12Exception) {
            throw X12Exception("${path.fileName}: ${e.message}")
        } catch (e: java.nio.file.NoSuchFileException) {
            throw X12Exception("File not found: $path")
        } catch (e: java.io.IOException) {
            throw X12Exception("Cannot read '${path.fileName}': ${e.message ?: e.javaClass.simpleName}")
        }
        env.add(path.fileName.toString(), chk)
        return ScanInfo(sets, bgn, segCount)
    }

    private fun kindOf(values: Set<String>, profile: Profile): String {
        if (values.isEmpty()) return "unknown"
        val full = profile.list("mode.full.codes", listOf("4", "RX")).toSet()
        val change = profile.list("mode.change.codes", listOf("2")).toSet()
        val kinds = values.map { if (it in full) "full" else if (it in change) "change" else "unknown" }.toSet()
        return if (kinds.size == 1) kinds.first() else "mixed"
    }

    fun diff(oldPath: Path, newPath: Path, profile: Profile, modeOverride: String? = null, sink: (Row) -> Unit): DiffSummary {
        val sum = DiffSummary()
        val index = HashMap<String, Entry>(1 shl 16)
        val delims = ArrayList<Delimiters>()
        val h = LongArray(2)

        val oldInfo = scan(oldPath, profile, sum.envelope) { segs, start, d ->
            sum.oldMembers++
            val key = profile.keyOf(segs)
            if (key.all { it == '|' }) sum.emptyKeys++
            var k = key
            var n = 1
            while (index.containsKey(k)) {
                index[k]!!.dup = true
                n++
                k = "$key#$n"
            }
            if (n == 2) sum.duplicateKeys++
            var di = delims.indexOf(d)
            if (di < 0) {
                delims.add(d)
                di = delims.size - 1
            }
            profile.hash(segs, h)
            index[k] = Entry(h[0], h[1], start, di).also { if (n > 1) it.dup = true }
        }
        // A file with no segments at all is reported as 0 members (exit 3), like parse does; any other file without an 834 is an error.
        if (oldInfo.sets == 0 && oldInfo.segments > 0) throw X12Exception("No 834 transaction set (ST*834) found in '$oldPath'")

        // The new file is scanned next; the mode must be known before absent members are reported, so the
        // BGN08 of the new file is only known afterwards. Absent members are reported after the scan anyway.
        val gone = ArrayList<Pair<String, Entry>>()
        val addedBuf = ArrayList<Row>()
        var bufFull = false
        fun addedRow(r: Row) {
            if (!bufFull && addedBuf.size < 100_000) { addedBuf.add(r); return }
            if (!bufFull) { bufFull = true; addedBuf.forEach(sink); addedBuf.clear() }
            sink(r)
        }
        var newInfo: ScanInfo? = null
        RandomAccessFile(oldPath.toFile(), "r").use { raf ->
            fun readOld(e: Entry): List<Segment> {
                raf.seek(e.start)
                val r = X12Reader(Channels.newInputStream(raf.channel), delims[e.delim], e.start, 8192)
                val list = ArrayList<Segment>()
                list.add(r.next() ?: return list)
                while (true) {
                    val s = r.next() ?: break
                    if (s.id == profile.memberStart || s.id in BOUNDARY) break
                    list.add(s)
                }
                return list
            }

            newInfo = scan(newPath, profile, sum.envelope) { segs, _, _ ->
                sum.newMembers++
                val key = profile.keyOf(segs)
                var k = key
                var e = index[k]
                var n = 1
                while (e != null && e.seen) {
                    n++
                    k = "$key#$n"
                    e = index[k]
                }
                val ambiguous = n > 1 || (e != null && e.dup)
                val amb = if (ambiguous) "ambiguous key (duplicate member key); " else ""
                if (ambiguous) sum.ambiguousMembers++
                if (e == null) {
                    // A member that exists only in the new file but carries a termination flag is a termination.
                    val flag = profile.termReason(segs)
                    if (flag != null) {
                        sum.termedFlagged++
                        sink(Row("TERMED", k, "", "", "", "", "", amb + "termination in new file; not in old file ($flag)"))
                    } else {
                        sum.added++
                        addedRow(Row("ADDED", k, "", "", "", "", "", amb + "not present in old file"))
                    }
                    return@scan
                }
                e.seen = true
                val h2 = LongArray(2)
                profile.hash(segs, h2)
                if (e.h1 == h2[0] && e.h2 == h2[1]) {
                    sum.unchanged++
                    return@scan
                }
                val oldSegs = readOld(e)
                val flagNew = profile.termReason(segs)
                val flagOld = profile.termReason(oldSegs)
                val type: String
                val detail: String
                if (flagNew != null && flagOld == null) {
                    sum.termedFlagged++
                    type = "TERMED"
                    detail = amb + "termination flag: $flagNew"
                } else {
                    sum.changed++
                    type = "CHANGED"
                    detail = amb.removeSuffix("; ")
                }
                val rows = fieldDiff(type, k, detail, oldSegs, segs, profile, delims[e.delim])
                if (rows.isEmpty()) sink(Row(type, k, "", "", "", "", "", listOf(detail, "content differs (segment order changed)").filter { it.isNotEmpty() }.joinToString("; ")))
                else rows.forEach(sink)
            }
            if (newInfo!!.sets == 0 && newInfo!!.segments > 0) throw X12Exception("No 834 transaction set (ST*834) found in '$newPath'")
            for ((k, v) in index) if (!v.seen) gone.add(k to v)
        }

        // ---- mode ----
        val ni = newInfo!!
        sum.oldKind = kindOf(oldInfo.bgn, profile)
        sum.newKind = kindOf(ni.bgn, profile)
        sum.oldBgn = oldInfo.bgn.joinToString(",") { it.ifEmpty { "(none)" } }
        sum.newBgn = ni.bgn.joinToString(",") { it.ifEmpty { "(none)" } }
        val mode: String
        when {
            modeOverride != null -> {
                mode = modeOverride
                sum.notes.add("Mode $mode set by --mode (OLD BGN08 ${sum.oldBgn} = ${sum.oldKind}, NEW BGN08 ${sum.newBgn} = ${sum.newKind}).")
            }
            sum.oldKind == "change" || sum.newKind == "change" -> {
                mode = "change"
                sum.notes.add("Mode change: OLD BGN08 ${sum.oldBgn} = ${sum.oldKind}, NEW BGN08 ${sum.newBgn} = ${sum.newKind}. " +
                    "Members absent from the new file are reported as NOT_IN_NEW, not TERMED.")
            }
            sum.oldKind == "full" && sum.newKind == "full" -> mode = "full"
            else -> {
                mode = "full"
                sum.notes.add("WARNING: BGN08 is unknown or mixed (OLD ${sum.oldBgn} = ${sum.oldKind}, NEW ${sum.newBgn} = ${sum.newKind}). " +
                    "Treated as full files: members absent from the new file are reported as TERMED. " +
                    "If these are change files, rerun with --mode change.")
            }
        }
        sum.mode = mode

        gone.sortBy { it.second.start }
        val goneRows = ArrayList<Row>()
        for ((k, e) in gone) {
            val amb = if (e.dup) "ambiguous key (duplicate member key); " else ""
            if (e.dup) sum.ambiguousMembers++
            if (mode == "change") {
                sum.absentNotTermed++
                goneRows.add(Row("NOT_IN_NEW", k, "", "", "", "", "", amb + "not in new file (change file: absence does not mean terminated)"))
            } else {
                sum.termedAbsent++
                goneRows.add(Row("TERMED", k, "", "", "", "", "", amb + "not present in new file"))
            }
        }
        // Pair one ADDED and one TERMED row with the same subscriber, relationship and birth date: a name change re-keys a dependent.
        if (mode == "full" && !bufFull) {
            val addedBySig = HashMap<String, MutableList<Int>>()
            for ((i, r) in addedBuf.withIndex()) renameSig(r.key)?.let { addedBySig.getOrPut(it) { ArrayList() }.add(i) }
            val goneBySig = HashMap<String, MutableList<Int>>()
            for ((i, r) in goneRows.withIndex()) renameSig(r.key)?.let { goneBySig.getOrPut(it) { ArrayList() }.add(i) }
            for ((sig, ai) in addedBySig) {
                val gi = goneBySig[sig] ?: continue
                if (ai.size != 1 || gi.size != 1) continue
                val a = addedBuf[ai[0]]; val g = goneRows[gi[0]]
                addedBuf[ai[0]] = Row(a.type, a.key, a.segment, a.element, a.label, a.oldValue, a.newValue, a.detail + "; possible name change (same subscriber, relationship and birth date as TERMED ${g.key})")
                goneRows[gi[0]] = Row(g.type, g.key, g.segment, g.element, g.label, g.oldValue, g.newValue, g.detail + "; possible name change (same subscriber, relationship and birth date as ADDED ${a.key})")
                sum.possibleRenames++
            }
            if (sum.possibleRenames > 0) {
                sum.notes.add("WARNING: ${sum.possibleRenames} ADDED/TERMED pair(s) look like a name change (same subscriber, relationship and birth date); " +
                    "the rows say 'possible name change'. Check them before acting on the terminations.")
            }
        } else if (bufFull) {
            sum.notes.add("WARNING: more than 100,000 ADDED members; possible name changes were not paired, so check ADDED/TERMED pairs yourself.")
        }
        addedBuf.forEach(sink)
        goneRows.forEach(sink)
        return sum
    }

    /** subscriber|relationship|birth date from a key built with the default name + birth date fallback, else null. */
    private fun renameSig(key: String): String? {
        val p = key.replace(Regex("#\\d+$"), "").split('|')
        if (p.size < 3 || !p[1].contains('/')) return null
        val dob = p[1].substringAfterLast('/')
        if (dob.isEmpty()) return null
        return p[0] + "|" + p[2] + "|" + dob
    }

    private class Keyed(val sig: String, val seg: Segment)

    private fun keyedSegments(segs: List<Segment>, profile: Profile): List<Keyed> {
        val occ = HashMap<String, Int>()
        val out = ArrayList<Keyed>(segs.size)
        for (s in segs) {
            if (profile.segmentIgnored(s)) continue
            val qpos = profile.align[s.id]
            val base = if (qpos != null) "${s.id}*${s.el(qpos)}" else s.id
            val n = occ.merge(base, 1, Int::plus)!!
            out.add(Keyed(if (n > 1) "$base#$n" else base, s))
        }
        return out
    }

    /** Element value with the file's sub-element separator replaced by ':' so files with different separators compare equal. */
    private fun norm(s: Segment, v: String): String = if (s.sub == ':' || v.indexOf(s.sub) < 0) v else v.replace(s.sub, ':')

    internal fun fieldDiff(
        type: String, key: String, detail: String,
        oldSegs: List<Segment>, newSegs: List<Segment>, profile: Profile, d: Delimiters,
    ): List<Row> {
        val rows = ArrayList<Row>()
        val oldMap = LinkedHashMap<String, Segment>()
        for (k in keyedSegments(oldSegs, profile)) oldMap[k.sig] = k.seg
        val matched = HashSet<String>()
        for (k in keyedSegments(newSegs, profile)) {
            val o = oldMap[k.sig]
            if (o == null) {
                rows.add(Row(type, key, k.sig, "", "", "", k.seg.raw(d.element), listOf("segment added", detail).filter { it.isNotEmpty() }.joinToString("; ")))
                continue
            }
            matched.add(k.sig)
            val n = k.seg
            val max = maxOf(o.elements.size, n.elements.size)
            for (p in 1..max) {
                if (profile.elementIgnored(n, p)) continue
                val ov = norm(o, o.el(p))
                val nv = norm(n, n.el(p))
                if (ov != nv) {
                    rows.add(Row(type, key, k.sig, n.id + p.toString().padStart(2, '0'), profile.label(n, p), ov, nv, detail))
                }
            }
        }
        for ((sig, o) in oldMap) {
            if (sig !in matched) rows.add(Row(type, key, sig, "", "", o.raw(d.element), "", listOf("segment removed", detail).filter { it.isNotEmpty() }.joinToString("; ")))
        }
        return rows
    }
}
