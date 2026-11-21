# AGENTS.md — RFIDtools (`pm3-android`)

Documento tecnico di riferimento, architettura, invarianti di qualità e piano evolutivo per sviluppatori e agenti AI che lavorano su **RFIDtools**.

---

## 1. Panoramica del Progetto

**RFIDtools** (`com.farmacia.pm3android`, `versionName 2.4.0`, `versionCode 24000`, licenza `GPL-3.0-or-later`) è un client Android nativo per **Proxmark3** (fork Iceman `RfidResearchGroup/proxmark3`) con **NFC Toolkit** standalone integrato.

### Capacità principali
- **Client Proxmark3 in-process**: compilato in `libpm3android.so` (`arm64-v8a`), nessun Termux, nessun processo esterno.
- **Trasporti hardware**:
  - **USB OTG (CDC-ACM)**: driver diretto via `UsbDeviceConnection.bulkTransfer` (`0x9ac4:0x4b8f`).
  - **Bluetooth Classic (SPP)**: RFCOMM per RDV4 BlueShark (`PM3_RDV4.0`).
  - **Bluetooth LE (BLE)**: GATT byte pipe sperimentale per Proxmark5 BWM (`0xAE86` / `0xAE88`).
- **Console & PM3 Tools**: esecuzione comandi serializzata con streaming live ANSI PC-fidelity (`8 sp` default), tab `CMD` | `Strumenti PM3` (con Comandi Rapidi integrati), storico persistito, `Pm3OperationService` (Foreground Service `connectedDevice`) per operazioni lunghe, catalogo comandi con policy di sicurezza per comandi write/distruttivi, bridge interattivo input/menu per script Lua (`nativeIsWaitingInput`/`nativeSendInput`).
- **Scripting multi-linguaggio**: `.lua` (hook di interruzione via `lua_sethook` + I/O interattivo), `.cmd` (drain queue in `pm3_cmdscript_run_pending`) e `.py` (runtime **Chaquopy** con hook `sys.monitoring` / `sys.settrace`).
- **Phone NFC Toolkit**: ispezione multi-protocollo (MIFARE Classic, Ultralight/EV1/AES/C, NTAG210/212/213/213F/213TT/215/216/216F/I2C/424 DNA, DESFire, MIFARE Plus, ISO-DEP con catalogo AID esteso incl. PagoBANCOMAT/CIE 3.0/FIDO2/OpenPGP/Calypso Italia, ISO 15693 standard + extended commands >256 blocchi per ICODE SLIX/ST25TV/ST25DV/Tag-it, FeliCa Type 3, eMRTD, Calypso), ispezione profonda Type 2 al primo tap (`Type2InspectProbe`: firma ECC 32B `0x3C`, contatori + tearing `0x39`/`0x3E`, static lock, CC, `CFG0`/`CFG1` `AUTH0`/`PROT`/`CFGLCK`/`AUTHLIM`), dump completo con `FAST_READ 0x3A` + restore con read-back verification, **NDEF Writer / Formatter** (URL, Testo, Wi-Fi WSC, Cancella + verifica read-back), console APDU con preset rapidi e SW color-coded, contatore EV1, value block Classic, cambio UID Magic cards (Gen2/CUID, Gen3/APDU, UL EV1 DirectWrite).
- **Data Manager & In-App Updater**: gestione dump (filtro predefinito `DUMP`), trace/log/report, hex editor non distruttivo e aggiornamento da GitHub Releases con verifica HTTPS, SHA-256 obbligatorio, `applicationId`, `versionCode` e certificato di firma.

---

## 2. Architettura del Sistema

Modulo unico Gradle `:core:pm3` (Kotlin + Jetpack Compose Material 3 + C/JNI + Python/Chaquopy).

