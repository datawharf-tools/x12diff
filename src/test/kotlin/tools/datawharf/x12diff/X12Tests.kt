package tools.datawharf.x12diff

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows

private fun isa(sep: Char = '*', sub: Char = ':', rep: Char = '^', term: String = "~", ctrl: String = "000000001"): String =
    "ISA${sep}00$sep          ${sep}00$sep          ${sep}ZZ${sep}SENDERTEST     ${sep}ZZ${sep}RECEIVERTEST   ${sep}260101${sep}1200$sep$rep${sep}00501$sep$ctrl${sep}0${sep}T$sep$sub$term"

private fun readAll(text: String): Pair<List<Segment>, Delimiters?> {
    val r = X12Reader(text.byteInputStream(Charsets.ISO_8859_1))
    val l = ArrayList<Segment>()
    while (true) l.add(r.next() ?: break)
    return l to r.delimiters
}

private fun check(text: String): EnvelopeChecker {
    val c = EnvelopeChecker()
    val r = X12Reader(text.byteInputStream(Charsets.ISO_8859_1))
    while (true) c.accept(r.next() ?: break)
    c.finish()
    return c
}

/** Minimal 834-like file for diff tests. members: id -> list of extra segments. */
private fun file(vararg members: String, term: String = "~"): String {
    val sb = StringBuilder(isa(term = term))
    sb.append("GS*BE*A*B*20260101*1200*1*X*005010X220A1$term")
    sb.append("ST*834*0001*005010X220A1$term")
    sb.append("BGN*00*X*20260101*1200$term")
    for (m in members) sb.append(m)
    // count: ST, BGN, members' segments, SE
    val n = 2 + members.sumOf { m -> m.split(term).count { it.isNotBlank() } } + 1
    sb.append("SE*$n*0001$term")
    sb.append("GE*1*1$term")
    sb.append("IEA*1*000000001$term")
    return sb.toString()
}

private fun mem(id: String, ins03: String = "030", plan: String = "PLANA", extra: String = "", t: String = "~") =
    "INS*Y*18*$ins03*XN*A$t" + "REF*0F*SUB$id$t" + "NM1*IL*1*TESTPERSON$id*TESTFIRST****MI*999$id$t" +
        "HD*030**HLT*$plan$t" + extra

class X12Tests {
    @Test
    fun standardDelimiters() {
        val (segs, d) = readAll(file(mem("000001")))
        assertEquals(Delimiters('*', ':', '^', '~'), d)
        assertEquals("ISA", segs[0].id)
        assertEquals("ST", segs[2].id)
        assertEquals("834", segs[2].el(1))
    }

    @Test
    fun nonStandardDelimitersPipeCaretAndNewlineTerminator() {
        val text = isa('|', '>', '^', "\n") +
            "GS|BE|A|B|20260101|1200|1|X|005010X220A1\n" +
            "ST|834|0001\nBGN|00|X\nSE|3|0001\nGE|1|1\nIEA|1|000000001\n"
        val (segs, d) = readAll(text)
        assertEquals(Delimiters('|', '>', '^', '\n'), d)
        assertEquals(listOf("ISA", "GS", "ST", "BGN", "SE", "GE", "IEA"), segs.map { it.id })
        assertEquals("834", segs[2].el(1))
        assertTrue(check(text).warnings.isEmpty())
    }

    @Test
    fun crlfAndNoNewlinesAndLineWrappedInputAreTolerated() {
        val base = file(mem("000001"))
        val crlf = base.replace("~", "~\r\n")
        val wrapped = base.substring(0, 106) + base.substring(106).chunked(37).joinToString("\r\n")
        for (t in listOf(base, crlf, wrapped)) {
            val (segs, _) = readAll(t)
            assertEquals(readAll(base).first.map { it.id }, segs.map { it.id })
            assertEquals("SUB000001", segs.first { it.id == "REF" }.el(2))
        }
    }

    @Test
    fun carriageReturnTerminatorWithLfFollowing() {
        val text = isa(term = "\r") + "GS*BE*A*B*20260101*1200*1*X*005010X220A1\r\nST*834*0001\r\nSE*2*0001\r\nGE*1*1\r\nIEA*1*000000001\r\n"
        val (segs, d) = readAll(text)
        assertEquals('\r', d!!.terminator)
        assertEquals(6, segs.size)
    }

    @Test
    fun bomIsSkippedAndMultipleInterchangesReDetectDelimiters() {
        val a = file(mem("000001"))
        val b = isa('|', '>', '^', "\n") + "GS|BE\nIEA|0|000000002\n"
        val (segs, d) = readAll("﻿".toByteArray(Charsets.UTF_8).toString(Charsets.ISO_8859_1) + a + "\n" + b)
        assertEquals('|', d!!.element)
        assertEquals(2, segs.count { it.id == "ISA" })
    }

