package tools.datawharf.x12diff

import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.BitSet
import java.util.TreeMap

private class ReconClaim(val id: String, val charge: Long, val date: Int, val setId: Int) {
    var next: ReconClaim? = null
    var remitEntries = 0
    var paid = 0L
    var patientResp = 0L
    var remitCharge = 0L
    var reversal = false
    var chargeMismatch = false
    var reject277: String? = null
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
    var warningCount = 0L
    val warnings = ArrayList<String>()
    /** bucket label -> count, for UNMATCHED claims */
    val aging = java.util.LinkedHashMap<String, Long>().apply { for (b in listOf("0-30", "31-60", "61-90", "91+", "unknown")) put(b, 0L) }
    val firstRows = ArrayList<List<String>>()
}

object ReconCmd {
    private val HEADER = listOf(
        "status", "claim_id", "claim_charge", "remit_charge", "paid", "patient_resp",
        "claim_date", "age_days", "reject_source", "detail",
    )

    fun run(files: List<Path>, profile: Profile, csvPath: String?, asOf: String, show: Int): ReconSummary {
        val sum = ReconSummary()
        val asOfDays = Dates.days(asOf) ?: throw X12Exception("--asof needs a date like 20260131")
        fun addWarn(m: String) { sum.warningCount++; if (sum.warnings.size < 200) sum.warnings.add(m) }

        val reversal = profile.list("recon.reversal.codes", listOf("22")).toSet()
        val minRatio = profile.value("recon.paid.min.ratio", "1.0").toDoubleOrNull()
            ?: throw X12Exception("Profile: recon.paid.min.ratio must be a number")
        val dateLoc = Locator.parse(profile.value("recon.claim.date", "DTP*472:03"), "recon.claim.date")
        val dateFallback = profile.value("recon.claim.date.fallback", "BHT:04").let { Locator.parse(it, "recon.claim.date.fallback") }
        val ackReject = profile.list("recon.ack.reject.codes", listOf("R")).toSet()
        val statusReject = profile.list("recon.status.reject.categories", listOf("A3", "A4", "A6", "A7", "A8")).toSet()

        val all = ArrayList<ReconClaim>()
        val byId = HashMap<String, ReconClaim>(1 shl 16)
        val setKeys = HashMap<String, Int>()          // "gs|st" -> set id
        val setsByGs = HashMap<String, ArrayList<Int>>()
        val setsBySt = HashMap<String, ArrayList<Int>>()
        var nextSet = 0
        val rejected = BitSet()
        val orphans = ArrayList<Orphan>()

        // ---------- phase 1: 837 claims ----------
        val h837 = object : TxHandler {
            var setId = 0
            var cur: String? = null
            var charge = 0L
            var date = ""
            var fallbackDate = ""
            var headerDone = false

            override fun begin(type: String, st: Segment, gs: Segment?, d: Delimiters, file: String) {
                val gsCtl = gs?.el(6) ?: ""
                setId = nextSet++
                setKeys["$gsCtl|${st.el(2)}"] = setId
                setsByGs.getOrPut(gsCtl) { ArrayList() }.add(setId)
                setsBySt.getOrPut(st.el(2)) { ArrayList() }.add(setId)
                sum.sets837++
                cur = null
                fallbackDate = ""
            }

            private fun flush() {
                val id = cur ?: return
                cur = null
                val dd = date.ifEmpty { fallbackDate }
                val c = ReconClaim(id, charge, Dates.days(dd)?.toInt() ?: Int.MIN_VALUE, setId)
                val head = byId[id]
                if (head == null) byId[id] = c else { var t = head; while (t!!.next != null) t = t.next; t.next = c }
                all.add(c)
                sum.chargedTotal += charge
            }

            override fun segment(s: Segment) {
                if (s.id == "CLM") {
                    flush()
                    cur = s.el(1)
                    charge = Money.parse(s.el(2)) ?: 0L
                    date = ""
                    if (cur!!.isEmpty()) { addWarn("837 claim with empty CLM01 at byte offset ${s.offset}"); cur = "(blank)" }
                } else if (cur != null) {
                    if (date.isEmpty() && dateLoc.matches(s)) date = dateLoc.value(s)
                } else if (fallbackDate.isEmpty() && dateFallback.matches(s)) {
                    fallbackDate = dateFallback.value(s)
                }
            }

            override fun end(complete: Boolean) {
                flush()
            }
        }

        for (f in files) TxWalker.walk(f, mapOf("837" to h837))
        sum.claims = all.size.toLong()

        // ---------- phase 2: 835 / 999 / 997 / 277 ----------
        fun pick(id: String, charge: Long): ReconClaim? {
            var c = byId[id] ?: return null
            if (c.next == null) return c
            var t: ReconClaim? = c
            while (t != null) { if (t.charge == charge) return t; t = t.next }
            return c
        }

        val remitListener = object : RemitListener {
            override fun warn(msg: String) { addWarn(msg) }
            override fun check(c: CheckInfo) { sum.remitChecks++ }
            override fun claim(c: RemitClaim) {
                sum.remitEntries++
                val cl = pick(c.id, c.charge)
                if (cl == null) {
                    orphans.add(Orphan(c.id, c.charge, c.paid, c.patientResp, c.status, c.check.label))
                    return
                }
                cl.remitEntries++
                cl.paid += c.paid
                cl.patientResp += c.patientResp
                if (c.status in reversal) cl.reversal = true
                else {
                    cl.remitCharge = c.charge
                    if (c.charge != cl.charge) cl.chargeMismatch = true
                }
            }
        }

        val h835 = RemitHandler(remitListener)
        val hAck = object : TxHandler {
            var gs = ""
            var st = ""
            override fun begin(type: String, st: Segment, gs: Segment?, d: Delimiters, file: String) { this.gs = ""; this.st = ""; sum.ackSets++ }

            private fun markSets(ids: List<Int>?) { ids?.forEach { rejected.set(it) } }

            override fun segment(s: Segment) {
                when (s.id) {
                    "AK1" -> gs = s.el(2)
                    "AK2" -> st = s.el(2)
                    "IK5", "AK5" -> if (s.el(1) in ackReject) {
                        val id = setKeys["$gs|$st"]
                        if (id != null) rejected.set(id) else if (gs.isEmpty()) markSets(setsBySt[st])
                    }
                    "AK9" -> if (s.el(1) in ackReject) markSets(setsByGs[gs])
                }
            }

            override fun end(complete: Boolean) {}
        }
        val h277 = object : TxHandler {
            var d = Delimiters('*', ':', null, '~')
            var claimId = ""
            override fun begin(type: String, st: Segment, gs: Segment?, d: Delimiters, file: String) { this.d = d; claimId = "" }
            override fun segment(s: Segment) {
                when (s.id) {
                    "HL" -> claimId = ""
                    "TRN" -> if (s.el(1) == "2") claimId = s.el(2)
                    "STC" -> if (claimId.isNotEmpty()) {
                        sum.statusEntries277++
                        val cat = s.el(1).substringBefore(d.sub)
                        if (cat in statusReject) {
                            var t = byId[claimId]
                            while (t != null) { t.reject277 = cat; t = t.next }
                        }
                    }
                }
            }
            override fun end(complete: Boolean) {}
        }
        val handlers = mapOf("835" to h835, "999" to hAck, "997" to hAck, "277" to h277)
        for (f in files) {
            val seen = TxWalker.walk(f, handlers)
            val known = (seen["837"] ?: 0) + (seen["835"] ?: 0) + (seen["999"] ?: 0) + (seen["997"] ?: 0) + (seen["277"] ?: 0)
            if (known == 0) addWarn("${f.fileName}: no 837, 835, 999 or 277 transaction set found (skipped)")
        }
        sum.rejectedSets = rejected.cardinality()

        // ---------- classify and report ----------
        var w: BufferedWriter? = null
        try {
            if (csvPath != null) {
                w = Files.newBufferedWriter(Path.of(csvPath), StandardCharsets.UTF_8)
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
                var detail = ""
                if (c.remitEntries == 0) {
                    status = if (ackRej || c.reject277 != null) "REJECTED" else "UNMATCHED"
                    if (ackRej) source = "999 set rejected"
                    if (c.reject277 != null) source = (if (source.isEmpty()) "" else "$source; ") + "277CA category ${c.reject277}"
                } else {
                    val ratioOk = c.paid.toDouble() >= c.charge.toDouble() * minRatio - 0.5
                    status = when {
                        c.reversal -> if (c.paid > 0) "ADJUSTED" else "REVERSED"
                        c.paid < 0 -> "REVERSED"
                        c.paid == 0L -> "DENIED"
                        ratioOk -> "PAID"
                        else -> "PARTIAL"
                    }
                    if (c.chargeMismatch) detail = "charge differs between 837 (${Money.fmt(c.charge)}) and 835 (${Money.fmt(c.remitCharge)})"
                    if (ackRej) detail = (if (detail.isEmpty()) "" else "$detail; ") + "999 rejected the set but a remit exists"
                    sum.paidTotal += c.paid
                }
                var age = ""
                if (status == "UNMATCHED" || status == "REJECTED") {
                    if (c.date == Int.MIN_VALUE) {
                        sum.aging.merge("unknown", 1L, Long::plus)
                    } else {
                        val a = asOfDays - c.date
                        age = a.toString()
                        val b = if (a <= 30) "0-30" else if (a <= 60) "31-60" else if (a <= 90) "61-90" else "91+"
                        if (status == "UNMATCHED") sum.aging.merge(b, 1L, Long::plus)
                    }
                }
                val dateText = if (c.date == Int.MIN_VALUE) "" else java.time.LocalDate.ofEpochDay(c.date.toLong()).toString().replace("-", "")
                emit(
                    listOf(
                        status, c.id, Money.fmt(c.charge),
                        if (c.remitEntries > 0 && !c.reversal) Money.fmt(c.remitCharge) else "",
                        if (c.remitEntries > 0) Money.fmt(c.paid) else "",
                        if (c.remitEntries > 0) Money.fmt(c.patientResp) else "",
                        dateText, age, source, detail,
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
}