```text
┌────────────────────────────────────────────────────────────────────────────┐
│                     Presentation Layer (Jetpack Compose)                   │
│  MainActivity -> Pm3App (Scaffold + 5 Tab + Full-Screen NFC/Editor Overlays)│
│  ├─ DeviceScreen (+ Pm3ToolsScreen + ScriptsSection + TagResultCard)       │
│  ├─ ConsoleScreen (ConsoleAnsi + TerminalColors + History)                 │
│  ├─ NfcScreen -> TagInspect / NfcRestore / Apdu / Counter / Value / Uid    │
│  ├─ FilesScreen -> DumpEditorScreen                                        │
│  └─ SettingsScreen (+ UpdateSection)                                       │
│                     Pm3ViewModel (stato condiviso — da decomporre)         │
├─────────────────────────────────┬──────────────────────────────────────────┤
│      PM3 Transport & Runtime    │           Phone NFC Engine               │
│  Pm3Client (Facade + Mutex)     │  NfcSession / AndroidTransports          │
│  ├─ UsbCdc                      │  ├─ inspect/ (HF, DESFire, NDEF, eMRTD,  │
│  ├─ BluetoothSpp                │  │            Iso15693, Felica, Calypso) │
│  ├─ BleSpp + BleByteStream      │  ├─ MfcReader / MfcRestorer / MfcAccess  │
│  ├─ LocalSocketBridge           │  ├─ MfuReader / MfuRestorer / MfuCounter │
│  ├─ Pm3OperationService (FGS)   │  ├─ MagicUidLayout / MagicUidAndroid     │
│  ├─ Pm3Resources (assets->$HOME)│  └─ DumpParser / EditableDump            │
│  └─ Pm3PythonRunner + pm3.py    │                                          │
├─────────────────────────────────┴──────────────────────────────────────────┤
│                  Native JNI Layer (core/pm3/src/main/cpp)                  │
│  Pm3Native.kt <-> pm3_jni.c <-> libpm3android.so (patched proxmark3)       │
└────────────────────────────────────────────────────────────────────────────┘
```

### Flusso di connessione PM3
1. `Pm3Resources.ensure()` installa asset (`dictionaries`, `lualibs`, `luascripts`, `cmdscripts`, `pyscripts`, `resources`) in `$HOME/.proxmark3` tramite staging atomico preservando i file utente.
2. `Pm3Client.connect()` apre il trasporto (`UsbCdc`, `BluetoothSpp` o `BleSpp`).
3. `LocalSocketBridge` apre l'abstract UNIX socket `pm3.android` e avvia due thread pump bidirezionali.
4. `Pm3Native.nativeOpen("socket:pm3.android")` esegue `pm3_init()`, `preferences_load()`, `OpenProxmark()` e `TestProxmark()`.

---

## 3. Mappa dei File Principali

| Percorso | Ruolo |
| --- | --- |
| `core/pm3/build.gradle.kts` | Build config, Chaquopy, `syncPm3ClientResources`, `buildPm3Native`, R8, firma release |
| `core/pm3/proguard-rules.pro` | Keep rules per JNI (`Pm3Native`) e Chaquopy (`Pm3PythonBridge`, `Pm3StreamSink`) |
| `core/pm3/src/main/cpp/pm3_jni.c` | Bridge JNI verso `proxmark3` |
| `patches/pm3-console.patch` | Patch su `ui.c`, `cmdscript.c`, `scripting.c`, `util.c` (grabber thread-safe + `g_user_interrupt`) |
| `patches/pm3-cmdscript.patch` | Drain della coda `.cmd` in `pm3_console()` |
| `patches/pm3-help-grab.patch` | Mantiene `PRINTANDLOG_GRAB` durante `CmdsHelp()` |
| `scripts/prepare-pm3.sh` / `build-native.sh` | Estrazione linenoise, patching submodulo, build Ninja cross-compile `arm64-v8a` |
| `core/pm3/src/main/python/pm3.py` / `pm3_runner.py` | Shim Python `pm3` e runner con hook di interruzione |
| `core/pm3/src/main/java/.../pm3/` | `Pm3Client`, `UsbCdc`, `BluetoothSpp`, `BleSpp`, `LocalSocketBridge`, `Pm3OperationService`, `Pm3Resources` |
| `core/pm3/src/main/java/.../nfc/` | Motore NFC standalone e sotto-pacchetto `inspect/` |
| `core/pm3/src/main/java/.../ui/console/` | `Pm3ViewModel`, `ConsoleScreen`, `ConsoleAnsi`, `ConsoleLineBuffer` |
| `core/pm3/src/main/java/.../ui/theme/` | `Color.kt`, `Theme.kt`, `TerminalColors.kt` |
| `core/pm3/src/main/java/.../update/` | `UpdateManager`, `UpdateParser`, `ApkInstaller` |
| `docs/audit-2026-11.md` | Registro storico dei fix di sicurezza e affidabilità |

