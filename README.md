# x12diff: compare, flatten and reconcile X12 healthcare files offline

**A free command-line tool to compare two 834 enrollment files, convert 835 remittances to CSV files that open in Excel, reconcile 837 claims against 835 payments, and trace 999 / 277CA rejections back to the claim.** It runs on your PC, makes no network connections, and handles files of several hundred megabytes without opening them in a spreadsheet.

By Datawharf Tools. MIT license. Windows app image or any Java 17+.

Page and Pro waitlist: https://datawharftools.com/x12/

## What do you need to do?

| I want to... | Command |
|---|---|
| See what is in an X12 file (interchanges, transaction types, envelope errors) | `x12diff parse FILE` |
| **Compare two 834 files**: who was added, termed or changed between last month and this month | `x12diff diff OLD NEW --csv changes.csv` |
| **Convert an 835 to CSV for Excel**: one row per service line, adjustments by reason code, balance checks | `x12diff remit FILES --csv lines.csv --rollup adjustments.csv` |
| **Reconcile 837 claims to 835 payments**: paid, partial, denied, reversed, unmatched with aging, orphan payments | `x12diff recon claims.837 remits.835 --csv recon.csv` |
| **Find why a claim never got paid: 999 and 277CA rejections** traced back to the 837 claim | `x12diff recon` with the 837, 835, 999 and 277 files |
| Try everything on fake data first | `x12diff gen ...` |

## Why use it

