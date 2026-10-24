# DJI Match Logger — Android

Android app that combines match-log functionality (score, cards, periods,
export) with direct BLE control of two DJI Osmo Action cameras. Built because
a dedicated embedded display was not readable enough outdoors in bright
sunlight; a phone screen is.

## Toolchain

| What | Version |
|---|---|
| Android Studio | 2026.1.4.7 |
| Gradle | 9.7.1 (via wrapper) |
| AGP | 9.4.0 |
| Kotlin | 2.2.10 (shipped with AGP) |

The Android SDK location lives in `local.properties` (not committed).

### The bundled JBR is enough

Use the JDK that Android Studio ships (JBR 25). No separate JDK is needed.

### AGP 9 provides Kotlin itself

Since AGP 9.0 Kotlin support is built into the plugin. The separate
`org.jetbrains.kotlin.android` plugin must **not** be applied any more; if it
is, the build fails with "The 'org.jetbrains.kotlin.android' plugin is no
longer required for Kotlin support since AGP 9.0".

Two consequences that are easy to get wrong:

- **The Kotlin version follows AGP.** AGP 9.4.0 brings KGP 2.2.10. The
  `kotlin` entry in `libs.versions.toml` only serves the Compose compiler
  plugin and must stay equal to what AGP brings, otherwise that plugin drifts
  out of step with the Kotlin compiler.
- **`kotlinOptions {}` no longer exists.** It is replaced by
  `kotlin { compilerOptions {} }`, but that is not needed here: `jvmTarget`
  falls back to `android.compileOptions.targetCompatibility`, which is
  already 17.

## Testing: emulator vs. physical device

The Android emulator has **no Bluetooth radio**. Anything that touches the
cameras cannot be tested there:

| | Compose Preview | Emulator | Physical device |
|---|---|---|---|
| Looking at layout | ✅ | ✅ | ✅ |
| Interaction, navigation, state | ❌ | ✅ | ✅ |
| BLE scan / GATT / record commands | ❌ | ❌ | ✅ |
| Readability in sunlight | ❌ | ❌ | ✅ |

Workflow: UI work in Preview and emulator, everything involving the cameras
on a real device over USB debugging. The same goes for sunlight readability —
the whole reason for this app.

## BLE protocol

The contract with the cameras is in
[`docs/ble-protocol.md`](docs/ble-protocol.md). It also covers the
behaviour that was established against real cameras (handshake, status
subscription, the two `device_id`s, write queueing, framing, sleep, SD card,
frame sync) — everything in it fails silently if you miss it.

Points that are easy to get wrong when implementing:

- **CRC16/CRC32 are DJI-specific variants** with init value `0x3AA3`, not
  standard CCITT/CRC32. Use the tables in `ble/DjiCrc.kt`; deriving them
  yourself almost guarantees a silently ignored command.
- **Full GATT discovery is required** — do not filter on service `0xFFF0`
  alone; some firmwares otherwise refuse notifications.
- **Do not force pairing/bonding.** The cameras work without BLE encryption.
- **Recording confirmation does not come from the command reply**, but from
  the next `0x1D02` status push with `camera_status == 0x03` (or `0x05`).
- **One active BLE link per camera**, and each camera has its own sequence
  number — confirm both separately.
- **A GATT link is not a protocol connection**: the `0x00/0x19` handshake
  must complete before the camera executes anything.

## Field use

Things that shape the design and cannot be derived from the code:

- **The operator starts recording themselves, well before kick-off** —
  usually a few minutes. That is deliberate: if something goes wrong with a
  camera there is still time to intervene. The app must therefore **never
  tie recording automatically to kick-off**. What it should do is log
  `video_start` as soon as the cameras confirm recording in their status
  push, so the gap between recording start and kick-off is in the log.
- **The look and feel is fixed by the operator's muscle memory.** Panels with
  titles, home in blue and away in orange, cards in the colour of the card.
  The palette in `ui/theme/Color.kt` encodes that. Do not deviate from it
  without discussion: the operator's hands know where the buttons are.
- **Adjust the look in the editor, not on the device.** All dimensions are in
  `ui/match/MatchMetrics.kt`, all colours in `ui/theme/Color.kt`, and
  `ui/match/MatchPreviews.kt` draws the screen in Android Studio's design
  pane. Change a number and the preview redraws — no build, no device. That is
  why the screen is split into `MatchScreen` (attached to the ViewModel) and
  `MatchContent` (plain `Match` + `MatchActions`); only the latter can be
  previewed.
- **The match screen is used in landscape.** There the screen is ~400dp high
  and nothing fits by itself; the layout is panels side by side and every
  button height is counted. Portrait works too, but is not what it was
  designed for.

## Files the app writes

| File | Where | What |
|---|---|---|
| `teams.json` | `getExternalFilesDir(null)` | `{"teams":[...]}` — team names, editable by hand on a PC. A bare array is also read. Unreadable content is **not** overwritten. |
| `matches/<date>_<time>-<home>-vs-<away>.json` | `Documents/DJIMatchLogger` | The match log, opened at kick-off and rewritten after every change. |

Two things about the match file that you should not rearrange:

- **Every event has two times.** `clock` is the match clock (what you write a
  report with), `time` is the time of day (what you match camera footage
  against — the footage knows nothing about periods or stoppages).
- **The time of day is derived, not stored.** The model runs on a monotonic
  clock so an NTP correction in the middle of a match cannot shift anything;
  the wall clock is anchored once, when the file is opened.

Sharing goes through a `FileProvider` with authority `${applicationId}.files`;
Android has rejected `file://` URIs since API 24.

## Conventions

- Kotlin, Jetpack Compose, Material3
- `minSdk 26`, `targetSdk 35`, `compileSdk 37`, JVM target 17
- `compileSdk` and `targetSdk` deliberately differ: `compileSdk 37` is a
  requirement of the current AndroidX libraries and only determines which
  APIs you compile against. Leaving `targetSdk` at 35 is a separate choice —
  that is what determines runtime behaviour, including how strict the BLE
  permissions are.
- Package: `com.djimatchlogger`
- All user-visible text lives in `strings.xml`, in English
- Versions are centralised in [`gradle/libs.versions.toml`](gradle/libs.versions.toml)
- Manifest permissions are already split around API 31 (`BLUETOOTH_SCAN` with
  `neverForLocation`) — see the comment blocks in `AndroidManifest.xml`
