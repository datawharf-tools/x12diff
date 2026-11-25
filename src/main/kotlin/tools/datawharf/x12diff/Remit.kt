package tools.datawharf.x12diff

import java.io.BufferedWriter
import java.nio.file.Path
import java.util.TreeMap

class Adj(val group: String, val reason: String, val amount: Long, val qty: String, val raw: String = "") {
    val key: String get() = "$group-$reason"
    fun text(): String = "$group:$reason:${Money.fmt(amount)}"
}

/** Amount fields keep the raw text when it is not a valid amount; the Long is then 0 and the Ok flag is false. */
class RemitLine(
    val qual: String, val code: String, val modifiers: String, val units: String,
    val charge: Long?, val paid: Long?, val chargeText: String, val paidText: String, val adjs: List<Adj>,
    /** CAS amounts that were not valid amounts (raw text kept in [Adj.raw]); they are not in [adjs]. */
    val badAdjs: List<Adj> = emptyList(),
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
    /** True when an invalid amount means the check total cannot be verified. */
    var unreliable = false
    val label: String get() = (if (trn.isNotEmpty()) "check $trn" else "check (no TRN)") + " in $file, ST02 $setCtl"
}

class RemitClaim(
    val check: CheckInfo, val id: String, val status: String,
    val charge: Long, val paid: Long, val patientResp: Long, val payerCtl: String,
    val adjs: List<Adj>, val lines: List<RemitLine>,
    val chargeText: String, val paidText: String, val prText: String,
    val chargeOk: Boolean, val paidOk: Boolean, val prOk: Boolean,
    /** Claim-level CAS amounts that were not valid amounts (not in [adjs]). */
    val badAdjs: List<Adj> = emptyList(),
    /** True when any amount in this claim (CLP, SVC or CAS) is not valid; the balance checks were then skipped. */
    val hasInvalid: Boolean = false,
    /** Charge - paid - adjustments, the value the balance warning reports; null when it could not be computed. */
    val balanceDiff: Long? = null,
    /** Result of the service-line paid check; null when it was not run (no lines, or an invalid amount). */
    val linesPaidOk: Boolean? = null,
) {
    fun allAdjs(): Sequence<Adj> = adjs.asSequence() + lines.asSequence().flatMap { it.adjs.asSequence() }
}

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
        val badAdjs = ArrayList<Adj>()
        val lines = ArrayList<RemitLine>()
        var bad = false
    }

    private class SvcB(val seg: Segment) {
        val adjs = ArrayList<Adj>()
        val badAdjs = ArrayList<Adj>()
    }

    private var cur: ClaimB? = null
    private var svc: SvcB? = null
    private var lastN1 = ""

    override fun begin(type: String, st: Segment, gs: Segment?, d: Delimiters, file: String) {
        ci = CheckInfo(file, st.el(2))
        this.d = d
        cur = null
        svc = null
        lastN1 = ""
    }

    override fun segment(s: Segment) {
        when (s.id) {
            "BPR" -> {
                ci.bpr = Money.parse(s.el(2))
                ci.date = s.el(16)
                if (ci.bpr == null) { listener.warn("${ci.label}: BPR02 is not a valid amount ('${s.el(2)}')"); ci.unreliable = true }
            }
            "TRN" -> if (ci.trn.isEmpty()) ci.trn = s.el(2)
            "N1" -> if (cur == null) {
                lastN1 = s.el(1)
                when (s.el(1)) {
                    "PR" -> ci.payerId = s.el(4)
                    "PE" -> ci.payeeId = s.el(4)
                }
            }
            "REF" -> if (cur == null && lastN1 == "PR" && s.el(1) == "2U" && ci.payerId.isEmpty()) ci.payerId = s.el(2)
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
                        c.bad = true
                        (svc?.badAdjs ?: c.badAdjs).add(Adj(group, reason, 0L, s.el(i + 2), s.el(i + 1)))
                        listener.warn("${ci.label}, claim ${c.seg.el(1)}: CAS amount '${s.el(i + 1)}' is not a valid amount; balance not checked for this claim")
                    } else {
                        target.add(Adj(group, reason, amt, s.el(i + 2)))
                    }
                    i += 3
                }
            }
            "PLB" -> {
                var i = 4
                while (i <= 14) {
                    val raw = s.el(i)
                    if (raw.isNotEmpty()) {
                        val v = Money.parse(raw)
                        if (v == null) {
                            ci.unreliable = true
                            listener.warn("${ci.label}: PLB amount '$raw' is not a valid amount; the check total cannot be verified")
                        } else ci.plb += v
                    }
                    i += 2
                }
            }
        }
    }

    /** Returns null (and warns) when the text is not a valid amount. Empty text returns null without a warning when [required] is false. */
    private fun money(raw: String, ctx: String, required: Boolean): Long? {
        if (raw.isEmpty()) {
            if (required) listener.warn("$ctx: amount is empty")
            return if (required) null else 0L
        }
        return Money.parse(raw) ?: run { listener.warn("$ctx: '$raw' is not a valid amount"); null }
    }

    private fun amountText(raw: String, v: Long?): String = if (raw.isEmpty()) "" else if (v == null) raw else Money.fmt(v)

    private fun flushSvc() {
        val c = cur ?: return
        val v = svc ?: return
        val s = v.seg
        val parts = s.el(1).split(d.sub)
        val ctx = "${ci.label}, claim ${c.seg.el(1)}, SVC"
        val ch = if (s.el(2).isEmpty()) null else money(s.el(2), ctx, false)
        val pd = if (s.el(3).isEmpty()) null else money(s.el(3), ctx, false)
        if ((s.el(2).isNotEmpty() && ch == null) || (s.el(3).isNotEmpty() && pd == null)) c.bad = true
        c.lines.add(
            RemitLine(
                parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, parts.drop(2).joinToString(":"),
                s.el(5), ch, pd, amountText(s.el(2), ch), amountText(s.el(3), pd), v.adjs, v.badAdjs,
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
        val chargeV = money(s.el(3), ctx, true)
        val paidV = money(s.el(4), ctx, true)
        val prV = money(s.el(5), ctx, false)
        val charge = chargeV ?: 0L
        val paid = paidV ?: 0L
        val pr = prV ?: 0L
        if (paidV != null) ci.sumPaid += paid else ci.unreliable = true
        ci.claims++
        val ok = chargeV != null && paidV != null && prV != null && !b.bad
        var balanceDiff: Long? = null
        var linesPaidOk: Boolean? = null
        if (ok) {
            // The balancing rule adds claim-level and line-level adjustments.
            val claimCas = b.adjs.sumOf { it.amount }
            val lineCas = b.lines.sumOf { l -> l.adjs.sumOf { it.amount } }
            val diff = charge - paid - claimCas - lineCas
            balanceDiff = diff
            if (diff != 0L) {
                listener.warn("$ctx: charge ${Money.fmt(charge)} - paid ${Money.fmt(paid)} - adjustments ${Money.fmt(claimCas + lineCas)} leaves ${Money.fmt(diff)} unexplained")
            }
            if (b.lines.isNotEmpty() && b.lines.all { it.paid != null }) {
                val ls = b.lines.sumOf { it.paid!! }
                linesPaidOk = !(ls != paid && ls - claimCas != paid)
                if (ls != paid && ls - claimCas != paid) {
                    listener.warn("$ctx: service-line paid amounts add up to ${Money.fmt(ls)} but CLP04 is ${Money.fmt(paid)}")
                }
            }
        }
        listener.claim(
            RemitClaim(
                ci, id, s.el(2), charge, paid, pr, s.el(7), b.adjs, b.lines,
                amountText(s.el(3), chargeV), amountText(s.el(4), paidV), amountText(s.el(5), prV),
                chargeV != null, paidV != null, prV != null,
                b.badAdjs, !ok, balanceDiff, linesPaidOk,
            )
        )
    }

    override fun end(complete: Boolean) {
        flushClaim()
        if (!complete) listener.warn("${ci.label}: file ended inside this 835 (no SE)")
        val bpr = ci.bpr
        if (ci.unreliable) {
            listener.warn("${ci.label}: payment total not checked because an amount in this check is not valid")
        } else if (bpr != null) {
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
    var invalidAmounts = 0L
    var warningCount = 0L
    val warnings = ArrayList<String>()
    val envelope = EnvelopeLog()
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

    private val CLAIMS_HEADER = listOf(
        "file", "check_number", "payment_date", "payer_id", "payee_id", "claim_id", "claim_status",
        "claim_charge", "claim_paid", "patient_resp", "payer_claim_ctrl",
        "line_count", "line_charge_total", "line_paid_total", "claim_adj_total", "line_adj_total",
        "adj_CO", "adj_PR", "adj_OA", "adj_PI", "adj_CR", "balance_diff", "balance_ok", "lines_paid_ok", "has_invalid_amount",
    )
    private val ADJ_GROUPS = listOf("CO", "PR", "OA", "PI", "CR")
    private val ADJ_HEADER = listOf("file", "check_number", "claim_id", "level", "line_no", "service_code", "group", "reason", "amount")

    private fun yn(b: Boolean?): String = if (b == null) "" else if (b) "Y" else "N"

    private fun writeClaimRow(w: BufferedWriter, c: RemitClaim) {
        val n = c.lines.size
        // Totals are blank when they cannot be trusted (an amount is empty or not valid) or when there are no lines.
        val lineCharge = if (n > 0 && c.lines.all { it.charge != null }) Money.fmt(c.lines.sumOf { it.charge!! }) else ""
        val linePaid = if (n > 0 && c.lines.all { it.paid != null }) Money.fmt(c.lines.sumOf { it.paid!! }) else ""
        val lineBad = c.lines.any { it.badAdjs.isNotEmpty() }
        val claimAdjTotal = if (c.badAdjs.isEmpty()) Money.fmt(c.adjs.sumOf { it.amount }) else ""
        val lineAdjTotal = if (!lineBad) Money.fmt(c.lines.sumOf { l -> l.adjs.sumOf { it.amount } }) else ""
        val badGroups = (c.badAdjs.asSequence() + c.lines.asSequence().flatMap { it.badAdjs.asSequence() }).map { it.group }.toSet()
        val groupCells = ADJ_GROUPS.map { g ->
            if (g in badGroups) "" else Money.fmt(c.allAdjs().filter { it.group == g }.sumOf { it.amount })
        }
        val diff = c.balanceDiff
        w.write(
            Csv.line(
                listOf(
                    c.check.file, c.check.trn, c.check.date, c.check.payerId, c.check.payeeId, c.id, c.status,
                    c.chargeText, c.paidText, c.prText, c.payerCtl,
                    n.toString(), lineCharge, linePaid, claimAdjTotal, lineAdjTotal,
                ) + groupCells + listOf(
                    if (diff != null) Money.fmt(diff) else "", yn(diff?.let { it == 0L }), yn(c.linesPaidOk), yn(c.hasInvalid),
                )
            )
        )
    }

    private fun writeAdjRows(w: BufferedWriter, c: RemitClaim) {
        fun row(level: String, lineNo: String, code: String, a: Adj, valid: Boolean) = w.write(
            Csv.line(
                listOf(
                    c.check.file, c.check.trn, c.id, level, lineNo, code, a.group, a.reason,
                    if (valid) Money.fmt(a.amount) else a.raw,
                )
            )
        )
        for (a in c.adjs) row("claim", "", "", a, true)
        for (a in c.badAdjs) row("claim", "", "", a, false)
        c.lines.forEachIndexed { i, l ->
            for (a in l.adjs) row("line", (i + 1).toString(), l.code, a, true)
            for (a in l.badAdjs) row("line", (i + 1).toString(), l.code, a, false)
        }
    }

    fun run(
        files: List<Path>, profile: Profile, csvPath: String?, rollupPath: String?,
        claimsPath: String? = null, adjustmentsPath: String? = null,
    ): RemitSummary {
        val sum = RemitSummary()
        var of: OutFile? = null
        var rof: OutFile? = null
        var cof: OutFile? = null
        var aof: OutFile? = null
        try {
            if (claimsPath != null) {
                cof = Csv.open(claimsPath)
                cof.w.write(Csv.line(CLAIMS_HEADER))
            }
            if (adjustmentsPath != null) {
                aof = Csv.open(adjustmentsPath)
                aof.w.write(Csv.line(ADJ_HEADER))
            }
            val cw = cof?.w
            val aw = aof?.w
            if (csvPath != null) {
                of = Csv.open(csvPath)
                of.w.write(Csv.line(HEADER))
            }
            val w = of?.w
            val listener = object : RemitListener {
                override fun warn(msg: String) {
                    sum.warningCount++
                    if (sum.warnings.size < 200) sum.warnings.add(msg)
                }

                override fun check(c: CheckInfo) { sum.checks++ }

                override fun claim(c: RemitClaim) {
                    sum.claims++
                    if (c.chargeOk) sum.charged += c.charge else sum.invalidAmounts++
                    if (c.paidOk) sum.paid += c.paid else sum.invalidAmounts++
                    if (c.prOk) sum.patientResp += c.patientResp else sum.invalidAmounts++
                    for (a in c.adjs) bump(sum, a)
                    for (l in c.lines) for (a in l.adjs) bump(sum, a)
                    if (cw != null) writeClaimRow(cw, c)
                    if (aw != null) writeAdjRows(aw, c)
                    val claimAdj = c.adjs.joinToString("|") { it.text() }
                    val base = listOf(
                        c.check.file, c.check.trn, c.check.date, c.check.payerId, c.check.payeeId, c.id, c.status,
                        c.chargeText, c.paidText, c.prText, c.payerCtl,
                    )
                    if (c.lines.isEmpty()) {
                        w?.write(Csv.line(base + listOf("", "", "", "", "", "", "", claimAdj)))
                    }
                    for (l in c.lines) {
                        sum.lines++
                        w?.write(
                            Csv.line(
                                base + listOf(
                                    l.qual, l.code, l.modifiers, l.units, l.chargeText, l.paidText,
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
                val chk = EnvelopeChecker()
                val seen = TxWalker.walk(f, mapOf("835" to handler), chk)
                sum.envelope.add(f.fileName.toString(), chk)
                if ((seen["835"] ?: 0) == 0) listener.warn("${f.fileName}: no 835 transaction set found")
            }
            if (rollupPath != null) {
                rof = Csv.open(rollupPath)
                val rw = rof.w
                rw.write(Csv.line(listOf("group", "reason", "label", "count", "amount")))
                for ((k, v) in sum.rollup) {
                    val g = k.substringBefore('-'); val r = k.substringAfter('-')
                    rw.write(Csv.line(listOf(g, r, profile.adjLabel(g, r), v[0].toString(), Money.fmt(v[1]))))
                }
            }
            of?.commit()
            rof?.commit()
            cof?.commit()
            aof?.commit()
        } finally {
            of?.abort()
            rof?.abort()
            cof?.abort()
            aof?.abort()
        }
        return sum
    }

    private fun bump(sum: RemitSummary, a: Adj) {
        val e = sum.rollup.getOrPut(a.key) { LongArray(2) }
        e[0]++
        e[1] += a.amount
    }
}
