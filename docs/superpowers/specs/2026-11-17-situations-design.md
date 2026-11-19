# Situations: a shared condition engine

Part 1 of 3 on the way to voice activation:

1. **Situations** (this document): named sets of conditions any feature can switch on.
2. Wake-word listening, active only in the situations the user picks (openWakeWord, on device).
3. Training the wake phrase inside the app (on-phone training spike first; PC training as the fallback).

## Why

The user wants hands-free activation, but rejected an always-listening microphone earlier: the green
microphone dot and the battery drain all day were too high a price. Listening only in chosen situations
("09:00 to 23:00 and the car isn't connected", "the car is connected") limits that price to moments where it
doesn't matter. The user wants these triggers fine-grained, and wants the system built so other features can
use the same situations later. Model: MacroDroid / Samsung Modes & Routines.

## Goals

- The user defines **named situations** once ("Daytime at home", "Driving"), each a set of conditions.
- Features refer to situations by id and get a live yes/no: "is any of my situations active?"
- Conditions in the first version:
  - **Time window** with weekdays; windows may cross midnight (22:00 to 02:00).
  - **Bluetooth**: a chosen device (or several, any of them), or "any car", connected or not connected.
  - **Charging**: yes or no.
  - **Screen**: on, off, unlocked, or locked.
  - **Wi-Fi**: connected to one of the chosen networks, or not connected to any of them.
  - **Do Not Disturb**: on or off.
- The editor shows live which situations, and which of their conditions, are true right now.
- Adding a new condition type later means adding a source plus a condition class, without touching consumers.

## Non-goals

- Situations don't run actions on their own (no "when Driving starts, turn on DnD"). The engine exposes
  enter/exit changes so actions could be added later, but this version only answers "active?".
- No location or geofence condition yet; Wi-Fi covers "at home" for now.
- No OR inside a situation. OR is a feature using several situations; a single condition can list several
  devices or networks ("any of").

## Anti-goals (would make it a failure even if it works)

- The engine itself costs noticeable battery: no polling, no wake-ups beyond time-window edges.
- A situation silently misreports: if a condition can't be read (permission missing, location off for Wi-Fi),
  the editor must say so instead of quietly showing "false".
- Bluetooth hiccups (a car dropping for a few seconds at a petrol station) flip situations on and off.

## Approaches considered

- **A. Shared, named situations (chosen by the user).** Defined once, referenced by features. Reuse is the
  point; the cost is one level of indirection and that editing a situation affects every feature using it
  (the editor shows "Used by …").
- **B. Rules stored per feature**, sharing only the editor and the engine. Simpler per screen, but
  "Driving" would be rebuilt for every feature. Rejected: works against reuse.
- **C. Leave the triggering to Samsung Routines / MacroDroid** via intents the app exposes. No in-app
  editor and nothing reusable inside the app; rejected.

## Design

### Model (`data/situations/`)

```kotlin
data class Situation(
    val id: String,               // UUID, stable across renames
    val name: String,
    val enabled: Boolean = true,
    val conditions: List<Condition>,   // all must hold; empty = never active
    val holdMinutes: Int = 0,     // stays active this long after its conditions stop holding
)

sealed interface Condition {
    data class Time(val startMin: Int, val endMin: Int, val days: Set<DayOfWeek>) : Condition
    data class Bluetooth(val devices: List<BtRef>, val anyCar: Boolean, val connected: Boolean) : Condition
    data class Charging(val charging: Boolean) : Condition
    data class Screen(val state: ScreenState) : Condition   // ON, OFF, UNLOCKED, LOCKED
    data class Wifi(val ssids: List<String>, val connected: Boolean) : Condition
    data class Dnd(val on: Boolean) : Condition
}
data class BtRef(val address: String, val name: String)
```

- Stored as JSON in one DataStore string key, the same way `wolDevices`/`httpActions` are
  (`org.json`, a `SituationsJson` helper next to `NetworkActionsJson`), as `IslandSettings.situations`.
- Unknown condition types in stored JSON are kept but evaluate false and show as "unsupported" (forward
  compatibility if a build is rolled back).
- **Time semantics:** `startMin == endMin` means the whole day. A window crossing midnight belongs to the day
  it starts on (Friday 22:00 to 02:00 is active Friday night into Saturday morning).

### Signals (`data/situations/Signals.kt`)

One `StateFlow<SignalSnapshot>` the engine reads; time isn't in it (see below).

