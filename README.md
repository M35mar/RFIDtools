# PM3 Android

**Proxmark3 in your pocket — no Termux, no external process, no Bluetooth bridge.**

PM3 Android runs the Proxmark3 Iceman client **inside the app process** and talks
to a PM3-compatible board over direct USB CDC-ACM. The UI is native Android
(Compose + Material 3), the client is the upstream Proxmark3 code compiled into
`libpm3android.so`, and client command output is captured in-process.

[![Android CI](https://github.com/M35mar/RFIDtools/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/M35mar/RFIDtools/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/M35mar/RFIDtools?sort=semver)](https://github.com/M35mar/RFIDtools/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Android 8+](https://img.shields.io/badge/Android-8%2B%20(API%2026)-green.svg)](#requirements)
[![arm64-v8a](https://img.shields.io/badge/ABI-arm64--v8a-informational.svg)](#requirements)

## Download

The official `0.6.0` GitHub release contains a **debug-signed APK for testing**:

**[Download `pm3-debug.apk`](https://github.com/M35mar/RFIDtools/releases/download/0.6.0/pm3-debug.apk)**

> The debug APK uses package id `com.farmacia.pm3android.debug`, version
> `0.6.0-debug`, and the Android debug keystore. It is intended for testing and
> sideloading, not for production distribution. A release build requires your
> own signing key. The APK includes the embedded CPython runtime used by the
> optional Python script support, so it is larger than the 0.4.x build.

Prefer building it yourself? See [Building from source](#building-from-source).

## What is this?

A focused Android front end for PM3-compatible Proxmark3 hardware. It keeps the
original RFIDtools idea — Proxmark3 commands on a phone — but replaces the
Termux/child-process architecture with a single app process:

- the Proxmark3 client is compiled into the APK;
- USB CDC bulk transfers are handled by a small Kotlin driver;
- the client's normal `socket:` transport is bridged to that USB link;
- the Compose UI sends commands as strings and shows captured output;
- Lua/Cmd scripts run inside the native client, while optional Python scripts
  run in an embedded CPython runtime and call the same client through JNI.

This means no external binaries, no Termux installation, no serial-port
library, and no Bluetooth/SPP layer.

## What's new in 0.6.0

- **Dump editor:** tap a MIFARE Classic block or an Ultralight/NTAG page in
  **Files** to inspect and edit its hex bytes in place. The list and the edit
  dialog show the ASCII rendering plus decoded details (block 0, sector
  trailers with access conditions, value blocks, Ultralight lock/OTP/
  configuration pages) and flag the rows a restore never writes.
- **Phone NFC — MIFARE Classic dump:** read a card with the phone's NFC reader
  using a key file from the app folder (`hf-mf-<UID>-key.bin` from
  `hf mf autopwn`, or any `.bin`/`.txt`). Keys are tried as Key A and Key B on
  every sector, blocks behind a Key B read condition are re-read after
  re-authentication, and an incomplete read is an error: nothing is saved. The
  result is `hf-mf-<UID>-dump.bin` with the proven keys in the trailers plus
  the sibling `hf-mf-<UID>-key.bin`.
- **Phone NFC — MIFARE Classic restore:** the tag keeps its own UID, the dump
  must match the tag size, and a blocking pre-check verifies every differing
  block is writable before the first write. Trailers (keys and access bits)
  come from the dump, the trailer is written with the key its access
  conditions require, a read-only block is unlocked by writing the dump
  trailer first, and each write is retried once.
- **One key field, always-visible options:** the phone-NFC screen has a single
  **Key file** field, importable from phone storage, and no longer expands.
- **Classic autopwn shortcut and build fixes:** upstream Proxmark3 submodule
  update, Windows-friendly native build scripts and a resource-sync fix for
  Lua tools adopted upstream.

## What's new in 0.5.1

- **MIFARE Classic autopwn:** a new one-tap button in
  **Tools → HF — MIFARE Classic** runs the native `hf mf autopwn` command.
- **Tools cleanup:** the generic **HF — MIFARE Ultralight** section has been
  removed; MIFARE Ultralight Ev1 tools remain available.

## What's new in 0.5.0

- **Custom script support** in Tools: import `.lua`, `.cmd` and `.py` files,
  keep them across client-resource resets, run them with optional arguments and
  delete them safely.
- **Embedded Python runtime** via Chaquopy. Python scripts call the same
  in-process native client through a small `pm3` compatibility shim; no
  Termux, shell or external process is involved.
- **Bundled upstream Python scripts** are installed with the other client
  resources and can be run from the console, for example
  `script run mfuev1_counter_reset.py -c 0`.
- **Python support is experimental**: scripts which need SWIG objects,
  external pip packages or long-running interactive stdin are not guaranteed
  to work. Lua and Cmd scripts continue to use the native client directly.
- Script files are validated, stored outside the disposable resource tree and
  mirrored into the client directories, so a resource reset does not destroy
  user work.

The 0.4.x release introduced the Material 3 UI, USB lifecycle hardening, the
in-process client, the Files manager, settings and localized tools. See
[CHANGELOG.md](CHANGELOG.md) for the full history.

## Features

### Device

- USB descriptor, vendor/product IDs, serial and interface diagnostics.
- Permission state and connection status.
- Quick commands for common device checks.
- Copy/share the complete diagnostics report.

### Console

- Full command input with history dropdown.
- Copy all output, share output, follow mode and font-size control.
- Clear confirmation and a bounded output buffer.
- Runs commands through the in-process client, not a shell.

### Tools

- Curated, searchable command sections for LF, HF, MIFARE Classic (including
  the one-tap autopwn command) and MIFARE Ultralight Ev1.
- Parameter dialogs for file names, counters and increment values.
- Recent command list.
- Commands are syntax-checked against the pinned client.

### Scripts

- Import user scripts with the Storage Access Framework (`.lua`, `.cmd`, `.py`).
- Run scripts with optional arguments through the normal console and history.
- Keep user scripts across **Reset client resources**.
- Python scripts run in an embedded CPython runtime and use the same in-process
  Proxmark3 client via a `pm3` compatibility module.
- Python is experimental. The bundled upstream scripts are provided as a
  starting point, but scripts that depend on the SWIG `_pm3` object API,
  non-bundled pip packages, audio/graphics libraries or interactive stdin may
  not work. Lua and Cmd remain the most compatible options.

Only run scripts you trust: imported scripts are code and can execute any
Proxmark3 command available to the app.

### Files

- Shows user dumps, traces and logs; hides client dictionaries, libraries and
  resources.
- Filters for dumps, traces, logs and other files.
- Import/export with the Storage Access Framework.
- Built-in hex editor for `.bin` dumps: tap a MIFARE Classic block or an
  Ultralight/NTAG page to edit it, with type/UID/sector information, the ASCII
  rendering of the bytes and decoded details (access bits, value blocks,
  configuration pages), plus a warning for rows a restore never writes (UID,
  lock bits, OTP, configuration).
- Share individual files with `FileProvider`.
- Delete confirmation and path-safety checks.

### Phone NFC (no Proxmark3)

- Dump a tag with the phone's NFC reader into a `.bin` file in **Files**:
  MIFARE Ultralight/NTAG (optional 8-hex-digit password) and MIFARE Classic.
- A MIFARE Classic dump uses the six-byte sector keys from the **Key file**
  picked on the screen (`.bin` or `.txt`, for example `hf-mf-<UID>-key.bin`,
  the file `hf mf autopwn` leaves next to the dump). Every sector is tried with
  each key as Key A and as Key B, so a card whose sectors do not share one key
  is read completely, and blocks behind a Key B read condition are re-read
  after re-authenticating the sector. With a file selected only its keys are
  tried; the factory `FFFFFFFFFFFF` key is used only when no file is selected.
- The key file can be chosen from the app folder or imported with **Choose from
  storage**; the import copies the document into the app folder, so it stays
  available for later runs. Only `.bin` and `.txt` files are accepted.
- The read must be complete: if any sector or block stays closed, the dump is
  reported as an error and not saved, so a partial file can never be restored
  as zeroes. The saved `hf-mf-<UID>-dump.bin` also holds the proven keys in the
  sector trailers, and the sibling `hf-mf-<UID>-key.bin` keeps the layout
  `hf mf restore` expects.
- Restore a dump to a tag over NFC: the tag keeps its own UID (block 0 is never
  written) and the dump must have the same size as the tag. Before writing
  anything the app opens every sector and checks that each differing block is
  writable; when a key is missing nothing is written. Keys and access bits of
  the trailers always come from the dump — the key file only authenticates the
  destination card — and the trailer is written with the key its current access
  conditions require (Key A or Key B). A block that the current access bits
  keep read-only makes the app install that sector's dump trailer first and
  retry the block automatically. Every write is retried once, like MIFARE
  Classic Tool, and the result lists every block with its status.

### Info / Settings

- App version, build type, application ID and native/client commit hashes.
- Theme and console preferences.
- Settings backup/restore as JSON.
- Client-resource reset and install status.
- Licence viewer and project links.

## Requirements

### Phone

- Android **8.0+** (API 26)
- **arm64-v8a**
- USB host / USB OTG support
- A compatible USB cable or OTG adapter

### Proxmark3 hardware

- USB IDs: `0x9ac4:0x4b8f` (CDC-ACM)
- Firmware built from the **same pinned Proxmark3 commit** as the embedded
  client, so the `CAPABILITIES_VERSION` handshake matches.
- Supported board flavour depends on the hardware (for example PM3GENERIC /
  PM3EASY 512k, or the flavour required by your board).

Firmware is **not** included and is **not** flashed by this app. Flash it from
a PC with a desktop client.

## Quick start

1. Install the debug APK from the release.
2. Connect the Proxmark3 with a USB OTG adapter or cable.
3. Open the app and grant the Android USB permission prompt.
4. Go to **Device** and tap **Connect**.
5. Use **Console** for free-form commands or **Tools** for curated commands.
6. Use **Files** to inspect, export or share dumps, traces and logs.

If the device is not detected, check the cable/adapter, confirm USB host mode,
and verify that the board enumerates with vendor ID `0x9ac4` and product ID
`0x4b8f`.

## Architecture

```text
┌────────────────────────────── app process ──────────────────────────────┐
│  Compose UI (Device / Console / Tools / Files / Info)                  │
│        │  commands as strings                                           │
│        ▼                                                                │
│  Pm3Client ─→ Pm3Native (JNI, libpm3android.so)                         │
│        │                      │                                         │
│        │                      ▼  pm3_console() → CommandReceived()      │
│        │ Proxmark3 client compiled into the same .so                    │
│        │                      │  socket:pm3.android (abstract socket)   │
│        ▼                      ▼                                         │
│  UsbCdc (bulkTransfer in/out) ◀─ LocalSocketBridge (2 pump threads)     │
└─────────────────────────────────────────────────────────────────────────┘
                          ▲ USB CDC bulk transfers
                    Proxmark3 device
```

- The client's `socket:` transport (`uart_posix.c`) is reused unchanged; the
  Kotlin `LocalSocketBridge` listens on the same abstract namespace it
  connects to.
- `pm3_open()`'s `exit()` on failure is never reached: the open sequence is
  reimplemented in `pm3_jni.c`, reporting failure as a status.
- Command output is captured with the client's own grabber
  (`pm3_grabbed_output_get`), with no PTY and no pipes.
- Python scripts run in Chaquopy's CPython runtime; the `pm3` shim forwards
  `console()` calls through `Pm3PythonBridge` to the same JNI client, so both
  script runtimes share one USB session and one command serialisation point.

## Building from source

### Requirements

- Android SDK with platform **37** and build-tools **37.0.0**
- NDK **27.2.12479018** (pinned; `NDK_VERSION` can override)
- CMake **3.22.1** (pinned; `CMAKE_VERSION` can override)
- JDK **21**
- Python **3.14** on the build host (required by Chaquopy)
- Git, Bash and Ninja

### Commands

```bash
git submodule update --init --recursive
./gradlew :core:pm3:assembleDebug
./gradlew :core:pm3:installDebug     # with a device attached
```

The Gradle `preBuild` task invokes `scripts/build-native.sh` and produces
`core/pm3/src/main/jniLibs/arm64-v8a/libpm3android.so`. Client resources
(dictionaries, Lua libraries/scripts, Cmd scripts, Python scripts and resources)
are synced from the submodule into `core/pm3/src/main/assets/`.

Both generated directories are build artifacts and are excluded from git.
`dictionaries/extras` (28 MB of generic brute-force dictionaries) is
deliberately not packaged. The embedded CPython runtime adds roughly 20 MB to
the debug APK.

The Python bridge source lives in `core/pm3/src/main/python/`:
`pm3.py` is the compatibility shim and `pm3_runner.py` captures script output
and arguments. The upstream SWIG `pm3.py` is intentionally not packaged.

The native build enables 16 KB page compatibility:

- `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`
- `-Wl,-z,max-page-size=16384`

### Release signing

Release builds are unsigned unless `keystore.properties` exists. Copy the
example and fill in your own paths/passwords:

```bash
cp keystore.properties.example keystore.properties
# edit keystore.properties, keep it out of git
```

The Gradle build picks it up automatically. Never commit the keystore or its
passwords.

### Tests and CI

```bash
./gradlew :core:pm3:testDebugUnitTest
./gradlew :core:pm3:lintDebug
./gradlew :core:pm3:assembleDebug
```

GitHub Actions runs tests, lint and the debug build. Pushing a tag such as
`0.6.0` also publishes a GitHub release with the debug APK attached.

## Troubleshooting

**The Proxmark3 is not detected**

- Use a data-capable USB cable and an OTG adapter supported by the phone.
- Confirm USB host mode is enabled.
- Check `adb shell dumpsys usb` or a USB device-info app for `0x9ac4:0x4b8f`.
- Replug the device and reopen the app.

**The USB permission prompt does not appear**

- Disconnect and reconnect the board.
- Check that another app is not holding the USB interface.
- On Android 12+, approve the system USB permission dialog.
- The app waits up to 60 seconds for permission, then returns to
  `DISCONNECTED`.

**Connection fails or the firmware handshake fails**

- The firmware must match the pinned Proxmark3 client commit.
- Reflash the board from a PC with the matching firmware image.
- Check the Device diagnostics for VID/PID and interface details.

**The app disconnects during a command**

- USB detach is detected and the transport is torn down.
- Reconnect the board and reconnect from the Device tab.
- Avoid moving the cable/adapter while a command is running.

**I need to reset the client resources**

Open **Info / Settings → Reset client resources** while disconnected. Dumps,
traces and logs are preserved, as are scripts imported through **Tools →
Custom scripts**. Dictionaries, bundled Lua/Cmd/Python scripts and resources
are reinstalled on the next connection.

## Privacy and security

- No `INTERNET` permission.
- No analytics, telemetry or advertising SDKs.
- Client data stays in the app's private `filesDir`.
- Files leave the app only through an explicit share or export action.
- USB access is requested through the Android USB permission dialog.
- The app does not flash firmware or expose a network service.
- Imported scripts execute with the app's permissions. Only import scripts
  you trust. The app has no `INTERNET` permission, so Python scripts cannot
  open network connections even though the Python standard library is present.

## Project layout

```text
core/pm3/
  src/main/java/com/farmacia/pm3android/
    pm3/         USB CDC, socket bridge, JNI facade, resource installer
    data/        preferences, file model and visibility rules
    ui/          Compose app, screens, components and theme
    util/        formatting helpers
  src/main/cpp/  JNI glue and native client build
  src/main/python/  Chaquopy compatibility shim and script runner
  src/test/      unit tests
scripts/         native build and submodule preparation
third_party/     pinned Proxmark3 submodule
```

## Attribution

- The original **RFIDtools** Android app (GPL-3.0) is by the
  [RfidResearchGroup](https://github.com/RfidResearchGroup), published on the
  [Play Store](https://play.google.com/store/apps/details?id=com.rfidresearchgroup.rfidtools)
  as `com.rfidresearchgroup.rfidtools`. This repository is a GPL-3.0
  derivative, rewritten with a different architecture (in-process client
  instead of Termux).
- The embedded Proxmark3 client comes from
  [RfidResearchGroup/proxmark3](https://github.com/RfidResearchGroup/proxmark3),
  pinned as a submodule (Iceman fork, commit `fabf0f7b`).
- The MIFARE Ultralight Ev1 counter-reset Lua script is a port of the Python
  script in the Proxmark3 client, which credits the
  [Quarkslab anti-tearing research](https://blog.quarkslab.com/rfid-monotonic-counter-anti-tearing-defeated.html).
- Python script execution uses [Chaquopy](https://chaquo.com/chaquopy/), which
  embeds CPython in the Android app. The Python standard library and bundled
  `pyscripts` retain their upstream licences.

## License

GPL-3.0, same as the Proxmark3 client embedded in the app. See
[LICENSE](LICENSE).
