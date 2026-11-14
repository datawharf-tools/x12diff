# x12diff

A free command-line tool for healthcare X12 files, from Datawharf Tools.

- `x12diff parse FILE` summarizes a file: interchanges, functional groups, transaction sets by type (834, 837, 835, 999, 277CA, TA1 and so on), segment counts, and envelope control-number mismatches as warnings.
- `x12diff diff OLD NEW` compares two 834 member files and reports who was ADDED, TERMED, CHANGED (field by field: segment, element, old value, new value) and how many are UNCHANGED. It writes a CSV with one row per change.
- `x12diff remit FILES...` flattens 835 remittances to one CSV row per service line, rolls up adjustments by group and reason code, and checks that each claim and each check balances.
- `x12diff recon FILES...` matches 837 claims to 835 payments and tells you which claims are PAID, PARTIAL, DENIED, REVERSED, ADJUSTED, UNMATCHED (no remit yet, with aging), REJECTED before adjudication (999 or 277CA), or ORPHAN remits with no claim.
- It streams. Memory depends on the number of members (diff) or claims (recon), not on file size. Two synthetic 300 MB 834 files diff in about 8 seconds, and a synthetic 295 MB 837/835/999/277 set reconciles in about 6 seconds with a 512 MB heap.

**Privacy: x12diff runs entirely on your machine and makes no network connections.** It has no telemetry, no update check and no network code. It reads the files you name and writes the CSV you ask for.

## Quick start

You need Java 17 or newer, or use the Windows app image (`x12diff.exe`, runtime bundled).

```
java -jar x12diff.jar parse enrollment.834
java -jar x12diff.jar diff last_month.834 this_month.834 --csv changes.csv
java -jar x12diff.jar diff old.834 new.834 --profile my-profile.properties
```

Exit codes: `0` ok (diff: no differences), `1` diff found differences, `2` error. Errors print a one-line message, never a stack trace.

Exit codes for `remit` and `recon`: `0` ok, `2` error. Findings (warnings, unmatched claims) are in the report, not the exit code.

Options for `diff`: `--csv FILE`, `--profile FILE`, `--show N` (changes echoed to the console, default 20), `--stats` (elapsed time and heap use).

## Examples (synthetic data)

```
$ x12diff gen --members 300 --old old.834 --new new.834 --seed 3
$ x12diff diff old.834 new.834 --show 3 --csv changes.csv
  CHANGED SUB00000031|999000031|18  N3 N301: '100 TEST STREET UNIT 31' -> '200 SAMPLE AVENUE APT 31'
  CHANGED SUB00000067|999000067|18  N3 N301: '100 TEST STREET UNIT 67' -> '200 SAMPLE AVENUE APT 67'
  TERMED  SUB00000073|999000073|18  DTP*349 segment added; termination flag: DTP*349:03 present
Old: old.834  members: 300
New: new.834  members: 298
ADDED:     3
TERMED:    13  (5 absent from new, 8 newly flagged)
CHANGED:   11
UNCHANGED: 276
```

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
x12diff remit C:emits6-11\ --csv lines.csv        (a folder: every X12 file in it)
x12diff remit "C:emits\*.835" --csv lines.csv           (a wildcard in the file name)
```

Synthetic output:

```
Files: 2   835 checks: 20
Claims: 1,910   Service lines: 3,820
Charged: 386982.50   Paid: 307194.50   Paid/charged: 79.4%   Patient responsibility: 8953.87
Adjustments by group-reason (count, amount):
  CO-50           150          40214.18
  CO-45           882          30619.95
  PR-1            740           8953.87
