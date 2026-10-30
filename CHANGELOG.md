# Changelog

## 1.0.3 – 2026-10-30

### Fixed
- EV1 counter writes now send the six-byte `INCREMENT_CNT` frame used by
  the reference Android app and the Proxmark3. The previous five-byte frame
  was rejected with `TagLostException`, so the UI reported “tag allontanato”
  even with the tag in place.
- `READ_TEARING` is best-effort: tags or clones that do not answer it no
  longer block the increment, and the result carries the tearing-unknown
  warning instead.
- Counter labels are **0, 1, 2** everywhere, matching the protocol index.
- Classic value-block detection now matches the Proxmark3 `mfc_value`
  check: the address field only has to be internally consistent, not equal
  to the absolute block number. This exposes RicaricaMi/En1545 counters and
  other transit cards that use a relative or slot address.
- Blocks whose trailer was not read, or whose access bits are invalid, are
  listed with the operations disabled instead of being silently hidden.
- Added a RicaricaMi hint (counters in sectors 2–5, blocks 0/1, sector 5
  blocks 1/2) and a warning that the En1545 hash is not recomputed.
- Clearer NFC errors: the failure now names the phase (version, counter
  read, increment, verification) instead of always blaming tag movement.

### Changed
- **Credit and rides** is now called **Credit** in the NFC home, the tag
  inspector, the tool, the dialogs and the documentation.
- Added a **Use FFFFFFFF** shortcut for protected EV1 counters.

### Release
- Version `1.0.3` (`versionCode` 14), built against the pinned Proxmark3
  submodule.

## 1.0.2 – 2026-10-30

### Added
- NFC **Set counter**: choose a MIFARE Ultralight EV1 counter (1–3) and an
  absolute target, then present the tag once. The tool authenticates with the
  optional password, reads the current value and tearing flag, computes the
  delta, sends INCREMENT_CNT and verifies the read-back. A target below the
  current value is refused, because EV1 counters cannot be reset from the phone.
- NFC **Credit and rides**: scan a MIFARE Classic with a key file, choose a
  standard value block and increment or decrement it. The first tap scans, the
  second applies INCREMENT/DECREMENT + TRANSFER and verifies the new value. The
  access bits are enforced (`000` either key, `001` decrement only, `110` Key B)
  and the tool never rewrites the block with a raw WRITE.
- Both tools are reachable from the NFC home and from the tag read result, where
  the compatible card family gets a **Write to tag** card even when the keyless
  read was incomplete.

### Changed
- The NFC home now has five tiles: **Read tag**, **Restore tag**, **APDU console**,
  **Set counter** and **Credit and rides**.
- Restore notes now point to the explicit counter tool instead of saying that
  EV1 counters are never touched.

### Tests
- Added JVM tests for the EV1 counter command sequence (delta, password,
  tearing, read-back, NTAG refusal) and for Classic value blocks (layout, ACL
  matrix, 4K access groups, changed value, transfer verification).

### Release
- Version `1.0.2` (`versionCode` 13), built against the pinned Proxmark3
  submodule.

## 1.0.1 – 2026-10-30

### Changed
- The NFC home now has three equal tiles — **Read tag**, **Restore tag** and
  **APDU console** — and the "How it works" card is gone.
- **Read tag** is one action: it identifies the tag and reads its memory in the
  same field, then offers **Save dump** without presenting the tag again. The
  full read with a key file or a password stays reachable as **Open full read**
  from an incomplete or failed read.
- A short restore note under the dump states the caveat per family: Ultralight
  EV1 counters are not restored, MIFARE Classic needs the sector keys, my-d move
  lean page 03 (OTP/CC) cannot be rewritten.
- Info: the build row now reads `Release / M35MAR RFIDtools` and the client
  folders list includes `$HOME/.proxmark3/pyscripts`.

### Fixed
- ATQA was assembled as `raw[0] << 8 | raw[1]`, so Android's `44 00` became
  `0x4400` and the Infineon my-d move lean was labelled "NFC Forum Type 2 (page
  memory)". The value is now little-endian (`0x0044`), both byte orders are
  accepted and the memory size is used as a fallback.
- my-d move lean can now save a dump: a 56-byte `hf mfu` header plus the raw
  pages, with PACK and password left as read. The file opens in the Data viewer;
  the phone refuses to restore it with a clear message, since page 03 is OTP.

## 1.0.0 – 2026-10-30

