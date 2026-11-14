# x12diff 0.2.0 build report (0.1.0 results kept below under Milestone 1)

Built 2026-11-14 on Windows 11, Temurin JDK 21.0.11, Gradle 9.4.1, Kotlin 2.2.21. Runtime dependencies: none (Kotlin stdlib only).

## Tests
`./gradlew test`: 27 unit tests, all passing (17 from milestone 1, 10 in ClaimsTests.kt: CSV escaping, money parsing, diff CSV escaping, remit flattening and rollup, remit claim and check balance warnings, formula-safe remit CSV and non-X12 input handling, recon classification with 999 and 277CA tracing, configurable reversal codes, generated 837/835/999/277 sets matching the generator's expected counts, malformed recon input errors).

Milestone 1 coverage: Covered: delimiter detection (standard, `|` with `^` and LF terminator, CR terminator, CRLF, no newlines, wrapped lines, BOM, multiple interchanges), envelope mismatch warnings and truncated files, summary counts (277CA, 999, TA1, multiple ST per GS), diff ADDED/TERMED(absent, code, date)/CHANGED/UNCHANGED, differing delimiters across files, profile ignore/label/key options, bad profile errors, malformed input and missing file (clear `error:` line, exit 2, no stack trace), and a generated 3,000-member pair whose diff counts equal the generator's expected counts.

## Smoke (synthetic data from `x12diff gen`)
Wall time and peak working set were measured from outside with a polling script. The JVM was run with `-Xmx512m`.

| Case | Size | Members (old/new) | Command | Time | Peak working set |
|---|---|---|---|---|---|
| tiny | 1,079 B / 1,349 B | 3 / 4 | diff | 0.2 s | about 40 MB (JVM floor) |
| large | 299,031,710 B / 299,560,961 B | 1,150,000 / 1,150,149 | diff + CSV | 7.7 s | 586 MB |
| large | 299,031,710 B | 1,150,000 | parse | 2.0 s | 361 MB |

The large diff matched the generator's expected counts exactly: ADDED 11,500; TERMED 34,228 (11,351 absent and 22,877 newly flagged); CHANGED 34,360; UNCHANGED 1,081,412.

Heap-limit check: the same 300 MB diff completes with `-Xmx256m` in 7.7 s. The working set is higher than the heap limit because of JVM overhead and the GC's headroom. Memory depends on the member index (key, 128-bit hash, offset), not on file size.

## Packaging
- `build/libs/x12diff.jar`: fat jar, works.
- `./gradlew appImage` (jpackage app-image, `--win-console`, `-Xmx512m`): works. `build/jpackage/x12diff/x12diff.exe`, 154 MB with the bundled runtime. Verified: `diff` and `parse` run, error exit code 2. No installer (MSI/EXE) was built, since that needs WiX and signing.

## Not done / limits
- No signed installer.
- Repeating segments are aligned by the `align` element, else by order. A reordered unqualified repeating segment shows as changed values.
- Comparison is on whole element values; sub-elements are not split.
- Only `ST*834` sets are diffed. Other transaction types are parsed and summarized only.
- Dependents are handled only through the key (default key includes INS02 and a member ID); profile tuning may be needed for a trading partner's files.
- No CSV formula-injection escaping; open CSVs from untrusted partners with care.

## Milestone 2 (0.2.0): CSV escaping, remit, recon, claims generator

New commands: `remit`, `recon`, `gen --kind claims`. CSV output everywhere now goes through one escaping routine (README, "CSV safety").

Smoke, synthetic data from `x12diff gen --kind claims --claims 1050000` (837 134+ MB, 835s 111 MB, 999 and 277 small, 294,777,752 bytes in total), JVM `-Xmx512m`, measured from outside by polling:

| Case | Input | Command | Time | Peak working set |
|---|---|---|---|---|
| tiny | 783 B 837 + 905 B and 225 B 835 + 999 + 277 (3 claims) | recon | 0.3 s | about 40 MB (JVM floor) |
| large | 1,050,000 claims, 1,012,795 remit entries | gen | 2.6 s | 453 MB |
| large | two 835 files, 111 MB, 2,025,590 service lines | remit + 277 MB CSV | 3.9 s | 435 MB |
| large | all five files, 295 MB | recon + CSV | 5.9 s | 628 MB |

The large recon counts equal the generator's expected counts exactly: PAID 508,842; PARTIAL 198,697; DENIED 80,112; REVERSED 50,043; ADJUSTED 39,936; UNMATCHED 69,664; REJECTED 102,706 (52,680 by 999 set, 50,026 by 277CA); ORPHAN 5,250. The remit run had 0 balance warnings, and the net paid and charged totals match the generator. Recon keeps one small object per 837 claim in memory (about 180 bytes each), so memory grows with the claim count, not with the 835 size. 835 files are streamed, and each file is read twice (837 pass first, then 835/999/277).

Packaging: `build/libs/x12diff.jar` and the app image `build/jpackage/x12diff/x12diff.exe` were rebuilt; `--version`, `recon` and `remit` run through the exe.

Limits and judgement calls (milestone 2):
- Claim classification is by net amounts across all remit entries for a claim; `recon.paid.min.ratio` decides PAID vs PARTIAL and defaults to 1.0, which makes most real contracted-rate payments PARTIAL until the owner sets it.
- Reversals are recognized by the claim status code (default 22) and amounts are summed as written. A payer that sends a reversal with a positive amount is not handled.
- 999 tie-back is by GS06 + ST02, not ST02 alone, because ST02 repeats across interchanges. A 999 without AK1 falls back to ST02 only.
- 277CA tie-back is by the patient control number in TRN02 (claim level); the same ID in several claims marks all of them.
- A claim rejected by 999/277CA that also has a remit keeps its remit status and gets a note in `detail`.
- Balance checks assume claim-level OR line-level adjustments; a payer that reports both for the same dollars will produce a warning.
- Adjustment amounts are parsed to cents; amounts with more than two non-zero decimals are reported as invalid.
- The synthetic reversal checks can net negative, which a real check would not.
- The CLI now includes the 999/277CA tracer and the 837 to 835 recon, so the README's "Pro (coming)" list was reworded to GUI, Excel export and saved profiles. The owner should decide the free/Pro split before publishing.
