# x12diff 0.2.5

A small fix release.

## Fixed
- Output files whose name starts with a Windows device name but has an extension (for example `nul.csv`, `con.csv`, `com1.txt`) were written directly instead of through a temporary file. On Windows 11 these are ordinary files, so a failed run could leave a header-only file or replace an existing one. They now get the same protection as every other output: a failed run leaves an existing file untouched.
- Only an exact device name (`NUL`, `CON`, `PRN`, `AUX`, `COM1`-`COM9`, `LPT1`-`LPT9`) is written directly, so `--csv NUL` still works.
- On Windows 10 and older, where `nul.csv` still means the NUL device, the output is written to the device as before.

## Downloads
- `x12diff-0.2.5.jar`: needs Java 17 or newer (`java -version`).
- `x12diff-0.2.5-windows-x64.zip`: no Java needed. Unzip it and run `.\x12diff\x12diff.exe`. Windows may show a SmartScreen warning for an unsigned download.

SHA-256:
- `x12diff-0.2.5.jar`: 4972f96a50642c56466c8895c89b71a45183ab1b8099a707912c8e82bde90c7a
- `x12diff-0.2.5-windows-x64.zip`: ccb2daf80431481d533e3cdd0d24267a785b253bd1bdc8efb56b55ab00edf30b