### Added
- Page-addressed memory over raw NfcA: when GET_VERSION does not answer and
  Android exposes no MifareUltralight (the Infineon SLE66R01L found on transport
  tickets), the inspector reads the memory with the 4-block command `0x30` and
  names the card from what it read: `my-d move lean (SLE66R01L)` at 16 blocks,
  `my-d move (SLE66R01P)` at 38. Capability container and NDEF are parsed from
  the same read.
- Memory table in the inspector: row number, hex bytes and ASCII, with the full
  memory one tap away. It covers the page memory above and the blocks of a
  MIFARE Classic read.
- Explicit dump save: reading the memory no longer writes a file. **Read
  memory** shows the result and **Save dump** opens a dialog with an editable
  name, `.bin` added automatically, invalid names rejected and collisions
  suffixed.

### Fixed
- Dumps saved from the inspector keep the `.bin` extension.
- The seven-byte UID note no longer claims that the memory is unreadable.

## 0.9.0 – 2026-10-29

### Added
- **Identifica tag** now reaches every practical tag family, not only the
  MIFARE basics: MIFARE Plus levels 1–3, DESFire EV1/EV2/EV3, Calypso/BNEVT,
  ISO 15693, FeliCa and generic ISO-DEP applications (EMV payment cards, NDEF
  Type 4, CIPURSE, PIV).
- MIFARE Plus identification with the native three-frame GET_VERSION, the
  hardware/software versions, the storage size and the security level inferred
  from the SAK, with a clear note when level 2 or 3 still needs AES keys.
- DESFire keyless metadata: PICC free memory, key settings and key count, every
  application with its DF name, key settings, file ids, ISO file ids and the
  settings of each file (type, size, record geometry, access rights, value
  limits). Locked levels are marked instead of hiding the application.
- DESFire authenticated read: enter a 16-byte AES key and a key number, present
  the tag again and the app runs the EV1 AES handshake, derives the session key
  and reads standard, backup, record and value files in plain or MACed
  communication mode. The key stays in memory only for the read; enciphered
  files are marked as not read.
- MIFARE Plus SL3 authenticated sector read: enter the sector AES key and the
  sector (key A or B), present the tag again and the app runs the native
  `0x70`/`0x72` handshake, derives Kenc/Kmac, MACs the `0x30` read and decrypts
  the blocks. The key number is derived from the sector (`0x4000 + sector × 2`),
  the key stays in memory and the blocks appear in the card and in the report.
- Calypso/BNEVT support: curated AID and RID table (including `A000000291` and
  the Setra `A000000404` family), SELECT with CLA `0x94`/`0x00` fallback, FCI
  parsing (DF name, serial, seven startup bytes) and the keyless ICC
  manufacturing record (serial, country, manufacturer, date, check byte).
- Calypso keyless ticketing environment (SFI `0x07`, with the `0x2000`/`0x2001`
  file fallback): environment version, 24-bit network identifier and raw record.
  The secure session itself is not implemented: it requires a Calypso SAM and
  the licensed Calypso specification, so the app states this in the card.
- Calypso free files: events log (SFI `0x08`), contracts (`0x09`), counters
  (`0x19`), special events (`0x1D`) and contract list (`0x1E`) are read one
  record at a time and stop at the first record the card refuses. Counters are
  decoded as 24-bit big-endian values; the records appear in the card and in the
  text report.
- ISO 15693 details (display-order UID, DSFID, AFI, first block) and FeliCa
  details (IDm, PMm, manufacturer, system code).
- **HF probe registry**: the inspector now runs one probe per technology behind a
  small extension interface (`HfProbe`), so a new family is added with a probe and
  not with a change to the Compose screen. ISO-DEP probes are ordered from the
  technology Android discovered (Calypso first on ISO 14443-B, DESFire first
  everywhere else).
- **eMRTD / eID**: the ICAO 9303 application, the German eID and the Italian CIE
  are selected; EF.CardAccess is read to report PACE and GET CHALLENGE reports
  whether BAC is still enabled. The card states that DG1..DG16 need the MRZ or
  the PACE password and that DG2/DG3 are biometric data.
- **ISO 15693**: GET SYSTEM INFO with manufacturer, DSFID, AFI, block count and
  size, IC reference; first block and Type 5 NDEF. NXP ICODE, ST25, Infineon
  SLE66R/my-d and EM Microelectronic are named from the UID manufacturer code.
- **FeliCa / ISO 18092**: Request Response (PMm), Request System Code and the
  Type 3 NDEF service, with a note that NFC-DEP peer-to-peer is not exposed by
  Android.
