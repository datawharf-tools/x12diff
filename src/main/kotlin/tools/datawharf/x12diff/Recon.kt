package tools.datawharf.x12diff

import java.io.BufferedWriter
import java.nio.file.Path
import java.util.BitSet
import java.util.TreeMap

private class SetRef(
    val id: Int, val fileIdx: Int, val isa13: String, val gs02: String, val gs03: String, val gs06: String, val st02: String,
    val gsDate: Long?, val gs08: String,
) {
    val group: String get() = "$fileIdx|$isa13|$gs06"
}

private class ReconClaim(val id: String, val charge: Long, val date: Int, val setId: Int, val fileIdx: Int) {
    var next: ReconClaim? = null
    var remitEntries = 0
    var paid = 0L
    var patientResp = 0L
    var remitCharge = 0L
    var reversal = false
    var chargeMismatch = false
    var deniedStatus = false
    var anyAdj = false
    var otherAdj = 0L
    var ctl: String? = null
    var reject277: String? = null
    var s277Date = Int.MIN_VALUE
    var dup = false
    var matchHow: String? = null
    var tieNote: String? = null
}

private class Orphan(val id: String, val charge: Long, val paid: Long, val patientResp: Long, val status: String, val where: String)

class ReconSummary {
    val counts = TreeMap<String, Long>()
    var claims = 0L
    var sets837 = 0
    var remitEntries = 0L
    var remitChecks = 0L
    var ackSets = 0
    var rejectedSets = 0
    var statusEntries277 = 0L
    var chargedTotal = 0L
    var paidTotal = 0L
    var duplicateClaimIds = 0L
    var ambiguousTies = 0L
    var warningCount = 0L
    val warnings = ArrayList<String>()
    val envelope = EnvelopeLog()
    val skipped = ArrayList<String>()
    /** bucket label -> count, for UNMATCHED claims */
    val aging = java.util.LinkedHashMap<String, Long>().apply { for (b in listOf("0-30", "31-60", "61-90", "91+", "future", "unknown")) put(b, 0L) }
    val firstRows = ArrayList<List<String>>()
}

object ReconCmd {
    private val HEADER = listOf(
        "status", "claim_id", "claim_charge", "remit_charge", "paid", "patient_resp",
        "claim_date", "age_days", "reject_source", "detail",
    )

    private fun stem(p: Path): String = p.fileName.toString().substringBeforeLast('.').lowercase()