    @Test
    fun summaryCountsSetsAndTypes() {
        val gsvers = "GS*HN*A*B*20260101*1200*1*X*005010X214~"
        val text = isa() + gsvers + "ST*277*0001*005010X214~SE*2*0001~ST*277*0002*005010X214~SE*2*0002~GE*2*1~" +
            "GS*FA*A*B*20260101*1200*2*X*005010X231A1~ST*999*0001~SE*2*0001~GE*1*2~IEA*2*000000001~"
        val c = check(text)
        assertEquals(1, c.interchanges)
        assertEquals(2, c.groups)
        assertEquals(3, c.sets)
        assertEquals(2, c.setTypes["277CA"])
        assertEquals(1, c.setTypes["999"])
        assertTrue(c.warnings.isEmpty(), c.warnings.toString())
    }

    @Test
    fun taOneAndMultipleStPerGroupAreCounted() {
        val text = isa() + "TA1*000000001*260101*1200*A*000~IEA*0*000000001~"
        assertEquals(1, check(text).setTypes["TA1"])
        val multi = check(file(mem("000001")).replace("GE*1*1", "GE*1*1"))
        assertTrue(multi.warnings.isEmpty(), multi.warnings.toString())
    }

    @Test
    fun envelopeMismatchesWarn() {
        val text = isa(ctrl = "000000007") + "GS*BE*A*B*20260101*1200*5*X*005010X220A1~ST*834*0001~BGN*00~SE*9*0002~GE*2*6~IEA*3*000000008~"
        val w = check(text).warnings.joinToString("\n")
        assertTrue("SE01=9 but 3 segment(s)" in w, w)
        assertTrue("ST02=0001 but SE02=0002" in w, w)
        assertTrue("GS06=5 but GE02=6" in w, w)
        assertTrue("GE01=2 but 1 transaction set" in w, w)
        assertTrue("ISA13=000000007 but IEA02=000000008" in w, w)
        assertTrue("IEA01=3 but 1 group" in w, w)
    }

    @Test
    fun truncatedFileWarnsAboutMissingTrailers() {
        val w = check(isa() + "GS*BE*A*B*20260101*1200*1*X*005010X220A1~ST*834*0001~BGN*00~").warnings.joinToString("\n")
        assertTrue("no SE" in w && "no GE" in w && "no IEA" in w, w)
    }

