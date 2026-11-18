package tools.datawharf.x12diff

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/** Regression tests for the findings in REVIEW_1.md (fixtures follow the reviewer's repros). */

private class S(val st01: String, val st02: String, val impl: String, val segs: List<String>)

private fun isa(ctl: String = "000000001", e: Char = '*', s: Char = ':', r: Char = '^', t: String = "~"): String =
    "ISA${e}00$e          ${e}00$e          ${e}ZZ${e}SENDERTEST     ${e}ZZ${e}RECEIVERTEST   ${e}260101${e}1200$e$r${e}00501$e$ctl${e}0${e}T$e$s$t"

private fun env(
    vararg sets: S, gsctl: String = "1", isactl: String = "000000001", fid: String = "BE",
    e: Char = '*', s: Char = ':', r: Char = '^', t: String = "~", gsdate: String = "20260101", gsver: String = "005010X000",
): String {
    val sb = StringBuilder(isa(isactl, e, s, r, t))
    sb.append(listOf("GS", fid, "SENDER", "RECEIVER", gsdate, "1200", gsctl, "X", gsver).joinToString(e.toString())).append(t)
    for (st in sets) {
        sb.append(listOf("ST", st.st01, st.st02, st.impl).joinToString(e.toString())).append(t)
        for (g in st.segs) sb.append(g).append(t)
        sb.append(listOf("SE", (st.segs.size + 2).toString(), st.st02).joinToString(e.toString())).append(t)
    }
    sb.append("GE${e}${sets.size}${e}$gsctl$t").append("IEA${e}1${e}$isactl$t")
    return sb.toString()
}

private fun member(sub: String, mid: String?, rel: String = "18", ins3: String = "030", last: String = "TESTLAST", first: String = "TESTFIRST", dob: String = "19800101", extra: List<String> = emptyList()): List<String> {
    val l = arrayListOf("INS*${if (rel == "18") "Y" else "N"}*$rel*$ins3*XN*A***FT", "REF*0F*$sub", "REF*1L*GRP001")
    l += if (mid != null) "NM1*IL*1*$last*$first****34*$mid" else "NM1*IL*1*$last*$first"
    l += "DMG*D8*$dob*F"; l += "HD*030**HLT*PLAN1"; l += "DTP*348*D8*20260101"
    l += extra
    return l
}

private fun f834(members: List<List<String>>, bgn8: String = "4", e: Char = '*', s: Char = ':', r: Char = '^', t: String = "~"): String {
    val segs = arrayListOf("BGN*00*REF1*20260101*1200****$bgn8", "N1*P5*SPONSOR TEST*FI*000000000", "N1*IN*PAYER TEST*FI*000000001")
    members.forEach { segs += it }
    return env(S("834", "0001", "005010X220A1", segs), e = e, s = s, r = r, t = t)
}

private fun f835(claims: List<String>, bpr: String, plb: String? = null, trn: String = "CHK001", st: String = "0001"): S {
    val segs = arrayListOf(
        "BPR*I*$bpr*C*ACH*CCP*01*999999999*DA*123456*1512345678**01*999999999*DA*654321*20260115",
        "TRN*1*$trn*1512345678", "DTM*405*20260115", "N1*PR*PAYER TEST", "N3*1 TEST WAY", "N4*TESTCITY*FL*00000",
        "REF*2U*PAYERID1", "N1*PE*PROVIDER TEST*XX*1999999999", "LX*1",
    )
    segs += claims
    if (plb != null) segs += plb
    return S("835", st, "005010X221A1", segs)
}

private fun f837(claims: List<Triple<String, String, String>>, st: String = "0001", impl: String = "005010X222A1", bht: String = "20260101"): S {
    val segs = arrayListOf("BHT*0019*00*BATCH1*$bht*1200*CH", "NM1*41*2*SUBMITTER TEST*****46*S1", "NM1*40*2*RECEIVER TEST*****46*R1", "HL*1**20*1", "NM1*85*2*BILLING TEST*****XX*1999999999")
    var hl = 2
    for ((cid, chg, dt) in claims) {
        segs += listOf("HL*${hl++}*1*22*0", "SBR*P*18*******CI", "NM1*IL*1*TESTLAST*TESTFIRST****MI*M1", "CLM*$cid*$chg***11:B:1*Y*A*Y*Y", "HI*ABK:Z0000", "LX*1", "SV1*HC:99213*$chg*UN*1***1", "DTP*472*D8*$dt")
    }
    return S("837", st, impl, segs)
}

private fun st277(cid: String, cat: String, date: String = "20260110", withLine: Boolean = false): S {
    val segs = arrayListOf("BHT*0085*08*T1*20260110*1200*TH", "HL*1**20*1", "NM1*PR*2*PAYER TEST*****PI*P1", "HL*2*1*21*1", "TRN*1*BATCH1", "STC*A1:19*20260110*WQ*100",
        "HL*3*2*19*1", "HL*4*3*PT", "NM1*QC*1*TESTLAST*TESTFIRST****MI*M1", "TRN*2*$cid", "STC*$cat:21:PR*$date*U*100", "REF*1K*PAYERCLAIM1")
    if (withLine) { segs += "SVC*HC:99213*100"; segs += "STC*A7:21*$date*U*100" }
    return S("277", "0001", "005010X214", segs)
}

private fun inTemp(block: (Path) -> Unit) {
    val dir = Files.createTempDirectory("x12diff-reg")
    try { block(dir) } finally { dir.toFile().deleteRecursively() }
}

private fun write(dir: Path, name: String, text: String): Path = dir.resolve(name).also { Files.writeString(it, text, Charsets.ISO_8859_1) }

private class Res(val code: Int, val out: String, val err: String)

private fun cli(vararg args: String): Res {
    val o = ByteArrayOutputStream(); val e = ByteArrayOutputStream()
    val code = run(arrayOf(*args), PrintStream(o, true, "UTF-8"), PrintStream(e, true, "UTF-8"))
    return Res(code, o.toString("UTF-8"), e.toString("UTF-8"))
}

private fun recon(files: List<Path>, asof: String = "20260301", profile: Profile = Profile.default(), csv: Path? = null) =
    ReconCmd.run(files, profile, csv?.toString(), asof, 10)

class RegressionTests {
    // ---------- C1 ----------
    @Test
    fun c1_changeFilesDoNotTermAbsentMembers() = inTemp { d ->
        val w1 = write(d, "w1.834", f834(listOf(member("SUBA", "MA", ins3 = "001"), member("SUBB", "MB", ins3 = "001")), bgn8 = "2"))
        val w2 = write(d, "w2.834", f834(listOf(member("SUBC", "MC", ins3 = "001")), bgn8 = "2"))
        val rows = ArrayList<Row>()
        val s = Differ.diff(w1, w2, Profile.default()) { rows.add(it) }
        assertEquals("change", s.mode)
        assertEquals(0L, s.termed)
        assertEquals(2L, s.absentNotTermed)
        assertEquals(1L, s.added)
        assertEquals(2, rows.count { it.type == "NOT_IN_NEW" })
        val r = cli("diff", w1.toString(), w2.toString())
        assertTrue("NOT_IN_NEW: 2" in r.out && "Mode: change" in r.out, r.out)
        // override
        val forced = Differ.diff(w1, w2, Profile.default(), "full") { }
        assertEquals(2L, forced.termedAbsent)
        assertEquals("full", forced.mode)
    }

