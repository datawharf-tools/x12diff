package tools.datawharf.x12diff

import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Synthetic 834-shaped file generator. All data is invented: names TESTPERSONnnnnnnn,
 * IDs starting with 999 or SUB, phone numbers in the 555 range. Output is deterministic per seed.
 */
object Gen {
    class Result(
        val oldMembers: Long, val newMembers: Long,
        val added: Long, val removed: Long, val termFlagged: Long, val changed: Long,
    )

    private fun mix(z0: Long): Long {
        var z = z0 + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** 0 base, 1 removed, 2 term code, 3 term date only, 4 address change, 5 line change. */
    private fun variantOf(n: Long, seed: Long): Int {
        val r = (mix(seed xor (n * 0x2545F4914F6CDD1DL)) ushr 11).toDouble() / (1L shl 53).toDouble()
        return when {
            r < 0.010 -> 1
            r < 0.020 -> 2
            r < 0.030 -> 3
            r < 0.050 -> 4
            r < 0.060 -> 5
            else -> 0
        }
    }

    private class Out(val w: BufferedWriter, val term: String) {
        var count = 0
        fun seg(s: String) {
            w.write(s)
            w.write(term)
            count++
        }
    }

    private fun member(o: Out, n: Long, variant: Int) {
        val id9 = "999" + n.toString().padStart(6, '0')
        val sub = "SUB" + n.toString().padStart(8, '0')
        val ins03 = if (variant == 2) "024" else "030"
        o.seg("INS*Y*18*$ins03*XN*A*E**N")
        o.seg("REF*0F*$sub")
        o.seg("REF*1L*GRPTEST01")
        o.seg("DTP*356*D8*20260101")
        o.seg("NM1*IL*1*TESTPERSON" + n.toString().padStart(7, '0') + "*TESTFIRST*A***MI*$id9")
        o.seg("PER*IP**HP*80055501" + (n % 100).toString().padStart(2, '0'))
        val street = if (variant == 4) "200 SAMPLE AVENUE APT ${n % 97}" else "100 TEST STREET UNIT ${n % 97}"
        o.seg("N3*$street")
        o.seg("N4*TESTVILLE*TS*00000")
        o.seg("DMG*D8*" + (1950 + (n % 50)) + "0101*M")
        val plan = if (variant == 5) "PLANB" else "PLANA"
        o.seg("HD*030**HLT*$plan")
        o.seg("DTP*348*D8*20260101")
        if (variant == 2 || variant == 3) o.seg("DTP*349*D8*20261231")
    }

    private fun writeFile(
        path: Path, base: Long, extra: Long, seed: Long,
        isNew: Boolean, lineBreaks: Boolean, sets: Int, total: Long,
    ) {
        val term = if (lineBreaks) "~\n" else "~"
        Files.newBufferedWriter(path, StandardCharsets.ISO_8859_1, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING).use { w ->
            val o = Out(w, term)
            o.seg("ISA*00*          *00*          *ZZ*SENDERTEST     *ZZ*RECEIVERTEST   *260101*1200*^*00501*000000001*0*T*:".also { check(it.length == 105) })
            o.seg("GS*BE*SENDERTEST*RECEIVERTEST*20260101*1200*1*X*005010X220A1")
            val chunk = maxOf(1L, (total + sets - 1) / sets)
            var inSet = false
            var setNo = 0
            var written = 0L
            fun closeSet() {
                if (inSet) {
                    o.count++ // the SE itself
                    w.write("SE*${o.count}*${setNo.toString().padStart(4, '0')}")
                    w.write(term)
                    inSet = false
                }
            }
            fun emit(n: Long, v: Int) {
                if (written % chunk == 0L) {
                    closeSet()
                    setNo++
                    o.count = 0
                    o.seg("ST*834*" + setNo.toString().padStart(4, '0') + "*005010X220A1")
                    o.seg("BGN*00*TESTFILE$setNo*20260101*1200****4")
                    inSet = true
                }
                member(o, n, v)
                written++
            }
            var n = 1L
            while (n <= base) {
                val v = if (isNew) variantOf(n, seed) else 0
                if (v != 1) emit(n, v)
                n++
            }
            if (isNew) for (a in 1..extra) emit(base + a, 0)
            if (!inSet) { // zero members: still produce a valid set
                setNo++
                o.count = 0
                o.seg("ST*834*0001*005010X220A1")
                inSet = true
            }
            closeSet()
            o.seg("GE*$setNo*1")
            o.seg("IEA*1*000000001")
        }
    }

    fun generate(members: Long, oldPath: Path, newPath: Path, seed: Long, lineBreaks: Boolean, sets: Int): Result {
        if (members < 1) throw X12Exception("--members must be at least 1")
        if (sets < 1) throw X12Exception("--sets must be at least 1")
        var removed = 0L
        var termFlag = 0L
        var changed = 0L
        for (n in 1..members) {
            when (variantOf(n, seed)) {
                1 -> removed++
                2, 3 -> termFlag++
                4, 5 -> changed++
            }
        }
        val added = maxOf(1L, members / 100)
        writeFile(oldPath, members, 0, seed, false, lineBreaks, sets, members)
        writeFile(newPath, members, added, seed, true, lineBreaks, sets, members - removed + added)
        return Result(members, members - removed + added, added, removed, termFlag, changed)
    }
}