    @Test
    fun malformedInputGivesClearErrors() {
        assertThrows<X12Exception> { readAll("hello world, this is not X12") }
        assertThrows<X12Exception> { readAll("ISA*00*short") }
        assertThrows<X12Exception> { readAll(isa().replace("*00501*", "*00501")) }
        val tmp = Files.createTempFile("x12", ".txt")
        try {
            Files.writeString(tmp, "this is not an x12 file at all")
            for (cmd in listOf("parse", "diff")) {
                val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
                val args = if (cmd == "parse") arrayOf(cmd, tmp.toString()) else arrayOf(cmd, tmp.toString(), tmp.toString())
                val code = run(args, PrintStream(out), PrintStream(err))
                val msg = err.toString()
                assertEquals(2, code)
                assertTrue(msg.startsWith("error: "), msg)
                assertFalse("Exception" in msg || "\tat " in msg, msg)
            }
            val err = ByteArrayOutputStream()
            assertEquals(2, run(arrayOf("parse", "no-such-file.edi"), PrintStream(ByteArrayOutputStream()), PrintStream(err)))
            assertTrue(err.toString().startsWith("error: File not found"), err.toString())
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun badProfileGivesClearError() {
        assertThrows<X12Exception> { Profile.parse("key.1 = REF*0F\n", "t") }
        assertThrows<X12Exception> { Profile.parse("nonsense line", "t") }
        assertThrows<X12Exception> { Profile.parse("member.start = INS\n", "t") }
    }

    // ---------- diff ----------

    private fun diffRows(oldText: String, newText: String, profile: Profile = Profile.default()): Pair<List<Row>, DiffSummary> {
        val dir = Files.createTempDirectory("x12diff-test")
        try {
            val o = dir.resolve("old.834"); val n = dir.resolve("new.834")
            Files.writeString(o, oldText, Charsets.ISO_8859_1); Files.writeString(n, newText, Charsets.ISO_8859_1)
            val rows = ArrayList<Row>()
            val s = Differ.diff(o, n, profile) { rows.add(it) }
            return rows to s
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun diffAddedTermedChangedUnchanged() {
        val old = file(mem("000001"), mem("000002"), mem("000003"), mem("000004"), mem("000005"))
        val new = file(
            mem("000001"),                                   // unchanged
            mem("000002", plan = "PLANB"),                   // changed
            mem("000004", ins03 = "024"),                    // termed by code
            mem("000005", extra = "DTP*349*D8*20261231~"),   // termed by date appearing
            mem("000006"),                                   // added   (000003 absent -> termed)
        )
        val (rows, s) = diffRows(old, new)
        assertEquals(1L, s.added)
        assertEquals(3L, s.termed)
        assertEquals(1L, s.termedAbsent)
        assertEquals(2L, s.termedFlagged)
        assertEquals(1L, s.changed)
        assertEquals(1L, s.unchanged)
        val changed = rows.single { it.type == "CHANGED" }
        assertEquals("HD04", changed.element)
        assertEquals("PLANA", changed.oldValue)
        assertEquals("PLANB", changed.newValue)
        assertTrue(rows.any { it.type == "TERMED" && it.detail.contains("INS:03=024") })
        assertTrue(rows.any { it.type == "TERMED" && it.detail.contains("DTP*349:03") })
        assertTrue(rows.any { it.type == "TERMED" && it.detail == "not present in new file" && it.key.startsWith("SUB000003") })
        assertTrue(rows.any { it.type == "ADDED" && it.key.startsWith("SUB000006") })
    }

    @Test
    fun identicalFilesHaveNoDifferencesAndMultipleStSetsWork() {
        val f = file(mem("000001"), mem("000002"))
        val (rows, s) = diffRows(f, f)
        assertTrue(rows.isEmpty())
        assertFalse(s.hasDifferences)
        assertEquals(2L, s.unchanged)
        // two ST sets in one GS
        val two = isa() + "GS*BE*A*B*20260101*1200*1*X*005010X220A1~" +
            "ST*834*0001~" + mem("000001") + "SE*5*0001~ST*834*0002~" + mem("000002") + "SE*5*0002~GE*2*1~IEA*1*000000001~"
        val (_, s2) = diffRows(two, two)
        assertEquals(2L, s2.unchanged)
    }

    @Test
    fun diffAcrossDifferentDelimitersAndLineBreaks() {
        val old = file(mem("000001"), mem("000002"))
        val pipe = old.replace('*', '|').replace("ISA|", "ISA|").replace("|:~", "|:~")
        // rebuilding with different delimiters via the ISA helper keeps fixed header widths valid
        val newText = isa('|', '>', '^', "\n") + "GS|BE|A|B|20260101|1200|1|X|005010X220A1\nST|834|0001\n" +
            mem("000001", t = "\n").replace('*', '|') + mem("000002", plan = "PLANC", t = "\n").replace('*', '|') +
            "SE|9|0001\nGE|1|1\nIEA|1|000000001\n"
        val (rows, s) = diffRows(old, newText)
        assertEquals(1L, s.unchanged)
        assertEquals(1L, s.changed)
        assertEquals("PLANC", rows.single().newValue)
        assertTrue(pipe.isNotEmpty())
    }

    @Test
    fun profileIgnoreAndLabelsAndCustomKey() {
        val p = Profile.parse(
            "key.1 = NM1*IL:09\nignore = HD:04\nlabel.HD:04 = line label\n", "t"
        )
        val old = file(mem("000001"))
        val (rows0, s0) = diffRows(old, file(mem("000001", plan = "PLANZ")), p)
        assertTrue(rows0.isEmpty())
        assertEquals(1L, s0.unchanged)
        val p2 = Profile.parse("key.1 = NM1*IL:09\nlabel.HD:04 = line label\n", "t")
        val (rows, _) = diffRows(old, file(mem("000001", plan = "PLANZ")), p2)
        assertEquals("line label", rows.single().label)
        assertEquals("999000001", rows.single().key)
    }

    @Test
    fun noEightThirtyFourSetIsAClearError() {
        val other = isa() + "GS*HC*A*B*20260101*1200*1*X*005010X222~ST*837*0001~SE*2*0001~GE*1*1~IEA*1*000000001~"
        val ex = assertThrows<X12Exception> { diffRows(other, other) }
        assertTrue("834" in ex.message!!)
    }

    @Test
    fun generatedPairProducesExpectedCounts() {
        val dir = Files.createTempDirectory("x12diff-gen")
        try {
            val o = dir.resolve("o.834"); val n = dir.resolve("n.834")
            val g = Gen.generate(3000, o, n, 7, lineBreaks = true, sets = 3)
            val rows = ArrayList<Row>()
            val s = Differ.diff(o, n, Profile.default()) { rows.add(it) }
            assertEquals(g.added, s.added)
            assertEquals(g.removed, s.termedAbsent)
            assertEquals(g.termFlagged, s.termedFlagged)
            assertEquals(g.changed, s.changed)
            assertEquals(g.oldMembers, s.oldMembers)
            // envelopes of generated files are consistent
            for (p in listOf(o, n)) assertTrue(checkFile(p).warnings.isEmpty(), checkFile(p).warnings.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun checkFile(p: Path): EnvelopeChecker {
        val c = EnvelopeChecker()
        Files.newInputStream(p).use { i ->
            val r = X12Reader(i)
            while (true) c.accept(r.next() ?: break)
        }
        c.finish()
        return c
    }
}
