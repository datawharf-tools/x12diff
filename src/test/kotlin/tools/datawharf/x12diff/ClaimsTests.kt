package tools.datawharf.x12diff

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

private const val ISA_LINE = "ISA*00*          *00*          *ZZ*SENDERTEST     *ZZ*RECEIVERTEST   *260101*1200*^*00501*000000001*0*T*:~"

/** Wraps transaction sets (each already ending in SE) in an interchange. gs = functional id + version. */
private fun wrap(gsFn: String, ver: String, vararg sets: String): String =
    ISA_LINE + "GS*$gsFn*A*B*20260101*1200*1*X*$ver~" + sets.joinToString("") + "GE*${sets.size}*1~IEA*1*000000001~"

private fun set(type: String, ctl: String, ver: String, vararg segs: String): String {
    val body = segs.joinToString("") { "$it~" }
    return "ST*$type*$ctl*$ver~" + body + "SE*${segs.size + 2}*$ctl~"
}

private fun tmp(vararg named: Pair<String, String>): Pair<Path, List<Path>> {
    val dir = Files.createTempDirectory("x12diff-claims")
    return dir to named.map { (n, t) -> dir.resolve(n).also { Files.writeString(it, t, Charsets.ISO_8859_1) } }
}

class ClaimsTests {
    // ---------- helpers ----------

    @Test
    fun csvEscapesFormulasButKeepsPlainNumbers() {
        assertEquals("'=1+1", Csv.cell("=1+1"))
        assertEquals("'@SUM(A1)", Csv.cell("@SUM(A1)"))
        assertEquals("'+cmd", Csv.cell("+cmd"))
        assertEquals("'-2+3", Csv.cell("-2+3"))
        assertEquals("-12.50", Csv.cell("-12.50"))
        assertEquals("+7", Csv.cell("+7"))
        assertEquals("12.50", Csv.cell("12.50"))
        assertEquals("\"'\tx\"".replace("\"", ""), Csv.cell("\tx"))
        assertEquals("\"'\rx\"", Csv.cell("\rx"))
        assertEquals("\"a,b\"", Csv.cell("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.cell("say \"hi\""))
        assertEquals("", Csv.cell(""))
    }

