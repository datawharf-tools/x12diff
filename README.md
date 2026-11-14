# x12diff

A free command-line tool for healthcare X12 files, from Datawharf Tools.

- `x12diff parse FILE` summarizes a file: interchanges, functional groups, transaction sets by type (834, 837, 835, 999, 277CA, TA1 and so on), segment counts, and envelope control-number mismatches as warnings.
- `x12diff diff OLD NEW` compares two 834 member files and reports who was ADDED, TERMED, CHANGED (field by field: segment, element, old value, new value) and how many are UNCHANGED. It writes a CSV with one row per change.
- It streams. Memory depends on the number of members, not on file size. Two synthetic 300 MB files diff in about 8 seconds with a 256 MB heap.

**Privacy: x12diff runs entirely on your machine and makes no network connections.** It has no telemetry, no update check and no network code. It reads the files you name and writes the CSV you ask for.

## Quick start

You need Java 17 or newer, or use the Windows app image (`x12diff.exe`, runtime bundled).

```
java -jar x12diff.jar parse enrollment.834
java -jar x12diff.jar diff last_month.834 this_month.834 --csv changes.csv
java -jar x12diff.jar diff old.834 new.834 --profile my-profile.properties
```

Exit codes: `0` ok (diff: no differences), `1` diff found differences, `2` error. Errors print a one-line message, never a stack trace.

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

## How the diff works

1. Each member record starts at the segment named by `member.start` (default `INS`) and runs to the next one or the end of the transaction set.
2. The member key is built from the profile's `key.N` lines (default: `REF*0F:02`, then `NM1*IL:09` or `REF*1L:02`, then `INS:02`). Duplicate keys get `#2`, `#3` suffixes in file order.
3. The old file is indexed by key with a 128-bit content hash and a file offset. The new file is then streamed once. Members with equal hashes are UNCHANGED. For the others, only that member is re-read from the old file for the field-level comparison.
4. TERMED means the member is missing from the new file, or it carries a termination flag in the new file that it did not carry in the old one. The flag is a value from `term.codes` at `term.code`, or a value at any `term.dates` locator.
5. Repeating segments are matched between files by the element listed in `align` (for example `REF:01`), otherwise by order.

## Profiles

Everything configurable lives in a plain-text profile. `x12diff profile > my-profile.properties` prints the built-in one, and `profiles/example-834.properties` is a copy. A locator is `SEG:NN` or `SEG*Q:NN`, where `SEG*Q` means the segment whose first element is `Q`.

x12diff refers to segments by their IDs and elements by their positions only. It ships no implementation-guide text: no loop names, element descriptions or code lists. Any labels in the CSV come from `label.` lines that you write. Bring your own implementation guide or companion guide, and set keys, termination codes and labels to match your trading partner's files.

## Synthetic data

`x12diff gen --members N --old OLD --new NEW [--seed S] [--newlines] [--sets K]` writes a fake old/new pair with names like `TESTPERSON0000001`, IDs starting `SUB` or `999`, and phone numbers in the 555 range. About 1 percent of members are removed, 1 percent flagged by code, 1 percent flagged by date, 2 percent have an address change, 1 percent a line change, and 1 percent are added. It prints the expected counts so you can check the diff. No real data is ever used or needed.

## Build

```
./gradlew test jar          # build/libs/x12diff.jar
./gradlew appImage          # build/jpackage/x12diff/x12diff.exe (needs a JDK with jpackage)
```

## Pro (coming)

- 999 / 277CA / TA1-to-claim tracer
- Excel export
- Desktop GUI

Details and waitlist: https://datawharftools.com/x12

Questions or bug reports with synthetic repro files: hello@datawharftools.com. Please never send real member or claim data.

## License

MIT, copyright Datawharf Tools. See `LICENSE`.
