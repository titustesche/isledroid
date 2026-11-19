# Wake word: "Hey GLaDOS", only in chosen situations

Part 2 of 3 on the way to voice activation (part 1, situations, is merged; part 3 is training the phrase in the
app). See `2026-11-17-situations-design.md`.

## Why

The user wants to call the assistant hands-free. An always-listening microphone was rejected earlier: a
permanent green microphone dot and battery drain all day. Listening only while one of the situations the user
picks is active (driving, daytime at home) limits that price to moments where it's worth it.

## Goals

- While the feature is on, a wake-word model is imported, and one of the picked situations is active, the phone
  listens on device for the wake phrase. Nothing leaves the phone before the phrase is heard.
- On the phrase: a short sound, the island opens listening, and a normal assistant conversation runs, exactly as
  if it had been opened by hand. Afterwards listening for the phrase resumes.
- Default phrase **"Hey GLaDOS"**. No "Hey Jarvis" or other stock phrase ships as a default.
- Listening starts and stops by itself as situations change, also with the screen off, while locked, and after a
  reboot, without opening the app.
- Two ways to talk:
  - **Pause (default):** "Hey GLaDOS", sound, then speak.
  - **One breath (experimental switch):** "Hey GLaDOS, stell einen Timer auf fünf Minuten". The audio after the
    phrase goes straight to speech recognition. If that isn't supported, it falls back to the pause.
- Knobs: on/off, which situations, sensitivity, sound on detection, one breath (experimental), whether it
  speaks first when woken by voice, and a cooldown after a detection.
- A training kit in the repo trains the model on the user's home PC (Fedora, AMD RDNA4 with ROCm). The app
  imports the resulting file.

## Non-goals

- Training in the app (part 3).
- Using the car's or a headset's microphone; the phone's own microphone is used.
- Low-power hardware hotword (the DSP that "Hey Google" uses): Android reserves it for preinstalled assistants.
- Listening outside situations ("always").

## Anti-goals

- **Silent failure:** listening stops (killed, permission lost, model missing) and nothing says so. The settings
  and the dump must always show whether it listens, and if not, why.
- **Talking over itself:** the assistant's own voice, music it started or a call triggers the phrase.
- **Noticeable battery drain** while no picked situation is active. The microphone must be really off then:
  no recording, no green dot.
- **Frequent false triggers.** The goal is under one a day in normal use; sensitivity is the knob.

## Constraints found

- Android 14+ doesn't let an app start a microphone foreground service from the background. The documented
  exemption is the app that provides the `VoiceInteractionService`. The app already holds the assistant role
  (`android.app.role.ASSISTANT`), but only through `AssistActivity` (`ACTION_ASSIST`). It has no voice
  interaction service yet.
- A `VoiceInteractionService` must come with a `VoiceInteractionSessionService` and a `RecognitionService`
  (declared in its XML meta-data), or the system won't offer it.
- The conversation's speech recognition (`SpeechRecognizer`, `AssistantController.listen`) and the wake-word
  recorder can't both use the microphone. The wake-word recorder must release it before a conversation listens,
  and take it back afterwards.
- One breath needs `RecognizerIntent.EXTRA_AUDIO_SOURCE` (Android 13+). Whether the phone's recognizer honours
  it is unknown.
- openWakeWord runs as three ONNX models: mel spectrogram, then speech embedding, then the phrase classifier
  (16 kHz mono, 80 ms frames). On Android that's ONNX Runtime (`com.microsoft.onnxruntime:onnxruntime-android`).
  The first two models are the same for every phrase and ship with the app; only the classifier is per phrase.

## Approaches considered

- **A. Full voice assistant service (chosen by the user).** Add a `VoiceInteractionService`. It is exempt from
  the background-start rule, and the system keeps it bound, so listening can start when a situation turns active
  with the screen off, and after a reboot. Cost: the session and recognition stubs the system requires, testing
  on Samsung, and the user picks the app once more under "Digital assistant app".
