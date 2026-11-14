# x12diff 0.1.0 build report

Built 2026-11-14 on Windows 11, Temurin JDK 21.0.11, Gradle 9.4.1, Kotlin 2.2.21. Runtime dependencies: none (Kotlin stdlib only).

## Tests
`./gradlew test`: 17 unit tests, all passing. Covered: delimiter detection (standard, `|` with `^` and LF terminator, CR terminator, CRLF, no newlines, wrapped lines, BOM, multiple interchanges), envelope mismatch warnings and truncated files, summary counts (277CA, 999, TA1, multiple ST per GS), diff ADDED/TERMED(absent, code, date)/CHANGED/UNCHANGED, differing delimiters across files, profile ignore/label/key options, bad profile errors, malformed input and missing file (clear `error:` line, exit 2, no stack trace), and a generated 3,000-member pair whose diff counts equal the generator's expected counts.

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
