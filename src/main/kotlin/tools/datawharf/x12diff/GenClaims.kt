package tools.datawharf.x12diff

import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate

/**
 * Synthetic 837 / 835 / 999 / 277 generator with known expected reconciliation counts.
 * All data is invented (claim IDs PCN########, check numbers CHKTEST######, payer TESTPAYER).
 */
object GenClaims {
    class Result(
        val claims: Long, val sets837: Int,
        val paid: Long, val partial: Long, val denied: Long, val reversed: Long, val adjusted: Long,
        val unmatched: Long, val rejectedBySet: Long, val rejectedBy277: Long, val orphans: Long, val patientResp: Long,
        val remitEntries: Long, val remitChecks: Long, val netPaidCents: Long, val chargedCents: Long,
        val rejectedSets: Int,
    ) {
        val rejected: Long get() = rejectedBySet + rejectedBy277
    }

    private fun mix(z0: Long): Long {
        var z = z0 + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    private fun unit(seed: Long, salt: Long, n: Long): Double =
        (mix(seed xor (n * 0x2545F4914F6CDD1DL) xor (salt * 0x9E3779B97F4A7C15uL.toLong())) ushr 11).toDouble() / (1L shl 53).toDouble()

    private enum class V { PAID, PARTIAL, DENIED, REVERSED, ADJUSTED, UNMATCHED, REJ277, PATRESP }

    private fun variant(seed: Long, n: Long): V {
        val r = unit(seed, 1, n)
        return when {
            r < 0.43 -> V.PAID
            r < 0.63 -> V.PARTIAL
            r < 0.71 -> V.DENIED
            r < 0.76 -> V.REVERSED
            r < 0.80 -> V.ADJUSTED
            r < 0.87 -> V.UNMATCHED
            r < 0.92 -> V.REJ277
            r < 0.95 -> V.PATRESP
            else -> V.PAID
        }
    }

    private fun chargeOf(seed: Long, n: Long): Long = 5000 + (mix(seed xor (n * 31)) ushr 40) % 40000 // cents
    private fun id(n: Long) = "PCN" + n.toString().padStart(8, '0')
    private fun dateOf(n: Long): String = LocalDate.of(2026, 1, 1).plusDays(n % 120).toString().replace("-", "")

    private class Doc(path: Path, val term: String, gsFn: String, gsVer: String, snd: String = "SENDERTEST", rcv: String = "RECEIVERTEST") {
        val w: BufferedWriter = Files.newBufferedWriter(
            path, StandardCharsets.ISO_8859_1, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
        )
        var cnt = 0
        var sets = 0
        private var ctl = 0

        init {
            raw("ISA*00*          *00*          *ZZ*" + snd.padEnd(15) + "*ZZ*" + rcv.padEnd(15) + "*260101*1200*^*00501*000000001*0*T*:")
            raw("GS*$gsFn*$snd*$rcv*20260101*1200*1*X*$gsVer")
        }

        private fun raw(s: String) { w.write(s); w.write(term) }
        fun seg(s: String) { raw(s); cnt++ }
        fun st(type: String, ver: String): Int {
            ctl++; sets++; cnt = 0
            seg("ST*$type*" + ctl.toString().padStart(4, '0') + "*$ver")
            return ctl
        }
        fun se() { cnt++; raw("SE*$cnt*" + ctl.toString().padStart(4, '0')) }
        fun close() {
            raw("GE*$sets*1"); raw("IEA*1*000000001"); w.close()
        }
    }

    private class Entry(val id: String, val status: String, val charge: Long, val paid: Long, val claimAdj: List<Triple<String, String, Long>>, val lines: List<Line>)
    private class Line(val code: String, val charge: Long, val paid: Long, val adj: List<Triple<String, String, Long>>)

    private fun casText(group: String, items: List<Triple<String, String, Long>>): String? {
        val g = items.filter { it.first == group }
        if (g.isEmpty()) return null
        return "CAS*$group*" + g.joinToString("**") { "${it.second}*${Money.fmt(it.third)}" }
    }

    private fun writeCas(d: Doc, items: List<Triple<String, String, Long>>) {
        for (g in items.map { it.first }.distinct()) casText(g, items)?.let { d.seg(it) }
    }

    private fun lineAmounts(total: Long): Pair<Long, Long> { val a = Math.round(total * 0.6); return a to (total - a) }

    private fun splitSigned(total: Long, a: Long): LongArray = longArrayOf(a, total - a)

    /** Build the 835 entry for a claim in a given role. */
    private fun entry(n: Long, charge: Long, role: String, seed: Long): Entry {
        val (c1, c2) = lineAmounts(charge)
        val codes = listOf("99213", "99214")
        val cs = listOf(c1, c2)
        fun paidLines(reason: String, share: Double) = cs.indices.map { i ->
            val co = Math.round(cs[i] * share)
            Line(codes[i], cs[i], cs[i] - co, listOf(Triple("CO", reason, co)))
        }
        return when (role) {
            "paid" -> { val lines = paidLines("45", 0.1); Entry(id(n), "1", charge, lines.sumOf { it.paid }, emptyList(), lines) }
            "partial" -> {
                val lines = cs.indices.map { i ->
                    val paid = Math.round(cs[i] * 0.6); val pr = Math.round(cs[i] * 0.1); val co = cs[i] - paid - pr
                    Line(codes[i], cs[i], paid, listOf(Triple("CO", "97", co), Triple("PR", "1", pr)))
                }
                Entry(id(n), "1", charge, lines.sumOf { it.paid }, emptyList(), lines)
            }
            "denied" -> Entry(id(n), "4", charge, 0, listOf(Triple("CO", "50", charge)), cs.indices.map { Line(codes[it], cs[it], 0, emptyList()) })
            "patresp" -> Entry(id(n), "1", charge, 0, emptyList(), cs.indices.map { Line(codes[it], cs[it], 0, listOf(Triple("PR", "1", cs[it]))) })
            "reversal" -> negate(entry(n, charge, "paid", seed))
            "repay" -> {
                val lines = cs.indices.map { i ->
                    val paid = Math.round(cs[i] * 0.8)
                    Line(codes[i], cs[i], paid, listOf(Triple("CO", "45", cs[i] - paid)))
                }
                Entry(id(n), "1", charge, lines.sumOf { it.paid }, emptyList(), lines)
            }
            "orphan" -> { val e = entry(n, charge, "paid", seed); Entry("ORPH" + n.toString().padStart(6, '0'), "1", e.charge, e.paid, e.claimAdj, e.lines) }
            else -> error(role)
        }
    }

    private fun negate(e: Entry): Entry = Entry(
        e.id, "22", -e.charge, -e.paid, e.claimAdj.map { Triple(it.first, it.second, -it.third) },
        e.lines.map { l -> Line(l.code, -l.charge, -l.paid, l.adj.map { Triple(it.first, it.second, -it.third) }) },
    )

    private fun patientResp(e: Entry): Long = e.lines.sumOf { l -> l.adj.filter { it.first == "PR" }.sumOf { it.third } }

    private fun write835(path: Path, entries: Sequence<Entry>, perCheck: Int, checkPrefix: String, term: String): Pair<Long, Int> {
        val d = Doc(path, term, "HP", "005010X221A1")
        var checks = 0
        var n = 0
        val buf = ArrayList<Entry>(perCheck)
        var netPaid = 0L
        fun flush() {
            if (buf.isEmpty()) return
            checks++
            d.st("835", "005010X221A1")
            val sum = buf.sumOf { it.paid }
            netPaid += sum
            val plb = 100L
            val bpr = listOf("BPR", "I", Money.fmt(sum - plb), "C", "CHK") + List(11) { "" } + listOf("20261231")
            d.seg(bpr.joinToString("*"))
            d.seg("TRN*1*$checkPrefix" + checks.toString().padStart(6, '0') + "*1999999999")
            d.seg("N1*PR*TESTPAYER*XV*PAYER001")
            d.seg("N1*PE*TESTPAYEE*XX*9999999999")
            for (e in buf) {
                d.seg("CLP*${e.id}*${e.status}*${Money.fmt(e.charge)}*${Money.fmt(e.paid)}*${Money.fmt(patientResp(e))}*12*PAYCTL${e.id.takeLast(6)}")
                writeCas(d, e.claimAdj)
                for (l in e.lines) {
                    d.seg("SVC*HC:${l.code}*${Money.fmt(l.charge)}*${Money.fmt(l.paid)}**1")
                    writeCas(d, l.adj)
                }
            }
            d.seg("PLB*9999999999*20261231*WO:TESTADJ*${Money.fmt(plb)}")
            d.se()
            buf.clear()
        }
        for (e in entries) {
            buf.add(e)
            n++
            if (buf.size == perCheck) flush()
        }
        flush()
        if (checks == 0) { d.st("835", "005010X221A1"); d.se() }
        d.close()
        return netPaid to checks
    }

    fun generate(claims: Long, dir: Path, seed: Long, lineBreaks: Boolean, setSize: Int, perCheck: Int = 100, rejectSets: Set<Int>? = null): Result {
        if (claims < 1) throw X12Exception("--claims must be at least 1")
        if (setSize < 1) throw X12Exception("--set-size must be at least 1")
        Files.createDirectories(dir)
        val term = if (lineBreaks) "~\n" else "~"
        val setCount = ((claims + setSize - 1) / setSize).toInt()
        // --reject-set N (1-based, repeatable): exactly those 837 sets are rejected by the 999; otherwise ~5% are chosen by seed.
        if (rejectSets != null) for (r in rejectSets) if (r < 1 || r > setCount) {
            throw X12Exception("--reject-set $r is out of range: $claims claims at --set-size $setSize make $setCount 837 set(s), numbered 1 to $setCount")
        }
        val setRejected = BooleanArray(setCount) { if (rejectSets != null) (it + 1) in rejectSets else unit(seed, 2, it.toLong()) < 0.05 }
        fun setOf(n: Long) = ((n - 1) / setSize).toInt()
        fun effective(n: Long): V? = if (setRejected[setOf(n)]) null else variant(seed, n)

        var paid = 0L; var partial = 0L; var denied = 0L; var reversed = 0L; var adjusted = 0L
        var unmatched = 0L; var rejSet = 0L; var rej277 = 0L; var charged = 0L; var patresp = 0L
        for (n in 1..claims) {
            charged += chargeOf(seed, n)
            when (effective(n)) {
                null -> rejSet++
                V.PAID -> paid++
                V.PARTIAL -> partial++
                V.DENIED -> denied++
                V.REVERSED -> reversed++
                V.ADJUSTED -> adjusted++
                V.UNMATCHED -> unmatched++
                V.REJ277 -> rej277++
                V.PATRESP -> patresp++
            }
        }
        val orphans = maxOf(1L, claims / 200)

        // 837
        val d837 = Doc(dir.resolve("claims.837"), term, "HC", "005010X222A1")
        var cnt = 0L
        for (s in 0 until setCount) {
            d837.st("837", "005010X222A1")
            d837.seg("BHT*0019*00*TESTBATCH${s + 1}*20260101*1200*CH")
            val from = s.toLong() * setSize + 1
            val to = minOf(claims, from + setSize - 1)
            for (n in from..to) {
                val ch = chargeOf(seed, n)
                val (c1, c2) = lineAmounts(ch)
                d837.seg("CLM*${id(n)}*${Money.fmt(ch)}***11:B:1*Y*A*Y*Y")
                d837.seg("LX*1")
                d837.seg("SV1*HC:99213*${Money.fmt(c1)}*UN*1***1")
                d837.seg("DTP*472*D8*${dateOf(n)}")
                d837.seg("LX*2")
                d837.seg("SV1*HC:99214*${Money.fmt(c2)}*UN*1***1")
                d837.seg("DTP*472*D8*${dateOf(n)}")
                cnt++
            }
            d837.se()
        }
        d837.close()

        // 835 file 1: first-pass entries, then orphans. File 2: reversals and repayments.
        val first = sequence {
            for (n in 1..claims) {
                val ch = chargeOf(seed, n)
                when (effective(n)) {
                    V.PAID -> yield(entry(n, ch, "paid", seed))
                    V.PARTIAL -> yield(entry(n, ch, "partial", seed))
                    V.DENIED -> yield(entry(n, ch, "denied", seed))
                    V.PATRESP -> yield(entry(n, ch, "patresp", seed))
                    V.REVERSED, V.ADJUSTED -> yield(entry(n, ch, "paid", seed))
                    else -> {}
                }
            }
            for (k in 1..orphans) yield(entry(k, 7500 + k, "orphan", seed))
        }
        val second = sequence {
            for (n in 1..claims) {
                val ch = chargeOf(seed, n)
                when (effective(n)) {
                    V.REVERSED -> yield(entry(n, ch, "reversal", seed))
                    V.ADJUSTED -> { yield(entry(n, ch, "reversal", seed)); yield(entry(n, ch, "repay", seed)) }
                    else -> {}
                }
            }
        }
        val (net1, checks1) = write835(dir.resolve("remits_1.835"), first, perCheck, "CHKTESTA", term)
        val (net2, checks2) = write835(dir.resolve("remits_2.835"), second, perCheck, "CHKTESTB", term)
        val entries = (claims - unmatched - rej277 - rejSet) + reversed + adjusted * 2 + orphans

        // 999
        val d999 = Doc(dir.resolve("ack.999"), term, "FA", "005010X231A1", "RECEIVERTEST", "SENDERTEST")
        d999.st("999", "005010X231A1")
        d999.seg("AK1*HC*1*005010X222A1")
        var rejectedSets = 0
        for (s in 0 until setCount) {
            d999.seg("AK2*837*" + (s + 1).toString().padStart(4, '0') + "*005010X222A1")
            if (setRejected[s]) { rejectedSets++; d999.seg("IK3*CLM*3*2300*8"); d999.seg("IK4*1*1028*7*" + id(s.toLong() * setSize + 1)); d999.seg("IK5*R*5") } else d999.seg("IK5*A")
        }
        d999.seg("AK9*" + (if (rejectedSets == 0) "A" else if (rejectedSets == setCount) "R" else "P") + "*$setCount*$setCount*${setCount - rejectedSets}")
        d999.se()
        d999.close()

        // 277CA
        val d277 = Doc(dir.resolve("status.277"), term, "HN", "005010X214", "RECEIVERTEST", "SENDERTEST")
        d277.st("277", "005010X214")
        d277.seg("BHT*0085*08*TESTSTATUS*20260102*1200*TH")
        var hl = 1
        for (n in 1..claims) {
            val v = effective(n)
            if (v == V.REJ277) {
                d277.seg("HL*${++hl}*1*PT*0"); d277.seg("TRN*2*${id(n)}"); d277.seg("STC*A3:21*20260102*U*${Money.fmt(chargeOf(seed, n))}")
            } else if (v == V.PAID && n % 7 == 0L) {
                d277.seg("HL*${++hl}*1*PT*0"); d277.seg("TRN*2*${id(n)}"); d277.seg("STC*A2:20*20260102*WQ*${Money.fmt(chargeOf(seed, n))}")
            }
        }
        d277.se()
        d277.close()

        return Result(
            claims, setCount, paid, partial, denied, reversed, adjusted, unmatched, rejSet, rej277, orphans, patresp,
            entries, (checks1 + checks2).toLong(), net1 + net2 - orphanPaid(orphans, seed), charged, rejectedSets,
        )
    }

    private fun orphanPaid(k: Long, seed: Long): Long = (1..k).sumOf { entry(it, 7500 + it, "orphan", seed).paid }
}
