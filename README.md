# pm3-android

Proxmark3 tools for Android — GPL-3.0.

A full rewrite of the [RFIDResearchGroup/RFIDtools](https://github.com/RfidResearchGroup/RFIDtools)
approach: **no Termux, no child process**. The Proxmark3 client (Iceman fork)
runs *in-process* as a shared library inside the app, and all communication
with a generic Chinese PM3 board happens over a plain CDC-ACM USB link.

## Attribution

- The original **RFIDtools** Android app (GPL-3.0) is by the
  [RfidResearchGroup](https://github.com/RfidResearchGroup), published on the
  [Play Store](https://play.google.com/store/apps/details?id=com.rfidresearchgroup.rfidtools)
  as `com.rfidresearchgroup.rfidtools`. This repository is a GPL-3.0
  derivative of that published app, rewritten from scratch with the ideas and
  goals of the original, but a completely different architecture (in-process
  client instead of Termux).
- The embedded Proxmark3 client and firmware come from the
  [proxmark3](https://github.com/RfidResearchGroup/proxmark3) repository,
  pinned as a submodule (iceman fork, commit `8ab96c9`).
- The MIFARE Ultralight Ev1 counter-reset Lua script is a port of the python
  script in the proxmark3 client, which itself credits the
  [Quarkslab anti-tearing research](https://blog.quarkslab.com/rfid-monotonic-counter-anti-tearing-defeated.html).

## Download

Prebuilt APK (arm64, Android 8+): see the
[releases](https://github.com/M35mar/RFIDtools/releases). For firmware
flavours other than PM3GENERIC/PM3EASY 512k, build it yourself from source
(below).

## Target hardware

This app only targets the generic Chinese Proxmark3 boards the author uses:

- firmware flavour: **PM3GENERIC / PM3EASY, 512k SPI flash**
- USB IDs: `0x9ac4:0x4b8f` (CDC-ACM, `VID_PID` set by `usb_cdc_desc.c`)
- ABI: **arm64-v8a** only

The client sources and the firmware come from the pinned `proxmark3`
submodule (`third_party/proxmark3`), so client and firmware always share the
same capabilities contract (`CAPABILITIES_VERSION`) by construction.

## Architecture in one minute

```
┌────────────────────────────── app process ──────────────────────────────┐
│  Compose UI (Device / Console / Tools / Files / About)                  │
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

Requirements: Android SDK (platform 35, build-tools, NDK 27.x, CMake 3.22.1),
JDK 17+.

```bash
git submodule update --init --recursive
./gradlew assembleDebug    # builds libpm3android.so + APK
./gradlew installDebug     # with a device attached
```

The Gradle `preBuild` task invokes `scripts/build-native.sh` (cmake+ninja in
two passes: the bzip2/lz4 external projects first, then the client target)
and drops `libpm3android.so` into `core/pm3/src/main/jniLibs/`. The bundled
client resources (dictionaries, lua scripts, resources) are synced from the
submodule into `core/pm3/src/main/assets/`. Both directories are build
artifacts and stay out of git.

`dictionaries/extras` (28 MB of generic brute-force password dictionaries) is
deliberately not packaged.

## Flashing the firmware

Not part of this app (yet). Use a desktop client, e.g. the provided
WSL workflow, with a PM3GENERIC 512k full image built from the same
submodule commit.

## License

GPL-3.0, same as the Proxmark3 client this app embeds. See `LICENSE`.
