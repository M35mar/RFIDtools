# RFIDtools 2.0.0

RFIDtools 2.0.0 is a consolidation release built on the 1.1.1 line. All 1.x
features remain available, and the application id stays
`com.farmacia.pm3android`, so existing installs update in place.

## Highlights

- **Background-safe commands** — a foreground service keeps native commands
  and Python scripts alive while the app is backgrounded. The notification
  Stop action interrupts native loops and Python scripts alike.
- **Wider PM3 catalog** — ISO 15693, iCLASS/Picopass, DESFire, LF search,
  antenna tuning and NDEF reads join the existing LF/HF/Classic/Ultralight
  tools.
- **Write safety** — `hf mfu restore`, `hf mfu incr`, `countertear` and the
  counter-reset script now require an explicit confirmation that shows the
  resolved command.
- **Structured results** — tag reads produce a compact UID/ATQA/SAK/type card
  above the console, with a re-read action and the full raw output on demand.

## Compatibility

- Android 8.0+ (API 26), arm64-v8a.
- Package `com.farmacia.pm3android`, versionCode `20000`, versionName `2.0.0`.
- Signed with the same release key as 1.x: in-app updates keep working.
- USB, Bluetooth SPP/BLE, phone NFC, Python scripts, the dump editor and the
  in-app updater are unchanged.

## Verification

- Unit tests cover the command catalog, command composition/validation and the
  result parser.
- `lintDebug` and `assembleDebug` run before a tag is published.
- Tagged builds are signed by CI and published as `pm3-release.apk`.
