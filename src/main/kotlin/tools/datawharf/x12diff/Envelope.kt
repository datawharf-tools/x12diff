package tools.datawharf.x12diff

import java.util.TreeMap

/** Streaming envelope checker and counter. Feed it every segment in order, then call [finish]. */
class EnvelopeChecker(private val maxWarnings: Int = 500) {
    val warnings = ArrayList<String>()
    var suppressed = 0
        private set
    var interchanges = 0
        private set
    var groups = 0
        private set
    var sets = 0
        private set
    var total = 0L
        private set
    var delimiters: Delimiters? = null
    val setTypes = TreeMap<String, Int>()
    private val segCounts = HashMap<String, LongArray>()

    private var isaOpen = false
    private var isaCtrl = ""
    private var groupsInIsa = 0

    private var gsOpen = false
    private var gsCtrl = ""
    private var gsVersion = ""
    private var setsInGs = 0

    private var stOpen = false
    private var stCtrl = ""
    private var stCount = 0

    val segmentCounts: Map<String, Long> get() = segCounts.mapValues { it.value[0] }.toSortedMap()

    private fun warn(seg: Segment?, msg: String) {
        val where = if (seg != null) " (segment #$total, byte offset ${seg.offset})" else ""
        if (warnings.size < maxWarnings) warnings.add(msg + where) else suppressed++
    }

    fun accept(seg: Segment) {
        total++
        segCounts.getOrPut(seg.id) { LongArray(1) }[0]++
        if (stOpen && seg.id != "ST") stCount++
        when (seg.id) {
            "ISA" -> {
                if (isaOpen) warn(seg, "ISA ${seg.el(13)} starts before interchange $isaCtrl was closed by IEA")
                closeOpenInner(seg)
                interchanges++
                isaOpen = true
                isaCtrl = seg.el(13)
                groupsInIsa = 0
            }
            "IEA" -> {
                if (!isaOpen) {
                    warn(seg, "IEA without a matching ISA")
                } else {
                    closeOpenInner(seg)
                    if (seg.el(2) != isaCtrl) warn(seg, "Control number mismatch: ISA13=$isaCtrl but IEA02=${seg.el(2)}")
                    if (seg.el(1) != groupsInIsa.toString()) {
                        warn(seg, "Group count mismatch in interchange $isaCtrl: IEA01=${seg.el(1)} but $groupsInIsa group(s) found")
                    }
                }
                isaOpen = false
            }
            "GS" -> {
                if (!isaOpen) warn(seg, "GS outside an ISA/IEA interchange")
                if (gsOpen) warn(seg, "GS ${seg.el(6)} starts before group $gsCtrl was closed by GE")
                closeOpenSet(seg)
                gsOpen = true
                gsCtrl = seg.el(6)
                gsVersion = seg.el(8)
                setsInGs = 0
                groups++
                groupsInIsa++
            }
            "GE" -> {
                if (!gsOpen) {
                    warn(seg, "GE without a matching GS")
                } else {
                    closeOpenSet(seg)
                    if (seg.el(2) != gsCtrl) warn(seg, "Control number mismatch: GS06=$gsCtrl but GE02=${seg.el(2)}")
                    if (seg.el(1) != setsInGs.toString()) {
                        warn(seg, "Transaction count mismatch in group $gsCtrl: GE01=${seg.el(1)} but $setsInGs transaction set(s) found")
                    }
                }
                gsOpen = false
            }
            "ST" -> {
                if (!gsOpen) warn(seg, "ST outside a GS/GE functional group")
                if (stOpen) warn(seg, "ST ${seg.el(2)} starts before transaction set $stCtrl was closed by SE")
                stOpen = true
                stCtrl = seg.el(2)
                stCount = 1
                sets++
                setsInGs++
                val label = typeLabel(seg.el(1), seg.el(3), gsVersion)
                setTypes.merge(label, 1, Int::plus)
            }
            "SE" -> {
                if (!stOpen) {
                    warn(seg, "SE without a matching ST")
                } else {
                    if (seg.el(2) != stCtrl) warn(seg, "Control number mismatch: ST02=$stCtrl but SE02=${seg.el(2)}")
                    if (seg.el(1) != stCount.toString()) {
                        warn(seg, "Segment count mismatch in transaction set $stCtrl: SE01=${seg.el(1)} but $stCount segment(s) found (ST and SE included)")
                    }
                }
                stOpen = false
            }
            "TA1" -> setTypes.merge("TA1", 1, Int::plus)
        }
    }

    private fun closeOpenSet(seg: Segment) {
        if (stOpen) {
            warn(seg, "Transaction set $stCtrl was not closed by SE")
            stOpen = false
        }
    }

    private fun closeOpenInner(seg: Segment) {
        closeOpenSet(seg)
        if (gsOpen) {
            warn(seg, "Functional group $gsCtrl was not closed by GE")
            gsOpen = false
        }
    }

    fun finish() {
        if (stOpen) warn(null, "File ended inside transaction set $stCtrl (no SE)")
        if (gsOpen) warn(null, "File ended inside functional group $gsCtrl (no GE)")
        if (isaOpen) warn(null, "File ended inside interchange $isaCtrl (no IEA)")
        if (interchanges == 0 && total > 0) warn(null, "No ISA interchange found")
    }

    companion object {
        /** Transaction type label: ST01, with 277 refined by an implementation reference containing X214. */
        fun typeLabel(st01: String, st03: String, gs08: String): String {
            if (st01 == "277" && (st03.contains("X214") || gs08.contains("X214"))) return "277CA"
            return st01.ifEmpty { "(blank)" }
        }
    }
}