- **ISO-DEP fallback**: ATS and historical bytes, EMV/PPSE, NDEF Type 4 and a
  generic AID scan, plus SmartMX/CIPURSE/PIV naming and the APDU-only note.
- **NDEF Type 4 and Type 5** are decoded with the same parser as Type 2, and
  DESFire cards with an NDEF application report both the filesystem and the
  message.
- **APDU console** in the NFC tab: an ISO-DEP overlay that keeps the connection
  open, parses hexadecimal commands, drains `61xx`/`6Cxx` and shows the response
  with the ISO 7816 status word description.
- New inspector cards and text-report sections for eMRTD, ISO-DEP, generic probe
  details and the command log, with Italian and English strings.
- New cards in the inspector and new sections in the text report for Plus,
  Calypso, ISO 15693, FeliCa and ISO-DEP applications, with Italian and English
  strings and new notes for the keyed and ISO 14443-B cases.

### Changed
- DESFire application ids are decoded in the card byte order (least significant
  byte first), so the ids shown match `hf mfdes` and the NXP documentation.
- Version `0.9.0` (`versionCode` 10).

## 0.8.0 — 2026-10-28

### Added
- **Identifica tag** in the **NFC** tab: a read-only overlay that identifies the
  tag in the hand with the phone's reader and shows everything it can learn
  without keys. Identification prefers GET_VERSION, then the Android tech list,
  then SAK, and labels the result as confirmed, likely or uncertain. Covers
  MIFARE Classic 1K/2K/4K, Ultralight, NTAG and EV1, Plus, DESFire, ISO 15693
  and FeliCa, including 4-byte and 7-byte UIDs and common clones.
- DESFire summary from the free commands: generation, software version, memory
  size and application IDs.
- Content of the memory once read: MAD1/MAD2 directory with CRC check and AID
  names, NDEF records (text, URI, MIME, external, smart poster), sector roles,
  access bits decoded with the NXP tables, value blocks, Type 2 capability
  container and NTAG counters.
- Dump from the inspector using the factory keys. The `.bin` and its
  `hf-mf-<UID>-key.bin` are written only when the read is complete, matching
  `hf mf dump`; an incomplete read keeps the readable content on screen and
  offers the full read flow for key files and passwords.
- Text report (`<base>-report.txt`) available as soon as the identity is known,
  with identity, notes, GET_VERSION/DESFire data, MAD, NDEF and the sector
  table. A new **Report** filter in **Data** lists these files.

### Changed
- **Data** filters gain **Report**; key `.txt` files stay under **Other**.
- Version `0.8.0` (`versionCode` 9).

## 0.7.0 — 2026-10-28

### Added
- In-app updater: the app checks the latest GitHub release at startup and shows
  a dialog when a newer version exists. **Info → Links** also has a **Check for
  updates** button with release notes, a download progress bar and an **Install
  now** action that opens the system package installer. Downloads are verified
  with the GitHub asset SHA-256 digest when it is available.
- **Check for updates on startup** setting, on by default. A dismissed version
  is not shown again until a newer one is published.
- Dedicated **NFC** tab for the phone's NFC reader, with reader status, a tag
  read action, a dump picker for writing, and a short how-to. It works without
  a Proxmark3 and does not show PM3-disconnected warnings.
- **Device** now has two sub-tabs: **Device** (USB status and diagnostics) and
  **PM3 tools** (the curated command sections and custom scripts).
- Unit tests for the release parser: version comparison, asset selection,
  prerelease rejection and debug/release channel separation.

### Changed
- The app is now named **RFIDtools** (launcher label and UI). The package id is
  unchanged, so existing data is preserved.
- Bottom navigation is now **Device · Console · NFC · Data · Info**. The old
  **Tools** tab has been removed and its contents moved to **Device → PM3
  tools**.
- The top bar no longer duplicates a global refresh action; each screen owns
  its refresh. The **Info** tab shows a badge when an update is available.
- Consistent card spacing and section order across screens.
- CI now builds a signed release APK on tags, checks that the tag equals
  `versionName`, and publishes only `pm3-release.apk`. Debug APKs remain CI
  artifacts and are no longer attached to GitHub releases.
- Version `0.7.0` (`versionCode` 8).

### Security
- Added `INTERNET` (update check and APK download only) and
  `REQUEST_INSTALL_PACKAGES` (system installer) permissions.
- Release builds are signed with the new stable RFIDtools keystore. Keep
  `release.jks` and `keystore.properties` backed up: losing them prevents
  in-place updates.

## 0.6.0 — 2026-10-28

