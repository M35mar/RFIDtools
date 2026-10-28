# RFIDtools

## 💖 Support the Project

**RFIDtools** is a free, open-source app for Android.
If this project saves you time or makes your pentesting easier, consider supporting its independent development!

* 🚀 **Help keep it ad-free** — [![Sponsor](https://img.shields.io/github/sponsors/M35mar?label=Sponsor&logo=GitHub&color=fe8e86)](https://github.com/sponsors/M35mar)
* ☕ **Buy me a coffee** — [![Donate](https://img.shields.io/badge/Donate-PayPal-green.svg?logo=paypal)](https://paypal.com/biz/profile/acquistaliSRL)

---

**Proxmark3 in your pocket — no Termux, no external process, no Bluetooth bridge.**

RFIDtools runs the Proxmark3 Iceman client **inside the app process** and talks to a
PM3-compatible board over direct USB CDC-ACM. The UI is native Android (Compose +
Material 3), the client is compiled into `libpm3android.so`, and the phone's own NFC
reader is available as a separate section that works without any Proxmark3.

[![Android CI](https://github.com/M35mar/RFIDtools/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/M35mar/RFIDtools/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/M35mar/RFIDtools?sort=semver)](https://github.com/M35mar/RFIDtools/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Android 8+](https://img.shields.io/badge/Android-8%2B%20(API%2026)-green.svg)](#requirements)
[![arm64-v8a](https://img.shields.io/badge/ABI-arm64--v8a-informational.svg)](#requirements)

## Download

### [⬇️ Download `pm3-release.apk`](https://github.com/M35mar/RFIDtools/releases/latest/download/pm3-release.apk)

Signed release build, package `com.farmacia.pm3android`. It installs over any previous
release, so in-app updates work. Debug builds (`pm3-debug.apk`, package
`com.farmacia.pm3android.debug`) exist only as CI artifacts and are never published.

The app checks GitHub for a newer release at startup and can download and install it
from **Info → Links → Check for updates**. The first install is a manual sideload.

> **Coming from a debug build?** It cannot update in place: export your data, uninstall,
> then install `pm3-release.apk` once.

## Features

| Tab | What you get |
| --- | --- |
| **Device** | USB status, vendor/product IDs, serial and interface diagnostics with copy/share. Sub-tab **PM3 tools**: searchable LF, HF, MIFARE Classic (one-tap autopwn) and Ultralight Ev1 commands, parameter dialogs, recent commands. |
| **Console** | Free-form command input with history, follow mode, font size, copy/share of the output. Commands run through the in-process client, not a shell. |
| **NFC** | Identify a tag with the phone's reader: family, UID, memory, MAD directory, NDEF, sectors with access bits and counters. Read and write Ultralight/NTAG and MIFARE Classic dumps, restored with the keys from `hf-mf-<UID>-key.bin`, and save a text report of what was read. No Proxmark3 required. |
| **Data** | Dumps, traces, logs and tag reports; filters, import/export via the Storage Access Framework, share. Built-in hex editor for `.bin` blocks and pages, with decoded access bits and value blocks. |
| **Info** | Version, build and client commit, theme, settings backup/restore, client-resource reset, in-app updates, licence and links. |

**Scripts:** import `.lua`, `.cmd` and `.py` files, run them with arguments and keep them
across resource resets. Lua and Cmd run in the native client; Python runs in an embedded
CPython runtime (experimental — scripts needing SWIG objects or extra pip packages may
fail). Only import scripts you trust.

## Quick start

1. Install [`pm3-release.apk`](https://github.com/M35mar/RFIDtools/releases/latest/download/pm3-release.apk).
2. Connect the Proxmark3 with a USB OTG cable or adapter.
3. Open the app and accept the Android USB permission prompt.
4. Go to **Device → Device**, tap **Connect**, then use **Console** or **PM3 tools**.

Use **Data** for dumps, traces, logs and reports, and **NFC** to identify tags and work with
them using the phone alone. If the board is not detected, check the cable/adapter and confirm
it enumerates as `0x9ac4:0x4b8f`.

## Requirements

- Android **8.0+** (API 26), **arm64-v8a**, with USB host/OTG.
- Proxmark3 hardware exposing USB IDs `0x9ac4:0x4b8f` (CDC-ACM).
- Firmware built from the **same pinned Proxmark3 commit** as the embedded client, so the
  `CAPABILITIES_VERSION` handshake matches.

Firmware is **not** included and **not** flashed by this app — use a desktop client.

## Build

<details>
<summary><b>Build from source</b></summary>

Requires Android SDK platform 37, build-tools 37.0.0, NDK 27.2.12479018, CMake 3.22.1,
JDK 21, Python 3.14 (Chaquopy), plus Git, Bash and Ninja.

```bash
git submodule update --init --recursive
./gradlew :core:pm3:assembleDebug
./gradlew :core:pm3:installDebug   # with a device attached
```

`preBuild` runs `scripts/build-native.sh` and produces
`core/pm3/src/main/jniLibs/arm64-v8a/libpm3android.so`. Client resources (dictionaries,
Lua/Cmd/Python scripts) are synced from the submodule into `assets/`. Both directories are
build artifacts and excluded from git; `dictionaries/extras` (28 MB) is not packaged.

</details>

<details>
<summary><b>Release signing</b></summary>

Release builds are signed when `keystore.properties` exists; otherwise Gradle produces an
unsigned APK that cannot install over a release build.

```bash
cp keystore.properties.example keystore.properties
# storeFile=release.jks  keyAlias=rfidtools
```

**Back up `release.jks` and `keystore.properties`.** Losing the key means future APKs can
never update an installed release. CI signs tagged builds with these secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64` of `release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | `rfidtools` |
| `ANDROID_KEY_PASSWORD` | key password |

</details>

<details>
<summary><b>Tests and CI</b></summary>

```bash
./gradlew :core:pm3:testDebugUnitTest
./gradlew :core:pm3:lintDebug
./gradlew :core:pm3:assembleDebug
```

Every push and pull request runs tests, lint and the debug build. Pushing a tag such as
`0.7.0` additionally checks that the tag equals `versionName`, builds a signed release and
publishes a GitHub release with only `pm3-release.apk` attached. Bump `versionCode` and
`versionName` together in `core/pm3/build.gradle.kts` before tagging.

</details>

## Troubleshooting

<details>
<summary><b>The Proxmark3 is not detected</b></summary>

- Use a data-capable cable and an OTG adapter supported by the phone.
- Confirm USB host mode and check `adb shell dumpsys usb` for `0x9ac4:0x4b8f`.
- Replug the device and reopen the app.

</details>

<details>
<summary><b>The USB permission prompt does not appear</b></summary>

- Disconnect and reconnect the board; make sure no other app holds the interface.
- The app waits up to 60 seconds for permission, then returns to `DISCONNECTED`.

</details>

<details>
<summary><b>Connection or firmware handshake fails</b></summary>

- The firmware must match the pinned Proxmark3 client commit: reflash from a PC.
- Check **Device → Device** diagnostics for VID/PID and interface details.

</details>

<details>
<summary><b>The update check or install fails</b></summary>

- The check needs internet and reads the public GitHub API; rate limits or an outage can
  make it fail. Retry from **Info → Links → Check for updates**.
- A release without an asset named exactly `pm3-release.apk` is reported as incompatible.
- If Android blocks the install, allow **Install unknown apps** for RFIDtools and retry.

</details>

<details>
<summary><b>I need to reset the client resources</b></summary>

Open **Info → Reset client resources** while disconnected. Dumps, traces, logs and imported
scripts are preserved; dictionaries, bundled scripts and resources are reinstalled on the
next connection.

</details>

## Privacy and security

- `INTERNET` is used only for the GitHub release check and APK download. No analytics,
  telemetry or advertising SDK.
- Client data stays in the app's private `filesDir` and leaves only through an explicit
  share or export.
- Installation always goes through the system package installer; the app never installs
  silently and exposes no network service.
- Imported scripts run with the app's permissions and can open network connections — only
  import code you trust.

## License and credits

GPL-3.0, same as the embedded Proxmark3 client. See [LICENSE](LICENSE) and
[CHANGELOG.md](CHANGELOG.md) for the full history.

- Original **RFIDtools** (GPL-3.0) by [RfidResearchGroup](https://github.com/RfidResearchGroup);
  this repository is a derivative rewritten with an in-process client instead of Termux.
- Embedded client: [RfidResearchGroup/proxmark3](https://github.com/RfidResearchGroup/proxmark3)
  (Iceman fork, pinned submodule).
- Ultralight Ev1 counter-reset script based on [Quarkslab's anti-tearing research](https://blog.quarkslab.com/rfid-monotonic-counter-anti-tearing-defeated.html).
- Python execution via [Chaquopy](https://chaquo.com/chaquopy/); bundled pyscripts keep
  their upstream licences.

## Architecture

<details>
<summary><b>How the client runs inside the app</b></summary>

```text
┌───────────────────────── app process ─────────────────────────┐
│  Compose UI (Device · Console · NFC · Data · Info)            │
│        │ commands as strings                                  │
│        ▼                                                      │
│  Pm3Client ─→ Pm3Native (JNI, libpm3android.so)               │
│        │                 │ pm3_console() → captured output    │
│        ▼                 ▼                                    │
│  UsbCdc ◀── LocalSocketBridge (socket:pm3.android)            │
└───────────────────────────────────────────────────────────────┘
                        ▲ USB CDC bulk transfers
                  Proxmark3 device
```

- The client's `socket:` transport is reused unchanged; the Kotlin `LocalSocketBridge`
  listens on the same abstract namespace it connects to.
- `pm3_open()`'s `exit()` on failure is reimplemented in `pm3_jni.c` and reported as a status.
- Output is captured with the client's own grabber — no PTY, no pipes.
- Python scripts share the same USB session and command serialisation through
  `Pm3PythonBridge`.

</details>