---

## 4. Build, Test, Lint e CI

### Prerequisiti
- **JDK 21** (bytecode target Java 17), **Android SDK 37**, **Build-Tools 37.0.0**, **NDK 27.2.12479018**, **CMake 3.22.1**, **Python 3.14**, **Git**, **Bash**, **Ninja**.
- Target: `minSdk 26`, `targetSdk 37`, ABI `arm64-v8a`.

### Comandi essenziali
```bash
# Inizializza submodulo Proxmark3
git submodule update --init --recursive

# Build completo (native + risorse + APK debug)
./gradlew :core:pm3:assembleDebug

# Ciclo rapido Kotlin/JVM test (senza ricompilare C++/NDK né sync asset)
./gradlew :core:pm3:testDebugUnitTest -PskipNative=true -PskipSync=true

# Lint Android (abortOnError = true)
./gradlew :core:pm3:lintDebug -PskipNative=true -PskipSync=true

# Verifica R8/ProGuard locale (come in CI per PR/push)
./gradlew :core:pm3:assembleRelease -PskipNative=true -PskipSync=true -PallowIncompleteRelease=true
```

### Release
- Aggiornare insieme `versionCode` e `versionName` in `core/pm3/build.gradle.kts` prima del tag (`vX.Y.Z` o `X.Y.Z`).
- Aggiornare `CHANGELOG.md` con intestazione `## X.Y.Z` (usata dal workflow `.github/workflows/android.yml` per generare le release notes).
- `assembleRelease` blocca la build se mancano `keystore.properties`, `libpm3android.so` o `assets/`.

---

## 5. Invarianti Critiche di Ingegneria

Ogni PR o modifica deve preservare rigorosamente questi contratti:

1. **Write-Safety RFID/NFC**:
   - Ogni scrittura su tag deve eseguire **read-back verification**; mai segnalare successo senza verifica.
   - Mai scrivere configurazione/lock Ultralight prima di verificare PWD, né trailer Classic senza validare access bits e chiavi correnti.
   - Mai sovrascrivere un dump in-place nell'editor esadecimale; mantenere copia/conferma sicura.
   - Le chiavi non devono mai comparire nei report esportati, nei log diagnostici o nei crash log.
2. **Sicurezza JNI & Coroutines**:
   - Il client Proxmark3 non è thread-safe: tutte le chiamate native passano sotto mutex.
   - Mai chiamare `nativeClose()` sul main thread; nei blocchi `finally` di coroutine cancellabili usare sempre `withContext(NonCancellable + Dispatchers.IO)`.
   - Mai passare buffer grezzi non validati a `NewStringUTF` in JNI (rischio abort ART): usare `jbyteArray` + `String(bytes, Charsets.UTF_8)`.
3. **Privacy, Rete & Updater**:
   - `INTERNET` esclusivamente per check/download GitHub Releases. Zero telemetria/analytics.
   - Verifica obbligatoria su URL HTTPS GitHub, SHA-256, limite dimensione in streaming, `applicationId`, `versionCode` e certificato di firma.
4. **Localizzazione & Testabilità**:
   - **Zero stringhe hardcoded** nelle eccezioni visibili all'utente (usare `R.string.*` con parità 100% EN/IT).
   - `unitTests.isReturnDefaultValues = false`: la logica pura (parser, ANSI, protocolli NFC, layout, updater) deve rimanere disaccoppiata dal framework Android e coperta da unit test JVM.

---

## 6. Debito Tecnico & Bug Aperti (Code Review)

Lista prioritaria dei problemi verificati nel codice attuale:

### P0 — Critici (Crash, Deadlock, Bug Funzionali)
1. **JNI `NewStringUTF` crash & Race `grab`/`close`** (`cpp/pm3_jni.c`):
   - `grab_to_string()` chiama `NewStringUTF` su output potenzialmente non Modified UTF-8 -> convertire a `jbyteArray`.
   - `grab_take()` viene chiamato fuori da `g_api_lock` in `native_open()` e `native_exec()`, e `native_grab()` legge `g_session.pm3_present` senza sincronizzazione rispetto a `native_close()` (`free_grabber()`).
   - `g_user_interrupt` è `volatile bool` invece di `_Atomic bool`.