### Added
- In-app editor for `.bin` dumps in the **Data** tab: tap a MIFARE Classic
  block or an Ultralight/NTAG page to view and edit its hex bytes, jump to a
  row, discard changes and save in place. Every row also shows its ASCII
  rendering and decoded details (block 0, sector trailer with access
  conditions, value blocks, Ultralight lock/OTP/configuration pages), both in
  the list and in the edit dialog. Rows a restore never writes (UID, lock
  bits, OTP, configuration area) are flagged.
- Phone-NFC dump of MIFARE Classic: pick the `hf-mf-<UID>-key.bin` file written
  by `hf mf autopwn`, or any `.bin`/`.txt` key file already in the **Data**
  tab. Every key is tried as Key A and as Key B on every sector, so cards whose
  sectors do not share one key are read completely, and the read is
  re-authenticated with Key B when a block requires it. With a file selected
  only its keys are tried; the factory `FFFFFFFFFFFF` key is used only without
  a file. An incomplete read is an error and nothing is saved. The dump is
  saved as `hf-mf-<UID>-dump.bin` with the proven keys in the sector trailers
  and the sibling `hf-mf-<UID>-key.bin`.
- The key file can be imported from phone storage with **Choose from storage**;
  the document is copied into the **Data** tab folder and only `.bin`/`.txt`
  files are accepted.

### Fixed
- Phone-NFC MIFARE Classic restore could write a partial dump: it now runs a
  blocking pre-check that opens every sector and verifies every differing block
  is writable. Missing keys or an unreadable sector stop the restore before the
  first write, and a dump of a different size than the tag is refused.
- The Classic reader no longer falls back to the factory key when a key file is
  selected, so a wrong file fails instead of silently reading the transport
  configuration.
- Phone-NFC MIFARE Classic restore left data blocks untouched when their access
  condition required Key B (`011`/`100`/`110`): the restorer kept a single Key A
  session and lost the key type. It now tracks a typed session, re-authenticates
  with Key B where needed, writes the trailer with the key its current access
  conditions require and retries each write once before reporting a failure.
- A block still read-only during a restore is repaired automatically: the
  sector trailer from the dump is written first and the failed blocks are
  retried, instead of being reported as unwritable.
- The hex bytes of a dump block or page were blank in the editor and in the
  edit dialog: `toHexSpaced()` looped over the empty `StringBuilder` instead of
  the byte array, so it always returned an empty string.
- Dumping a MIFARE Classic over phone NFC failed with `GET_version` because
  the reader treated every tag as Ultralight; the tag technology now decides
  between the Classic and the Ultralight reader.

### Changed
- The phone-NFC Classic restore has a single **Key file** field: no dictionary
  and no manual key entry. The key file only authenticates the destination
  tag; keys and access bits written into the sector trailers always come from
  the dump.
- The phone-NFC options are always visible, without an expand step.
- The phone-NFC restore no longer writes a `*-pre-restore.bin` copy next to the
  dump.
- Version `0.6.0` (`versionCode` 7), built against the Proxmark3 submodule
  `61ede21`.

## 0.5.1 — 2026-10-26

### Added
- One-tap `hf mf autopwn` command in **Tools → HF — MIFARE Classic**.

### Changed
- Removed the generic **HF — MIFARE Ultralight** Tools section; MIFARE
  Ultralight Ev1 tooling remains available.
- Version `0.5.1` (`versionCode` 6).

## 0.5.0 — 2026-10-26

### Added
- Custom script manager in **Tools**: import, run with arguments and delete
  `.lua`, `.cmd` and `.py` files. User scripts survive client-resource resets.
- Embedded CPython 3.14 runtime via Chaquopy for Python scripts. A `pm3`
  compatibility shim forwards `console()` calls to the in-process native
  client, so Python does not need Termux, a shell or an external process.
- Upstream `pyscripts` are packaged and installed with the other client
  resources. Python commands typed as `script run name.py ...` are routed to
  the embedded runtime.
- Unit tests for script-name validation and language detection.

### Changed
- Version `0.5.0` (`versionCode` 5).
- CI installs Python 3.14 for the Chaquopy build and release notes identify the
  Python support as experimental.
- Debug APK size increases because it now contains CPython and the Python
  standard library; native libraries are still 16 KB page aligned.

### Notes
- Python support is an MVP. Scripts which depend on the SWIG `_pm3` object API,
  non-bundled pip packages, audio/graphics libraries or interactive stdin may
  fail. Lua and Cmd remain the most compatible scripting options.
- Imported scripts execute with the app's permissions; only run trusted code.

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
