# Situations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Named situations (sets of conditions over time, Bluetooth, charging, screen, Wi-Fi and Do Not Disturb) that any feature can ask "is one of mine active?", with an editor, a debug dump, and the assistant as first consumer.

**Architecture:** Pure model + evaluator (time passed in, unit-tested like `BluetoothTracker`), a `Signals` flow built from `SystemMonitor` plus new screen and Wi-Fi sources, and a `SituationEngine` in `AppGraph` that combines settings, signals and a time-edge ticker into `active: StateFlow<Set<String>>`. Situations persist as JSON in DataStore like `wolDevices`.

**Tech Stack:** Kotlin, coroutines/Flow, DataStore Preferences, `org.json`, Jetpack Compose (existing `SettingsComponents`), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-17-situations-design.md`

## Global Constraints

- minSdk 26, targetSdk 37; `java.time` is available.
- No polling and no periodic wake-ups: re-evaluate only on signal changes, settings changes, time-window edges, hold expiry, screen-on, and time/zone changes.
- All conditions in a situation must hold (AND); empty conditions = never active; `enabled = false` = never active.
- Time: `startMin == endMin` is the whole day; a window crossing midnight belongs to the day it starts on.
- `holdMinutes` range 0..30, default 0.
- A condition that can't be read (Wi-Fi SSID hidden) evaluates **false**, for both "connected" and "not connected", and the editor shows why.
- Bluetooth devices are matched by address first, then by name.
- JSON via `org.json`; unknown condition types are kept on load/save and evaluate false.
- No manifest receivers: runtime-registered receivers only (the accessibility service keeps the process alive).
- Commits use the repo-local identity (`git config user.email` must be the personal noreply address); end messages with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Unit tests: `./gradlew :app:testDebugUnitTest --tests '<Class>'`. Package for new code: `com.titus.dynamicisland.data.situations` (model, JSON, signals) and `com.titus.dynamicisland.domain` (evaluator, engine).

## Review Focus

1. A feature still holds the id of a deleted situation: `anyActive` ignores unknown ids (false), never crashes. Test in Task 4.
2. A situation is edited or disabled while active (or while its hold runs): the result changes at once; disabling cancels the hold. Test in Task 2.
3. The car is renamed in Bluetooth settings: the condition still matches by address. Test in Task 2.
4. Clock or time zone changes (travel, DST): edges are recomputed from the new local time. `nextEdge` test across a DST switch in Task 2; `ACTION_TIME_CHANGED`/`ACTION_TIMEZONE_CHANGED` re-evaluation in Task 4.
5. Wi-Fi unreadable with a "not connected to Home" condition: false, not true. Test in Task 2.

---

### Task 1: Model, JSON, settings field

**Files:**
- Create: `app/src/main/java/com/titus/dynamicisland/data/situations/Situation.kt`
- Create: `app/src/main/java/com/titus/dynamicisland/data/situations/SituationsJson.kt`
- Modify: `app/src/main/java/com/titus/dynamicisland/data/settings/IslandSettings.kt` (add fields)
- Modify: `app/src/main/java/com/titus/dynamicisland/data/settings/SettingsRepository.kt` (keys, read, write; follow `wolDevices`)
- Test: `app/src/test/java/com/titus/dynamicisland/data/situations/SituationsJsonTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class Situation(val id: String, val name: String, val enabled: Boolean = true,
                       val conditions: List<Condition> = emptyList(), val holdMinutes: Int = 0)
  sealed interface Condition {
      data class Time(val startMin: Int, val endMin: Int, val days: Set<java.time.DayOfWeek>) : Condition
      data class Bluetooth(val devices: List<BtRef>, val anyCar: Boolean, val connected: Boolean) : Condition
      data class Charging(val charging: Boolean) : Condition
      data class Screen(val state: ScreenState) : Condition
      data class Wifi(val ssids: List<String>, val connected: Boolean) : Condition
      data class Dnd(val on: Boolean) : Condition
      /** Kept verbatim from JSON this build doesn't understand. */
      data class Unknown(val json: String) : Condition
  }
  data class BtRef(val address: String, val name: String)
  enum class ScreenState { ON, OFF, UNLOCKED, LOCKED }
  object SituationsJson { fun toJson(list: List<Situation>): String; fun fromJson(json: String?): List<Situation>? }
  // IslandSettings:
  val situations: List<Situation> = emptyList()
  val situationsTellAssistant: Boolean = true
  ```
- JSON shape: array of `{id,name,enabled,holdMinutes,conditions:[{type:"time"|"bluetooth"|"charging"|"screen"|"wifi"|"dnd", …}]}`; days as ISO numbers 1..7; `Unknown` re-emits its original object.

- [ ] **Step 1: Write the failing tests** in `SituationsJsonTest`:
  - `roundTripAllConditionTypes`: a list with one situation per condition type (Time 22:00–02:00 Fri/Sat, Bluetooth with `BtRef("AA:BB","Golf")` + `anyCar=false`, connected=false, Charging(true), Screen(UNLOCKED), Wifi(["Home"], true), Dnd(false)), `holdMinutes = 5`, `enabled = false` → `fromJson(toJson(x)) == x`.
  - `unknownConditionSurvives`: `fromJson("""[{"id":"a","name":"A","enabled":true,"holdMinutes":0,"conditions":[{"type":"geofence","lat":1}]}]""")` yields `Condition.Unknown`, and `toJson` of that contains `"geofence"`.
  - `garbageGivesNull`: `fromJson("not json") == null`, `fromJson(null) == null`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*SituationsJsonTest'` → FAIL (unresolved references).
