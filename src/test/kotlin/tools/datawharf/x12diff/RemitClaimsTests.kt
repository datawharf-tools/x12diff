package tools.datawharf.x12diff

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

private const val ISA = "ISA*00*          *00*          *ZZ*SENDERTEST     *ZZ*RECEIVERTEST   *260101*1200*^*00501*000000001*0*T*:~"

private fun remit835(bpr: String, vararg segs: String): String {
    val all = ArrayList<String>()
    all += "BPR*I*$bpr*C*CHK" + "*".repeat(11) + "*20261231"
    all += "TRN*1*SYN-CHK1*1999999999"
    all += "N1*PR*TESTPAYER*XV*SYN-PAYER"
    all += "N1*PE*TESTPAYEE*XX*SYN-PAYEE"
    all += segs
    val body = all.joinToString("") { "$it~" }
    return ISA + "GS*HP*A*B*20260101*1200*1*X*005010X221A1~ST*835*0001*005010X221A1~" + body +
        "SE*${all.size + 2}*0001~GE*1*1~IEA*1*000000001~"
}

/** Minimal RFC 4180 reader (the BOM is dropped); returns rows as header-keyed maps. */
private fun readCsv(p: Path): List<Map<String, String>> {
    val t = Files.readString(p).removePrefix("﻿")
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    val sb = StringBuilder()
    var q = false
    var i = 0
    while (i < t.length) {
        val c = t[i]
        if (q) {
            if (c == '"') {
                if (i + 1 < t.length && t[i + 1] == '"') { sb.append('"'); i++ } else q = false
            } else sb.append(c)
        } else when (c) {
            '"' -> q = true
            ',' -> { row.add(sb.toString()); sb.setLength(0) }
            '\r' -> {}
            '\n' -> { row.add(sb.toString()); sb.setLength(0); rows.add(row); row = ArrayList() }
            else -> sb.append(c)
        }
        i++
    }
    val head = rows[0]
    return rows.drop(1).map { r -> head.indices.associate { head[it] to r[it] } }
}

class RemitClaimsTests {
    private val claimsCols = ("file,check_number,payment_date,payer_id,payee_id,claim_id,claim_status,claim_charge,claim_paid," +
        "patient_resp,payer_claim_ctrl,line_count,line_charge_total,line_paid_total,claim_adj_total,line_adj_total," +
        "adj_CO,adj_PR,adj_OA,adj_PI,adj_CR,balance_diff,balance_ok,lines_paid_ok,has_invalid_amount").split(",")
    private val adjCols = "file,check_number,claim_id,level,line_no,service_code,group,reason,amount".split(",")