    @Test
    fun c1_fullFilesStillTerm_unknownAndMixedWarn() = inTemp { d ->
        val o = write(d, "o.834", f834(listOf(member("SUBA", "MA"), member("SUBB", "MB")), bgn8 = "4"))
        val n = write(d, "n.834", f834(listOf(member("SUBA", "MA")), bgn8 = "4"))
        val s = Differ.diff(o, n, Profile.default()) { }
        assertEquals("full", s.mode); assertEquals(1L, s.termedAbsent); assertTrue(s.notes.isEmpty())
        val unk = write(d, "u.834", f834(listOf(member("SUBA", "MA")), bgn8 = ""))
        val s2 = Differ.diff(o, unk, Profile.default()) { }
        assertEquals("full", s2.mode)
        assertTrue(s2.notes.any { it.startsWith("WARNING") && "unknown or mixed" in it && "Treated as full" in it }, s2.notes.toString())
        // old full, new change -> change mode
        val chg = write(d, "c.834", f834(listOf(member("SUBA", "MA")), bgn8 = "2"))
        assertEquals("change", Differ.diff(o, chg, Profile.default()) { }.mode)
        assertEquals(2, cli("diff", o.toString(), n.toString(), "--mode", "bogus").code)
    }

    // ---------- C2 / M3 ----------
    @Test
    fun c2_truncatedNewFileIsLoudAndNonZero() = inTemp { d ->
        val members = (1..4).map { member("SUB$it", "M$it") }
        val old = write(d, "old.834", f834(members))
        val full = f834(members)
        val cut = full.indexOf("INS*Y*18*030*XN*A***FT~REF*0F*SUB3")
        val trunc = write(d, "trunc.834", full.substring(0, cut))
        val r = cli("diff", old.toString(), trunc.toString())
        assertEquals(3, r.code, r.out + r.err)
        assertTrue("INPUT INCOMPLETE" in r.err && "no SE" in r.err, r.err)
        assertTrue("INPUT INCOMPLETE" in r.out, r.out)
        assertTrue("allow-incomplete" in r.err)
        val ok = cli("diff", old.toString(), trunc.toString(), "--allow-incomplete")
        assertEquals(1, ok.code, ok.out + ok.err)
        assertTrue("accepted by --allow-incomplete" in ok.err, ok.err)
        // a complete pair is unaffected
        assertEquals(0, cli("diff", old.toString(), old.toString()).code)
    }

    @Test
    fun m3_truncated837InReconAndTruncatedRemit() = inTemp { d ->
        val full = env(f837(listOf(Triple("T1", "100", "20260101"), Triple("T2", "250", "20260101"))), fid = "HC")
        val cut = full.indexOf("SV1*HC:99213*250") + "SV1*HC:99213*25".length
        val p = write(d, "c.837", full.substring(0, cut))
        val s = recon(listOf(p))
        assertTrue(s.envelope.any)
        assertTrue(s.warnings.any { "cut off" in it }, s.warnings.toString())
        assertEquals(3, cli("recon", p.toString()).code)
        assertEquals(0, cli("recon", p.toString(), "--allow-incomplete").code)
        val rem = env(f835(listOf("CLP*A*1*10*10*0*12", "SVC*HC:99213*10*10**1"), "10"), fid = "HP")
        val q = write(d, "r.835", rem.substring(0, rem.indexOf("SE*")))
        assertEquals(3, cli("remit", q.toString()).code)
    }

    // ---------- C3 ----------
    @Test
    fun c3_duplicateClm01NetsReversalAndRepaymentOnTheRightClaim() = inTemp { d ->
        val c = write(d, "c.837", env(f837(listOf(Triple("DUP1", "100", "20260101"), Triple("DUP1", "200", "20260102"))), fid = "HC"))
        val r = write(d, "r.835", env(f835(listOf(
            "CLP*DUP1*1*200*200*0*12*X1", "CLP*DUP1*22*-200*-200*0*12*X1", "CLP*DUP1*1*200*180*0*12*X2"), "180"), fid = "HP"))
        val csv = d.resolve("o.csv")
        val s = recon(listOf(c, r), csv = csv)
        val text = Files.readString(csv)
        assertTrue("ADJUSTED,DUP1,200.00,200.00,180.00" in text, text)
        assertTrue("UNMATCHED,DUP1,100.00" in text, text)
        assertEquals(1L, s.counts["ADJUSTED"]); assertEquals(1L, s.counts["UNMATCHED"])
        assertEquals(null, s.counts["REVERSED"])
        assertEquals(1L, s.duplicateClaimIds)
        assertTrue(s.warnings.any { "duplicate CLM01 'DUP1'" in it }, s.warnings.toString())
    }

    @Test
    fun c3_paidAboveChargeIsFlagged() = inTemp { d ->
        val c = write(d, "c.837", env(f837(listOf(Triple("P1", "100", "20260101"))), fid = "HC"))
        val r = write(d, "r.835", env(f835(listOf("CLP*P1*1*100*150*0*12", "CAS*CO*45*-50"), "150"), fid = "HP"))
        val s = recon(listOf(c, r))
        assertTrue(s.warnings.any { "exceeds the 837 charge" in it }, s.warnings.toString())
    }

    // ---------- C4 ----------
    private fun monthFiles(d: Path, ackName: String, ack: String): List<Path> {
        val jan = write(d, "jan.837", env(f837(listOf(Triple("JAN1", "100", "20260105")), bht = "20260105"), isactl = "000000011", fid = "HC", gsver = "005010X222A1"))
        val feb = write(d, "feb.837", env(f837(listOf(Triple("FEB1", "100", "20260205")), bht = "20260205"), isactl = "000000012", fid = "HC", gsver = "005010X222A1"))
        val a = write(d, ackName, env(S("999", "0001", "005010X231A1", listOf("AK1*HC*1*005010X222A1", "AK2*837*0001*005010X222A1", "IK3*CLM*5**8", "IK5*R*5", ack)), gsctl = "7", isactl = "000000013", fid = "FA"))
        return listOf(feb, jan, a)
    }

    @Test
    fun c4_ackCollisionIsResolvedByFileContextAndNeverMarksBoth() = inTemp { d ->
        val files = monthFiles(d, "jan.999", "AK9*R*1*1*0")
        val s = recon(files)
        assertEquals(1L, s.counts["REJECTED"], s.counts.toString())   // JAN1 only
        assertEquals(1L, s.counts["UNMATCHED"])                        // FEB1 was never rejected
        assertEquals(1, s.rejectedSets)
    }

    @Test
    fun c4_ambiguousAckMarksNothingAndWarns() = inTemp { d ->
        val files = monthFiles(d, "ack_x.999", "AK9*R*1*1*0")
        val s = recon(files)
        assertEquals(null, s.counts["REJECTED"], s.counts.toString())
        assertEquals(2L, s.counts["UNMATCHED"])
        assertTrue(s.ambiguousTies > 0)
        assertTrue(s.warnings.any { "no claim was marked rejected" in it }, s.warnings.toString())
    }

    // ---------- H1 ----------
    @Test
    fun h1_pathsWildcardsAndNegativeShowGiveCleanErrors() = inTemp { d ->
        for (args in listOf(
            arrayOf("remit", "my claims/*.835"), arrayOf("remit", "C:\\nope\\*.835"), arrayOf("parse", "a<b.834"),
            arrayOf("remit", "x.835", "--show", "-5"), arrayOf("recon", "x.837", "--show", "-5"), arrayOf("diff", "a<b", "c>d"),
            arrayOf("diff", "a", "b", "--show", "-1"), arrayOf("gen", "--members", "1", "--old", "q<.834", "--new", "n.834"),
        )) {
            val r = cli(*args)
            assertEquals(2, r.code, args.joinToString(" ") + " -> " + r.out + r.err)
            assertTrue(r.err.startsWith("error: "), r.err)
            assertFalse("Exception" in r.err || "\tat " in r.err, r.err)
        }
        // wildcards and spaces in folder names work
        val sp = Files.createDirectory(d.resolve("my claims"))
        write(sp, "a.835", env(f835(listOf("CLP*A*1*10*10*0*12", "SVC*HC:99213*10*10**1"), "10"), fid = "HP"))
        write(sp, "notes.txt", "hello")
        val r = cli("remit", sp.toString() + java.io.File.separator + "*.835")
        assertEquals(0, r.code, r.err)
        assertTrue("Claims: 1" in r.out, r.out)
        val folder = cli("remit", sp.toString())
        assertEquals(0, folder.code, folder.err)
        assertTrue("Skipped (not X12): notes.txt" in folder.out, folder.out)
        assertTrue(expandInputs(listOf(sp.toString() + "/*.835")).size == 1)
    }

