# Changelog

## 0.4.0 — 2026-10-26

### Fixed
- Correct USB vendor ID in the device filter (0x9AC4, decimal 39620). Plugging
  in a PM3 now offers the app automatically.
- First connection no longer copies client resources on the main thread.
- USB permission requests time out instead of leaving the app in `CONNECTING`.
- Bridge/port cleanup when the native client fails to open.
- USB reader loop now stops on port close or device detach.
- ViewModel tears down the native/USB connection when it is cleared.
- Files screen no longer lists or deletes client resources.
- Edge-to-edge and IME handling for Android 15+.
- Selected tab is restored after rotation instead of jumping back to Device.

### Added
- Material 3 theme with light/dark/system modes and dynamic colours.
- Adaptive launcher icon and Android 12+ splash screen.
- Italian and English string resources.
- Top bar with live connection status and refresh action.
- USB hotplug/detach handling.
- USB descriptor and diagnostics cards with copy/share.
- Console command history, copy/share, follow mode, clear confirmation and
  configurable font size.
- Files filters, SAF import/export, delete confirmation and protected client
  resources.
- Searchable, collapsible Tools sections, localized command/parameter labels and
  recent commands.
- Info/settings screen with version, build, commits, licence, links, backup and
  restore of app settings, client-resource reset and install status.
- Unit tests for the file visibility/category rules.
- GitHub Actions CI, with a debug APK artifact and automatic GitHub Release
  publication when a version tag is pushed.
- Optional release signing via `keystore.properties`.
- 16 KB page-size linker option and pinned NDK/CMake versions.
- Complete `README` refresh with install, hardware, build, privacy and
  troubleshooting documentation.

### Changed
- Compile/target SDK 37, AndroidX/Compose dependency refresh, version
  `0.4.0` (`versionCode` 4).
- `minSdk` stays 26 (Android 8+), ABI remains arm64-v8a.
