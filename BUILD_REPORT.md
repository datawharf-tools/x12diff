# x12diff 0.2.2 build report (0.1.0, 0.2.0 and 0.2.1 sections kept below)

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

## 0.2.1: fixes from REVIEW_1.md

52 unit tests, all passing (27 earlier + 25 in RegressionTests.kt, one or more per finding, using the reviewer's repros).

| Finding | Status | Fix |
|---|---|---|
| C1 change files termed everyone | Fixed | BGN08 read; change mode reports `NOT_IN_NEW`, full mode `TERMED`; mixed/unknown warns and says full was used; `--mode full\|change`; modes printed; README caveat |
| C2 truncated NEW file | Fixed | Envelope checker runs on every file in diff/remit/recon; loud notice on stdout and stderr, exit 3, `--allow-incomplete` |
| C3 duplicate CLM01 | Fixed | Warns on every duplicate; matches by abs(charge), payer control number, reversal/repay state; reviewer case gives ADJUSTED 180.00 and the other claim UNMATCHED; paid > charge flagged |
| C4 999 collisions | Fixed | Candidates narrowed by GS sender/receiver then file name; still ambiguous = nothing marked plus warning. The brief asked for ISA13 too: a 999 does not carry the 837's ISA13, so ISA13 separates interchanges internally only |
| H1 stack traces, wildcards | Fixed | Wildcards split before `Paths.get`; invalid paths and negative `--show` give one-line errors; catch-all in `run()` |
| H2 zero paid to patient resp = DENIED | Fixed | New status PATIENT_RESP; DENIED needs a denial status code (default 4) or no patient responsibility |
| H3 personal identity in history | Fixed | History rewritten, see the commit log; repo-local identity set to Datawharf Tools |
| H4 siblings collapsed | Fixed | Default key falls back to name + birth date; duplicate keys warn and rows say `ambiguous key` |
| M1 claim + line CAS | Fixed | Balance adds claim and line adjustments; line paid check allows the claim-level adjustments |
| M2 invalid amounts as 0.00 | Fixed | Raw text kept in CSV, excluded from totals, counted; bad PLB warns and skips the check total |
| M3 truncated 837 in recon | Fixed | Warns ("cut off") and exit 3 |
| M4 277CA acceptance | Fixed | Newest STC02 date wins; acceptance clears a rejection; same date: acceptance wins |
| M5 non-ASCII | Fixed | Valid UTF-8 byte sequences decoded; CSV written as UTF-8 with BOM |
| M6 PAID ratio default | Fixed | PAID = only PR and contractual (`recon.contractual`, default CO-45) adjustments; other adjustments = PARTIAL; `recon.paid.min.ratio` removed |
| M7 README control chars, phones | Fixed | README rewritten and checked by a test; phones 800-555-0100..0199 |
| L1 RD8, DTP*434 | Fixed | First date of a range; DTP*472 then DTP*434 |
| L2 reject codes | Fixed | Default R, M, W, X |
| L3 sub-element separator | Fixed | Composites normalized before hashing and comparing |
| L4 multi-ISA parse, 837 flavours | Fixed | One Delimiters line per interchange; 837P/837I/837D labels |
| L5 payer_id | Fixed | Falls back to REF*2U |
| L6 stale docs | Fixed | Stale line removed; README samples now carry the seed and command that reproduce them |
| L7 error wording | Fixed | Missing output folder, folder passed to parse, skipped non-X12 files named |
| L8 term date vs file date | Deferred | Needs a policy choice (compare with BGN03 or a profile cutoff); low risk because `term.dates` is optional in the profile |
| L9 line-level 277CA STC | Fixed | Ignored |
| L10 negative age | Fixed | `future` bucket |

Smoke, synthetic data, JVM `-Xmx512m`, measured from outside:

| Case | Time | Peak working set |
|---|---|---|
| gen 1,050,000 claims (316 MB: 837 + 835 + 999 + 277) | 2.9 s | 441 MB |
| remit, two 835 files, 2,025,590 lines, 0 warnings | 5.2 s | 423 MB |
| recon, all five files, counts equal the generator's | 7.0 s | 640 MB |
| diff, 1,150,000-member 834 pair (299 MB each), full mode | 11.1 s | 585 MB |

The recon and diff counts equal the generator's expected counts exactly. The 0.1.0 table above holds the 1 KB case; the 1 KB runs are still instant. Packaging: `build/libs/x12diff.jar` and `build/jpackage/x12diff/x12diff.exe` were rebuilt.

Known limits now: the synthetic reversal checks can net negative; PAID/PARTIAL depends on `recon.contractual` matching your payers; an equal-charge duplicate CLM01 can still be matched to the wrong twin (warned).

## 0.2.2: fixes from REVIEW_2.md

62 unit tests, all passing (52 earlier + 10 new, one or more per finding).

| Finding | Status | Fix |
|---|---|---|
| N1 CRITICAL, new-only terminated member shown as ADDED | Fixed | A member that exists only in the new file and carries the term flag is TERMED (`termination in new file; not in old file`) in both modes |
| N2 HIGH, last name re-keys a dependent | Fixed | Default fallback key is first name + birth date (no last name), so a last-name change is a CHANGED row. If a first name changes, the ADDED and TERMED rows with the same subscriber, relationship and birth date are marked `possible name change` and a warning is printed (pairing buffers up to 100,000 ADDED rows) |
| N3 HIGH, 999 applied without a date check, README overclaim | Fixed | 999/997 and 277CA must be dated on or after the 837 (GS04, or BHT04) and within `recon.ack.window.days` (default 30), and the AK1-03 version must match GS08; otherwise nothing is marked and a warning says so. The row detail says when a file name resolved a match. README wording softened |
| N4 MEDIUM, stale example profile, silent unknown keys | Fixed | `profiles/example.properties` regenerated from the built-in text; a test keeps them identical; unknown keys (including the removed `recon.paid.min.ratio`) print a warning |
| N5 MEDIUM, PATIENT_RESP hid denials | Fixed | PATIENT_RESP only when nothing but PR was adjusted; any other adjustment with zero paid is DENIED; documented |
| N6 MEDIUM, routine Medicare PARTIAL | Fixed | Default `recon.contractual = CO-45, CO-253`; README says how to extend it |
| N7 README 834 example | Fixed | Replaced with a real run's output; a test checks that it shows the current format |
| N8 empty file | Fixed | `parse` warns and exits 3 (also for other envelope problems); `--allow-incomplete` returns 0 |
| N9 BOM | Documented | README explains the BOM and how to read it |
| N10 duplicate-CLM01 detail | Fixed | Detail says how the remit was matched (charge, payer control number, payment state and order, claim ID only, or ambiguous) |
| N11 double notice | By design, documented | stdout and stderr both carry the notice so redirecting either keeps it |

The reviewer's note on the global git identity: `~/.gitconfig` is not touched by this project; the repo-local identity is Datawharf Tools.

Smoke on the 316 MB synthetic claims set and the 299 MB 834 pair, `-Xmx512m`: recon 7.1 s, 631 MB peak working set, counts equal the generator's; 834 diff 7.9 s, 591 MB. Packaging: the jar and `build/jpackage/x12diff/x12diff.exe` (reports 0.2.2) were rebuilt.
