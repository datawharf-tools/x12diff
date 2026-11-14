package tools.datawharf.x12diff

import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap

class Adj(val group: String, val reason: String, val amount: Long, val qty: String) {
    val key: String get() = "$group-$reason"
    fun text(): String = "$group:$reason:${Money.fmt(amount)}"
}

class RemitLine(
    val qual: String, val code: String, val modifiers: String, val units: String,
    val charge: Long?, val paid: Long?, val adjs: List<Adj>,
)

class CheckInfo(val file: String, val setCtl: String) {
    var trn = ""
    var date = ""
    var payerId = ""
    var payeeId = ""
    var bpr: Long? = null
    var sumPaid = 0L
    var plb = 0L
    var claims = 0
    val label: String get() = (if (trn.isNotEmpty()) "check $trn" else "check (no TRN)") + " in $file, ST02 $setCtl"
}

class RemitClaim(
    val check: CheckInfo, val id: String, val status: String,
    val charge: Long, val paid: Long, val patientResp: Long, val payerCtl: String,
    val adjs: List<Adj>, val lines: List<RemitLine>,
)

interface RemitListener {
    fun claim(c: RemitClaim)
    fun check(c: CheckInfo) {}
    fun warn(msg: String)
}

/** Reads the segments of one 835 transaction set and emits claims, with balance checks. */
class RemitHandler(private val listener: RemitListener) : TxHandler {
    private var ci = CheckInfo("", "")
    private var d = Delimiters('*', ':', null, '~')

    private class ClaimB(val seg: Segment) {
        val adjs = ArrayList<Adj>()
        val lines = ArrayList<RemitLine>()
    }

    private class SvcB(val seg: Segment) {
        val adjs = ArrayList<Adj>()
    }

    private var cur: ClaimB? = null
    private var svc: SvcB? = null

    override fun begin(type: String, st: Segment, gs: Segment?, d: Delimiters, file: String) {
        ci = CheckInfo(file, st.el(2))
        this.d = d
        cur = null
        svc = null
    }

    override fun segment(s: Segment) {
        when (s.id) {
            "BPR" -> {
                ci.bpr = Money.parse(s.el(2))
                ci.date = s.el(16)
                if (ci.bpr == null) listener.warn("${ci.label}: BPR02 is not a valid amount ('${s.el(2)}')")
            }
            "TRN" -> if (ci.trn.isEmpty()) ci.trn = s.el(2)
            "N1" -> if (cur == null) {
                when (s.el(1)) {
                    "PR" -> ci.payerId = s.el(4)
                    "PE" -> ci.payeeId = s.el(4)
                }
            }
            "CLP" -> { flushClaim(); cur = ClaimB(s) }
            "SVC" -> if (cur != null) { flushSvc(); svc = SvcB(s) }
            "CAS" -> {
                val c = cur ?: return
                val target = svc?.adjs ?: c.adjs
                val group = s.el(1)
                var i = 2
                while (i + 1 <= 19) {
                    val reason = s.el(i)
                    if (reason.isEmpty() && s.el(i + 1).isEmpty()) break
                    val amt = Money.parse(s.el(i + 1))
                    if (amt == null) {
                        listener.warn("${ci.label}, claim ${c.seg.el(1)}: CAS amount '${s.el(i + 1)}' is not a valid amount")
                    } else {
                        target.add(Adj(group, reason, amt, s.el(i + 2)))
                    }
                    i += 3
                }
            }
            "PLB" -> {
                var i = 4
                while (i <= 14) {
                    val v = Money.parse(s.el(i))
                    if (v != null) ci.plb += v
                    i += 2
                }
            }
        }
    }

    private fun amount(s: String, ctx: String): Long {
        if (s.isEmpty()) return 0L
        return Money.parse(s) ?: run { listener.warn("$ctx: '$s' is not a valid amount"); 0L }
    }

    private fun flushSvc() {
        val c = cur ?: return
        val v = svc ?: return
        val s = v.seg
        val parts = s.el(1).split(d.sub)
        val ctx = "${ci.label}, claim ${c.seg.el(1)}, SVC"
        c.lines.add(
            RemitLine(
                parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, parts.drop(2).joinToString(":"),
                s.el(5),
                if (s.el(2).isEmpty()) null else amount(s.el(2), ctx),
                if (s.el(3).isEmpty()) null else amount(s.el(3), ctx),
                v.adjs,
            )
        )
        svc = null
    }

    private fun flushClaim() {
        flushSvc()
        val b = cur ?: return
        cur = null
        val s = b.seg
        val id = s.el(1)
        val ctx = "${ci.label}, claim $id"
        val charge = amount(s.el(3), ctx)
        val paid = amount(s.el(4), ctx)
        val pr = amount(s.el(5), ctx)
        ci.sumPaid += paid
        ci.claims++
        val adjTotal = if (b.adjs.isNotEmpty()) b.adjs.sumOf { it.amount } else b.lines.sumOf { l -> l.adjs.sumOf { it.amount } }
        val diff = charge - paid - adjTotal
        if (diff != 0L) {
            listener.warn("$ctx: charge ${Money.fmt(charge)} - paid ${Money.fmt(paid)} - adjustments ${Money.fmt(adjTotal)} leaves ${Money.fmt(diff)} unexplained")
        }
        if (b.lines.isNotEmpty() && b.lines.all { it.paid != null }) {
            val ls = b.lines.sumOf { it.paid!! }
            if (ls != paid) listener.warn("$ctx: service-line paid amounts add up to ${Money.fmt(ls)} but CLP04 is ${Money.fmt(paid)}")
        }
        listener.claim(RemitClaim(ci, id, s.el(2), charge, paid, pr, s.el(7), b.adjs, b.lines))
    }