    // ---------- H2 ----------
    @Test
    fun h2_zeroPaidToPatientResponsibilityIsNotDenied() = inTemp { d ->
        val c = write(d, "c.837", env(f837(listOf(Triple("PCN3", "150", "20260101"), Triple("PCN4", "150", "20260101"))), fid = "HC"))
        val r = write(d, "r.835", env(f835(listOf(
            "CLP*PCN3*1*150*0*150*12*PCTL3", "CAS*PR*1*150", "SVC*HC:99213*150*0**1",
            "CLP*PCN4*4*150*0*0*12*PCTL4", "CAS*CO*50*150"), "0"), fid = "HP"))
        val s = recon(listOf(c, r))
        assertEquals(1L, s.counts["PATIENT_RESP"]); assertEquals(1L, s.counts["DENIED"])
    }

    // ---------- H4 ----------
    @Test
    fun h4_siblingsWithoutIdsAreKeyedByNameAndBirthDate() = inTemp { d ->
        val sub = member("SUBF", "MF")
        val a = member("SUBF", null, rel = "19", first = "CHILDA", dob = "20100101")
        val b = member("SUBF", null, rel = "19", first = "CHILDB", dob = "20120202")
        val o = write(d, "o.834", f834(listOf(sub, a, b)))
        val n = write(d, "n.834", f834(listOf(sub, b)))
        val rows = ArrayList<Row>()
        val s = Differ.diff(o, n, Profile.default()) { rows.add(it) }
        assertEquals(1L, s.termed); assertEquals(0L, s.changed); assertEquals(2L, s.unchanged)
        assertTrue(rows.single().key.contains("CHILDA"), rows.single().key)
        assertEquals(0L, s.duplicateKeys)
    }

    @Test
    fun h4_trueDuplicateKeysAreMarkedAmbiguous() = inTemp { d ->
        val twin1 = member("SUBD", null, rel = "19", first = "TWIN", dob = "20100101")
        val twin2 = member("SUBD", null, rel = "19", first = "TWIN", dob = "20100101", extra = listOf("DTP*349*D8*20260301"))
        val o = write(d, "o.834", f834(listOf(twin1, twin1)))
        val n = write(d, "n.834", f834(listOf(twin1, twin2)))
        val rows = ArrayList<Row>()
        val s = Differ.diff(o, n, Profile.default()) { rows.add(it) }
        assertEquals(1L, s.duplicateKeys)
        assertTrue(s.ambiguousMembers >= 2)
        assertTrue(rows.all { "ambiguous key" in it.detail }, rows.map { it.detail }.toString())
        assertTrue("ambiguous key" in cli("diff", o.toString(), n.toString(), "--csv", d.resolve("x.csv").toString()).out.let { Files.readString(d.resolve("x.csv")) })
    }

    // ---------- M1 ----------
    @Test
    fun m1_claimAndLineAdjustmentsTogetherBalance() = inTemp { d ->
        val r = write(d, "r.835", env(f835(listOf(
            "CLP*PCN1*1*100*60*10*12*PAYERCTL1", "CAS*PR*1*10", "SVC*HC:99213:25:59*100*70**1", "DTM*472*20260105", "CAS*CO*45*30"), "60"), fid = "HP"))
        val s = RemitCmd.run(listOf(r), Profile.default(), null, null)
        assertEquals(0L, s.warningCount, s.warnings.toString())
        // a genuinely unbalanced claim still warns
        val bad = write(d, "b.835", env(f835(listOf("CLP*PCN1*1*100*60*10*12", "CAS*PR*1*10"), "60"), fid = "HP"))
        assertTrue(RemitCmd.run(listOf(bad), Profile.default(), null, null).warnings.any { "unexplained" in it })
    }

    // ---------- M2 ----------
    @Test
    fun m2_invalidAmountsAreNotInventedAsZero() = inTemp { d ->
        val r = write(d, "r.835", env(f835(listOf("CLP*PCN6*1*100.005*100*0*12*P6", "SVC*HC:99213*100*100**1"), "100"), fid = "HP"))
        val csv = d.resolve("o.csv")
        val s = RemitCmd.run(listOf(r), Profile.default(), csv.toString(), null)
        val line = Files.readAllLines(csv)[1]
        assertTrue(",100.005,100.00," in line, line)
        assertEquals(0L, s.charged)
        assertEquals(1L, s.invalidAmounts)
        assertTrue(s.warnings.any { "'100.005' is not a valid amount" in it }, s.warnings.toString())
        assertFalse(s.warnings.any { "unexplained" in it }, s.warnings.toString())
        val p = write(d, "p.835", env(f835(listOf("CLP*PCN4*1*100*100*0*12*P4", "SVC*HC:99213*100*100**1"), "90", "PLB*1512345678*20261231*WO:R*1O.00"), fid = "HP"))
        val s2 = RemitCmd.run(listOf(p), Profile.default(), null, null)
        assertTrue(s2.warnings.any { "PLB amount '1O.00' is not a valid amount" in it }, s2.warnings.toString())
        assertFalse(s2.warnings.any { "differs from sum" in it }, s2.warnings.toString())
    }

    // ---------- M4 / L9 ----------
    @Test
    fun m4_laterAcceptanceClearsA277Rejection() = inTemp { d ->
        val c = write(d, "c.837", env(f837(listOf(Triple("RS1", "100", "20260101"))), fid = "HC"))
        val rej = write(d, "z_rej.277", env(st277("RS1", "A7", "20260110"), gsctl = "2", isactl = "000000021", fid = "HN"))
        val acc = write(d, "a_acc.277", env(st277("RS1", "A2", "20260111"), gsctl = "3", isactl = "000000022", fid = "HN"))
        assertEquals(null, recon(listOf(c, rej, acc)).counts["REJECTED"])
        assertEquals(1L, recon(listOf(c, rej)).counts["REJECTED"])
        // same date: the acceptance wins whatever the file order
        val acc2 = write(d, "b_acc.277", env(st277("RS1", "A2", "20260110"), gsctl = "4", isactl = "000000023", fid = "HN"))
        assertEquals(null, recon(listOf(c, rej, acc2)).counts["REJECTED"])
        assertEquals(null, recon(listOf(c, acc2, rej)).counts["REJECTED"])
        // a newer rejection after an acceptance rejects again
        val rej2 = write(d, "y_rej.277", env(st277("RS1", "A3", "20260115"), gsctl = "5", isactl = "000000024", fid = "HN"))
        assertEquals(1L, recon(listOf(c, acc, rej2)).counts["REJECTED"])
    }

    @Test
    fun l9_lineLevelStatusIsIgnored() = inTemp { d ->
        val c = write(d, "c.837", env(f837(listOf(Triple("RS1", "100", "20260101"))), fid = "HC"))
        val s277 = write(d, "s.277", env(st277("RS1", "A2", "20260110", withLine = true), fid = "HN"))
        val s = recon(listOf(c, s277))
        assertEquals(null, s.counts["REJECTED"])
        assertEquals(1L, s.statusEntries277)  // only the claim-level STC counts, not the batch-level or line-level ones
    }