- It streams. Memory depends on the number of members (diff) or claims (recon), not on file size. On a desktop PC (24-thread Intel Core Ultra 9, 64 GB RAM, other programs running) we measured, on synthetic files: two 260 MB 834 files (1,000,000 members each) compared in 7 to 13 seconds across several runs (the jar with Java's default heap used about 1.7 GB; the Windows app, capped at 512 MB, took 8 to 10 seconds), and a 1,000,000-claim 837/835/999/277 set (about 300 MB) reconciled in about 8 seconds. Flattening its 144 MB of 835 remits took about 3 seconds. A slower or busier PC takes longer: one run on a heavily loaded PC took about 36 seconds for the diff. Your files and hardware will differ.
- **Privacy: x12diff runs entirely on your machine and makes no network connections.** It has no telemetry, no update check and no network code. It reads the files you name and writes the CSV you ask for.
- It checks envelopes (SE, GE, IEA, control numbers) and refuses to give you a quiet wrong answer from a cut-off file: it exits with code 3 and a loud notice.
- It ships no implementation-guide text. You set member keys, termination codes and labels in a plain-text profile that matches your trading partner.

## Quick start (3 commands)

**Which download?** Both are on the [Releases page](https://github.com/datawharf-tools/x12diff/releases/latest). No Java on your PC: take the Windows zip (about 57 MB, Java is bundled). Java 17 or newer already installed: the 2 MB jar is enough.

**Java check (jar only).** Run `java -version`. It must report 17 or newer. If Java is missing or older, install a free Java 17 or 21 (Eclipse Temurin, at adoptium.net), or use the Windows zip instead.

```
java -jar x12diff-0.2.4.jar gen --members 300 --old old.834 --new new.834 --seed 3
java -jar x12diff-0.2.4.jar diff old.834 new.834 --csv changes.csv
java -jar x12diff-0.2.4.jar parse new.834
```

The first command writes a fake old/new pair (no real data is ever used). The second compares them and writes `changes.csv`; it exits with code 1 when it finds differences, which is expected (see exit codes below). The third summarizes a file. Replace the fake files with your own to use it for real. For 835 and 837 files, generate a synthetic set with `java -jar x12diff-0.2.4.jar gen --kind claims --claims 200 --dir demo --seed 4`, then:

```
java -jar x12diff-0.2.4.jar remit demo/remits_1.835 demo/remits_2.835 --csv lines.csv
java -jar x12diff-0.2.4.jar recon demo --csv recon.csv --show 0
```

`recon` also prints the first 20 claims that are not PAID (status, claim ID, charge, why); `--show 0` hides that listing and the warnings detail, `--show N` sets how many lines to print.

### Windows zip (no Java needed)

1. Download `x12diff-0.2.4-windows-x64.zip` from the Releases page. In PowerShell, check the download: `Get-FileHash .\x12diff-0.2.4-windows-x64.zip -Algorithm SHA256` (or `certutil -hashfile x12diff-0.2.4-windows-x64.zip SHA256` in Command Prompt). The value must equal the SHA-256 in the release notes.
2. Right-click the zip, Extract All. You get a folder named `x12diff`. Keep everything in it together.
3. Open PowerShell or Command Prompt in the folder that contains `x12diff` and run the same commands, with the exe in place of `java -jar x12diff-0.2.4.jar`:

```
.\x12diff\x12diff.exe gen --members 300 --old old.834 --new new.834 --seed 3
.\x12diff\x12diff.exe diff old.834 new.834 --csv changes.csv
.\x12diff\x12diff.exe parse new.834
```

Typing just `x12diff` works only after you add the `x12diff` folder to your PATH (Windows Settings, search for "environment variables", edit the user variable Path). Until then use the full path as above.

The exe is not code-signed, so Windows SmartScreen may show "Windows protected your PC" the first time. Only continue (More info, Run anyway) when the SHA-256 from step 1 matches the release notes. The app makes no network connections. Its Java heap is capped at 512 MB, which is enough for the 1,000,000-member and 1,000,000-claim runs above.

## Running it: options and exit codes

You need Java 17 or newer (the jar is built for Java 17 and also runs on 21), or use the Windows zip (runtime bundled; see above).

```
java -jar x12diff-0.2.4.jar parse enrollment.834
java -jar x12diff-0.2.4.jar diff last_month.834 this_month.834 --csv changes.csv
java -jar x12diff-0.2.4.jar diff old.834 new.834 --profile my-profile.properties
```

Exit codes: `0` ok (`diff`: no differences), `1` `diff` found differences, `2` error, `3` input incomplete or inconsistent (also for `parse`: an empty file, a missing trailer or a count mismatch; `parse --allow-incomplete` returns 0). Errors print a one-line message, never a stack trace.

**Incomplete input.** `diff`, `remit` and `recon` check the envelopes of every file they read. A missing SE, GE or IEA (for example after a cut-off transfer), or a segment-count or control-number mismatch, prints a loud `INPUT INCOMPLETE OR INCONSISTENT` notice on the console and on stderr, and the command exits with `3`. The report is still written, but do not trust it. Pass `--allow-incomplete` to accept such files knowingly. The notice goes to both stdout and stderr on purpose, so it survives redirecting either one; in a terminal you see it twice.

**Empty input.** If `diff` reads a file with no members (an empty file, a file that is not an 834, lowercase segment IDs, or a `member.start` that does not match), it prints `EMPTY INPUT: new file has 0 members` (or `old file`) and exits with `3`, because every member of the other file would otherwise show as ADDED or TERMED. `--allow-incomplete` accepts it knowingly. An empty file gives exit code 3 in both `diff` and `parse`.

**Output files.** CSV files are written under a temporary name and renamed when the run succeeds. A run that fails (out of memory, a bad input file) leaves no half-written CSV, and an existing CSV of the same name is left untouched. Naming the same file for two outputs (`--csv x.csv --rollup x.csv`, `gen --old o --new o`), or an output that is also an input, is an error (exit 2).

Options for `diff`: `--csv FILE`, `--profile FILE`, `--mode full|change` (see below), `--show N` (changes echoed to the console, default 20), `--stats` (elapsed time and heap use), `--allow-incomplete`.

Inputs for `remit` and `recon` can be files, folders (every X12 file in the folder; other files are skipped and named) or a wildcard in the file name such as `"C:/remits/*.835"`. Paths with spaces work when quoted.

## Examples (synthetic data)

```
$ x12diff gen --members 300 --old old.834 --new new.834 --seed 3
$ x12diff diff old.834 new.834 --show 3 --csv changes.csv
  CHANGED    SUB00000031|999000031|18  N3 N301: '100 TEST STREET UNIT 31' -> '200 SAMPLE AVENUE APT 31'
  CHANGED    SUB00000067|999000067|18  N3 N301: '100 TEST STREET UNIT 67' -> '200 SAMPLE AVENUE APT 67'
  TERMED     SUB00000073|999000073|18  DTP*349 segment added; termination flag: DTP*349:03 present
Old: old.834  members: 300  (BGN08 4: full)
New: new.834  members: 298  (BGN08 4: full)
Mode: full (a member missing from the new file is termed)
ADDED:      3
TERMED:     13  (5 absent from new, 8 newly flagged)
CHANGED:    11
UNCHANGED:  276
CSV written: changes.csv
```

The diff CSV lists the CHANGED rows and the TERMED rows caused by a termination flag first, in new-file order, then the ADDED rows, then the TERMED (or, in change mode, NOT_IN_NEW) rows for members that are absent from the new file.

`changes.csv`:

```
change_type,member_key,segment,element,label,old_value,new_value,detail
CHANGED,SUB00000031|999000031|18,N3,N301,,100 TEST STREET UNIT 31,200 SAMPLE AVENUE APT 31,
TERMED,SUB00000073|999000073|18,DTP*349,,,,DTP*349*D8*20261231,segment added; termination flag: DTP*349:03 present
```

`parse` output:

```
File: old.834 (78,225 bytes)
Delimiters: element '*'  sub-element ':'  repetition '^'  segment terminator '~'
Interchanges (ISA): 1
Functional groups (GS): 1
Transaction sets (ST): 1
  834: 1
Segments: 3,307
  ...
Warnings: 0
```

Envelope warnings look like `Control number mismatch: ST02=0001 but SE02=0002 (segment #9, byte offset 912)`. The checks are ISA13/IEA02, GS06/GE02, ST02/SE02, SE01 against the real segment count, and the group and transaction counts in IEA01 and GE01.

## Remit: 835 to one row per service line

```
x12diff remit remits_1.835 remits_2.835 --csv lines.csv --rollup adjustments.csv
x12diff remit "C:/remits/2026-11" --csv lines.csv        (a folder: every X12 file in it)
x12diff remit "C:/remits/*.835" --csv lines.csv          (a wildcard in the file name; backslashes work too)
```

Synthetic output (`x12diff gen --kind claims --claims 2000 --dir demo --seed 4`):

```
Files: 2   835 checks: 20
Claims: 1,910   Service lines: 3,820
Charged: 386982.50   Paid: 270751.67   Paid/charged: 70.0%   Patient responsibility: 22952.25
Adjustments by group-reason (count, amount):
  CO-50           150          40214.18
  CO-97           740          26859.71
  CO-45         2,662          26204.69
  PR-1            858          22952.25
Warnings: 0
```

`lines.csv` columns: `file, check_number, payment_date, payer_id, payee_id, claim_id, claim_status, claim_charge, claim_paid, patient_resp, payer_claim_ctrl, service_qualifier, service_code, service_modifiers, units, line_charge, line_paid, line_adjustments, claim_adjustments`. Adjustments are written as `GROUP:REASON:AMOUNT` and joined with `|`, so a line with several adjustments stays on one row. Claim-level adjustments repeat on each line of the claim. `payer_id` is N1*PR element 4, or REF*2U element 2 when that is empty.

Checks, reported as warnings with the check number and claim ID:

- per claim: charge - paid - (claim-level adjustments + line-level adjustments) must be zero, and the service-line paid amounts must add up to the claim paid amount, or to that amount plus the claim-level adjustments;
- per check: BPR02 must equal the sum of CLP04 minus the PLB adjustments.

An amount that is not a valid number (for example `100.005`) is never turned into 0.00. The CSV keeps the raw text, the amount is left out of the totals and counted in a warning, and the balance checks that depend on it are skipped.

Only codes are shown. To get readable text in the rollup, add `adjlabel.CO-45 = your text` lines to a profile and pass `--profile`. x12diff ships no description text for any code.

## Recon: claims, payments and the 999/277CA trace

```
x12diff recon claims.837 remits_1.835 remits_2.835 ack.999 status.277 --asof 20260601 --csv recon.csv --show 0
x12diff recon "C:/month-end" --csv recon.csv
```

Without `--show 0`, `recon` also prints a `First 20 non-PAID rows:` listing (one line per claim that is not PAID, with its charge, paid amount and reject source) before the warnings. `--show N` changes the number of lines; `--show 0` leaves the listing out of the console (the CSV always has every row).

Files can be given in any order, or as folders. x12diff recognizes the transaction type inside each file (837, 835, 999 or 997, 277), so you can mix them. Synthetic output (`x12diff recon demo --asof 20260601`):

```
Files: 5   837 claims: 2,000 in 50 sets   835 claim entries: 1,910 on 20 checks
Acknowledgement sets: 1 (3 837 sets rejected)   277CA claim statuses: 239
PAID:         892
PARTIAL:      370
DENIED:       150
PATIENT_RESP: 59
REVERSED:     108
ADJUSTED:     71
UNMATCHED:    131
REJECTED:     219
ORPHAN:       10
Charged (837): 503572.20   Paid (matched 835s, net): 270076.15
UNMATCHED aging (days since claim date): 0-30: 0  31-60: 32  61-90: 35  91+: 64  future: 0  unknown: 0
Warnings: 0
```

How claims are classified:

- A claim matches remit entries by CLM01 = CLP01. When the 837 input holds the same CLM01 more than once (resubmissions, corrected claims), x12diff warns and picks the claim by absolute charge, then the payer claim control number (CLP07), then by whether the claim already has a payment or a reversal waiting for repayment. A reversal therefore lands on the claim that holds its original payment, and a repayment on the same claim. If two duplicate claims have the same charge the match can still be wrong, and the warning says so. A paid amount above the 837 charge is flagged.
- Amounts for one claim are summed across all remit entries, so an original payment, a reversal and a repayment net out. If any entry has a reversal status (profile `recon.reversal.codes`, default 22): REVERSED when the net paid is zero or less, ADJUSTED when money is still paid.
- Otherwise, with zero paid: PATIENT_RESP only when there is patient responsibility and nothing but PR and the `recon.contractual` codes was adjusted (for example the whole amount went to the deductible); that claim was processed, not denied. DENIED when an entry has a denial status (`recon.denied.codes`, default 4), when there is no patient responsibility, or when any non-PR adjustment such as CO-50 reduced the payment, even if some of the amount also went to the patient.
- Otherwise, with money paid: PAID when the only adjustments are patient responsibility (PR) and the routine write-offs listed in `recon.contractual` (default `CO-45, CO-253`: contractual plus sequestration), PARTIAL when any other adjustment reduced the payment. A claim with no adjustment segments at all is PARTIAL if paid plus patient responsibility is below the charge. To extend the list, add `GROUP-REASON` codes to the profile line, for example `recon.contractual = CO-45, CO-253, CO-42, PI-45`. If PARTIAL looks too high for a payer, the reason codes in the `claim_adjustments` and `line_adjustments` columns of `remit` show which ones to add.
- No remit yet: UNMATCHED, aged by days from the claim date to `--asof` (default today). A claim date after `--asof` goes in the `future` bucket. The claim date is the first match of each `recon.claim.date` locator in the claim (default `DTP*472:03`, then `DTP*434:03`; a date range uses its first date), else `recon.claim.date.fallback` (default `BHT:04`).
- REJECTED before adjudication, only when no remit exists:
  - a 999 or 997 rejected the claim's transaction set (IK5 or AK5 element 1 in `recon.ack.reject.codes`, default R, M, W, X; AK9 rejects a whole group). The 999 is tied to the 837 by GS06 and ST02, the version in AK1-03 against GS08, and the date: the 999's GS04 must be on or after the 837's GS04 and within `recon.ack.window.days` (default 30), otherwise nothing is marked and a warning says so. If several 837 sets still match, the sender and receiver in the GS segments, then the file name (`jan.837` with `jan.999`), then the most recent 837 sent before the acknowledgement decide, so a resubmission that reused the same control numbers is resolved to the newer set; the row's `detail` says when the file name was used. If more than one candidate is left, nothing is marked and a warning says why, naming whether the date check or the version check failed. These checks make a wrong match unlikely but cannot rule it out when two sets share every identifier and date; keep one month per folder if in doubt;
  - or a 277CA claim-level status has a category in `recon.status.reject.categories` (default A3, A4, A6, A7, A8; claims are tied by the patient control number in TRN02, and by the charge in STC04 if the ID is duplicated; the 277CA's BHT04 (or GS04) must also be on or after the 837 date and within the same window). Service-line statuses are ignored. The newest status (by STC02 date) wins, and an acceptance (`recon.status.accept.categories`, default A0, A1, A2) clears an earlier rejection. On the same date an acceptance wins.
  - or a TA1 (interchange acknowledgement) rejected the interchange the claim was sent in: TA104 is `R` (profile `recon.ta1.reject.codes`, default `R`). TA101 holds the ISA13 control number of the 837's interchange, so every claim in that interchange is REJECTED with `reject_source` `TA1 interchange rejected` and the TA105 note code in `detail`. If several interchanges share that ISA13, the sender and receiver in the ISA segments, then the file name, decide; if it is still ambiguous, or no 837 has that ISA13, nothing is marked and a warning says so. TA104 `E` (accepted with errors) is not treated as a rejection by default: the claims are not marked and a warning names the interchange (add `E` to `recon.ta1.reject.codes` to treat it as one). TA104 `A` is silent. A TA1 is never ignored silently.
- ORPHAN: an 835 entry with no matching 837 claim.

`recon.csv` has one row per claim, then the orphans: `status, claim_id, claim_charge, remit_charge, paid, patient_resp, claim_date, age_days, reject_source, detail`.

## How the diff works

1. Each member record starts at the segment named by `member.start` (default `INS`) and runs to the next one or the end of the transaction set.
2. The member key is built from the profile's `key.N` lines. Default: `REF*0F:02`, then `NM1*IL:09` or, when that is empty, first name + birth date (`NM1*IL:04 + DMG:02`), then `INS:02`. Siblings without their own ID are therefore told apart by first name and birth date. The last name is deliberately not part of the key, so a last-name change shows up as a CHANGED row instead of a TERMED plus an ADDED. If a first name changes, the ADDED and TERMED rows that share subscriber, relationship and birth date are marked `possible name change` and the console warns. Duplicate keys get `#2`, `#3` suffixes in file order, the console warns, and every row for such a member says `ambiguous key` because it may be matched to the wrong person.
3. The old file is indexed by key with a 128-bit content hash and a file offset. The new file is then streamed once. Members with equal hashes are UNCHANGED. For the others, only that member is re-read from the old file for the field-level comparison.
4. TERMED means the member carries a termination flag in the new file that it did not carry in the old one (a value from `term.codes` at `term.code`, or a value at any `term.dates` locator; any value counts, so a future end date also counts), or, **in full-file mode only**, the member is missing from the new file. A member that exists only in the new file and carries the termination flag is TERMED (`termination in new file; not in old file`), in both modes, not ADDED.
5. Repeating segments are matched between files by the element listed in `align` (for example `REF:01`), otherwise by order. Sub-element separators are normalized, so a partner switching from `:` to `>` does not show as a change.
6. **Full or change files.** BGN08 of each 834 is read (profile `mode.full.codes`, default 4 and RX; `mode.change.codes`, default 2). A change file lists only the members that changed, so a member missing from the new file was simply not changed. If either file is a change file, the mode is `change`: absent members are reported as `NOT_IN_NEW` and are not counted as TERMED. If both are full files, absent members are TERMED. If BGN08 is missing, unrecognized or mixed, x12diff warns, says which mode it used (full) and tells you to rerun with `--mode change` if needed. `--mode full|change` overrides the detection. The console always prints both BGN08 values and the mode.

## Profiles

Everything configurable lives in a plain-text profile. `x12diff profile > my-profile.properties` prints the built-in one, and `profiles/example.properties` is an identical copy (a test keeps them equal). Keys x12diff does not recognize, such as the removed `recon.paid.min.ratio`, print a warning. A locator is `SEG:NN` or `SEG*Q:NN`, where `SEG*Q` means the segment whose first element is `Q`.

x12diff refers to segments by their IDs and elements by their positions only. It ships no implementation-guide text: no loop names, element descriptions or code lists. Any labels in the CSV come from `label.` lines that you write. Bring your own implementation guide or companion guide, and set keys, termination codes and labels to match your trading partner's files.

## CSV safety

All CSV output is protected against spreadsheet formula injection. A cell that starts with `=`, `@`, a tab or a carriage return gets a leading apostrophe. A cell that starts with `+` or `-` gets one too, unless the whole cell is a plain number (digits with an optional decimal part), so `-12.50` stays a number and `-1+2` becomes `'-1+2`. Standard CSV quoting is applied after that.

CSV files are written as UTF-8 with a byte-order mark (BOM), so Excel shows accented characters correctly. A script that reads the header will see the three BOM bytes in front of the first column name; read the file as `utf-8-sig` (Python) or skip them. X12 files are read byte by byte; when the bytes of a value are valid UTF-8 they are decoded as UTF-8, otherwise as ISO-8859-1.

## Synthetic data

`x12diff gen --members N --old OLD --new NEW [--seed S] [--newlines] [--sets K]` writes a fake old/new pair with names like `TESTPERSON0000001`, IDs starting `SUB` or `999`, and phone numbers 800-555-0100 to 800-555-0199, which are reserved for fiction. About 1 percent of members are removed, 1 percent flagged by code, 1 percent flagged by date, 2 percent have an address change, 1 percent a line change, and 1 percent are added. It prints the expected counts so you can check the diff. The files are full files (BGN08 = 4). No real data is ever used or needed.

`x12diff gen --kind claims --claims N --dir DIR [--seed S] [--newlines] [--set-size K] [--reject-set N]...` writes a consistent `claims.837`, `remits_1.835`, `remits_2.835` (reversals and repayments), `ack.999` and `status.277`, with claim IDs like `PCN00000001` and no names at all. It prints the counts that `recon` should report. The 999 and 277 mirror the GS sender and receiver of the 837. By default about 5 percent of the 837 sets are rejected by the 999, chosen by the seed. `--reject-set N` (repeatable, 1 is the first set) makes the 999 reject exactly those sets instead (their claims get no remit); for example `--claims 200 --reject-set 2` rejects claims 41 to 80. The synthetic reversal checks may net to a negative total, which a real check would not.

## Build

```
./gradlew test jar          # build/libs/x12diff.jar (83 tests)
./gradlew appImage          # build/jpackage/x12diff/x12diff.exe (needs a JDK with jpackage)
```

## Pro (planned, not released)

The command-line tool stays fully free and MIT licensed: parse, diff, remit and recon, including the 999/277CA tracer. Pro ($99 a year) is a planned desktop app for people who do not want a command line:

- Desktop app (no command line)
- Excel workbook reports
- Saved per-trading-partner profiles
- Folder watching

Pro is pre-order and waitlist only. Details: https://datawharftools.com/x12/

## Related reading

- [Diffing two 834 files without crashing Excel](https://dev.to/datawharf-tools/diffing-two-834-files-without-crashing-excel-275n)
- [Why was this 837 rejected? Tracing 999 and 277CA responses back to the claim](https://dev.to/datawharf-tools/why-was-this-837-rejected-tracing-999-and-277ca-responses-back-to-the-claim-1ehk)
- [Flatten an 835 remittance to CSV, then reconcile it against your 837 claims](https://dev.to/datawharf-tools/flatten-an-835-remittance-to-csv-then-reconcile-it-against-your-837-claims-169b)

Questions or bug reports with synthetic repro files: hello@datawharftools.com. Please never send real member or claim data.

## License

MIT, copyright Datawharf Tools. See `LICENSE`.