    private fun runIt(text: String, block: (List<Map<String, String>>, List<Map<String, String>>, RemitSummary) -> Unit) {
        val dir = Files.createTempDirectory("x12diff-rc")
        try {
            val f = dir.resolve("r.835")
            Files.writeString(f, text, Charsets.ISO_8859_1)
            val c = dir.resolve("claims.csv")
            val a = dir.resolve("adj.csv")
            val s = RemitCmd.run(listOf(f), Profile.default(), null, null, c.toString(), a.toString())
            assertEquals(claimsCols, Files.readAllLines(c)[0].removePrefix("﻿").split(","))
            assertEquals(adjCols, Files.readAllLines(a)[0].removePrefix("﻿").split(","))
            block(readCsv(c), readCsv(a), s)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun normalClaimWithTwoLines() = runIt(
        remit835(
            "120.00", "CLP*SYN-1*1*200.00*120.00*20.00*12*CTL1",
            "CAS*CO*45*40.00", "SVC*HC:99213*120.00*80.00**1", "CAS*CO*97*10.00", "CAS*PR*1*10.00",
            "SVC*HC:99214*80.00*40.00**1", "CAS*CO*45*20.00",
        ),
    ) { claims, adjs, s ->
        assertEquals(1, claims.size)
        val c = claims[0]
        assertEquals("SYN-1", c["claim_id"]); assertEquals("SYN-CHK1", c["check_number"]); assertEquals("SYN-PAYER", c["payer_id"])
        assertEquals("2", c["line_count"]); assertEquals("200.00", c["line_charge_total"]); assertEquals("120.00", c["line_paid_total"])
        assertEquals("40.00", c["claim_adj_total"]); assertEquals("40.00", c["line_adj_total"])
        assertEquals("70.00", c["adj_CO"]); assertEquals("10.00", c["adj_PR"]); assertEquals("0.00", c["adj_OA"])
        assertEquals("0.00", c["balance_diff"]); assertEquals("Y", c["balance_ok"]); assertEquals("Y", c["lines_paid_ok"]); assertEquals("N", c["has_invalid_amount"])
        assertEquals(listOf("claim", "line", "line", "line"), adjs.map { it["level"] })
        assertEquals(listOf("", "1", "1", "2"), adjs.map { it["line_no"] })
        assertEquals(listOf("", "99213", "99213", "99214"), adjs.map { it["service_code"] })
        assertEquals(0L, s.warningCount, s.warnings.toString())
    }

    @Test
    fun claimWithNoServiceLines() = runIt(
        remit835("0.00", "CLP*SYN-2*4*10.00*0.00*0.00*12", "CAS*CO*50*10.00"),
    ) { claims, adjs, _ ->
        val c = claims[0]
        assertEquals("0", c["line_count"]); assertEquals("", c["lines_paid_ok"]); assertEquals("", c["line_charge_total"])
        assertEquals("10.00", c["claim_adj_total"]); assertEquals("0.00", c["line_adj_total"]); assertEquals("Y", c["balance_ok"])
        assertEquals(1, adjs.size); assertEquals("claim", adjs[0]["level"]); assertEquals("10.00", adjs[0]["amount"])
    }

    @Test
    fun invalidAmountKeepsRawTextAndBlanksDependentCells() = runIt(
        remit835("0.00", "CLP*SYN-3*1*100.005*0.00*0.00*12", "CAS*CO*45*100.00"),
    ) { claims, _, s ->
        val c = claims[0]
        assertEquals("100.005", c["claim_charge"]); assertEquals("Y", c["has_invalid_amount"])
        assertEquals("", c["balance_diff"]); assertEquals("", c["balance_ok"]); assertEquals("", c["lines_paid_ok"])
        assertTrue(s.invalidAmounts > 0)
    }

    @Test
    fun invalidAdjustmentAmountKeepsRawTextInBothFiles() = runIt(
        remit835("0.00", "CLP*SYN-3B*1*100.00*0.00*0.00*12", "CAS*CO*45*95.00**97*5.555"),
    ) { claims, adjs, _ ->
        val c = claims[0]
        assertEquals("Y", c["has_invalid_amount"]); assertEquals("", c["claim_adj_total"]); assertEquals("", c["adj_CO"])
        assertEquals("0.00", c["adj_PR"]); assertEquals("", c["balance_ok"])
        assertEquals(listOf("95.00", "5.555"), adjs.map { it["amount"] })
    }

    @Test
    fun blankPatientRespIsBlankButCounts() = runIt(
        remit835("50.00", "CLP*SYN-4*1*50.00*50.00**12"),
    ) { claims, _, s ->
        val c = claims[0]
        assertEquals("", c["patient_resp"]); assertEquals("N", c["has_invalid_amount"]); assertEquals("Y", c["balance_ok"]); assertEquals("0.00", c["balance_diff"])
        assertEquals(0L, s.invalidAmounts)
    }

    @Test
    fun claimLevelAdjustmentsOnMultiLineClaimAreCountedOnce() = runIt(
        remit835(
            "0.00", "CLP*SYN-5*1*90.00*0.00*0.00*12", "CAS*CO*45*30.00**97*10.00",
            "SVC*HC:1*30.00*0.00**1", "SVC*HC:2*30.00*0.00**1", "SVC*HC:3*30.00*0.00**1",
        ),
    ) { claims, adjs, _ ->
        assertEquals(1, claims.size)
        assertEquals("40.00", claims[0]["claim_adj_total"]); assertEquals("0.00", claims[0]["line_adj_total"])
        assertEquals("40.00", claims[0]["adj_CO"]); assertEquals("3", claims[0]["line_count"])
        assertEquals(2, adjs.size); assertTrue(adjs.all { it["level"] == "claim" && it["line_no"] == "" })
        assertEquals("50.00", claims[0]["balance_diff"]); assertEquals("N", claims[0]["balance_ok"])
    }

    @Test
    fun unbalancedClaim() = runIt(
        remit835("60.00", "CLP*SYN-6*1*100.00*60.00*0.00*12", "CAS*CO*45*30.00", "SVC*HC:1*100.00*50.00**1"),
    ) { claims, _, s ->
        val c = claims[0]
        assertEquals("10.00", c["balance_diff"]); assertEquals("N", c["balance_ok"]); assertEquals("N", c["lines_paid_ok"])
        assertTrue(s.warnings.any { "leaves 10.00 unexplained" in it })
    }

    @Test
    fun injectionLookingIdIsEscapedInBothFiles() {
        val dir = Files.createTempDirectory("x12diff-rc")
        try {
            val f = dir.resolve("r.835")
            Files.writeString(f, remit835("0.00", "CLP*=SYN-CMD*4*10.00*0.00*0.00*12", "CAS*CO*50*10.00"), Charsets.ISO_8859_1)
            val c = dir.resolve("c.csv")
            val a = dir.resolve("a.csv")
            RemitCmd.run(listOf(f), Profile.default(), null, null, c.toString(), a.toString())
            assertEquals("'=SYN-CMD", readCsv(c)[0]["claim_id"]); assertEquals("'=SYN-CMD", readCsv(a)[0]["claim_id"])
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun cliPrintsLinesAndRejectsSameFile() {
        val dir = Files.createTempDirectory("x12diff-rc")
        try {
            val f = dir.resolve("r.835")
            Files.writeString(f, remit835("0.00", "CLP*SYN-7*4*10.00*0.00*0.00*12", "CAS*CO*50*10.00"), Charsets.ISO_8859_1)
            val out = ByteArrayOutputStream()
            val code = run(
                arrayOf("remit", f.toString(), "--claims", dir.resolve("c.csv").toString(), "--adjustments", dir.resolve("a.csv").toString()),
                PrintStream(out), PrintStream(ByteArrayOutputStream()),
            )
            assertEquals(0, code)
            assertTrue("Claims written:" in out.toString() && "Adjustments written:" in out.toString(), out.toString())
            val x = dir.resolve("x.csv").toString()
            assertEquals(2, run(arrayOf("remit", f.toString(), "--claims", x, "--adjustments", x), PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream())))
            assertEquals(2, run(arrayOf("remit", f.toString(), "--adjustments", f.toString()), PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream())))
            assertEquals(2, run(arrayOf("remit", f.toString(), "--claims", f.toString()), PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream())))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun generatedFileCrossCheck() {
        val dir = Files.createTempDirectory("x12diff-rc")
        try {
            GenClaims.generate(1500, dir, 11, lineBreaks = false, setSize = 30)
            val files = listOf(dir.resolve("remits_1.835"), dir.resolve("remits_2.835"))
            val c = dir.resolve("claims.csv")
            val a = dir.resolve("adj.csv")
            val r = dir.resolve("roll.csv")
            val s = RemitCmd.run(files, Profile.default(), null, r.toString(), c.toString(), a.toString())
            val claims = readCsv(c)
            assertEquals(s.claims, claims.size.toLong())
            fun sum(col: String) = claims.sumOf { Money.parse(it[col]!!) ?: 0L }
            assertEquals(s.charged, sum("claim_charge")); assertEquals(s.paid, sum("claim_paid")); assertEquals(s.patientResp, sum("patient_resp"))
            val byKey = java.util.TreeMap<String, Long>()
            for (row in readCsv(a)) byKey.merge(row["group"] + "-" + row["reason"], Money.parse(row["amount"]!!)!!, Long::plus)
            val roll = readCsv(r).associate { (it["group"] + "-" + it["reason"]) to Money.parse(it["amount"]!!)!! }
            assertEquals(roll, byKey)
            assertEquals(s.rollup.mapValues { it.value[1] }, byKey)
            assertTrue(byKey.isNotEmpty())
            for (g in listOf("CO", "PR", "OA", "PI", "CR")) {
                assertEquals(byKey.filterKeys { it.startsWith("$g-") }.values.sum(), sum("adj_$g"), g)
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}