    // ---------- M5 ----------
    @Test
    fun m5_utf8NamesSurviveAndCsvHasBom() = inTemp { d ->
        val o = write(d, "o.834", f834(listOf(member("SUBU", "MU", last = "TESTLAST"))))
        val n = Files.write(d.resolve("n.834"), f834(listOf(member("SUBU", "MU", last = "MU\u00D1OZTEST"))).toByteArray(Charsets.UTF_8))
        val csv = d.resolve("c.csv")
        cli("diff", o.toString(), n.toString(), "--csv", csv.toString())
        val bytes = Files.readAllBytes(csv)
        assertEquals(0xEF, bytes[0].toInt() and 0xFF); assertEquals(0xBB, bytes[1].toInt() and 0xFF); assertEquals(0xBF, bytes[2].toInt() and 0xFF)
        val text = String(bytes, Charsets.UTF_8)
        assertTrue("MU\u00D1OZTEST" in text, text)
        assertEquals("a\u00E9", Csv.fixUtf8("a\u00E9"))   // already-correct text is untouched
        assertEquals("\u00E9", Csv.fixUtf8(String(byteArrayOf(0xC3.toByte(), 0xA9.toByte()), Charsets.ISO_8859_1)))
    }

    // ---------- M6 ----------
    @Test
    fun m6_contractedPaymentIsPaidByDefaultAndOtherAdjustmentsArePartial() = inTemp { d ->
        val c = write(d, "c.837", env(f837(listOf(Triple("K1", "100", "20260101"), Triple("K2", "100", "20260101"))), fid = "HC"))
        val r = write(d, "r.835", env(f835(listOf(
            "CLP*K1*1*100*60*10*12", "CAS*CO*45*30", "CAS*PR*1*10",
            "CLP*K2*1*100*60*10*12", "CAS*CO*97*30", "CAS*PR*1*10"), "120"), fid = "HP"))
        val s = recon(listOf(c, r))
        assertEquals(1L, s.counts["PAID"]); assertEquals(1L, s.counts["PARTIAL"])
        val p = Profile.parse("key.1 = REF*0F:02\nrecon.contractual = CO-45, CO-97\n", "t")
        assertEquals(2L, recon(listOf(c, r), profile = p).counts["PAID"])
    }

    // ---------- M7 ----------
    @Test
    fun m7_readmeHasNoControlCharactersAndGeneratorUsesFictionalPhones() = inTemp { d ->
        val readme = Files.readString(Path.of("README.md"), Charsets.UTF_8)
        for (ch in readme) {
            assertTrue(ch == '\n' || ch == '\t' || ch.code >= 0x20 && ch.code !in 0x7F..0x9F, "control character U+%04X in README".format(ch.code))
        }
        Gen.generate(20, d.resolve("o.834"), d.resolve("n.834"), 1, false, 1)
        val txt = Files.readString(d.resolve("o.834"))
        assertTrue("PER*IP**HP*8005550" in txt, txt.take(600))
        assertFalse("787" in Regex("PER\\*IP\\*\\*HP\\*(\\d+)").findAll(txt).joinToString(" ") { it.groupValues[1] })
        val phones = Regex("PER\\*IP\\*\\*HP\\*(\\d+)").findAll(txt).map { it.groupValues[1] }.toSet()
        assertTrue(phones.all { it.startsWith("800555") && it.substring(6, 8) == "01" }, phones.toString())
    }

    // ---------- L ----------
    @Test
    fun l1_rd8DateRangeAndInstitutionalClaimDate() = inTemp { d ->
        val inst = env(S("837", "0001", "005010X223A2", listOf("BHT*0019*00*B*20260101*1200*CH", "HL*1**20*1", "HL*2*1*22*0", "SBR*P*18*******MC",
            "CLM*INST1*500***11:A:1**A*Y*Y", "DTP*434*RD8*20260101-20260105", "HI*ABK:Z0000", "LX*1", "SV2*0450*HC:99283*500*UN*1")), fid = "HC")
        val p = write(d, "i.837", inst)
        val s = recon(listOf(p), asof = "20260601")
        assertEquals(0L, s.aging["unknown"]); assertEquals(1L, s.aging["91+"])
        val csv = d.resolve("o.csv"); recon(listOf(p), csv = csv)
        assertTrue("20260101" in Files.readString(csv))
    }

    @Test
    fun l2_l10_defaultRejectCodesAndFutureDates() = inTemp { d ->
        val c = write(d, "c.837", env(f837(listOf(Triple("F1", "100", "20260901"))), fid = "HC"))
        val s = recon(listOf(c), asof = "20260301")
        assertEquals(1L, s.aging["future"]); assertEquals(0L, s.aging["0-30"])
        assertTrue(Profile.default().list("recon.ack.reject.codes", emptyList()).containsAll(listOf("R", "M", "W", "X")))
    }

    @Test
    fun l3_subElementSeparatorChangesAreNotDifferences() = inTemp { d ->
        val a = member("SUBS", "MS", extra = listOf("DMG*D8*19800101*F**RET:2135-2"))
        val b = member("SUBS", "MS", extra = listOf("DMG*D8*19800101*F**RET>2135-2"))
        val o = write(d, "o.834", f834(listOf(a)))
        val n = write(d, "n.834", f834(listOf(b), s = '>'))
        val s = Differ.diff(o, n, Profile.default()) { }
        assertEquals(1L, s.unchanged, "changed=${s.changed}")
    }

    @Test
    fun l4_parseShowsEveryInterchangeDelimiterSetAnd837Flavours() = inTemp { d ->
        val a = f834(listOf(member("SUBX", "MX")))
        val b = env(S("834", "0001", "005010X220A1", listOf("BGN|00|R")), e = '|', s = '^', r = '{', t = "\n")
        val p = write(d, "m.834", a + "\r\n" + b)
        val r = cli("parse", p.toString())
        assertEquals(2, Regex("Delimiters").findAll(r.out + r.err).count(), r.out)
        assertTrue("from interchange 1" in r.out && "from interchange 2" in r.out, r.out)
        val c = write(d, "c.837", env(f837(listOf(Triple("A", "1", "20260101"))), fid = "HC") )
        assertTrue("837P: 1" in cli("parse", c.toString()).out)
        val f = cli("parse", d.toString())
        assertEquals(2, f.code); assertTrue("is a folder" in f.err, f.err)
    }

    @Test
    fun l5_payerIdFallsBackToRef2U() = inTemp { d ->
        val r = write(d, "r.835", env(f835(listOf("CLP*A*1*10*10*0*12", "SVC*HC:99213*10*10**1"), "10"), fid = "HP"))
        val csv = d.resolve("o.csv")
        RemitCmd.run(listOf(r), Profile.default(), csv.toString(), null)
        assertTrue(",PAYERID1,1999999999," in Files.readAllLines(csv)[1], Files.readAllLines(csv)[1])
    }

    @Test
    fun l7_outputFolderMissingGivesClearMessage() = inTemp { d ->
        val o = write(d, "o.834", f834(listOf(member("SUBA", "MA"))))
        val r = cli("diff", o.toString(), o.toString(), "--csv", d.resolve("nodir").resolve("x.csv").toString())
        assertEquals(2, r.code)
        assertTrue("the folder does not exist" in r.err, r.err)
    }

    // ================= REVIEW_2 =================

    // ---------- N1 ----------
    @Test
    fun n1_newOnlyMemberWithTermFlagIsTermedNotAdded() = inTemp { d ->
        val w1 = write(d, "w1.834", f834(listOf(member("SUBA", "MA", ins3 = "001")), bgn8 = "2"))
        val term = listOf("DTP*349*D8*20261231")
        val w2 = write(d, "w2.834", f834(listOf(member("SUBT", "MT", ins3 = "024", extra = term), member("SUBA", "MA", ins3 = "024", extra = term)), bgn8 = "2"))
        for (mode in listOf(null, "full")) {
            val rows = ArrayList<Row>()
            val s = Differ.diff(w1, w2, Profile.default(), mode) { rows.add(it) }
            assertEquals(0L, s.added, "mode=$mode")
            assertEquals(2L, s.termedFlagged, "mode=$mode")
            val t = rows.single { it.key.startsWith("SUBT") }
            assertEquals("TERMED", t.type)
            assertTrue("termination in new file; not in old file" in t.detail, t.detail)
        }
        // a plain new member is still ADDED
        val w3 = write(d, "w3.834", f834(listOf(member("SUBN", "MN", ins3 = "021")), bgn8 = "2"))
        assertEquals(1L, Differ.diff(w1, w3, Profile.default()) { }.added)
    }