- **B. A microphone service started once from the open app.** Less work, but killed or rebooted means silently
  off until the app is opened. Rejected: it fails silently.
- Wake engine: **openWakeWord** (local, free, Apache 2.0 code). Picovoice Porcupine is out: its free tier ended
  on 30 June 2026.
- Training: **on the user's home PC** with openWakeWord's synthetic-data pipeline (the user's choice; it's
  stronger and already has most libraries). Not on this Mac, not in Colab.

## Design

### Voice interaction service (`service/IslandVoiceService.kt` and friends)

- `IslandVoiceService : VoiceInteractionService`, `IslandVoiceSessionService` with an `IslandVoiceSession`, and
  `IslandRecognitionService` (a stub that reports "not supported"), plus `res/xml/voice_interaction_service.xml`
  naming them, with `supportsAssist="true"` and `supportsLaunchVoiceAssistFromKeyguard="true"`.
- **Assist gesture:** `IslandVoiceSession.onShow` starts the same flow `AssistActivity` does today
  (`assistant.start()`). Long-press and swipe behave as before. `AssistActivity` stays, for older paths and the
  "try it now" button.
- **Wake word:** `IslandVoiceService.onReady` starts observing the wake-word state (below). Being the voice
  interaction service is what lets it start the microphone service from the background.
- Settings show whether the app is the active voice assistant service, with a button to the system's
  "Digital assistant app" page when it isn't.

### Wake-word listener (`data/wakeword/`, `service/WakeWordService.kt`)

- `WakeWordModel`: loads `melspectrogram.onnx` and `embedding_model.onnx` from assets, and the imported
  classifier from app storage (`files/wakeword/<name>.onnx`). It turns 80 ms frames of 16-bit 16 kHz audio into
  a score from 0 to 1, as openWakeWord's own `Model.predict` does (mel features, a 76-frame window to an
  embedding, the last 16 embeddings to the classifier).
- `WakeWordService`: a foreground service of type `microphone`, with an `AudioRecord` (VOICE_RECOGNITION
  source, 16 kHz mono) on its own thread. It runs only while it should listen (below), so the green dot appears
  only then. Its notification reads "Listening for "Hey GLaDOS"" and has a "Pause for 1 hour" action.
- `WakeWordController` (domain, in `AppGraph`) decides whether to listen:
  `enabled && modelImported && micPermission && engine.anyActive(wakeWordSituations) && !paused`.
  It pauses while:
  - a conversation runs (`assistant.state != null`, the conversation needs the microphone and must not hear
    itself),
  - a call is ringing or active (`SystemMonitor.state.callAudio`),
  - another app records (`AudioManager.AudioRecordingCallback`: another client's recording is active, or ours
    is silenced),
  - the notification's "pause for 1 hour" runs.
  It starts and stops `WakeWordService` accordingly (started via the voice interaction service's context, so
  it's exempt) and exposes `status: StateFlow<WakeWordStatus>` (Listening / Off(reason)) for settings and the
  dump.
- Detection: when the score reaches the sensitivity threshold, ignore further hits for the cooldown. Play the
  sound if enabled, stop recording, and call `assistant.startFromVoice()`.
- `AssistantController.startFromVoice()` is like `start()`, but it doesn't need a foreground activity (the
  microphone service is running), it skips the opener unless "speaks first when woken by voice" is on, and it
  records in the trace that it was woken by voice.
- **One breath (experimental):** after detection the recorder keeps running and streams what follows into a pipe
  (`ParcelFileDescriptor.createPipe`) passed as `EXTRA_AUDIO_SOURCE` (with
  `EXTRA_AUDIO_SOURCE_CHANNEL_COUNT`, `_ENCODING`, `_SAMPLING_RATE`). If the recognizer errors immediately, or
  delivers nothing within 2 s, it falls back to the pause flow, and the fallback is noted in the trace.
