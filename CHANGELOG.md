# Changelog

## 0.2.6 (unreleased)

- `remit --claims claims.csv`: one row per claim with line and adjustment totals per group (CO, PR, OA, PI, CR), `balance_diff`, `balance_ok`, `lines_paid_ok` and `has_invalid_amount`, so a workbook can load finished tables instead of grouping lines in Power Query.
- `remit --adjustments adjustments.csv`: one row per adjustment (claim-level ones once per claim, line-level ones with the line number and service code).
- Both outputs use the same CSV writer (formula-injection protection, temp file then rename) and the same output and input checks as `--csv` and `--rollup`. The console prints `Claims written:` and `Adjustments written:`.
- Invalid amounts keep their raw text in the new files and are never written as 0.

Earlier releases: see the RELEASE_NOTES files.
