# pm3-android

Proxmark3 tools for Android — GPL-3.0.

A rewrite of the [RfidResearchGroup/RFIDtools](https://github.com/RfidResearchGroup/RFIDtools)
approach: **no Termux, no child process**. The Proxmark3 client (Iceman fork)
runs *in-process* as a shared library inside the app, and communication with a
PM3-compatible board happens over a plain CDC-ACM USB link.

Current app version: **0.4.0** (`versionCode` 4).

## Features

- USB CDC transport with no serial-port library and no external process.
- Device tab with USB descriptor, permission state, diagnostics and quick
  commands.
- Console with command history, copy/share, follow mode, configurable font
  size and clear confirmation.
- Curated, searchable Tools commands with parameter dialogs.
- Files tab for dumps, traces and logs, with filters, SAF import/export and a
  FileProvider share action. Client resources are hidden and protected.
- Material 3 light/dark/system theme, dynamic colour, adaptive icon and
  Android 12+ splash screen.
- Italian and English UI strings.
- Hotplug/detach handling, USB permission timeout and clean teardown.
- Settings backup/restore, client-resource reset, version/build/commit info and
  GPL licence viewer.
- No Bluetooth, SPP or firmware flashing from the phone.

## Attribution

- The original **RFIDtools** Android app (GPL-3.0) is by the
  [RfidResearchGroup](https://github.com/RfidResearchGroup), published on the
  [Play Store](https://play.google.com/store/apps/details?id=com.rfidresearchgroup.rfidtools)
  as `com.rfidresearchgroup.rfidtools`. This repository is a GPL-3.0
  derivative of that published app, rewritten with the ideas and goals of the
  original but a different architecture (in-process client instead of Termux).
- The embedded Proxmark3 client comes from the
  [proxmark3](https://github.com/RfidResearchGroup/proxmark3) repository,
  pinned as a submodule (iceman fork, commit `fabf0f7b`).
- The MIFARE Ultralight Ev1 counter-reset Lua script is a port of the Python
  script in the proxmark3 client, which itself credits the
  [Quarkslab anti-tearing research](https://blog.quarkslab.com/rfid-monotonic-counter-anti-tearing-defeated.html).

## Download

Prebuilt APK (arm64, Android 8+): see the
[releases](https://github.com/M35mar/RFIDtools/releases).

## Target hardware

This app targets PM3-compatible boards with:

- USB IDs: `0x9ac4:0x4b8f` (CDC-ACM, `VID_PID` set by `usb_cdc_desc.c`)
- ABI: **arm64-v8a** only
- Android 8.0+ (API 26)

Firmware is **not** included and is **not** flashed by the app. Flash it from a
PC with a desktop client. The firmware must be built from the same pinned
`third_party/proxmark3` commit as the embedded client so that the
`CAPABILITIES_VERSION` handshake matches. The firmware flavour depends on the
board (for example PM3GENERIC/PM3EASY 512k, or the flavour required by your
hardware).

## Architecture in one minute

```
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
  connects to. `OpenProxmark()` runs the ordinary client reader thread.
- `pm3_open()`'s `exit()` on failure is never reached: the open sequence is
  reimplemented in `pm3_jni.c`, reporting failure as a status instead.
- Command output is captured with the client's own grabber
  (`pm3_grabbed_output_get`), no PTY, no pipes.

## Building

Requirements:

- Android SDK with platform 37, build-tools 37.0.0
- NDK **27.2.12479018** (pinned; `NDK_VERSION` can override)
- CMake **3.22.1** (pinned; `CMAKE_VERSION` can override)
- JDK **21** (the Gradle daemon toolchain is 21)
- Git, Bash, Ninja (Ninja is shipped with the SDK CMake package)

```bash
git submodule update --init --recursive
./gradlew :core:pm3:assembleDebug    # builds libpm3android.so + APK
./gradlew :core:pm3:installDebug     # with a device attached
```

The Gradle `preBuild` task invokes `scripts/build-native.sh` (cmake+ninja in
two passes: the bzip2/lz4 external projects first, then the client target) and
drops `libpm3android.so` into `core/pm3/src/main/jniLibs/`. The bundled client
resources (dictionaries, Lua scripts, resources) are synced from the submodule
into `core/pm3/src/main/assets/`. Both directories are build artifacts and stay
out of git.

`dictionaries/extras` (28 MB of generic brute-force password dictionaries) is
deliberately not packaged.

The native build enables 16 KB page compatibility
(`ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES` plus
`-Wl,-z,max-page-size=16384`).

## Release signing

Release builds are unsigned unless a `keystore.properties` file exists. Copy
`keystore.properties.example` to `keystore.properties`, fill in the paths and
passwords, and keep it out of git. The Gradle build picks it up automatically.

## Tests and CI

```bash
./gradlew :core:pm3:testDebugUnitTest
./gradlew :core:pm3:lintDebug
```

GitHub Actions runs unit tests, lint and builds a debug APK on pushes and
pull requests (`.github/workflows/android.yml`).

## Privacy

The app has no network permission and no analytics. All client files live in
the app's private storage (`filesDir`); they leave the app only when you
explicitly share or export them.

## Flashing the firmware

Not part of this app. Use a desktop client, e.g. the provided WSL workflow,
with a full firmware image built from the same submodule commit.

## License

GPL-3.0, same as the Proxmark3 client this app embeds. See `LICENSE`.