- Registered in `SituationUsers` as "Wake word" with `wakeWordSituations`.

### Settings (`IslandSettings`, `ui/settings/WakeWordScreen.kt`, under Assistant)

| Setting | Default | Range |
|---|---|---|
| `wakeWordEnabled` | false | |
| `wakeWordSituations: Set<String>` | empty (listens nowhere until picked) | |
| `wakeWordModel: String?` (imported file name) | null | |
| `wakeWordPhrase: String` (shown in texts) | "Hey GLaDOS" | |
| `wakeWordThreshold` | 0.5 | 0.2–0.9 |
| `wakeWordSound` | true | |
| `wakeWordOneBreath` | false | |
| `wakeWordOpener` (speaks first when woken by voice) | false | |
| `wakeWordCooldownS` | 3 | 1–10 |

The screen shows, top to bottom:
- a live status line ("Listening in Fahren" / "Off: no situation active" / "Off: no model imported" / "Off: not
  the digital assistant app")
- the situation picker (`PickRow`s of all situations, "New situation" link)
- "Import model" (system file picker, `.onnx`, copied into app storage, with the phrase name field pre-filled
  from the file name)
- the sliders and switches above
- a live score meter while the screen is open and listening, for tuning sensitivity

### Debugging

`dumpsys … wakeword`: the status and why, the model and threshold, the recent maximum score, the last 30
detections with score and time, pauses and their reasons, and the processing time per frame (for the battery
check). Detections and pauses are also kept on disk like `ConversationTrace` (`wakeword-trace.txt`).

### Training kit (`tools/wakeword/`)

- `train.sh` for Fedora:
  - creates a Python 3.11 environment with `uv`
  - installs PyTorch's ROCm build (falls back to CPU if `torch.cuda.is_available()` is false; RDNA4 may need a
    ROCm 6.4+ wheel)
  - installs openWakeWord's training requirements and piper-sample-generator
  - downloads the training data (negative feature set, validation set, room impulse responses, background
    noise; several GB, cached in `~/.cache/wakeword-train`)
  - generates positive clips for the phrase with English and German Piper voices, and adversarial
    near-misses ("Hey Gladys", "Hey Carlos" …)
  - trains, and writes `out/<phrase>.onnx` plus a small report (recall and false accepts per hour on the
    validation set)
- `config.yaml` with the phrase "hey glados", sample counts and steps; `README.md` with the steps and how to get
  the file onto the phone (`adb push` or any file transfer, then "Import model").
- Not run in CI or on this Mac; the user runs it at home.

### Tests

- `WakeWordController`'s decision as a pure function: every combination of enabled, model, permission, situation,
  conversation, call, another recorder and manual pause gives the right status and reason.
- Score pipeline: frame and window bookkeeping (76-frame mel window, 16 embeddings) with a fake model; the
  threshold and cooldown logic.
- `SituationUsers` lists "Wake word" for picked situations.
- On the phone: with a stock openWakeWord model imported for testing, it detects, the conversation runs and
  listening resumes. Paused during a call; off when the situation ends (the green dot goes away); after a reboot
  it listens again without opening the app. A one-breath attempt shows in the trace as working or as the
  fallback.

## Calls made while writing this (open to correction)

- The voice session shows no UI of its own: it starts the island conversation and hides itself.
- An imported model is checked by loading it and checking its input shape (`[1, 16, 96]`); anything else is
  rejected with a message.
- The island shows nothing while listening for the phrase; the notification and Android's green dot are the
  signs.
- Listening keeps going while media plays (the car with music is a main case).
- "Pause for 1 hour" in the notification is the quick way to silence it without changing settings.
- No opener when woken by voice (you're about to speak); a switch brings it back.

## Left to the builder

- Exact sound for detection: a short system tone, or a tiny bundled one.
- ONNX Runtime session options (threads) and buffer sizes, measured on the phone.
- How the score meter looks.