- [ ] **Step 3: Implement** the model and `SituationsJson` (`org.json`, same style as `NetworkActionsJson`); `holdMinutes` coerced to 0..30 on load.
- [ ] **Step 4: Add the settings fields** and DataStore keys `situations` (string) and `situations_tell_assistant` (boolean), read with `SituationsJson.fromJson(...) ?: d.situations`.
- [ ] **Step 5: Run** the test → PASS; `./gradlew :app:compileDebugKotlin` → succeeds.
- [ ] **Step 6: Commit** `Add the situation model and its JSON storage`.

### Task 2: Evaluator and hold state (pure)

**Files:**
- Create: `app/src/main/java/com/titus/dynamicisland/data/situations/SignalSnapshot.kt`
- Create: `app/src/main/java/com/titus/dynamicisland/domain/SituationEvaluator.kt`
- Modify: `app/src/main/java/com/titus/dynamicisland/data/system/BluetoothTracker.kt` (add `addresses`)
- Test: `app/src/test/java/com/titus/dynamicisland/domain/SituationEvaluatorTest.kt`, extend `BluetoothTrackerTest`

**Interfaces:**
- Consumes: Task 1 model.
- Produces:
  ```kotlin
  // BluetoothTracker.kt: default keeps existing call sites compiling
  data class BluetoothDeviceInfo(val name: String, val kind: BluetoothKind, val battery: Int? = null,
                                 val addresses: Set<String> = emptySet())
  data class SignalSnapshot(val bluetooth: List<BluetoothDeviceInfo> = emptyList(), val charging: Boolean = false,
      val dndOn: Boolean = false, val screenOn: Boolean = true, val locked: Boolean = false,
      val wifiSsid: String? = null, val wifiReadable: Boolean = true)
  object SituationEvaluator {
      fun holds(c: Condition, s: SignalSnapshot, now: java.time.LocalDateTime): Boolean
      fun rawActive(sit: Situation, s: SignalSnapshot, now: java.time.LocalDateTime): Boolean
      /** Next local time any Time condition of an enabled situation starts or ends, or null. */
      fun nextEdge(sits: List<Situation>, now: java.time.ZonedDateTime): java.time.ZonedDateTime?
  }
  /** Applies holdMinutes; time is passed in (no clock inside), like BluetoothTracker. */
  class SituationState {
      fun update(sits: List<Situation>, s: SignalSnapshot, now: java.time.ZonedDateTime): Set<String>
      /** Earliest of nextEdge and the next hold expiry, or null. */
      fun nextWake(sits: List<Situation>, now: java.time.ZonedDateTime): java.time.ZonedDateTime?
  }
  ```
- Semantics: `ScreenState.ON` = screenOn (locked or not); `OFF` = !screenOn; `UNLOCKED` = screenOn && !locked; `LOCKED` = locked. Bluetooth match: device's `addresses` contains ref address, or name equals ref name; `anyCar` matches any `kind == CAR`; `connected=false` holds when no listed device (and, with `anyCar`, no car) is connected. Wi-Fi: `!wifiReadable` → false; else `connected` ⇔ `wifiSsid in ssids` (SSID compared without surrounding quotes). Hold: a situation stays in the result until `lastRawTrue + holdMinutes`; disabled or removed situations leave immediately.