    // ---------- N2 ----------
    @Test
    fun n2_lastNameChangeDoesNotRekeyADependent() = inTemp { d ->
        val sub = member("SUBF", "MF")
        val o = write(d, "o.834", f834(listOf(sub, member("SUBF", null, rel = "01", last = "TESTLASTA", first = "SPOUSE", dob = "19850101"))))
        val n = write(d, "n.834", f834(listOf(sub, member("SUBF", null, rel = "01", last = "TESTLASTB", first = "SPOUSE", dob = "19850101"))))
        val rows = ArrayList<Row>()
        val s = Differ.diff(o, n, Profile.default()) { rows.add(it) }
        assertEquals(0L, s.added); assertEquals(0L, s.termed); assertEquals(1L, s.changed)
        assertEquals("NM103", rows.single().element)
        assertEquals("TESTLASTA", rows.single().oldValue)
    }

    @Test
    fun n2_firstNameChangePairsTermedAndAddedAsPossibleRename() = inTemp { d ->
        val sub = member("SUBF", "MF")
        val o = write(d, "o.834", f834(listOf(sub, member("SUBF", null, rel = "01", first = "SPOUSE", dob = "19850101"))))
        val n = write(d, "n.834", f834(listOf(sub, member("SUBF", null, rel = "01", first = "SPOUSEN", dob = "19850101"))))
        val rows = ArrayList<Row>()
        val s = Differ.diff(o, n, Profile.default()) { rows.add(it) }
        assertEquals(1L, s.added); assertEquals(1L, s.termedAbsent); assertEquals(1L, s.possibleRenames)
        assertTrue(rows.all { "possible name change" in it.detail }, rows.map { it.detail }.toString())
        assertTrue(s.notes.any { it.startsWith("WARNING") && "name change" in it })
        // a genuinely different person is not paired
        val n2 = write(d, "n2.834", f834(listOf(sub, member("SUBF", null, rel = "01", first = "OTHER", dob = "19900202"))))
        assertEquals(0L, Differ.diff(o, n2, Profile.default()) { }.possibleRenames)
    }

    // ---------- N3 ----------
    private fun febOnly(d: Path, ackDate: String, ackVer: String = "005010X222A1"): List<Path> {
        val feb = write(d, "feb.837", env(f837(listOf(Triple("FEB1", "100", "20260205")), bht = "20260205"), isactl = "000000012", fid = "HC", gsdate = "20260205", gsver = "005010X222A1"))
        val ack = write(d, "jan.999", env(S("999", "0001", "005010X231A1", listOf("AK1*HC*1*$ackVer", "AK2*837*0001*005010X222A1", "IK5*R*5", "AK9*R*1*1*0")),
            gsctl = "7", isactl = "000000013", fid = "FA", gsdate = ackDate))
        return listOf(feb, ack)
    }

    @Test
    fun n3_ackMustBeOnOrAfterTheClaimSetAndWithinTheWindow() = inTemp { d ->
        // a January 999 must not reject a February 837 that happens to share GS06 and ST02
        val before = recon(febOnly(d, "20260110"))
        assertEquals(null, before.counts["REJECTED"], before.counts.toString())
        assertEquals(1L, before.counts["UNMATCHED"])
        assertTrue(before.warnings.any { "nothing was marked" in it }, before.warnings.toString())
        // on time: marked
        assertEquals(1L, recon(febOnly(d, "20260210")).counts["REJECTED"])
        // too late for the default window, fine with a larger one
        assertEquals(null, recon(febOnly(d, "20260401")).counts["REJECTED"])
        val wide = Profile.parse("key.1 = REF*0F:02\nrecon.ack.window.days = 90\n", "t")
        assertEquals(1L, recon(febOnly(d, "20260401"), profile = wide).counts["REJECTED"])
        // a different version in AK1-03
        assertEquals(null, recon(febOnly(d, "20260210", ackVer = "005010X999")).counts["REJECTED"])
        assertThrows<X12Exception> { recon(febOnly(d, "20260210"), profile = Profile.parse("key.1 = REF*0F:02\nrecon.ack.window.days = abc\n", "t")) }
    }

    @Test
    fun n3_a277BeforeTheClaimSetIsIgnoredAndFileNameResolutionIsNoted() = inTemp { d ->
        val c = write(d, "c.837", env(f837(listOf(Triple("RS1", "100", "20260301"))), fid = "HC", gsdate = "20260301"))
        val early = write(d, "s.277", env(st277("RS1", "A7", "20260110"), fid = "HN", gsdate = "20260110"))
        val s = recon(listOf(c, early))
        assertEquals(null, s.counts["REJECTED"]); assertTrue(s.warnings.any { "ignored" in it }, s.warnings.toString())
        // file-name resolution shows in the detail
        val files = monthFiles(d.resolve("m").also { Files.createDirectory(it) }, "jan.999", "AK9*R*1*1*0")
        val csv = d.resolve("o.csv")
        recon(files, csv = csv)
        assertTrue("999 matched to this 837 by file name" in Files.readString(csv), Files.readString(csv))
    }

    // ---------- N4 ----------
    @Test
    fun n4_exampleProfileEqualsBuiltInAndUnknownKeysWarn() = inTemp { d ->
        val ex = Files.readString(Path.of("profiles", "example.properties"), Charsets.UTF_8).replace("\r\n", "\n")
        assertEquals(Profile.DEFAULT_TEXT.replace("\r\n", "\n"), ex)
        assertTrue(Profile.default().unknownKeys.isEmpty(), Profile.default().unknownKeys.toString())
        val p = Profile.parse("key.1 = REF*0F:02\nrecon.paid.min.ratio = 1.0\nbogus.key = 1\nlabel.INS:03 = x\nadjlabel.CO-45 = y\n", "t")
        assertEquals(2, p.unknownKeys.size)
        assertTrue(p.unknownKeys.any { "recon.paid.min.ratio was removed in 0.2.1" in it })
        val f = write(d, "p.properties", "key.1 = REF*0F:02\nrecon.paid.min.ratio = 1.0\n")
        val o = write(d, "o.834", f834(listOf(member("SUBA", "MA"))))
        val r = cli("diff", o.toString(), o.toString(), "--profile", f.toString())
        assertTrue("WARNING: profile key recon.paid.min.ratio" in r.out && "profile key" !in r.err, r.out + r.err)
    }

    // ---------- N5 / N6 ----------
    @Test
    fun n5_zeroPaidWithADenialAdjustmentIsDeniedNotPatientResp() = inTemp { d ->
        val c = write(d, "c.837", env(f837(listOf(Triple("D1", "150", "20260101"), Triple("D2", "150", "20260101"))), fid = "HC"))
        val r = write(d, "r.835", env(f835(listOf(
            "CLP*D1*1*150*0*50*12", "CAS*PR*1*50", "CAS*CO*50*100",
            "CLP*D2*1*150*0*150*12", "CAS*PR*1*150"), "0"), fid = "HP"))
        val s = recon(listOf(c, r))
        assertEquals(1L, s.counts["DENIED"]); assertEquals(1L, s.counts["PATIENT_RESP"])
    }