    override fun end(complete: Boolean) {
        flushClaim()
        if (!complete) listener.warn("${ci.label}: file ended inside this 835 (no SE)")
        val bpr = ci.bpr
        if (bpr != null) {
            val expected = ci.sumPaid - ci.plb
            if (bpr != expected) {
                listener.warn(
                    "${ci.label}: BPR02 ${Money.fmt(bpr)} differs from sum of CLP04 ${Money.fmt(ci.sumPaid)} " +
                        "minus PLB adjustments ${Money.fmt(ci.plb)} = ${Money.fmt(expected)}"
                )
            }
        } else if (ci.claims > 0) {
            listener.warn("${ci.label}: no usable BPR02, payment total not checked")
        }
        listener.check(ci)
    }
}

class RemitSummary {
    var files = 0
    var checks = 0L
    var claims = 0L
    var lines = 0L
    var charged = 0L
    var paid = 0L
    var patientResp = 0L
    var warningCount = 0L
    val warnings = ArrayList<String>()
    /** group-reason -> [count, amount] */
    val rollup = TreeMap<String, LongArray>()
}

object RemitCmd {
    private val HEADER = listOf(
        "file", "check_number", "payment_date", "payer_id", "payee_id", "claim_id", "claim_status",
        "claim_charge", "claim_paid", "patient_resp", "payer_claim_ctrl",
        "service_qualifier", "service_code", "service_modifiers", "units", "line_charge", "line_paid",
        "line_adjustments", "claim_adjustments",
    )

    fun run(files: List<Path>, profile: Profile, csvPath: String?, rollupPath: String?): RemitSummary {
        val sum = RemitSummary()
        var w: BufferedWriter? = null
        var rw: BufferedWriter? = null
        try {
            if (csvPath != null) {
                w = Files.newBufferedWriter(Path.of(csvPath), StandardCharsets.UTF_8)
                w.write(Csv.line(HEADER))
            }
            val listener = object : RemitListener {
                override fun warn(msg: String) {
                    sum.warningCount++
                    if (sum.warnings.size < 200) sum.warnings.add(msg)
                }

                override fun check(c: CheckInfo) { sum.checks++ }

                override fun claim(c: RemitClaim) {
                    sum.claims++
                    sum.charged += c.charge
                    sum.paid += c.paid
                    sum.patientResp += c.patientResp
                    for (a in c.adjs) bump(sum, a)
                    for (l in c.lines) for (a in l.adjs) bump(sum, a)
                    val claimAdj = c.adjs.joinToString("|") { it.text() }
                    val base = listOf(
                        c.check.file, c.check.trn, c.check.date, c.check.payerId, c.check.payeeId, c.id, c.status,
                        Money.fmt(c.charge), Money.fmt(c.paid), Money.fmt(c.patientResp), c.payerCtl,
                    )
                    if (c.lines.isEmpty()) {
                        w?.write(Csv.line(base + listOf("", "", "", "", "", "", "", claimAdj)))
                    }
                    for (l in c.lines) {
                        sum.lines++
                        w?.write(
                            Csv.line(
                                base + listOf(
                                    l.qual, l.code, l.modifiers, l.units,
                                    l.charge?.let { Money.fmt(it) } ?: "", l.paid?.let { Money.fmt(it) } ?: "",
                                    l.adjs.joinToString("|") { it.text() }, claimAdj,
                                )
                            )
                        )
                    }
                }
            }
            val handler = RemitHandler(listener)
            for (f in files) {
                sum.files++
                val seen = TxWalker.walk(f, mapOf("835" to handler))
                if ((seen["835"] ?: 0) == 0) listener.warn("${f.fileName}: no 835 transaction set found")
            }
            w?.close(); w = null
            if (rollupPath != null) {
                rw = Files.newBufferedWriter(Path.of(rollupPath), StandardCharsets.UTF_8)
                rw.write(Csv.line(listOf("group", "reason", "label", "count", "amount")))
                for ((k, v) in sum.rollup) {
                    val g = k.substringBefore('-'); val r = k.substringAfter('-')
                    rw.write(Csv.line(listOf(g, r, profile.adjLabel(g, r), v[0].toString(), Money.fmt(v[1]))))
                }
                rw.close(); rw = null
            }
        } finally {
            try { w?.close() } catch (_: java.io.IOException) {}
            try { rw?.close() } catch (_: java.io.IOException) {}
        }
        return sum
    }

    private fun bump(sum: RemitSummary, a: Adj) {
        val e = sum.rollup.getOrPut(a.key) { LongArray(2) }
        e[0]++
        e[1] += a.amount
    }
}