- [ ] **Step 1: Write the failing tests** (`SituationEvaluatorTest`), each a few assertions:
  - `timeWindowSameDay` (09:00–23:00 Mon: Mon 08:59 false, 09:00 true, 22:59 true, 23:00 false).
  - `timeWindowCrossesMidnight` (22:00–02:00 Fri only: Fri 23:00 true, Sat 01:59 true, Sat 23:00 false, Thu→Fri 01:00 false).
  - `wholeDayWhenStartEqualsEnd`.
  - `bluetoothByAddressSurvivesRename` (ref `BtRef("AA","Golf")`, connected device name "VW Golf", addresses {"AA"} → true).
  - `bluetoothAnyCarAndNotConnected`.
  - `screenStates` (all four against on/locked combos).
  - `wifiUnreadableIsFalseEitherWay` (connected=true and connected=false both false when `wifiReadable=false`).
  - `allConditionsMustHold`, `emptyOrDisabledNeverActive`, `unknownConditionIsFalse`.
  - `holdKeepsActiveThenExpires` (holdMinutes=5: raw false at t → still active at t+4m59s, gone at t+5m).
  - `disablingCancelsHold`.
  - `nextEdgeFindsStartAndEnd` and `nextEdgeAcrossDst` (zone `Europe/Berlin`, window 02:30–03:30 on 2026-03-29: the next edge is a real instant, no exception).
  - `BluetoothTrackerTest.connectedCarriesAddresses`: after `onConnected("Buds","AA",…)`, `connected().single().addresses == setOf("AA")`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*SituationEvaluatorTest' --tests '*BluetoothTrackerTest'` → FAIL.
- [ ] **Step 3: Implement** `SignalSnapshot`, `addresses` in `BluetoothTracker.connected()` (from `links`; keep the last known addresses while a disconnect is debounced), `SituationEvaluator`, `SituationState`. Edges: for each Time condition, candidate start/end on today and the next 7 days at the condition's local times, resolved with `ZonedDateTime.of(localDate, localTime, zone)` (gap times shift forward), smallest one after `now`.
- [ ] **Step 4: Run** the tests → PASS; also run the full unit suite `./gradlew :app:testDebugUnitTest` → PASS (the `BluetoothDeviceInfo` change must not break `IslandStateMachineTest`).
- [ ] **Step 5: Commit** `Evaluate situations: conditions, time edges and hold`.

### Task 3: Signal sources (screen, Wi-Fi) and the snapshot flow

**Files:**
- Create: `app/src/main/java/com/titus/dynamicisland/data/situations/Signals.kt`
- Modify: `app/src/main/java/com/titus/dynamicisland/DynamicIslandApp.kt` (`AppGraph`: `val signals = Signals(app, scope, system)`)

**Interfaces:**
- Consumes: `SystemMonitor.state` (`bluetooth`, `charging`, `dndOn`), `SignalSnapshot`.
- Produces:
  ```kotlin
  class Signals(context: Context, scope: CoroutineScope, system: SystemMonitor) {
      val snapshot: StateFlow<SignalSnapshot>
      /** Emits on screen-on, time set and time-zone change: moments to re-check time conditions. */
      val recheck: SharedFlow<Unit>
      /** SSIDs seen connected since install, newest first, for the Wi-Fi picker (prefs "situations"). */
      fun seenSsids(): List<String>
      fun start()   // idempotent; registers receivers and the network callback
  }
  ```
- Screen: runtime receiver for `ACTION_SCREEN_ON`, `ACTION_SCREEN_OFF`, `ACTION_USER_PRESENT`; `locked` from `KeyguardManager.isKeyguardLocked` on each of those; initial values from `PowerManager.isInteractive` and the keyguard. Same receiver handles `ACTION_TIME_CHANGED`, `ACTION_TIMEZONE_CHANGED` → `recheck`.
- Wi-Fi: `ConnectivityManager.registerNetworkCallback` for `TRANSPORT_WIFI`; on API 31+ the callback is constructed with `FLAG_INCLUDE_LOCATION_INFO`; SSID from `(capabilities.transportInfo as? WifiInfo)?.ssid`; `"<unknown ssid>"` or missing location permission → `wifiReadable = false`; lost network → `wifiSsid = null`, readable stays as last known.
- `start()` is called where `SystemMonitor` is started today (find its `start()` call site and add `signals.start()` next to it).