2. **Cleanup nativo saltato su cancellazione** (`pm3/Pm3Client.kt`):
   - `finally` di `connect()` chiama `withContext(Dispatchers.IO)` senza `NonCancellable` -> salta `nativeClose()` su `CancellationException`.
   - `finally` di `exec()` chiama `poller.cancelAndJoin()` senza `NonCancellable`.
   - `abortNow()` non invoca `Pm3Native.nativeAbort()` / `stopRequested = true` prima di `nativeClose()`.
3. **Abort Bluetooth rotto durante comandi attivi** (`ui/console/Pm3ViewModel.kt#abortBluetoothIfActive`):
   - `if (busyFlow.value) return` impedisce di abortire una sessione Bluetooth se il link cade **mentre un comando è in esecuzione** (quando `busyFlow.value == true`). Va sostituito con un flag dedicato `disconnecting`.
4. **Timer `abortExecution()` e stato dead-end** (`ui/console/Pm3ViewModel.kt`):
   - Il job `delay(ABORT_GRACE_MS)` non viene cancellato se parte un nuovo comando, potendo azzerare `busyFlow` a metà comando. Inoltre, se `execMutex` rimane bloccato da un job nativo "wedged", anche `connect()` fallisce `execMutex.tryLock()`, rendendo impossibile riconnettersi senza riavvio.
5. **Connessione Bluetooth disabilitata dalla Console** (`ui/console/ConsoleScreen.kt:141`):
   - Il pulsante Connect controlla solo `device != null` (USB), ignorando `ConnectionMode.BLUETOOTH` + `settings.bluetoothAddress != null`.
6. **Bug tema nella Console** (`ui/theme/TerminalColors.kt:44` e `ui/console/ConsoleScreen.kt:99`):
   - Usano `isSystemInDarkTheme()` invece di `settings.themeMode`, invertendo i colori del testo rispetto allo sfondo quando il tema app differisce dal tema di sistema.
7. **Python `pm3.grabbed_output` sempre vuoto** (`python/pm3.py:37`):
   - Ritorna sempre `""` invece dell'ultimo output di `console()`, rompendo gli script upstream che leggono `p.grabbed_output`.
8. **Protocollo FeliCa Type 3 non conforme** (`nfc/inspect/hf/FelicaProbe.kt`):
   - Block List Element usa `0x00` invece di `0x80` (2-byte format), e `readNdef()` fa parsing Type 2 TLV su Block 0 invece di leggere l'Attribute Information Block (Block 0, `Ln` a byte 11..13) e l'NDEF raw da Block 1+.

### P1 — Performance, Sicurezza & UX
1. **Console non fedele al PC (ANSI nativo disabilitato)**:
   - Il client nativo filtra gli escape ANSI verso il grabber; l'app colora solo l'intera riga tramite regex con falsi positivi (es. `"0 failed"` in rosso).
2. **Performance Console O(N²) / O(N) per tick**:
   - `ConsoleLineBuffer.append()` fa `pending.delete(0, newline + 1)` in loop (O(N²)).
   - `Pm3ViewModel.appendLines()` copia l'intera lista da 5000 elementi ogni 90ms e `clearConsole()` non acquisisce `linesLock`.
   - `ConsoleScreen` usa `itemsIndexed(lines)` senza `key` stabile, invalidando `remember` su ogni shift della finestra.
3. **USB IPC overhead per pacchetto** (`pm3/UsbCdc.kt:252`):
   - `isAttached()` interroga `usbManager.deviceList` (Binder IPC) prima di **ogni** `bulkTransfer` invece che solo su timeout/errore (`result <= 0`).
