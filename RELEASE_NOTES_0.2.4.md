# x12diff 0.2.4

Fixes from two independent QA passes of 0.2.3 (black-box testing, and a first-time-user walkthrough of the docs). Same commands, same CSV columns; a few exit codes and messages are stricter or clearer.

## Changes

- **diff: a file with no members is now a loud error.** If the old or new file parses to 0 members (empty file, not an 834, lowercase segment IDs, wrong `member.start`), `diff` prints `EMPTY INPUT: new file has 0 members` (or `old file`) and exits 3, instead of reporting every member of the other file as TERMED or ADDED with exit 1. `--allow-incomplete` accepts it. An empty file now exits 3 in both `diff` and `parse` (it was 2 in `diff`).
- **recon: TA1 interchange acknowledgements are used.** A TA1 with TA104 `R` marks every claim in the interchange it names (TA101 = the 837's ISA13) as REJECTED, with `reject_source` `TA1 interchange rejected`. TA104 `E` (accepted with errors) is not a rejection by default: a warning names the interchange. Both are configurable with the new profile key `recon.ta1.reject.codes` (default `R`). A TA1 that cannot be tied to an 837 gives a warning; it is never ignored silently. The summary line gains a TA1 count when a TA1 is present.
- **TA1 matching also checks the interchange date and time** (TA102/TA103 against ISA09/ISA10), so a TA1 for January cannot reject a February interchange that reuses the same ISA13; with no match it warns and marks nothing.
- **Output to devices still works**: `--csv NUL` (or `/dev/null`) is written directly; only regular files use the temp-file-and-rename path.
- **Folders with thousands of files stay fast** (the same-file check is linear). A file named twice (for example also inside a listed folder) is an error.
- The generated 999 for a rejected set points at the real CLM segment, and AK9 is `R` when every set is rejected.
- **gen: `--reject-set N` (repeatable)** makes the generated 999 reject exactly those 837 sets (1 is the first). Rejected sets in the 999 now carry IK3/IK4 detail segments. `gen --kind claims --claims 200 --dir demo --seed 4 --reject-set 2 --newlines` reproduces the second dev.to article without hand edits.
- **No half-written output files.** CSVs are written under a temporary name and renamed on success. Out of memory or any other failure deletes the temporary file and leaves an existing CSV untouched (before, a header-only CSV could be left behind that looked like "no differences").
- **Same output path twice is an error** (exit 2): `--csv X --rollup X`, `gen --old o --new o`, or an output that is also an input file.
- **Clearer error wording.** A folder given where a file is expected says "is a folder"; an unwritable output says why; a missing `--profile` says "Profile not found"; the unknown-profile-key warning prints once.
- **Console encoding.** Non-ASCII file names and values are readable in cmd, Git Bash and UTF-8 consoles. PowerShell pipes on a non-UTF-8 code page may still garble non-ASCII.
- **A trailing Ctrl-Z (0x1A) at the end of a file is ignored** instead of counting as an extra segment.

## Known and not changed

- The same name stored as UTF-8 in one file and ISO-8859-1 in the other still shows as CHANGED (the bytes differ); the console now shows both readings correctly.
- Duplicate 837 files given to `recon` still count their claims twice (a duplicate warning is shown).

## Docs

- README: Windows zip quick start (run `.\x12diff\x12diff.exe`, PATH tip, SmartScreen note, verify SHA-256), which download to pick, Java 17+ check, the `recon` non-PAID listing and `--show 0`.
- Speed claims re-measured (see the README). The old "about 8 seconds for 300 MB" wording is replaced by the measured sizes and a note that a busy PC is slower.

## Files and checksums

SHA-256 of the release files:

- `x12diff-0.2.4.jar`: 81584a8011f0e4a73a93e0f4813d698e2674bc90d686574b007e7107af4e28d6
- `x12diff-0.2.4-windows-x64.zip`: c70a279a4f271e938654509427e7bc9a48a7b69fa749628ac2414a9f53fe82c1

Tests: 83 pass (`./gradlew test`).