- [ ] **Step 1: Implement** `Signals` as above.
- [ ] **Step 2: Wire** it into `AppGraph` and the start call site.
- [ ] **Step 3: Verify** `./gradlew :app:compileDebugKotlin` succeeds (behaviour is verified on the phone in Task 4's dump).
- [ ] **Step 4: Commit** `Collect screen and Wi-Fi signals for situations`.

### Task 4: Engine, consumer API, change log, dumpsys

**Files:**
- Create: `app/src/main/java/com/titus/dynamicisland/domain/SituationEngine.kt`
- Modify: `app/src/main/java/com/titus/dynamicisland/domain/ConversationTrace.kt` (file name and log tag as constructor parameters, defaults unchanged)
- Modify: `app/src/main/java/com/titus/dynamicisland/DynamicIslandApp.kt` (`val situations = SituationEngine(app, scope, settings, signals)`, started with the others)
- Modify: `app/src/main/java/com/titus/dynamicisland/service/IslandAccessibilityService.kt` (`dump`: `situations` command, any build, beside `trace`)
- Test: `app/src/test/java/com/titus/dynamicisland/domain/SituationEngineTest.kt`

**Interfaces:**
- Consumes: `Signals.snapshot`, `Signals.recheck`, `SettingsRepository.settings`, `SituationState`.
- Produces:
  ```kotlin
  data class SituationChange(val id: String, val name: String, val entered: Boolean, val at: Long)
  class SituationEngine(context: Context, scope: CoroutineScope, settings: SettingsRepository, signals: Signals,
                        clock: () -> java.time.ZonedDateTime = { java.time.ZonedDateTime.now() }) {
      val active: StateFlow<Set<String>>
      val changes: SharedFlow<SituationChange>
      fun anyActive(ids: Set<String>): Flow<Boolean>          // unknown ids count as inactive
      fun activeNames(): List<String>
      fun dump(): String                                       // for dumpsys
      fun start()
  }
  /** The engine's Android-free part; the engine feeds it and the tests drive it directly. */
  class SituationEngine.Core {
      val active: Set<String>
      /** Re-evaluates and returns what entered or left since the last call. */
      fun evaluate(sits: List<Situation>, s: SignalSnapshot, now: java.time.ZonedDateTime): List<SituationChange>
      fun nextWake(sits: List<Situation>, now: java.time.ZonedDateTime): java.time.ZonedDateTime?
  }
  /** Ids no situation has (deleted) count as inactive. */
  fun anyActive(active: Set<String>, ids: Set<String>): Boolean   // top-level, used by SituationEngine.anyActive
  /** Which feature uses which situations, for "Used by …" in the editor. */
  object SituationUsers { fun usersOf(id: String, s: IslandSettings): List<String> }
  ```
- Re-evaluation triggers: settings `situations` change, `snapshot` change, `recheck`, and a single scheduled wake at `SituationState.nextWake`: coroutine `delay` plus an inexact `AlarmManager.setAndAllowWhileIdle(RTC_WAKEUP, …)` to a runtime-registered receiver (explicit package, `RECEIVER_NOT_EXPORTED`), each replacing the previous one.
- Every enter/exit is emitted on `changes` and logged via `ConversationTrace(context, "situations-trace.txt", "Situations")` as `entered "Driving"` / `left "Driving" (hold 5 min over)`.
- `dump()` prints: the snapshot (one line per field), each situation with ✓/✗ per condition and its hold state, the next wake time, then the change log.
- `SituationUsers` in this plan registers only `"Assistant context"` (all situations, when `situationsTellAssistant`).

- [ ] **Step 1: Write the failing tests** (`SituationEngineTest`, plain JUnit against `SituationEngine.Core` and `anyActive`, fixed `ZonedDateTime`s):
  - `entersAndLeavesWithSnapshot`: charging condition; `evaluate` with charging → `active == {id}`, returns one change with `entered = true`; again uncharged → `active` empty, one change with `entered = false`.
  - `anyActiveIgnoresUnknownIds`: `anyActive(setOf("a"), setOf("deleted")) == false`, `anyActive(setOf("a"), setOf("a","deleted")) == true`.
  - `editWhileActiveAppliesAtOnce`: active situation; `evaluate` with the same id but `enabled = false` → `left` change in that same call.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*SituationEngineTest'` → FAIL.
- [ ] **Step 3: Implement** `SituationEngine` (pure `Core` holding `SituationState` and producing changes; the Android part only schedules wakes and wires flows), `SituationUsers`, the `ConversationTrace` parameters, and the `situations` dump command.
- [ ] **Step 4: Run** the tests → PASS.
- [ ] **Step 5: Verify on the phone:** `./gradlew :app:installDebug`, wait ~10 s, then via `~/Library/Android/sdk/platform-tools/adb shell dumpsys activity service com.titus.dynamicisland/.service.IslandAccessibilityService situations` → prints the snapshot with the real charging state, screen on/unlocked, and the current Wi-Fi SSID (or "unreadable" with the reason). With no situations defined it says so.
- [ ] **Step 6: Commit** `Run situations live and expose them to features`.

### Task 5: The assistant knows the active situations

**Files:**
- Modify: `app/src/main/java/com/titus/dynamicisland/domain/AssistantContext.kt` (`situation()`)
- Modify: `app/src/main/java/com/titus/dynamicisland/domain/AssistantController.kt` (pass the engine into `AssistantContext`)
- Modify: `app/src/main/java/com/titus/dynamicisland/DynamicIslandApp.kt` (construction order: engine before assistant)

**Interfaces:**
- Consumes: `SituationEngine.activeNames()`, `IslandSettings.situationsTellAssistant`.
- Produces: in `AssistantContext.situation()`, when the setting is on and names are non-empty, the line `Active situations (set up by the user): Driving, Daytime at home.`

- [ ] **Step 1: Implement** the line (first in the list) and the wiring.
- [ ] **Step 2: Verify** `./gradlew :app:installDebug`; with a situation "Testlauf" (Charging: yes) and the phone on the charger, `… dumpsys … ask "Welche Situationen sind gerade aktiv?"`, then `… dumpsys … trace` → the answer names "Testlauf". Turn "Tell the assistant" off and ask again → it no longer knows.
- [ ] **Step 3: Commit** `Tell the assistant which situations are active`.

### Task 6: Situations settings screens

**Files:**
- Create: `app/src/main/java/com/titus/dynamicisland/ui/settings/SituationScreens.kt`
- Modify: `app/src/main/java/com/titus/dynamicisland/ui/settings/SettingsApp.kt` (routes `situations`, `situations/{id}`; breadcrumb map entries like `ASSISTANT_LOOK`)
- Modify: `app/src/main/java/com/titus/dynamicisland/ui/settings/Screens.kt` (`HomeScreen`: a `NavRow("Situations", "When features switch on: time, Bluetooth, Wi-Fi …", Icons.Rounded.Tune, …)` after "Quick tools")

**Interfaces:**
- Consumes: `SettingsHandle.update`, `IslandSettings.situations`, `SituationEngine.active` (collected for live dots), `Signals.snapshot` and `SituationEvaluator.holds` (per-condition live ✓/✗), `Signals.seenSsids()`, `SituationUsers.usersOf`, bonded devices via `BluetoothAdapter.bondedDevices` (needs `BLUETOOTH_CONNECT`, already declared; reuse the app's permission request helper in `Permissions.kt`).
- Produces: `@Composable fun SituationsScreen(s: SettingsHandle, onOpen: (String) -> Unit, onBack: () -> Unit)` and `@Composable fun SituationEditScreen(s: SettingsHandle, id: String, onBack: () -> Unit)`. A new situation is created with `UUID.randomUUID().toString()` and opened immediately.

Screen contents (from the spec): list rows = name, live dot, one-line summary of conditions, enable switch, "+ Add situation"; edit = name field, condition rows with live ✓/✗ and an unreadable reason ("Wi-Fi name hidden: turn on location"), "+ Add condition" dialog with the six types, `IntSliderSetting("Stays active after it ends", holdMinutes, 0..30, "min")`, "Used by …", delete (`TermDialog` warning when used); list screen footer switch `situationsTellAssistant` ("Tell the assistant"). Condition editors: time = two time pickers + weekday chips; Bluetooth = bonded devices as `PickRow`s + "Any car" + connected/not connected `SegmentedSetting`; charging/DnD = `SegmentedSetting`; screen = four-way `SegmentedSetting`; Wi-Fi = seen SSIDs as `PickRow`s + `TermField` for manual entry + connected/not connected.

- [ ] **Step 1: Implement** the two screens, the routes and the home entry.
- [ ] **Step 2: Verify** `./gradlew :app:installDebug`; on the phone create "Driving" (Bluetooth: the car, connected) and "Daytime" (09:00–23:00, all days; car not connected); the list shows the live dots matching reality, and `… dumpsys … situations` agrees. Rename the car's situation, toggle it off and on, delete one: no crash, the dump follows.
- [ ] **Step 3: Commit** `Add the situations editor`.

### Task 7: Wrap-up

- [ ] **Step 1: Run** `./gradlew :app:testDebugUnitTest` → all PASS; `./gradlew :app:lintDebug` shows no new errors in the touched files.
- [ ] **Step 2: Update** the spec's "Left to the builder" with what was decided (where the screen source lives, SSID history storage).
- [ ] **Step 3: Commit** `Note the situations build decisions in the spec`.
