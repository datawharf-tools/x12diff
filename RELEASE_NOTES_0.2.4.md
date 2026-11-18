# x12diff 0.2.4

Fixes from two independent QA passes of 0.2.3 (black-box testing, and a first-time-user walkthrough of the docs). Same commands, same CSV columns; a few exit codes and messages are stricter or clearer.

## Changes

- **diff: a file with no members is now a loud error.** If the old or new file parses to 0 members (empty file, not an 834, lowercase segment IDs, wrong `member.start`), `diff` prints `EMPTY INPUT: new file has 0 members` (or `old file`) and exits 3, instead of reporting every member of the other file as TERMED or ADDED with exit 1. `--allow-incomplete` accepts it. An empty file now exits 3 in both `diff` and `parse` (it was 2 in `diff`).
- **recon: TA1 interchange acknowledgements are used.** A TA1 with TA104 `R` marks every claim in the interchange it names (TA101 = the 837's ISA13) as REJECTED, with `reject_source` `TA1 interchange rejected`. TA104 `E` (accepted with errors) is not a rejection by default: a warning names the interchange. Both are configurable with the new profile key `recon.ta1.reject.codes` (default `R`). A TA1 that cannot be tied to an 837 gives a warning; it is never ignored silently. The summary line gains a TA1 count when a TA1 is present.
- **gen: `--reject-set N` (repeatable)** makes the generated 999 reject exactly those 837 sets (1 is the first). Rejected sets in the 999 now carry IK3/IK4 detail segments. `gen --kind claims --claims 200 --dir demo --seed 4 --reject-set 2 --newlines` reproduces the second dev.to article without hand edits.
- **No half-written output files.** CSVs are written under a temporary name and renamed on success. Out of memory or any other failure deletes the temporary file and leaves an existing CSV untouched (before, a header-only CSV could be left behind that looked like "no differences").
- **Same output path twice is an error** (exit 2): `--csv X --rollup X`, `gen --old o --new o`, or an output that is also an input file.
- **Clearer error wording.** A folder given where a file is expected says "is a folder"; an unwritable output says why; a missing `--profile` says "Profile not found"; the unknown-profile-key warning prints once.
- **Console encoding.** Non-ASCII file names and values print readably (UTF-8 when output is redirected, the console code page in an interactive console).
- **A trailing Ctrl-Z (0x1A) at the end of a file is ignored** instead of counting as an extra segment.

## Known and not changed

- The same name stored as UTF-8 in one file and ISO-8859-1 in the other still shows as CHANGED (the bytes differ); the console now shows both readings correctly.
- Duplicate 837 files given to `recon` still count their claims twice (a duplicate warning is shown).

## Docs

- README: Windows zip quick start (run `.\x12diff\x12diff.exe`, PATH tip, SmartScreen note, verify SHA-256), which download to pick, Java 17+ check, the `recon` non-PAID listing and `--show 0`.
- Speed claims re-measured (see the README). The old "about 8 seconds for 300 MB" wording is replaced by the measured sizes and a note that a busy PC is slower.

## Files and checksums

SHA-256 of the release files:

- `x12diff-0.2.4.jar`: d34f4de91af4434d5c5322372edc1ceb6c0894f58df4c7570306cb2e63090ff6
- `x12diff-0.2.4-windows-x64.zip`: 9404a8b1346613868a22a3eaeb92c3719a7e9583b02abd3d6a95fa1f97bbf47f

Tests: 79 pass (`./gradlew test`).