    @Test
    fun moneyParsesAndFormats() {
        assertEquals(12345L, Money.parse("123.45"))
        assertEquals(-1250L, Money.parse("-12.5"))
        assertEquals(700L, Money.parse("7"))
        assertEquals(50L, Money.parse(".5"))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("1.234"))
        assertNull(Money.parse(""))
        assertEquals("-12.50", Money.fmt(-1250))
        assertEquals("0.05", Money.fmt(5))
    }

    @Test
    fun diffCsvIsAlsoEscaped() {
        val dir = Files.createTempDirectory("x12diff-csv")
        try {
            val o = dir.resolve("o.834"); val n = dir.resolve("n.834")
            fun f(plan: String) = wrap("BE", "005010X220A1", set("834", "0001", "005010X220A1",
                "INS*Y*18*030*XN*A", "REF*0F*SUB1", "NM1*IL*1*TESTPERSON1*TESTFIRST****MI*9991", "HD*030**HLT*$plan"))
            Files.writeString(o, f("PLANA")); Files.writeString(n, f("=PLANB"))
            val csv = dir.resolve("c.csv")
            val code = run(arrayOf("diff", o.toString(), n.toString(), "--csv", csv.toString()), PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream()))
            assertEquals(1, code)
            assertTrue("'=PLANB" in Files.readString(csv), Files.readString(csv))
        } finally { dir.toFile().deleteRecursively() }
    }

    // ---------- remit ----------

    private fun remitFile(bpr: String, vararg claimSegs: String, plb: String? = null): String {
        val segs = ArrayList<String>()
        segs += "BPR*I*$bpr*C*CHK" + "*".repeat(11) + "*20261231"
        segs += "TRN*1*CHK0001*1999999999"
        segs += "N1*PR*TESTPAYER*XV*PAYER001"
        segs += "N1*PE*TESTPAYEE*XX*9999999999"
        segs += claimSegs
        if (plb != null) segs += plb
        return wrap("HP", "005010X221A1", set("835", "0001", "005010X221A1", *segs.toTypedArray()))
    }

    @Test
    fun remitFlattensLinesAndRollsUpAdjustments() {
        val text = remitFile(
            "120.00",
            "CLP*PCN1*1*200.00*120.00*20.00*12*CTL1",
            "SVC*HC:99213:25*120.00*80.00**2",
            "CAS*CO*45*20.00**97*10.00",
            "CAS*PR*1*10.00",
            "SVC*HC:99214*80.00*40.00**1",
            "CAS*PR*2*10.00",
            "CAS*CO*45*30.00",
        )
        val (dir, files) = tmp("r.835" to text)
        try {
            val csv = dir.resolve("lines.csv"); val roll = dir.resolve("roll.csv")
            val s = RemitCmd.run(files, Profile.default(), csv.toString(), roll.toString())
            assertEquals(1L, s.claims); assertEquals(2L, s.lines)
            assertEquals(20000L, s.charged); assertEquals(12000L, s.paid); assertEquals(2000L, s.patientResp)
            val lines = Files.readAllLines(csv)
            assertEquals(3, lines.size)
            assertTrue(lines[1].contains("PCN1") && lines[1].contains("HC,99213,25,2,120.00,80.00,CO:45:20.00|CO:97:10.00|PR:1:10.00,"), lines[1])
            assertTrue(lines[2].contains("HC,99214,,1,80.00,40.00,PR:2:10.00|CO:45:30.00,"), lines[2])
            assertTrue(lines[1].contains("PAYER001,9999999999,PCN1,1,200.00,120.00,20.00,CTL1"), lines[1])
            assertEquals(2L, s.rollup["CO-45"]!![0]); assertEquals(5000L, s.rollup["CO-45"]!![1])
            assertEquals(1000L, s.rollup["CO-97"]!![1])
            assertTrue(Files.readString(roll).contains("CO,45,,2,50.00"), Files.readString(roll))
            assertEquals(0L, s.warningCount, s.warnings.toString())
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun remitBalanceWarningsPerClaimAndPerCheck() {
        // claim: 100 - 60 - 30 leaves 10 unexplained; lines paid 50 vs CLP04 60; BPR02 says 70 but CLP04 60 - PLB 5 = 55
        val text = remitFile(
            "70.00",
            "CLP*PCN9*1*100.00*60.00*0.00*12*CTL9",
            "CAS*CO*45*30.00",
            "SVC*HC:99213*100.00*50.00**1",
            plb = "PLB*9999999999*20261231*WO:TEST*5.00",
        )
        val (dir, files) = tmp("r.835" to text)
        try {
            val s = RemitCmd.run(files, Profile.default(), null, null)
            val w = s.warnings.joinToString("\n")
            assertTrue("leaves 10.00 unexplained" in w, w)
            assertTrue("service-line paid amounts add up to 50.00 but CLP04 is 60.00" in w, w)
            assertTrue("BPR02 70.00 differs from sum of CLP04 60.00 minus PLB adjustments 5.00 = 55.00" in w, w)
            assertEquals(3L, s.warningCount)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun remitCsvEscapesFormulaInClaimIdAndRejectsNonX12() {
        val text = remitFile("0.00", "CLP*=CMD*4*10.00*0.00*0.00*12", "CAS*CO*50*10.00")
        val (dir, files) = tmp("r.835" to text, "junk.txt" to "hello")
        try {
            val csv = dir.resolve("o.csv")
            RemitCmd.run(listOf(files[0]), Profile.default(), csv.toString(), null)
            assertTrue("'=CMD" in Files.readString(csv))
            val err = ByteArrayOutputStream()
            assertEquals(2, run(arrayOf("remit", files[1].toString()), PrintStream(ByteArrayOutputStream()), PrintStream(err)))
            assertTrue(err.toString().startsWith("error: "), err.toString())
            assertFalse("Exception" in err.toString())
            // folder input skips non-X12 files
            assertEquals(listOf(files[0].fileName), expandInputs(listOf(dir.toString())).filter { it.fileName.toString().endsWith("835") }.map { it.fileName })
            assertFalse(expandInputs(listOf(dir.toString())).any { it.fileName.toString() == "junk.txt" })
        } finally { dir.toFile().deleteRecursively() }
    }

    // ---------- recon ----------

    private fun claims837(vararg ids: Pair<String, String>, st: String = "0001", date: String = "20260101"): String =
        set("837", st, "005010X222A1", *(listOf("BHT*0019*00*B1*20260101*1200*CH") +
            ids.flatMap { (id, ch) -> listOf("CLM*$id*$ch***11:B:1*Y*A*Y*Y", "DTP*472*D8*$date") }).toTypedArray())

    private fun clp(id: String, status: String, charge: String, paid: String, adj: String? = null): List<String> {
        val l = arrayListOf("CLP*$id*$status*$charge*$paid*0.00*12")
        if (adj != null) l += adj
        return l
    }

    private fun remit835(ctl: String, bpr: String, vararg segs: String) =
        set("835", ctl, "005010X221A1", "BPR*I*$bpr*C*CHK" + "*".repeat(11) + "*20261231", "TRN*1*CHK$ctl*1999999999", *segs)

    @Test
    fun reconClassifiesAndTracesRejections() {
        val f837 = wrap("HC", "005010X222A1",
            claims837("A1" to "100.00", "A2" to "100.00", "A3" to "100.00", "A4" to "100.00", "A5" to "100.00", st = "0001"),
            claims837("B1" to "50.00", "B2" to "50.00", st = "0002"),
            claims837("C1" to "10.00", st = "0003", date = "20260401"))
        val first = clp("A1", "1", "100.00", "100.00") + clp("A2", "1", "100.00", "40.00", "CAS*CO*97*60.00") +
            clp("A3", "4", "100.00", "0.00", "CAS*CO*50*100.00") + clp("A4", "1", "100.00", "100.00") +
            clp("ORPHAN1", "1", "5.00", "5.00")
        val f835 = wrap("HP", "005010X221A1",
            remit835("0001", "245.00", *first.toTypedArray()),
            remit835("0002", "-100.00", *clp("A4", "22", "-100.00", "-100.00").toTypedArray()))
        val f999 = wrap("FA", "005010X231A1", set("999", "0001", "005010X231A1",
            "AK1*HC*1*005010X222A1",
            "AK2*837*0001*005010X222A1", "IK5*A",
            "AK2*837*0002*005010X222A1", "IK5*R*5",
            "AK2*837*0003*005010X222A1", "IK5*A"))
        val f277 = wrap("HN", "005010X214", set("277", "0001", "005010X214",
            "BHT*0085*08*S*20260102*1200*TH", "HL*2*1*PT*0", "TRN*2*A5", "STC*A3:21*20260102*U*100.00",
            "HL*3*1*PT*0", "TRN*2*A1", "STC*A2:20*20260102*WQ*100.00"))
        val (dir, files) = tmp("c.837" to f837, "r.835" to f835, "a.999" to f999, "s.277" to f277)
        try {
            val csv = dir.resolve("out.csv")
            val s = ReconCmd.run(files, Profile.default(), csv.toString(), "20260501", 10)
            assertEquals(1L, s.counts["PAID"])           // A1
            assertEquals(1L, s.counts["PARTIAL"])        // A2
            assertEquals(1L, s.counts["DENIED"])         // A3
            assertEquals(1L, s.counts["REVERSED"])       // A4: paid, then reversed
            assertEquals(3L, s.counts["REJECTED"])       // A5 by 277CA, B1 and B2 by the rejected 999 set
            assertEquals(1L, s.counts["UNMATCHED"])      // C1
            assertEquals(1L, s.counts["ORPHAN"])
            assertEquals(1, s.rejectedSets)
            assertEquals(1L, s.aging["0-30"])            // C1 dated 2026-04-01, as of 2026-05-01
            assertEquals(0L, s.warningCount, s.warnings.toString())
            val text = Files.readString(csv)
            assertTrue("REJECTED,B1,50.00,,,,20260101,120,999 set rejected," in text, text)
            assertTrue("REJECTED,A5,100.00,,,,20260101,120,277CA category A3," in text, text)
            assertTrue("ORPHAN,ORPHAN1" in text, text)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun reconReversalCodesAreConfigurable() {
        val f837 = wrap("HC", "005010X222A1", claims837("R1" to "100.00"))
        val f835 = wrap("HP", "005010X221A1",
            remit835("0001", "100.00", *clp("R1", "1", "100.00", "100.00").toTypedArray()),
            remit835("0002", "-100.00", *clp("R1", "23", "-100.00", "-100.00").toTypedArray()))
        val (dir, files) = tmp("c.837" to f837, "r.835" to f835)
        try {
            val def = ReconCmd.run(files, Profile.default(), null, "20260501", 5)
            // code 23 is not a reversal by default, but the net paid is zero, so the claim shows as DENIED
            assertEquals(1L, def.counts["DENIED"])
            val p = Profile.parse("key.1 = REF*0F:02\nrecon.reversal.codes = 22, 23\n", "t")
            val cfg = ReconCmd.run(files, p, null, "20260501", 5)
            assertEquals(1L, cfg.counts["REVERSED"])
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun generatedClaimsMatchExpectedReconCounts() {
        val dir = Files.createTempDirectory("x12diff-gc")
        try {
            val g = GenClaims.generate(1500, dir, 9, lineBreaks = true, setSize = 30)
            val s = ReconCmd.run(expandInputs(listOf(dir.toString())), Profile.default(), dir.resolve("recon.csv").toString(), "20260601", 5)
            assertEquals(g.paid, s.counts["PAID"] ?: 0L)
            assertEquals(g.partial, s.counts["PARTIAL"] ?: 0L)
            assertEquals(g.denied, s.counts["DENIED"] ?: 0L)
            assertEquals(g.reversed, s.counts["REVERSED"] ?: 0L)
            assertEquals(g.adjusted, s.counts["ADJUSTED"] ?: 0L)
            assertEquals(g.unmatched, s.counts["UNMATCHED"] ?: 0L)
            assertEquals(g.rejected, s.counts["REJECTED"] ?: 0L)
            assertEquals(g.orphans, s.counts["ORPHAN"] ?: 0L)
            assertEquals(g.claims, s.claims)
            assertEquals(g.remitEntries, s.remitEntries)
            assertEquals(g.remitChecks, s.remitChecks)
            assertEquals(g.netPaidCents, s.paidTotal)
            assertEquals(g.chargedCents, s.chargedTotal)
            assertEquals(g.rejectedSets, s.rejectedSets)
            assertEquals(0L, s.warningCount, s.warnings.toString())
            // remit over the same files is balanced and counts the same entries
            val r = RemitCmd.run(listOf(dir.resolve("remits_1.835"), dir.resolve("remits_2.835")), Profile.default(), null, null)
            assertEquals(0L, r.warningCount, r.warnings.toString())
            assertEquals(g.remitEntries, r.claims)
            assertEquals(r.claims * 2, r.lines)
            // all generated envelopes are consistent
            for (n in listOf("claims.837", "remits_1.835", "remits_2.835", "ack.999", "status.277")) {
                val c = EnvelopeChecker()
                Files.newInputStream(dir.resolve(n)).use { i -> val rd = X12Reader(i); while (true) c.accept(rd.next() ?: break) }
                c.finish()
                assertTrue(c.warnings.isEmpty(), "$n: ${c.warnings}")
            }
            assertTrue(Files.readString(dir.resolve("status.277")).contains("005010X214"))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun malformedClaimInputsGiveClearErrors() {
        val (dir, files) = tmp("bad.837" to "ISA*00*short")
        try {
            val ex = assertThrows<X12Exception> { ReconCmd.run(files, Profile.default(), null, "20260501", 5) }
            assertTrue("bad.837" in ex.message!!, ex.message)
            assertThrows<X12Exception> { ReconCmd.run(files, Profile.default(), null, "not-a-date", 5) }
            val err = ByteArrayOutputStream()
            assertEquals(2, run(arrayOf("recon", files[0].toString()), PrintStream(ByteArrayOutputStream()), PrintStream(err)))
            assertTrue(err.toString().startsWith("error: "), err.toString())
            assertEquals(2, run(arrayOf("recon"), PrintStream(ByteArrayOutputStream()), PrintStream(err)))
            assertEquals(2, run(arrayOf("remit", "--nonsense"), PrintStream(ByteArrayOutputStream()), PrintStream(err)))
        } finally { dir.toFile().deleteRecursively() }
    }
}