    fun run(files: List<Path>, profile: Profile, csvPath: String?, asOf: String, show: Int): ReconSummary {
        val sum = ReconSummary()
        val asOfDays = Dates.days(asOf) ?: throw X12Exception("--asof needs a date like 20260131")
        fun addWarn(m: String) { sum.warningCount++; if (sum.warnings.size < 200) sum.warnings.add(m) }

        val reversal = profile.list("recon.reversal.codes", listOf("22")).toSet()
        val deniedCodes = profile.list("recon.denied.codes", listOf("4")).toSet()
        val contractual = profile.list("recon.contractual", listOf("CO-45", "CO-253")).toSet()
        val dateLocs = profile.list("recon.claim.date", listOf("DTP*472:03", "DTP*434:03")).map { Locator.parse(it, "recon.claim.date") }
        val dateFallback = Locator.parse(profile.value("recon.claim.date.fallback", "BHT:04"), "recon.claim.date.fallback")
        val ackReject = profile.list("recon.ack.reject.codes", listOf("R", "M", "W", "X")).toSet()
        val statusReject = profile.list("recon.status.reject.categories", listOf("A3", "A4", "A6", "A7", "A8")).toSet()
        val statusAccept = profile.list("recon.status.accept.categories", listOf("A0", "A1", "A2")).toSet()
        val stems = files.map { stem(it) }
        val windowDays = profile.value("recon.ack.window.days", "30").toLongOrNull()?.takeIf { it >= 0 }
            ?: throw X12Exception("Profile: recon.ack.window.days must be a number of 0 or more")
        val setNotes = HashMap<Int, String>()

        val all = ArrayList<ReconClaim>()
        val byId = HashMap<String, ReconClaim>(1 shl 16)
        val sets = ArrayList<SetRef>()
        val byGsSt = HashMap<String, ArrayList<SetRef>>()
        val byGs = HashMap<String, ArrayList<SetRef>>()
        val rejected = BitSet()
        val orphans = ArrayList<Orphan>()

        fun chain(h: ReconClaim): List<ReconClaim> { val l = ArrayList<ReconClaim>(); var t: ReconClaim? = h; while (t != null) { l.add(t); t = t.next }; return l }

        // ---------- phase 1: 837 claims ----------
        var fileIdx = 0
        val h837 = object : TxHandler {
            var setId = 0
            var cur: String? = null
            var charge = 0L
            val dateVals = arrayOfNulls<String>(dateLocs.size)
            var fallbackDate = ""

            override fun begin(type: String, st: Segment, gs: Segment?, d: Delimiters, file: String) {
                setId = sets.size
                val ref = SetRef(setId, fileIdx, isa13 = curIsa, gs02 = gs?.el(2) ?: "", gs03 = gs?.el(3) ?: "", gs06 = gs?.el(6) ?: "", st02 = st.el(2),
                    gsDate = Dates.days(gs?.el(4) ?: ""), gs08 = gs?.el(8) ?: "")
                sets.add(ref)
                byGsSt.getOrPut("${ref.gs06}|${ref.st02}") { ArrayList() }.add(ref)
                byGs.getOrPut(ref.gs06) { ArrayList() }.add(ref)
                sum.sets837++
                cur = null
                fallbackDate = ""
            }

            var curIsa = ""

            private fun flush() {
                val id = cur ?: return
                cur = null
                var dd = ""
                for (v in dateVals) if (!v.isNullOrEmpty()) { dd = v; break }
                if (dd.isEmpty()) dd = fallbackDate
                val c = ReconClaim(id, charge, Dates.days(dd)?.toInt() ?: Int.MIN_VALUE, setId, fileIdx)
                val head = byId[id]
                if (head == null) byId[id] = c else {
                    sum.duplicateClaimIds++
                    val l = chain(head)
                    c.dup = true
                    l.forEach { it.dup = true }
                    addWarn("duplicate CLM01 '$id' in the 837 input (charges ${l.joinToString(" / ") { Money.fmt(it.charge) }} / ${Money.fmt(charge)}): " +
                        "remit entries are matched by charge, payer control number and order; check these rows")
                    if (l.any { it.charge == charge }) addWarn("duplicate CLM01 '$id' has two claims with the same charge ${Money.fmt(charge)}: the match may be wrong")
                    l.last().next = c
                }
                all.add(c)
                sum.chargedTotal += charge
            }

            override fun segment(s: Segment) {
                if (s.id == "CLM") {
                    flush()
                    cur = s.el(1)
                    charge = Money.parse(s.el(2)) ?: 0L
                    java.util.Arrays.fill(dateVals, null)
                    if (cur!!.isEmpty()) { addWarn("837 claim with empty CLM01 at byte offset ${s.offset}"); cur = "(blank)" }
                    if (Money.parse(s.el(2)) == null) addWarn("837 claim ${cur}: CLM02 '${s.el(2)}' is not a valid amount")
                } else if (cur != null) {
                    for (i in dateLocs.indices) if (dateVals[i] == null && dateLocs[i].matches(s)) dateVals[i] = dateLocs[i].value(s)
                } else if (fallbackDate.isEmpty() && dateFallback.matches(s)) {
                    fallbackDate = dateFallback.value(s)
                }
            }

            override fun end(complete: Boolean) {
                flush()
                if (!complete) addWarn("an 837 transaction set in ${files[fileIdx].fileName} is cut off (no SE); its last claim may be incomplete")
            }
        }
        for ((i, f) in files.withIndex()) {
            fileIdx = i
            h837.curIsa = isa13Of(f)
            val chk = EnvelopeChecker()
            TxWalker.walk(f, mapOf("837" to h837), chk)
            sum.envelope.add(f.fileName.toString(), chk)
        }
        sum.claims = all.size.toLong()

        // ---------- phase 2: 835 / 999 / 997 / 277 ----------
        fun pick(c: RemitClaim): ReconClaim? {
            val head = byId[c.id] ?: return null
            if (head.next == null) return head
            val list = chain(head)
            val isRev = c.status in reversal || c.charge < 0
            val abs = Math.abs(c.charge)
            var cand = list.filter { it.charge == abs }
            var how = "charge"
            if (cand.isEmpty()) { cand = list; how = "claim ID only" }
            if (cand.size > 1 && c.payerCtl.isNotEmpty()) {
                val b = cand.filter { it.ctl == c.payerCtl }
                if (b.isNotEmpty() && b.size < cand.size) { cand = b; how = "payer control number" }
            }
            if (cand.size > 1) {
                val pref = if (isRev) cand.filter { it.paid > 0 }
                else cand.filter { it.reversal && it.paid <= 0 }.ifEmpty { cand.filter { it.remitEntries == 0 } }
                if (pref.isNotEmpty() && pref.size < cand.size) { cand = pref; how = "payment state and order" }
            }
            if (cand.size > 1) {
                how = "first candidate (ambiguous)"
                sum.ambiguousTies++
                addWarn("remit entry for duplicated CLM01 '${c.id}' (${c.check.label}) could not be matched to one claim; applied to the first candidate")
            }
            val chosen = cand[0]
            chosen.matchHow = how
            if (c.payerCtl.isNotEmpty() && chosen.ctl == null) chosen.ctl = c.payerCtl
            return chosen
        }

        val remitListener = object : RemitListener {
            override fun warn(msg: String) { addWarn(msg) }
            override fun check(c: CheckInfo) { sum.remitChecks++ }
            override fun claim(c: RemitClaim) {
                sum.remitEntries++
                val cl = pick(c)
                if (cl == null) {
                    orphans.add(Orphan(c.id, c.charge, c.paid, c.patientResp, c.status, c.check.label))
                    return
                }
                cl.remitEntries++
                if (c.paidOk) cl.paid += c.paid
                if (c.prOk) cl.patientResp += c.patientResp
                if (c.status in deniedCodes) cl.deniedStatus = true
                for (a in c.allAdjs()) {
                    cl.anyAdj = true
                    if (a.group != "PR" && a.key !in contractual) cl.otherAdj += a.amount
                }
                if (c.status in reversal) cl.reversal = true
                else {
                    cl.remitCharge = c.charge
                    if (c.chargeOk && c.charge != cl.charge) cl.chargeMismatch = true
                }
            }
        }

        fun markSet(s: SetRef) { rejected.set(s.id) }

        /** Narrows candidate sets by the (mirrored or same) sender/receiver pair, then by file name. */
        fun narrow(cands: List<SetRef>, gs02: String, gs03: String, fileStem: String, usedStem: BooleanArray): List<SetRef> {
            var c = cands
            if (c.map { it.group }.distinct().size > 1) {
                val m = c.filter { (it.gs02 == gs03 && it.gs03 == gs02) || (it.gs02 == gs02 && it.gs03 == gs03) }
                if (m.isNotEmpty() && m.size < c.size) c = m
            }
            if (c.map { it.group }.distinct().size > 1) {
                val f = c.filter { stems[it.fileIdx] == fileStem }
                if (f.isNotEmpty() && f.size < c.size) { c = f; usedStem[0] = true }
            }
            return c
        }

        val h835 = RemitHandler(remitListener)
        val hAck = object : TxHandler {
            var gs = ""
            var st = ""
            var gs02 = ""
            var gs03 = ""
            var fileStem = ""
            var fname = ""
            var ackDate = ""
            var ackVer = ""

            override fun begin(type: String, st: Segment, gs: Segment?, d: Delimiters, file: String) {
                this.gs = ""; this.st = ""; sum.ackSets++; ackVer = ""
                gs02 = gs?.el(2) ?: ""; gs03 = gs?.el(3) ?: ""; fname = file; ackDate = gs?.el(4) ?: ""
                fileStem = file.substringBeforeLast('.').lowercase()
            }

            private fun reject(cands: List<SetRef>, what: String, wholeGroup: Boolean) {
                if (cands.isEmpty()) {
                    addWarn("$fname: $what refers to an 837 set that is not in the input files; nothing was marked")
                    return
                }
                // The acknowledgement must be dated on or after the 837 and within the window, and name the same version.
                val ackDays = Dates.days(ackDate)
                val timely = cands.filter { sr ->
                    val okDate = ackDays == null || sr.gsDate == null || (ackDays - sr.gsDate in 0..windowDays)
                    val okVer = ackVer.isEmpty() || sr.gs08.isEmpty() || ackVer == sr.gs08
                    okDate && okVer
                }
                if (timely.isEmpty()) {
                    sum.ambiguousTies++
                    addWarn("$fname: $what matches ${cands.size} 837 set(s), but the acknowledgement date $ackDate is not on or after the 837 date and within ${windowDays} days (or the version differs); nothing was marked. " +
                        "If the 999 really belongs to this 837, raise recon.ack.window.days in the profile")
                    return
                }
                val usedStem = BooleanArray(1)
                val c = narrow(timely, gs02, gs03, fileStem, usedStem)
                if (c.map { it.group }.distinct().size > 1) {
                    sum.ambiguousTies++
                    addWarn("$fname: $what matches ${c.map { it.group }.distinct().size} different 837 groups (same GS06/ST02 in several files); no claim was marked rejected. " +
                        "Name the 999 like its 837 (for example jan.837 and jan.999) or keep one month per folder")
                    return
                }
                if (wholeGroup) c.forEach { markSet(it) } else markSet(c.first())
                if (usedStem[0]) c.forEach { setNotes[it.id] = "999 matched to this 837 by file name" }
            }

            override fun segment(s: Segment) {
                when (s.id) {
                    "AK1" -> { gs = s.el(2); ackVer = s.el(3) }
                    "AK2" -> st = s.el(2)
                    "IK5", "AK5" -> if (s.el(1) in ackReject) {
                        val c = if (gs.isEmpty()) sets.filter { it.st02 == st } else byGsSt["$gs|$st"] ?: emptyList()
                        reject(c, "rejection for GS06=$gs ST02=$st", false)
                    }
                    "AK9" -> if (s.el(1) in ackReject) reject(byGs[gs] ?: emptyList(), "group rejection for GS06=$gs", true)
                }
            }

            override fun end(complete: Boolean) {}
        }
        val h277 = object : TxHandler {
            var d = Delimiters('*', ':', null, '~')
            var claimId = ""
            var inLine = false
            var fname = ""
            var fileStem = ""
            var gsDate = ""
            var bhtDate = ""

            override fun begin(type: String, st: Segment, gs: Segment?, d: Delimiters, file: String) {
                this.d = d; claimId = ""; inLine = false; fname = file; fileStem = file.substringBeforeLast('.').lowercase()
                gsDate = gs?.el(4) ?: ""; bhtDate = ""
            }

            override fun segment(s: Segment) {
                when (s.id) {
                    "BHT" -> bhtDate = s.el(4)
                    "HL" -> { claimId = ""; inLine = false }
                    "TRN" -> { inLine = false; if (s.el(1) == "2") claimId = s.el(2) }
                    "SVC" -> inLine = true
                    "STC" -> if (claimId.isNotEmpty() && !inLine) {
                        sum.statusEntries277++
                        val head = byId[claimId]
                        if (head == null) { addWarn("$fname: 277CA status for claim '$claimId' which is not in the 837 input"); return }
                        var cands = chain(head)
                        val statusDays = Dates.days(bhtDate.ifEmpty { gsDate })
                        val timely = cands.filter { cl ->
                            val sd = sets[cl.setId].gsDate
                            statusDays == null || sd == null || (statusDays - sd in 0..windowDays)
                        }
                        if (timely.isEmpty()) {
                            sum.ambiguousTies++
                            addWarn("$fname: 277CA status for claim '$claimId' is not dated on or after its 837 and within $windowDays days; it was ignored. Raise recon.ack.window.days if it belongs to this claim")
                            return
                        }
                        cands = timely
                        if (cands.size > 1) {
                            val amt = Money.parse(s.el(4))
                            if (amt != null) { val m = cands.filter { it.charge == amt }; if (m.isNotEmpty()) cands = m }
                        }
                        if (cands.size > 1) {
                            val f = cands.filter { stems[it.fileIdx] == fileStem }
                            if (f.isNotEmpty() && f.size < cands.size) { cands = f; cands[0].tieNote = "277CA matched to this claim by file name" }
                        }
                        if (cands.size > 1) {
                            sum.ambiguousTies++
                            addWarn("$fname: 277CA status for duplicated CLM01 '$claimId' matches ${cands.size} claims; none was marked")
                            return
                        }
                        val cl = cands[0]
                        val cat = s.el(1).substringBefore(d.sub)
                        val date = Dates.days(s.el(2))?.toInt() ?: Int.MIN_VALUE + 1
                        // The newest status wins. On the same date an acceptance wins over a rejection.
                        if (cat in statusReject) {
                            if (date > cl.s277Date) { cl.reject277 = cat; cl.s277Date = date }
                        } else if (cat in statusAccept) {
                            if (date >= cl.s277Date) { cl.reject277 = null; cl.s277Date = date }
                        }
                    }
                }
            }

            override fun end(complete: Boolean) {}
        }
        val handlers = mapOf("835" to h835, "999" to hAck, "997" to hAck, "277" to h277)
        for ((i, f) in files.withIndex()) {
            fileIdx = i
            val seen = TxWalker.walk(f, handlers)
            val known = (seen["837"] ?: 0) + (seen["835"] ?: 0) + (seen["999"] ?: 0) + (seen["997"] ?: 0) + (seen["277"] ?: 0)
            if (known == 0) addWarn("${f.fileName}: no 837, 835, 999 or 277 transaction set found (skipped)")
        }
        sum.rejectedSets = rejected.cardinality()

        // ---------- classify and report ----------
        var w: BufferedWriter? = null
        try {
            if (csvPath != null) {
                w = Csv.open(csvPath)
                w.write(Csv.line(HEADER))
            }
            fun emit(row: List<String>, status: String) {
                sum.counts.merge(status, 1L, Long::plus)
                w?.write(Csv.line(row))
                if (status != "PAID" && sum.firstRows.size < show) sum.firstRows.add(row)
            }
            for (c in all) {
                val ackRej = rejected.get(c.setId)
                val status: String
                var source = ""
                val notes = ArrayList<String>()
                if (c.dup) notes.add("duplicate CLM01" + (c.matchHow?.let { "; remit matched by $it" } ?: ""))
                c.tieNote?.let { notes.add(it) }
                setNotes[c.setId]?.let { if (rejected.get(c.setId)) notes.add(it) }
                if (c.remitEntries == 0) {
                    status = if (ackRej || c.reject277 != null) "REJECTED" else "UNMATCHED"
                    if (ackRej) source = "999 set rejected"
                    if (c.reject277 != null) source = (if (source.isEmpty()) "" else "$source; ") + "277CA category ${c.reject277}"
                } else {
                    status = when {
                        c.reversal -> if (c.paid > 0) "ADJUSTED" else "REVERSED"
                        c.paid < 0 -> "REVERSED"
                        c.paid == 0L -> if (c.deniedStatus || c.patientResp <= 0L || c.otherAdj != 0L) "DENIED" else "PATIENT_RESP"
                        c.otherAdj != 0L -> "PARTIAL"
                        !c.anyAdj && c.paid + c.patientResp < c.charge -> "PARTIAL"
                        else -> "PAID"
                    }
                    if (c.chargeMismatch) notes.add("charge differs between 837 (${Money.fmt(c.charge)}) and 835 (${Money.fmt(c.remitCharge)})")
                    if (c.paid > c.charge) { notes.add("paid exceeds the 837 charge"); addWarn("claim '${c.id}': paid ${Money.fmt(c.paid)} exceeds the 837 charge ${Money.fmt(c.charge)}") }
                    if (ackRej) notes.add("999 rejected the set but a remit exists")
                    sum.paidTotal += c.paid
                }
                var age = ""
                if (status == "UNMATCHED" || status == "REJECTED") {
                    if (c.date == Int.MIN_VALUE) {
                        if (status == "UNMATCHED") sum.aging.merge("unknown", 1L, Long::plus)
                    } else {
                        val a = asOfDays - c.date
                        age = a.toString()
                        val b = if (a < 0) "future" else if (a <= 30) "0-30" else if (a <= 60) "31-60" else if (a <= 90) "61-90" else "91+"
                        if (status == "UNMATCHED") sum.aging.merge(b, 1L, Long::plus)
                    }
                }
                val dateText = if (c.date == Int.MIN_VALUE) "" else java.time.LocalDate.ofEpochDay(c.date.toLong()).toString().replace("-", "")
                emit(
                    listOf(
                        status, c.id, Money.fmt(c.charge),
                        if (c.remitEntries > 0 && c.remitCharge != 0L) Money.fmt(c.remitCharge) else "",
                        if (c.remitEntries > 0) Money.fmt(c.paid) else "",
                        if (c.remitEntries > 0) Money.fmt(c.patientResp) else "",
                        dateText, age, source, notes.joinToString("; "),
                    ),
                    status,
                )
            }
            for (o in orphans) {
                emit(listOf("ORPHAN", o.id, "", Money.fmt(o.charge), Money.fmt(o.paid), Money.fmt(o.patientResp), "", "", "", "no matching 837 claim; ${o.where}"), "ORPHAN")
            }
            w?.close(); w = null
        } finally {
            try { w?.close() } catch (_: java.io.IOException) {}
        }
        return sum
    }

    /** ISA13 of the first interchange in a file (blank if unreadable). */
    private fun isa13Of(p: Path): String = try {
        java.nio.file.Files.newInputStream(p).use { ins -> X12Reader(ins).next()?.takeIf { it.id == "ISA" }?.el(13) ?: "" }
    } catch (e: Exception) { "" }
}
