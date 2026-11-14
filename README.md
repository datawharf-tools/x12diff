# x12diff

A free command-line tool for healthcare X12 files, from Datawharf Tools.

- `x12diff parse FILE` summarizes a file: interchanges, functional groups, transaction sets by type (834, 837, 835, 999, 277CA, TA1 and so on), segment counts, and envelope control-number mismatches as warnings.
- `x12diff diff OLD NEW` compares two 834 member files and reports who was ADDED, TERMED, CHANGED (field by field: segment, element, old value, new value) and how many are UNCHANGED. It reads BGN08 to tell full files from change files (see "How the diff works"). It writes a CSV with one row per change.
- `x12diff remit FILES...` flattens 835 remittances to one CSV row per service line, rolls up adjustments by group and reason code, and checks that each claim and each check balances.
- `x12diff recon FILES...` matches 837 claims to 835 payments and tells you which claims are PAID, PARTIAL, DENIED, PATIENT_RESP, REVERSED, ADJUSTED, UNMATCHED (no remit yet, with aging), REJECTED before adjudication (999 or 277CA), or ORPHAN remits with no claim.
- It streams. Memory depends on the number of members (diff) or claims (recon), not on file size. Two synthetic 300 MB 834 files diff in about 8 seconds, and a synthetic 295 MB 837/835/999/277 set reconciles in about 6 seconds with a 512 MB heap.

**Privacy: x12diff runs entirely on your machine and makes no network connections.** It has no telemetry, no update check and no network code. It reads the files you name and writes the CSV you ask for.

## Quick start

You need Java 17 or newer (the jar is built for Java 17 and also runs on 21), or use the Windows app image (`x12diff.exe`, runtime bundled).

```
java -jar x12diff.jar parse enrollment.834
java -jar x12diff.jar diff last_month.834 this_month.834 --csv changes.csv
java -jar x12diff.jar diff old.834 new.834 --profile my-profile.properties
```

Exit codes: `0` ok (`diff`: no differences), `1` `diff` found differences, `2` error, `3` input incomplete or inconsistent (also for `parse`: an empty file, a missing trailer or a count mismatch; `parse --allow-incomplete` returns 0). Errors print a one-line message, never a stack trace.

**Incomplete input.** `diff`, `remit` and `recon` check the envelopes of every file they read. A missing SE, GE or IEA (for example after a cut-off transfer), or a segment-count or control-number mismatch, prints a loud `INPUT INCOMPLETE OR INCONSISTENT` notice on the console and on stderr, and the command exits with `3`. The report is still written, but do not trust it. Pass `--allow-incomplete` to accept such files knowingly. The notice goes to both stdout and stderr on purpose, so it survives redirecting either one; in a terminal you see it twice.

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
x12diff recon claims.837 remits_1.835 remits_2.835 ack.999 status.277 --asof 20260601 --csv recon.csv
x12diff recon "C:/month-end" --csv recon.csv
```

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

`x12diff gen --kind claims --claims N --dir DIR [--seed S] [--newlines] [--set-size K]` writes a consistent `claims.837`, `remits_1.835`, `remits_2.835` (reversals and repayments), `ack.999` and `status.277`, with claim IDs like `PCN00000001` and no names at all. It prints the counts that `recon` should report. The 999 and 277 mirror the GS sender and receiver of the 837. The synthetic reversal checks may net to a negative total, which a real check would not.

## Build

```
./gradlew test jar          # build/libs/x12diff.jar
./gradlew appImage          # build/jpackage/x12diff/x12diff.exe (needs a JDK with jpackage)
```

## Pro (coming)

The command-line tool stays fully free: parse, diff, remit and recon, including the 999/277CA tracer. Pro ($99 a year) is for people who do not want a command line:

- Desktop app (no command line)
- Excel workbook reports
- Saved per-trading-partner profiles
- Folder watching

Details and waitlist: https://datawharftools.com/x12

Questions or bug reports with synthetic repro files: hello@datawharftools.com. Please never send real member or claim data.

## License

MIT, copyright Datawharf Tools. See `LICENSE`.