4. **UpdateManager & Share limit**:
   - `UpdateManager.download()` fa doppia passata su disco per SHA-256, non limita i byte durante lo streaming e non fa throttling delle callback `onProgress`.
   - `shareText()` può andare in `TransactionTooLargeException` su log grandi (>500KB): condividere via file temporaneo + `FileProvider`.
   - Restringere `res/xml/file_paths.xml` alle sole sottocartelle condivisibili (non l'intera `filesDir`).
5. **Main-thread I/O & OOM guard**:
   - `SettingsScreen` legge 3 raw resource di licenza sincronamente sul main thread alla composizione.
   - `NfcRestoreViewModel.selectKeyFile()` usa `file.readBytes()` senza limite dimensione.
6. **Localizzazione inconsistente**:
   - Stringhe hardcoded in italiano in `BleSpp.kt`, `BluetoothSpp.kt`, `Pm3ViewModel.kt` e in inglese in `UpdateManager.kt`.

---

## 7. Roadmap di Evoluzione & Modernizzazione

### Fase 1 — Stabilità Critica & Sicurezza (Completata)
- [x] Fix JNI (`pm3_jni.c`: sanitizzazione UTF-8 sicura per `NewStringUTF`, `grabber_take_all()` dentro `g_api_lock`, `g_grab_state_lock`, `__atomic_store_n`, `g_session.supports_colors = true`).
- [x] Fix Coroutines (`Pm3Client.kt`: `NonCancellable` in `connect()` e `exec()`, `nativeAbort()` in `abortNow()`; `Pm3ViewModel.kt`: `disconnecting` dedicato per `abortBluetoothIfActive()`, cancellazione `abortGraceJob`, `clearConsole()` sotto `linesLock`, `persistHistory()` atomico, `stripAnsi()` prima di `TagParsers.parse()`).
- [x] Fix Python (`pm3.py`: implementato `grabbed_output`), Bluetooth Connect in `ConsoleScreen.kt`, e protocollo FeliCa Type 3 (`FelicaProbe.kt` + `HfProbeTest.kt`).
- [x] Ottimizzazioni P1: `UsbCdc.kt` (Binder IPC solo su timeout/errore), `ConsoleLineBuffer.kt` (O(N)), `UpdateManager.kt` (SHA-256 inline, cap streaming, HTTPS redirect check, progress throttle), `Common.kt` (`shareText()` via `FileProvider` >100 KB), `NfcRestoreViewModel.kt` (cap 2 MB key file), `SettingsScreen.kt` (caricamento licenze lazy su `Dispatchers.IO`).
- [ ] Isolare `prepare-pm3.sh` in una directory sotto `build/` per non sporcare il submodulo git e restringere `file_paths.xml` alle sole sottocartelle condivisibili.

### Fase 2 — Console PC-Fidelity, Scripting Interattivo & Redesign UI (Completata)
- [x] **Console PC-Fidelity & Workflow Unificato**:
  - `patches/pm3-console.patch` + `pm3_jni.c`: colori ANSI reali del client PC nel grabber (`!g_grabber_capture`), mirroring `core.console`, e bridge interattivo `io.read` / `print` per menu Lua (`nativeIsWaitingInput` / `nativeSendInput`).
  - `ConsoleAnsi.kt`: supporto SGR completo (16 colori, bright, background, 256-color `38;5;N`, TrueColor `38;2;R;G;B`, bold/dim/italic/underline/inverse, strip CSI non-SGR) + syntax highlighter token-level + fix falsi positivi `"0 failed"`.
  - `TerminalColors.kt` & `Theme.kt`: tema scuro nitido di default (`dynamicColor = false`), tema chiaro **Nordic Ocean Petrol** abbinato, TopBar & BottomBar ad alto contrasto separati dal contenuto con divider.
  - `ConsoleScreen.kt`: font default `8 sp`, tab `CMD` | `Strumenti PM3` (con Comandi Rapidi spostati dentro `Strumenti PM3`), banner interattivo per prompt script Lua, `ConsoleLine(id, raw)` con `key = { it.id }`, toggle Wrap / Wide, ricerca live con highlight, frecce cronologia ↑/↓, `TagResultCard` integrata.
- [x] **Phone NFC Toolkit — Salto in Avanti (Completata)**:
  - **Espansione chip Type 2 (`VersionInfo.kt`, `DumpModel.kt`, `MfuReader.kt`, `TagInspectViewModel.kt`)**: disambiguazione Ultralight AES (41p) vs NTAG213 (45p), riconoscimento Ultralight Nano, NTAG213F/TT, NTAG216F, NTAG I2C 1K/2K, NTAG 424 DNA; `MfuReader` esteso a tutta la famiglia (MF0UL11/21, UL AES, NTAG210/212/213/215/216) con `FAST_READ` (`0x3A`) e fallback per Ultralight / Ultralight C legacy senza `GET_VERSION`, dump automatico al primo tap anche via `NfcA`.
  - **Ispezione Profonda Type 2 (`Type2InspectProbe.kt`)**: lettura automatica firma ECC Originality 32B (`0x3C`), contatori + tearing (`0x39`/`0x3E`), static lock, CC NDEF, decodifica sicurezza `CFG0`/`CFG1` (`AUTH0`, `PROT`, `CFGLCK`, `AUTHLIM`, `NFC_CNT_EN`) e NDEF istantaneo.
  - **ISO 15693 Extended (`Iso15693.kt`, `Iso15693Probe.kt`, `OtherTagInfo.kt`)**: supporto comandi estesi (`0x30`/`0x31`/`0x32`/`0x33`/`0x3B`/`0x2C`) per tag >256 blocchi (es. ST25DV16K/64K), CC Type 5 a 8 byte (`0xE2`), identificazione modelli IC (ICODE SLIX/SLIX2, ST25TV/ST25DV, TI Tag-it HF-I) e vendor ISO/IEC 7816-6.
  - **Catalogo AID esteso (`AidCatalog.kt`)**: aggiunti PagoBANCOMAT, V Pay, RuPay, MIR, FIDO2/WebAuthn, OpenPGP Card, CIE 3.0 italiana, Calypso Italia / BIP.
  - **Nuovo strumento NDEF Writer / Formatter (`NdefBuilder.kt`, `NdefWriterViewModel.kt`, `NdefWriterScreen.kt`)**: scrittura URL/URI (con compressione prefissi), Testo UTF-8, credenziali Wi-Fi (WSC TLV), Cancella NDEF, formattazione automatica tag vergini (`NdefFormatable`) e read-back verification obbligatoria.
  - **Console APDU potenziata (`ApduConsoleScreen.kt`)**: 7 preset rapidi 1-tap (PPSE, NDEF, DESFire GetVersion/Next, eMRTD, Get Challenge, Calypso), colorazione SW per esito (`9000`/`9100` verde, `61xx`/`91AF` ciano, errori rosso), copia log con 1 tap.

### Fase 3 — Refactoring Architetturale & Protocolli (Sprint 3)
- [ ] **Decomporre `Pm3ViewModel.kt` (~1300 righe)** in componenti modulari:
  - `DeviceConnectionController`, `ConsoleController`, `FilesRepository`, `ScriptsRepository`, `UpdateController`.
- [ ] Completare verifica ISO 14443-3 per 7-byte UID in `MagicUidLayout.kt`.
- [ ] Validare su hardware reale tutti i punti `untested on hardware` in `docs/audit-2026-11.md` (BLE GATT, USB backoff, NFC pause-abort e backup).

### Fase 4 — Qualità Continua & Distribuzione su Larga Scala (Continuativa)
- [ ] Aggiungere **ktlint** / **detekt** + check automatico di parità chiavi `strings.xml` (EN/IT) in `.github/workflows/android.yml`.
- [ ] Aggiungere **Compose UI Test** (`createComposeRule`) per flussi critici: conferme distruttive, restore NFC, hex editor, console rendering ANSI.
- [ ] Aggiungere esportazione **Diagnostic Bundle** anonimizzato (escludendo dump/chiavi) e schermata di recovery guidata in caso di native stuck.

---

## 8. Checklist Obbligatoria per Ogni Pull Request

- [ ] `./gradlew :core:pm3:testDebugUnitTest -PskipNative=true -PskipSync=true` — 100% verde.
- [ ] `./gradlew :core:pm3:lintDebug -PskipNative=true -PskipSync=true` — zero errori/warning.
- [ ] `./gradlew :core:pm3:assembleRelease -PskipNative=true -PskipSync=true -PallowIncompleteRelease=true` — R8 verde.
- [ ] Parità 100% tra `res/values/strings.xml` e `res/values-it/strings.xml`, nessuna stringa hardcoded.
- [ ] Nuovi parser/protocolli/logica UI pura coperti da unit test JVM.
- [ ] Aggiornati `CHANGELOG.md` e (se rilevante) `docs/audit-2026-11.md` e `AGENTS.md`.