Warnings: 0
```

`lines.csv` columns: `file, check_number, payment_date, payer_id, payee_id, claim_id, claim_status, claim_charge, claim_paid, patient_resp, payer_claim_ctrl, service_qualifier, service_code, service_modifiers, units, line_charge, line_paid, line_adjustments, claim_adjustments`. Adjustments are written as `GROUP:REASON:AMOUNT` and joined with `|`, so a line with several adjustments stays on one row. Claim-level adjustments repeat on each line of the claim.

Checks, reported as warnings with the check number and claim ID:

- per claim: charge - paid - adjustments must be zero (claim-level adjustments if present, otherwise the line-level ones), and the service-line paid amounts must add up to the claim paid amount;
- per check: BPR02 must equal the sum of CLP04 minus the PLB adjustments.

Only codes are shown. To get readable text in the rollup, add `adjlabel.CO-45 = your text` lines to a profile and pass `--profile`. x12diff ships no description text for any code.

## Recon: claims, payments and the 999/277CA trace

```
x12diff recon claims.837 remits_1.835 remits_2.835 ack.999 status.277 --asof 20260601 --csv recon.csv
x12diff recon C:\month-end\ --csv recon.csv
```

Files can be given in any order, or as folders. x12diff recognizes the transaction type inside each file (837, 835, 999 or 997, 277), so you can mix them. Synthetic output:

```
Files: 5   837 claims: 2,000 in 50 sets   835 claim entries: 1,910 on 20 checks
Acknowledgement sets: 1 (3 837 sets rejected)   277CA claim statuses: 242
PAID:      951
PARTIAL:   370
DENIED:    150
REVERSED:  108
ADJUSTED:  71
UNMATCHED: 131
REJECTED:  219
ORPHAN:    10
Charged (837): 503572.20   Paid (matched 835s, net): 306443.95
UNMATCHED aging (days since claim date): 0-30: 0  31-60: 32  61-90: 35  91+: 64  unknown: 0
```

How claims are classified:

- A claim matches a remit entry when CLM01 equals CLP01 (when several claims share an ID, the one with the same charge wins). A charge difference is flagged in `detail`.
- Amounts for one claim are summed across all remit entries, so an original payment, a reversal and a repayment net out. If any entry has a reversal status (profile `recon.reversal.codes`, default 22): REVERSED when the net paid is zero or less, ADJUSTED when money is still paid.
- Otherwise: DENIED when paid is zero, PAID when paid >= charge x `recon.paid.min.ratio` (default 1.0, lower it for contracted-rate payers), else PARTIAL.
- No remit yet: UNMATCHED, aged by days from the claim date to `--asof` (default today). The claim date is the first `recon.claim.date` match in the claim (default `DTP*472:03`), else `recon.claim.date.fallback` (default `BHT:04`).
- REJECTED before adjudication, only when no remit exists: the 999 (or 997) rejected the claim's transaction set (IK5 or AK5 element 1 in `recon.ack.reject.codes`, default `R`, tied back to the 837 by the GS06/ST02 control numbers; AK9 rejects a whole group), or a 277CA claim-level status has a category in `recon.status.reject.categories` (default A3, A4, A6, A7, A8; claims are tied by the patient control number in TRN02).
- ORPHAN: an 835 entry with no matching 837 claim.

`recon.csv` has one row per claim, then the orphans: `status, claim_id, claim_charge, remit_charge, paid, patient_resp, claim_date, age_days, reject_source, detail`.

## How the diff works

1. Each member record starts at the segment named by `member.start` (default `INS`) and runs to the next one or the end of the transaction set.
2. The member key is built from the profile's `key.N` lines (default: `REF*0F:02`, then `NM1*IL:09` or `REF*1L:02`, then `INS:02`). Duplicate keys get `#2`, `#3` suffixes in file order.
3. The old file is indexed by key with a 128-bit content hash and a file offset. The new file is then streamed once. Members with equal hashes are UNCHANGED. For the others, only that member is re-read from the old file for the field-level comparison.
4. TERMED means the member is missing from the new file, or it carries a termination flag in the new file that it did not carry in the old one. The flag is a value from `term.codes` at `term.code`, or a value at any `term.dates` locator.
5. Repeating segments are matched between files by the element listed in `align` (for example `REF:01`), otherwise by order.

## Profiles

Everything configurable lives in a plain-text profile. `x12diff profile > my-profile.properties` prints the built-in one, and `profiles/example.properties` is a copy. A locator is `SEG:NN` or `SEG*Q:NN`, where `SEG*Q` means the segment whose first element is `Q`.

x12diff refers to segments by their IDs and elements by their positions only. It ships no implementation-guide text: no loop names, element descriptions or code lists. Any labels in the CSV come from `label.` lines that you write. Bring your own implementation guide or companion guide, and set keys, termination codes and labels to match your trading partner's files.

## CSV safety

All CSV output is protected against spreadsheet formula injection. A cell that starts with `=`, `@`, a tab or a carriage return gets a leading apostrophe. A cell that starts with `+` or `-` gets one too, unless the whole cell is a plain number (digits with an optional decimal part), so `-12.50` stays a number and `-1+2` becomes `'-1+2`. Standard CSV quoting is applied after that.

## Synthetic data

`x12diff gen --members N --old OLD --new NEW [--seed S] [--newlines] [--sets K]` writes a fake old/new pair with names like `TESTPERSON0000001`, IDs starting `SUB` or `999`, and phone numbers in the 555 range. About 1 percent of members are removed, 1 percent flagged by code, 1 percent flagged by date, 2 percent have an address change, 1 percent a line change, and 1 percent are added. It prints the expected counts so you can check the diff. No real data is ever used or needed.

`x12diff gen --kind claims --claims N --dir DIR [--seed S] [--newlines] [--set-size K]` writes a consistent `claims.837`, `remits_1.835`, `remits_2.835` (reversals and repayments), `ack.999` and `status.277`, with claim IDs like `PCN00000001` and no names at all. It prints the counts that `recon` should report. The synthetic reversal checks may net to a negative total, which a real check would not.

## Build

```
./gradlew test jar          # build/libs/x12diff.jar
./gradlew appImage          # build/jpackage/x12diff/x12diff.exe (needs a JDK with jpackage)
```

## Pro (coming)

- Desktop GUI with a claim-to-remit drill-down and a guided 999/277CA trace view, built on the same engine
- Excel export
- Saved trading-partner profiles

Details and waitlist: https://datawharftools.com/x12

Questions or bug reports with synthetic repro files: hello@datawharftools.com. Please never send real member or claim data.

## License

MIT, copyright Datawharf Tools. See `LICENSE`.
