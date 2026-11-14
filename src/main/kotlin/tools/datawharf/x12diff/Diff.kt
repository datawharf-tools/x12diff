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
    var duplicateKeys = 0L
    var emptyKeys = 0L
    val termed: Long get() = termedAbsent + termedFlagged
    val hasDifferences: Boolean get() = added + termed + changed > 0
}

private class Entry(val h1: Long, val h2: Long, val start: Long, val delim: Int) {
    var seen = false
}

object Differ {
    private val BOUNDARY = setOf("SE", "GE", "IEA", "ISA", "GS", "ST")

    /** Streams a file and reports each 834 member record. Returns the number of 834 transaction sets seen. */
    private fun scan(path: Path, profile: Profile, onMember: (List<Segment>, Long, Delimiters) -> Unit): Int {
        var sets = 0
        Files.newInputStream(path).use { ins ->
            val r = X12Reader(ins)
            var in834 = false
            var cur: ArrayList<Segment>? = null
            var curStart = 0L
            var curDelim: Delimiters? = null
            fun flush() {
                val c = cur
                if (c != null) onMember(c, curStart, curDelim!!)
                cur = null
            }
            while (true) {
                val s = r.next() ?: break
                when (s.id) {
                    "ST" -> {
                        flush()
                        in834 = s.el(1) == "834"
                        if (in834) sets++
                    }
                    "SE", "GE", "IEA", "ISA", "GS" -> {
                        flush()
                        in834 = false
                    }
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
            flush()
        }
        return sets
    }

    fun diff(oldPath: Path, newPath: Path, profile: Profile, sink: (Row) -> Unit): DiffSummary {
        val sum = DiffSummary()
        val index = HashMap<String, Entry>(1 shl 16)
        val delims = ArrayList<Delimiters>()
        val h = LongArray(2)

        val oldSets = scan(oldPath, profile) { segs, start, d ->
            sum.oldMembers++
            val key = profile.keyOf(segs)
            if (key.all { it == '|' }) sum.emptyKeys++
            var k = key
            var n = 1
            while (index.containsKey(k)) {
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
            index[k] = Entry(h[0], h[1], start, di)
        }
        if (oldSets == 0) throw X12Exception("No 834 transaction set (ST*834) found in '$oldPath'")

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

            val newSets = scan(newPath, profile) { segs, _, _ ->
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
                if (e == null) {
                    sum.added++
                    sink(Row("ADDED", k, "", "", "", "", "", "not present in old file"))
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
                    detail = "termination flag: $flagNew"
                } else {
                    sum.changed++
                    type = "CHANGED"
                    detail = ""
                }
                val rows = fieldDiff(type, k, detail, oldSegs, segs, profile, delims[e.delim])
                if (rows.isEmpty()) sink(Row(type, k, "", "", "", "", "", "content differs (segment order changed)"))
                else rows.forEach(sink)
            }
            if (newSets == 0) throw X12Exception("No 834 transaction set (ST*834) found in '$newPath'")
        }

        val gone = index.entries.filter { !it.value.seen }.sortedBy { it.value.start }
        for ((k, _) in gone) {
            sum.termedAbsent++
            sink(Row("TERMED", k, "", "", "", "", "", "not present in new file"))
        }
        return sum
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
                val ov = o.el(p)
                val nv = n.el(p)
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