    @Test
    fun n6_sequestrationIsContractualByDefault() = inTemp { d ->
        val c = write(d, "c.837", env(f837(listOf(Triple("M1", "100", "20260101"))), fid = "HC"))
        val r = write(d, "r.835", env(f835(listOf("CLP*M1*1*100*78.40*0*12", "CAS*CO*45*20**253*1.60"), "78.40"), fid = "HP"))
        assertEquals(1L, recon(listOf(c, r)).counts["PAID"])
        val only45 = Profile.parse("key.1 = REF*0F:02\nrecon.contractual = CO-45\n", "t")
        assertEquals(1L, recon(listOf(c, r), profile = only45).counts["PARTIAL"])
        assertTrue(Profile.DEFAULT_TEXT.contains("recon.contractual = CO-45, CO-253"))
    }

    // ---------- N7 / N8 / N9 / N10 ----------
    @Test
    fun n8_emptyFileWarnsAndExitsThree() = inTemp { d ->
        val e = write(d, "empty.834", "")
        val r = cli("parse", e.toString())
        assertEquals(3, r.code, r.out + r.err)
        assertTrue("empty or contains no X12 segments" in r.out, r.out)
        assertEquals(0, cli("parse", e.toString(), "--allow-incomplete").code)
        assertEquals(0, cli("parse", write(d, "ok.834", f834(listOf(member("SUBA", "MA")))).toString()).code)
    }

    @Test
    fun n9n10_duplicateRowsSayHowTheyWereMatchedAndReadmeDocumentsBom() = inTemp { d ->
        val c = write(d, "c.837", env(f837(listOf(Triple("DUP1", "100", "20260101"), Triple("DUP1", "200", "20260102"))), fid = "HC"))
        val r = write(d, "r.835", env(f835(listOf("CLP*DUP1*1*200*200*0*12*X1"), "200"), fid = "HP"))
        val csv = d.resolve("o.csv")
        recon(listOf(c, r), csv = csv)
        assertTrue("duplicate CLM01; remit matched by charge" in Files.readString(csv), Files.readString(csv))
        val readme = Files.readString(Path.of("README.md"), Charsets.UTF_8)
        assertTrue("byte-order mark" in readme && "BOM" in readme)
        assertTrue("Mode: full" in readme && "(BGN08 4: full)" in readme)   // N7: the 834 example shows the current console format
        assertTrue("future end date also counts" in readme)
    }

    // ================= REVIEW_3 =================

    @Test
    fun v1_classFilesTargetJava17() {
        // The jar must run on Java 17: class file major version 61 (Java 21 would be 65).
        val cls = Path.of("build", "classes", "kotlin", "main", "tools", "datawharf", "x12diff", "MainKt.class")
        val bytes = Files.readAllBytes(cls)
        val major = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        assertEquals(61, major)
        assertTrue(Files.readString(Path.of("build.gradle.kts")).contains("jvmToolchain(17)"))
    }

    @Test
    fun la_lb_readmeStatesPatientRespRuleAndDiffRowOrder() {
        val readme = Files.readString(Path.of("README.md"), Charsets.UTF_8)
        assertTrue("nothing but PR and the `recon.contractual` codes was adjusted" in readme)
        assertTrue("then the ADDED rows, then the TERMED" in readme)
        assertTrue("Java 17" in readme)
    }

    @Test
    fun lb_diffCsvRowOrderIsChangedThenAddedThenAbsent() = inTemp { d ->
        val o = write(d, "o.834", f834(listOf(member("SUBA", "MA"), member("SUBB", "MB"), member("SUBC", "MC"))))
        val n = write(d, "n.834", f834(listOf(member("SUBA", "MA", extra = listOf("DTP*999*D8*20260101")), member("SUBC", "MC"), member("SUBN", "MN"))))
        val types = ArrayList<String>()
        Differ.diff(o, n, Profile.default()) { types.add(it.type) }
        assertEquals(listOf("CHANGED", "ADDED", "TERMED"), types.distinct())
    }

    @Test
    fun lc_resubmissionIsResolvedToTheMostRecentEarlier837() = inTemp { d ->
        val orig = write(d, "a_orig.837", env(f837(listOf(Triple("ORIG1", "100", "20260105")), bht = "20260105"), isactl = "000000011", fid = "HC", gsdate = "20260105", gsver = "005010X222A1"))
        val resub = write(d, "b_resub.837", env(f837(listOf(Triple("RESUB1", "100", "20260202")), bht = "20260202"), isactl = "000000012", fid = "HC", gsdate = "20260202", gsver = "005010X222A1"))
        val ack = write(d, "x_ack.999", env(S("999", "0001", "005010X231A1", listOf("AK1*HC*1*005010X222A1", "AK2*837*0001*005010X222A1", "IK5*R*5")),
            gsctl = "7", isactl = "000000013", fid = "FA", gsdate = "20260203"))
        val csv = d.resolve("o.csv")
        val s = recon(listOf(orig, resub, ack), csv = csv)
        assertEquals(1L, s.counts["REJECTED"]); assertEquals(1L, s.counts["UNMATCHED"])
        val text = Files.readString(csv)
        assertTrue("REJECTED,RESUB1" in text && "UNMATCHED,ORIG1" in text, text)
        assertTrue("most recent 837 sent before it" in text, text)
    }

    @Test
    fun ld_ackWarningSaysWhichCheckFailed() = inTemp { d ->
        val dateFail = recon(febOnly2(d, "20260110", "005010X222A1"))
        assertTrue(dateFail.warnings.any { "date check failed for 1" in it && "version check" !in it }, dateFail.warnings.toString())
        val verFail = recon(febOnly2(d, "20260210", "005010X999"))
        assertTrue(verFail.warnings.any { "version check failed for 1" in it && "date check" !in it }, verFail.warnings.toString())
    }

    private fun febOnly2(d: Path, ackDate: String, ackVer: String): List<Path> {
        val feb = write(d, "feb.837", env(f837(listOf(Triple("FEB1", "100", "20260205")), bht = "20260205"), isactl = "000000012", fid = "HC", gsdate = "20260205", gsver = "005010X222A1"))
        val ack = write(d, "jan.999", env(S("999", "0001", "005010X231A1", listOf("AK1*HC*1*$ackVer", "AK2*837*0001*005010X222A1", "IK5*R*5")),
            gsctl = "7", isactl = "000000013", fid = "FA", gsdate = ackDate))
        return listOf(feb, ack)
    }

    @Test
    fun le_pairingCutoffNoticeIsAWarning() {
        val src = Files.readString(Path.of("src", "main", "kotlin", "tools", "datawharf", "x12diff", "Diff.kt"))
        assertTrue("WARNING: more than 100,000 ADDED members" in src)
        assertEquals("0.2.5", VERSION)
    }
}

/** Tests for the 0.2.4 release. */
class Release024Tests {
    private fun ta1File(d: Path, name: String, vararg ta1: String): Path {
        val sb = StringBuilder(isa("000000099"))
        for (t in ta1) sb.append(t).append("~")
        sb.append("IEA*0*000000099~")
        return write(d, name, sb.toString())
    }

    private fun claimFile(d: Path, name: String, isactl: String, vararg ids: String): Path =
        write(d, name, env(f837(ids.map { Triple(it, "100", "20260105") }, bht = "20260105"), isactl = isactl, fid = "HC", gsver = "005010X222A1"))

    // ---- 1: zero members ----
    @Test
    fun f1_diffAgainstAFileWithNoMembersIsLoudAndExits3() = inTemp { d ->
        val o = write(d, "o.834", f834(listOf(member("SUBA", "MA"), member("SUBB", "MB"))))
        val empty = write(d, "e.834", f834(emptyList()))
        val txt = Files.readString(o, Charsets.ISO_8859_1); val lower = write(d, "l.834", txt.replace("~INS*", "~ins*"))
        for (bad in listOf(empty, lower)) {
            val r = cli("diff", o.toString(), bad.toString())
            assertEquals(3, r.code, r.out + r.err)
            assertTrue("EMPTY INPUT: new file has 0 members" in r.out && "EMPTY INPUT" in r.err, r.out)
            val ok = cli("diff", o.toString(), bad.toString(), "--allow-incomplete")
            assertEquals(1, ok.code, ok.out + ok.err)
            assertTrue("accepted by --allow-incomplete" in ok.out)
        }
        val rOld = cli("diff", empty.toString(), o.toString())
        assertEquals(3, rOld.code); assertTrue("old file has 0 members" in rOld.out)
    }