```kotlin
data class SignalSnapshot(
    val bluetooth: List<BluetoothDeviceInfo>,  // from SystemMonitor.state (already debounced by BluetoothTracker)
    val charging: Boolean,                     // SystemMonitor.state
    val dndOn: Boolean,                        // SystemMonitor.state
    val screenOn: Boolean, val locked: Boolean,// new: SCREEN_ON/OFF, USER_PRESENT + KeyguardManager
    val wifiSsid: String?,                     // new: ConnectivityManager network callback
    val wifiReadable: Boolean,                 // false when location permission/service blocks the SSID
)
```

- Built from `SystemMonitor.state` plus two small new sources (screen, Wi-Fi). New sources go here, not
  into consumers.
- Wi-Fi: Android only reveals the SSID with location permission granted and location services on (API 31+:
  `NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)`). When it can't be read, `wifiReadable = false`, Wi-Fi
  conditions evaluate false, and the editor shows why. The SSIDs seen while the app runs are remembered to
  offer them in the picker.

### Engine (`domain/SituationEngine.kt`)

- Pure evaluation, unit-tested: `Evaluator.holds(condition, snapshot, now)` and
  `Evaluator.nextEdge(situations, now)` (the next minute any time window starts or ends).
- `SituationEngine` (in `AppGraph`) combines the situations from settings, the signals, and a time ticker
  into `active: StateFlow<Set<String>>` (ids of active situations, after `holdMinutes`).
- Time ticker: re-evaluates at the next time-window edge only. Coroutine `delay` while the process is awake;
  an inexact `AlarmManager.setAndAllowWhileIdle` as a backstop for Doze, plus a re-evaluation on every
  screen-on. A start a few minutes late while the phone sleeps is acceptable (my call; no exact-alarm
  permission).
- Consumer API:
  ```kotlin
  fun anyActive(ids: Set<String>): Flow<Boolean>      // distinctUntilChanged
  val changes: SharedFlow<SituationChange>            // entered / left, for future actions and the log
  ```
  Features store the ids they use in their own settings (part 2: `wakeWordSituations: Set<String>`).
- Usage registry: a small `SituationUsers` list maps feature name to "which ids does it use", read from
  settings, so the editor can show "Used by: Wake word" and warn before deleting. Part 1 registers only the
  assistant-context consumer below.

### First consumer: the assistant knows the situation (my call)

`AssistantContext.situation()` adds a line "Active situations: Driving" when any are active, behind a switch
on the Situations screen (default on). It proves the plumbing end to end and is cheap; the user can strike it.

### Settings UI (`ui/settings/SituationScreens.kt`)

- Top-level entry "Situations" on the settings home (it serves several features, so not under Assistant).
- List screen: each situation with a live dot (active now), its conditions in one line, an enable switch.
  "+ Add situation".
- Edit screen: name, conditions (each row shows a live check/cross and, if unreadable, the reason), "+ Add
  condition" (sheet with the six types), "Stays active after it ends" slider (0 to 30 min), "Used by …",
  delete (with a warning when used).
- Condition editors: time (two time pickers + weekday chips), Bluetooth (bonded devices list via
  `BLUETOOTH_CONNECT` + "any car" + connected/not connected), charging/DnD (segmented), screen (four-way
  segmented), Wi-Fi (seen networks + manual entry, connected/not connected; shows the location requirement
  if needed).

### Debugging

- `dumpsys … IslandAccessibilityService situations` (any build): the current snapshot, each situation with
  each condition's result, hold timers, the next time edge, and the last ~30 enter/exit changes with
  timestamps (kept on disk like `ConversationTrace`, since logcat only holds minutes).

### Tests

- `Evaluator`: time windows (normal, crossing midnight, whole day, weekday boundaries), Bluetooth (device by
  address, any car, not connected), screen states, Wi-Fi unreadable, AND of several conditions, hold timing,
  `nextEdge`.
- `SituationsJson`: round trip, unknown types kept.

## Decided while building

- The screen and Wi-Fi sources live in `Signals` (`data/situations/Signals.kt`), beside `SystemMonitor`, which
  stays untouched apart from Bluetooth addresses (`BluetoothDeviceInfo.addresses`).
- Seen SSIDs: SharedPreferences `situations`, key `seen_ssids`, newest first, at most 20.
- Wi-Fi needs `ACCESS_NETWORK_STATE` (added to the manifest); granting location in the Wi-Fi editor re-registers
  the network callback so the name is read at once.
- The hold counts from the evaluation that first finds the conditions no longer holding.
- `SituationUsers` lists only features that store situation ids; the assistant context reads all active
  situations and isn't listed, so deleting doesn't warn about it.
- Readable texts for conditions (`SituationText`) serve both the editor and `dumpsys … situations`.
- Time pickers are the platform `TimePickerDialog` (24 h).
