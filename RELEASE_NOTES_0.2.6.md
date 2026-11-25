# x12diff 0.2.6

Two new remit outputs and a free Excel workbook. Existing commands and outputs are unchanged.

## New
- `remit --claims claims.csv`: one row per claim, with line and adjustment totals per group (CO, PR, OA, PI, CR), `balance_diff`, `balance_ok`, `lines_paid_ok` and `has_invalid_amount`.
- `remit --adjustments adjustments.csv`: one row per adjustment. Claim-level adjustments appear once per claim, not once per service line.
- Both use the same CSV safety as `--csv` (formula-injection protection, write to a temp file then rename). Invalid amounts keep their raw text and are never written as 0.
- `x12diff-remit-workbook.xlsx`: a free Excel workbook (Power Query and pivots, no macros) with Overview, Denials, Claims, Adjustments and Lines views. Run `x12diff remit FILES --claims claims.csv --adjustments adjustments.csv --csv lines.csv` into one folder, put that folder in the Start sheet and choose Data > Refresh All. It opens with a small synthetic sample. Tested in Excel for Microsoft 365 on Windows: about 7-15 s for 100,000 service lines and about 30-40 s for 480,000.

## Compatibility
`parse`, `diff`, `remit --csv/--rollup` and `recon` give byte-identical output and the same exit codes as 0.2.5 on the same input files.

## Downloads
- `x12diff-0.2.6.jar`: needs Java 17 or newer (`java -version`).
- `x12diff-0.2.6-windows-x64.zip`: no Java needed. Unzip it and run `.\x12diff\x12diff.exe`. Windows may show a SmartScreen warning for an unsigned download.
- `x12diff-remit-workbook.xlsx`: the Excel workbook.

SHA-256:
- `x12diff-0.2.6.jar`: 662bd68a192fd801f7f458face5031cbf28f408ad26a403dfcdfa91b27e8c0f9
- `x12diff-0.2.6-windows-x64.zip`: bdf1bc4c1c84d2852f6712f458286da9f93d97c1bfec8563294cb8935797d447
- `x12diff-remit-workbook.xlsx`: b7dd0d1f76e5c84568d1b0e99bc09e4a5b6c8b2efd39686ac3d89f663543e090