    @Test
    fun f1_emptyFileGivesTheSameExitCodeInDiffAndParse() = inTemp { d ->
        val o = write(d, "o.834", f834(listOf(member("SUBA", "MA"))))
        val e = write(d, "empty.834", "")
        assertEquals(3, cli("parse", e.toString()).code)
        val r = cli("diff", o.toString(), e.toString())
        assertEquals(3, r.code, r.out + r.err)
        assertEquals(1, cli("diff", o.toString(), e.toString(), "--allow-incomplete").code)
    }

    // ---- 2: TA1 ----
    @Test
    fun f2_ta1RejectMarksOnlyTheMatchingInterchange() = inTemp { d ->
        val a = claimFile(d, "a.837", "000000021", "A1", "A2")
        val b = claimFile(d, "b.837", "000000022", "B1")
        val ta = ta1File(d, "ack.ta1", "TA1*000000021*260101*1200*R*024")
        val csv = d.resolve("o.csv")
        val s = recon(listOf(a, b, ta), csv = csv)
        assertEquals(2L, s.counts["REJECTED"], s.counts.toString())
        assertEquals(1L, s.counts["UNMATCHED"])
        assertEquals(1, s.ta1RejectedInterchanges); assertEquals(1, s.ta1Count)
        assertEquals(1, s.rejectedSets)
        val text = Files.readString(csv)
        assertTrue("TA1 interchange rejected" in text && "note code 024" in text, text)
        assertTrue(s.warnings.none { "TA1 or" in it || "skipped" in it }, s.warnings.toString())
    }

    @Test
    fun f2_ta1AcceptedWithErrorsAndUntiedTa1BothWarn() = inTemp { d ->
        val a = claimFile(d, "a.837", "000000021", "A1")
        val e = ta1File(d, "e.ta1", "TA1*000000021*260101*1200*E*002")
        val s = recon(listOf(a, e))
        assertEquals(null, s.counts["REJECTED"]); assertEquals(1L, s.counts["UNMATCHED"])
        assertTrue(s.warnings.any { "accepted interchange 000000021 with errors" in it }, s.warnings.toString())
        val p = Profile.parse("key.1 = REF*0F:02\nrecon.ta1.reject.codes = R, E\n", "t")
        assertEquals(1L, recon(listOf(a, e), profile = p).counts["REJECTED"])
        val lost = ta1File(d, "x.ta1", "TA1*000000777*260101*1200*R*001")
        val s2 = recon(listOf(a, lost))
        assertEquals(null, s2.counts["REJECTED"])
        assertTrue(s2.warnings.any { "no 837 with that ISA13" in it }, s2.warnings.toString())
        val acc = ta1File(d, "ok.ta1", "TA1*000000021*260101*1200*A*000")
        val s3 = recon(listOf(a, acc))
        assertEquals(0L, s3.warningCount); assertEquals(null, s3.counts["REJECTED"])
    }

    @Test
    fun f2_ta1WorksForTheSecondInterchangeOfOneFile() = inTemp { d ->
        val two = write(d, "two.837",
            env(f837(listOf(Triple("P1", "100", "20260105"))), isactl = "000000031", fid = "HC", gsver = "005010X222A1") +
                env(f837(listOf(Triple("P2", "100", "20260105"))), isactl = "000000032", fid = "HC", gsver = "005010X222A1"))
        val ta = ta1File(d, "t.ta1", "TA1*000000032*260101*1200*R*001")
        val csv = d.resolve("o.csv")
        recon(listOf(two, ta), csv = csv)
        val rows = Files.readAllLines(csv).filter { it.contains("REJECTED") }
        assertEquals(1, rows.size, rows.toString()); assertTrue("P2" in rows[0])
    }

    // ---- 3: gen --reject-set ----
    @Test
    fun f3_genRejectSetProducesAMatchingValid999() = inTemp { d ->
        val dir = d.resolve("demo")
        val r = cli("gen", "--kind", "claims", "--claims", "200", "--dir", dir.toString(), "--seed", "4", "--reject-set", "2", "--reject-set", "4")
        assertEquals(0, r.code, r.out + r.err)
        assertTrue("(80 by 999" in r.out, r.out)
        assertEquals(0, cli("parse", dir.resolve("ack.999").toString()).code) // SE counts and control numbers are valid
        val files = listOf("claims.837", "remits_1.835", "remits_2.835", "ack.999", "status.277").map { dir.resolve(it) }
        val s = recon(files)
        assertEquals(2, s.rejectedSets)
        val expected = Regex("REJECTED (\\d+)").find(r.out)!!.groupValues[1].toLong()
        assertEquals(expected, s.counts["REJECTED"])
        val bad = cli("gen", "--kind", "claims", "--claims", "200", "--dir", dir.toString(), "--reject-set", "9")
        assertEquals(2, bad.code); assertTrue("out of range" in bad.err, bad.err)
    }

    // ---- 4: no partial output ----
    @Test
    fun f4_failedRunLeavesNoPartialOutputAndKeepsAnExistingFile() = inTemp { d ->
        val o = write(d, "o.834", f834(listOf(member("SUBA", "MA"))))
        val bad = write(d, "bad.834", "not x12 at all")
        val csv = d.resolve("out.csv")
        val r = cli("diff", o.toString(), bad.toString(), "--csv", csv.toString())
        assertEquals(2, r.code, r.out + r.err)
        assertFalse(Files.exists(csv))
        assertEquals(listOf("bad.834", "o.834"), Files.list(d).use { st -> st.map { it.fileName.toString() }.sorted().toList() })
        Files.writeString(csv, "keep me")
        assertEquals(2, cli("diff", o.toString(), bad.toString(), "--csv", csv.toString()).code)
        assertEquals("keep me", Files.readString(csv))
        // success replaces it and leaves no temp file
        assertEquals(0, cli("diff", o.toString(), o.toString(), "--csv", csv.toString()).code)
        assertTrue(Files.readString(csv).contains("change_type"))
        assertEquals(3L, Files.list(d).use { it.count() })
    }

    @Test
    fun f4_abortDeletesTheTemporaryFile() = inTemp { d ->
        val out = Csv.open(d.resolve("x.csv").toString())
        out.w.write("partial")
        out.abort()
        assertEquals(0L, Files.list(d).use { it.count() })
    }

    // ---- 5: same output path ----
    @Test
    fun f5_theSameOutputPathTwiceIsAnError() = inTemp { d ->
        val o = write(d, "o.834", f834(listOf(member("SUBA", "MA"))))
        val r835 = write(d, "r.835", env(f835(listOf("CLP*C1*1*100*90*10*12", "CAS*CO*45*10"), "90"), fid = "HP"))
        val x = d.resolve("x.csv").toString()
        val a = cli("remit", r835.toString(), "--csv", x, "--rollup", x)
        assertEquals(2, a.code); assertTrue("same file" in a.err, a.err)
        assertFalse(Files.exists(d.resolve("x.csv")))
        val g = cli("gen", "--members", "10", "--old", d.resolve("g.834").toString(), "--new", d.resolve("g.834").toString())
        assertEquals(2, g.code); assertTrue("same file" in g.err)
        // output over an input file is refused too
        val c = cli("diff", o.toString(), o.toString(), "--csv", o.toString())
        assertEquals(2, c.code); assertTrue("same file" in c.err)
        assertTrue(Files.readString(o).startsWith("ISA"))
    }

    // ---- 6: wording ----
    @Test
    fun f6_errorWording() = inTemp { d ->
        val o = write(d, "o.834", f834(listOf(member("SUBA", "MA"))))
        val dir = Files.createDirectory(d.resolve("g"))
        assertTrue("is a folder" in cli("diff", o.toString(), dir.toString()).err)
        assertTrue("is a folder" in cli("remit", o.toString(), "--csv", dir.toString()).err)
        val p = cli("diff", o.toString(), o.toString(), "--profile", d.resolve("none.properties").toString())
        assertEquals(2, p.code); assertTrue("Profile not found" in p.err, p.err)
    }

    // ---- 8: Ctrl-Z ----
    @Test
    fun f8_trailingCtrlZIsNotASegment() = inTemp { d ->
        val text = f834(listOf(member("SUBA", "MA")))
        val plain = write(d, "p.834", text)
        val z = write(d, "z.834", text + "\u001A")
        val rp = cli("parse", plain.toString()); val rz = cli("parse", z.toString())
        assertEquals(0, rz.code, rz.out + rz.err)
        assertEquals(rp.out.substringAfter("Segments:"), rz.out.substringAfter("Segments:"))
        assertEquals(0, cli("diff", plain.toString(), z.toString()).code)
    }
}

/** Tests for the release-gate fixes. */
class Release024GateTests {
    private fun claimFile(d: Path, name: String, isactl: String, date: String, vararg ids: String): Path {
        val t = env(f837(ids.map { Triple(it, "100", "20260105") }, bht = "20260105"), isactl = isactl, fid = "HC", gsver = "005010X222A1")
        return write(d, name, t.substring(0, 106).replace("*260101*", "*$date*") + t.substring(106))
    }

    private fun ta1(d: Path, name: String, vararg t: String): Path =
        write(d, name, isa("000000099") + t.joinToString("") { "$it~" } + "IEA*0*000000099~")

    @Test
    fun h1_folderOfManyFilesIsLinearAndDuplicateInputsAreNamed() = inTemp { d ->
        val dir = Files.createDirectory(d.resolve("many"))
        val one = f835(listOf("CLP*C1*1*100*90*10*12", "CAS*CO*45*10"), "90")
        for (i in 1..400) write(dir, "r%04d.835".format(i), env(one, fid = "HP"))
        val t0 = System.nanoTime()
        val r = cli("remit", dir.toString(), "--show", "0")
        val secs = (System.nanoTime() - t0) / 1e9
        assertEquals(0, r.code, r.out + r.err)
        assertTrue(secs < 20, "took $secs s")
        // the same file named twice, and a file named again inside its folder
        val f = dir.resolve("r0001.835").toString()
        val twice = cli("remit", f, f)
        assertEquals(2, twice.code); assertTrue("is given twice" in twice.err, twice.err)
        val inside = cli("remit", dir.toString(), f)
        assertEquals(2, inside.code); assertTrue("also inside folder" in inside.err, inside.err)
        // an output that is also an input is still refused, in one pass
        val out = cli("remit", dir.toString(), "--csv", f)
        assertEquals(2, out.code); assertTrue("same file" in out.err, out.err)
    }

    @Test
    fun m1_ta1MustMatchTheInterchangeDateAndTime() = inTemp { d ->
        val feb = claimFile(d, "feb.837", "000000001", "260201", "FEB1")
        val jan = claimFile(d, "jan.837", "000000001", "260101", "JAN1")
        val ta = ta1(d, "jan.ta1", "TA1*000000001*260101*1200*R*024")
        val s = recon(listOf(feb, ta))
        assertEquals(null, s.counts["REJECTED"], s.counts.toString())
        assertTrue(s.warnings.any { "nothing was marked" in it && "date 260101" in it }, s.warnings.toString())
        val both = recon(listOf(feb, jan, ta))
        assertEquals(1L, both.counts["REJECTED"])
        val csv = d.resolve("o.csv")
        recon(listOf(feb, jan, ta), csv = csv)
        val rows = Files.readAllLines(csv).filter { "REJECTED" in it }
        assertEquals(1, rows.size); assertTrue("JAN1" in rows[0], rows.toString())
        // a different time of day does not match either
        val late = ta1(d, "late.ta1", "TA1*000000001*260101*1300*R*024")
        assertEquals(null, recon(listOf(jan, late)).counts["REJECTED"])
    }

    @Test
    fun m2_deviceTargetsAreWrittenDirectly() = inTemp { d ->
        val r835 = write(d, "r.835", env(f835(listOf("CLP*C1*1*100*90*10*12", "CAS*CO*45*10"), "90"), fid = "HP"))
        val nul = if (System.getProperty("os.name", "").startsWith("Windows")) "NUL" else "/dev/null"
        val r = cli("remit", r835.toString(), "--csv", nul)
        assertEquals(0, r.code, r.out + r.err)
        assertEquals(0L, Files.list(d).use { st -> st.filter { it.fileName.toString().contains("tmp") }.count() })
    }

    @Test
    fun l1_l2_rejectedSetCarriesRealPositionsAndAk9Code() = inTemp { d ->
        val dir = d.resolve("g")
        assertEquals(0, cli("gen", "--kind", "claims", "--claims", "200", "--dir", dir.toString(), "--reject-set", "2", "--newlines").code)
        val ack = Files.readString(dir.resolve("ack.999"))
        assertTrue("IK3*CLM*3*2300*8~" in ack && "IK4*1*1028*7*PCN00000041~" in ack && "AK9*P*5*5*4~" in ack, ack)
        val st = Files.readString(dir.resolve("claims.837")).lines()
        assertTrue(st[st.indexOfFirst { it.startsWith("ST*837*0002") } + 2].startsWith("CLM*PCN00000041*"))
        val all = d.resolve("h")
        assertEquals(0, cli("gen", "--kind", "claims", "--claims", "40", "--dir", all.toString(), "--reject-set", "1").code)
        assertTrue("AK9*R*1*1*0~" in Files.readString(all.resolve("ack.999")))
    }
}

/** Tests for the 0.2.5 release. */
class Release025Tests {
    @Test
    fun deviceDetectionNeedsTheWholeName() {
        val win = System.getProperty("os.name", "").startsWith("Windows")
        for (n in listOf("NUL", "nul", "Con", "COM1", "lpt9", "NUL.")) assertEquals(win, isWindowsDevice(Path.of(n)), n)
        for (n in listOf("nul.csv", "Nul.txt", "con.csv", "com1.csv", "NULL", "COM0", "COM10", "x.csv")) assertFalse(isWindowsDevice(Path.of(n)), n)
        for (n in listOf("nul.csv", "Com1.txt")) assertEquals(win, hasReservedBaseName(Path.of(n)), n)
        assertFalse(hasReservedBaseName(Path.of("annual.csv")))
    }

    @Test
    fun reservedBaseNameKeepsTheTempFileProtection() = inTemp { d ->
        val csv = d.resolve("nul.csv")
        try { Files.writeString(csv, "keep me") } catch (e: java.io.IOException) { return@inTemp }
        // On Windows 10 and older nul.csv is the NUL device; only test where it is a real file (Windows 11, Linux, macOS).
        if (!Files.isRegularFile(csv) || Files.readString(csv) != "keep me") return@inTemp
        val o = write(d, "o.834", f834(listOf(member("SUBA", "MA"))))
        val bad = write(d, "bad.834", "not x12 at all")
        assertEquals(2, cli("diff", o.toString(), bad.toString(), "--csv", csv.toString()).code)
        assertEquals("keep me", Files.readString(csv))
        assertEquals(0, cli("diff", o.toString(), o.toString(), "--csv", csv.toString()).code)
        assertTrue(Files.readString(csv).contains("change_type"))
        assertEquals(0L, Files.list(d).use { st -> st.filter { it.fileName.toString().contains("x12diff-tmp") }.count() })
    }
}
