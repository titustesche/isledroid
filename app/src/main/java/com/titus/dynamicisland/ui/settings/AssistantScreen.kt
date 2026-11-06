package com.titus.dynamicisland.ui.settings

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import com.titus.dynamicisland.ui.theme.Term
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.compose.runtime.key
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titus.dynamicisland.appGraph
import com.titus.dynamicisland.data.assistant.ElevenLabs
import com.titus.dynamicisland.data.assistant.ElevenVoice
import com.titus.dynamicisland.data.assistant.GoogleTts
import com.titus.dynamicisland.data.assistant.GoogleVoice
import com.titus.dynamicisland.data.assistant.PhoneVoices
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import com.titus.dynamicisland.data.assistant.PiperStore
import com.titus.dynamicisland.data.assistant.PiperVoice
import com.titus.dynamicisland.data.assistant.PiperVoices
import com.titus.dynamicisland.data.settings.ActionStyle
import com.titus.dynamicisland.data.settings.AssistantBackendType
import com.titus.dynamicisland.data.settings.AssistantVoiceType
import com.titus.dynamicisland.data.settings.ControlConfirm
import com.titus.dynamicisland.data.settings.WorkIndicator
import com.titus.dynamicisland.data.settings.ElevenModel
import com.titus.dynamicisland.data.settings.NavigationApp
import com.titus.dynamicisland.domain.PreviewSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

private fun isDefaultAssistant(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)

private fun hasMic(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

@Composable
fun AssistantScreen(s: SettingsHandle, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val v = s.value
    val context = LocalContext.current
    val graph = context.appGraph
    val keys by graph.assistantSecrets.keys.collectAsStateWithLifecycle()
    var isDefault by remember { mutableStateOf(isDefaultAssistant(context)) }
    var mic by remember { mutableStateOf(hasMic(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isDefault = isDefaultAssistant(context)
        mic = hasMic(context)
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { mic = it }

    SettingsScaffold("Assistant", onBack, v, preview = PreviewSample.ASSISTANT) {
        if (!isDefault || !mic) item {
            TermCard("Setup") {
                    SetupRow(
                        "Default assistant app",
                        "Hold the side key or swipe up from a bottom corner to talk to the island.",
                        isDefault,
                        "Choose",
                    ) { context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                    SetupRow("Microphone", "Needed to hear your question.", mic, "Allow") {
                        askMic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    if (!isDefault) {
                        Text(
                            "Under Default apps, pick \"Digital assistant app\" → Dynamic Island. On Samsung, also set " +
                                "Settings → Advanced features → Side button → Press and hold to \"Digital assistant\".",
                            fontSize = 13.sp, lineHeight = 18.sp, color = Term.Dim,
                        )
                    }
            }
        }

        header("AI backend")
        item {
            SegmentedSetting("Provider", AssistantBackendType.entries, v.assistantBackend, { it.label }) { b ->
                s.update { it.copy(assistantBackend = b) }
            }
        }
        when (v.assistantBackend) {
            AssistantBackendType.CLAUDE -> {
                item { SecretField("Claude API key", keys.claude, graph.assistantSecrets::setClaudeKey) }
                item {
                    PlainField("Model", v.claudeModel, "claude-opus-5-5") { m -> s.update { it.copy(claudeModel = m.trim()) } }
                }
                note("Create a key at platform.claude.com. Answers use low effort for quick spoken replies.")
            }
            AssistantBackendType.OPENAI_COMPATIBLE -> {
                item {
                    PlainField("Base URL", v.openAiBaseUrl, "https://api.openai.com/v1", KeyboardType.Uri) { u ->
                        s.update { it.copy(openAiBaseUrl = u.trim()) }
                    }
                }
                item { SecretField("API key (optional for local servers)", keys.openAi, graph.assistantSecrets::setOpenAiKey) }
                item { PlainField("Model", v.openAiModel, "model name") { m -> s.update { it.copy(openAiModel = m.trim()) } } }
                item {
                    Hint(
                        "Works with OpenAI, OpenRouter, Groq, Mistral, or a local Ollama (http://<pc-ip>:11434/v1) / " +
                            "LM Studio server. Plain-http local servers must be reachable from the phone.",
                    )
                }
            }
        }

        header("Voice")
        item {
            SegmentedSetting("Spoken by", AssistantVoiceType.entries, v.assistantVoice, { it.label }) { t ->
                s.update { it.copy(assistantVoice = t) }
            }
        }
        when (v.assistantVoice) {
            AssistantVoiceType.PHONE -> {
                item { PhoneVoiceSettings(s) }
                item {
                    Hint(
                        "Google's engine sounds far better than most phone makers' own, so it's used when installed. " +
                            "More voices: Google Speech Services → Settings → Install voice data.",
                    )
                }
            }
            AssistantVoiceType.PIPER -> {
                item { PiperVoiceSettings(s, graph.piper) }
                item {
                    Hint(
                        "Piper voices run entirely on your phone: free, without limits, even offline. Each voice " +
                            "is a one-time download; until one is downloaded, the phone voice speaks.",
                    )
                }
            }
            AssistantVoiceType.GOOGLE -> {
                item { SecretField("Google Cloud API key", keys.googleTts, graph.assistantSecrets::setGoogleTtsKey) }
                item { GoogleVoiceSettings(s, keys.googleTts) }
                item {
                    Hint(
                        "Google's Chirp 3 HD voices: the first million characters a month are free (many hours of " +
                            "answers), then 30 dollars per million. Setup: in console.cloud.google.com create a project, " +
                            "enable billing and the Cloud Text-to-Speech API, then create an API key restricted to that " +
                            "API. A budget alert keeps surprises away. If Google fails, the phone voice takes over.",
                    )
                }
            }
            AssistantVoiceType.ELEVENLABS -> {
                item { SecretField("ElevenLabs API key", keys.elevenLabs, graph.assistantSecrets::setElevenLabsKey) }
                item { ElevenVoiceSettings(s, keys.elevenLabs) }
                item {
                    SegmentedSetting("Model", ElevenModel.entries, v.elevenModel, { it.label }) { m -> s.update { it.copy(elevenModel = m) } }
                }
                item {
                    Hint(
                        "Create a key at elevenlabs.io (the free plan covers roughly ten minutes of speech a month). " +
                            "\"Fast\" starts talking sooner; \"Most natural\" sounds a little better. If ElevenLabs " +
                            "can't be reached or the quota is used up, the phone voice takes over.",
                    )
                }
            }
        }
        item {
            SliderSetting("Speaking speed", v.speechRate, 0.6f..1.6f, { f -> s.update { it.copy(speechRate = f) } },
                format = { String.format(Locale.ROOT, "%.1f×", it) })
        }
        if (v.assistantVoice == AssistantVoiceType.ELEVENLABS) {
            note("ElevenLabs voices go from 0.7× to 1.2×.")
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                TermButton("hear the voice") { graph.assistant.previewVoice() }
                TermButton("[ try it now ]", filled = true) { graph.assistant.start() }
            }
        }
        header("Look")
        item {
            ColorSetting("Color", v.assistantColor, { c -> s.update { it.copy(assistantColor = c) } },
                summary = "The voice glow and the assistant's cards")
        }
        item {
            SwitchSetting("Soft bottom edge", v.assistantSoftEdge, { on -> s.update { it.copy(assistantSoftEdge = on) } },
                summary = "The island fades out towards the bottom instead of ending in a hard edge")
        }
        if (v.assistantSoftEdge) {
            item {
                SliderSetting("Fade starts at", v.assistantFadeStart, 0.1f..0.9f, { f -> s.update { it.copy(assistantFadeStart = f) } },
                    format = { "${(it * 100).roundToInt()}% of the height" })
            }
        }
        item {
            SwitchSetting("Glow beyond the island", v.assistantGlowSpill, { on -> s.update { it.copy(assistantGlowSpill = on) } },
                summary = "The voice glow spills softly past the island's edges onto the screen")
        }
        if (v.assistantGlowSpill) {
            item { IntSliderSetting("Glow reach", v.assistantGlowReachDp, 0..40) { n -> s.update { it.copy(assistantGlowReachDp = n) } } }
        }
        item {
            SwitchSetting("Feathered edge", v.assistantFeather, { on -> s.update { it.copy(assistantFeather = on) } },
                summary = "A soft halo of the island's color melts its outline into the screen")
        }
        if (v.assistantFeather) {
            item { IntSliderSetting("Halo reach", v.featherSpreadDp, 0..40) { n -> s.update { it.copy(featherSpreadDp = n) } } }
            item { IntSliderSetting("Halo below", v.featherBottomDp, 0..40) { n -> s.update { it.copy(featherBottomDp = n) } } }
            item { IntSliderSetting("Softness", v.featherBlurDp, 2..40) { n -> s.update { it.copy(featherBlurDp = n) } } }
            item {
                SliderSetting("Halo strength", v.featherOpacity, 0.1f..1f, { f -> s.update { it.copy(featherOpacity = f) } },
                    format = { "${(it * 100).roundToInt()}%" })
            }
        }
        item {
            SegmentedSetting("What it did", ActionStyle.entries, v.assistantActionStyle, { it.label }) { st ->
                s.update { it.copy(assistantActionStyle = st) }
            }
        }
        item {
            SwitchSetting("ASCII background", v.assistantAscii, { on -> s.update { it.copy(assistantAscii = on) } },
                summary = "Drifting characters behind the assistant's text")
        }
        if (v.assistantAscii) {
            item {
                SliderSetting("Opacity", v.asciiOpacity, 0.05f..1f, { f -> s.update { it.copy(asciiOpacity = f) } },
                    format = { "${(it * 100).roundToInt()}%" })
            }
            item { IntSliderSetting("Character size", v.asciiSizeSp, 6..18, "sp") { n -> s.update { it.copy(asciiSizeSp = n) } } }
            item {
                SliderSetting("Drift speed", v.asciiSpeed, 0f..1f, { f -> s.update { it.copy(asciiSpeed = f) } },
                    format = { if (it < 0.005f) "Still" else String.format(Locale.ROOT, "%.2f×", it) })
            }
            item { IntSliderSetting("Grain", v.asciiGrainDp, 40..400) { n -> s.update { it.copy(asciiGrainDp = n) } } }
            note("Small grain is busy and detailed, large is calm with big soft patches.")
            item {
                SwitchSetting("In the assistant's color", v.asciiAccent, { on -> s.update { it.copy(asciiAccent = on) } },
                    summary = "Otherwise white")
            }
        }
        if (v.assistantBackend == AssistantBackendType.CLAUDE) {
            header("What it can do")
            item {
                SwitchSetting("Use apps for you", v.assistantControl, { on -> s.update { it.copy(assistantControl = on) } },
                    summary = "It taps, types and scrolls in other apps (\"turn on Wi-Fi\", \"order my usual\"). Off by default")
            }
            if (v.assistantControl) {
                item {
                    ChoiceSetting("Asks before", ControlConfirm.entries, v.controlConfirm, { it.label }) { c -> s.update { it.copy(controlConfirm = c) } }
                }
                note("\"Only important ones\": sending, paying, deleting, confirming and the like, and all typing. Taps on a card do it too.")
                item {
                    IntSliderSetting("Steps per question", v.controlMaxSteps, 6..40, "") { n -> s.update { it.copy(controlMaxSteps = n) } }
                }
                item {
                    IntSliderSetting("Wait after each step", v.controlStepDelayMs, 200..3000, "ms") { n ->
                        s.update { it.copy(controlStepDelayMs = (n / 100) * 100) }
                    }
                }
                item {
                    SwitchSetting("Tap anywhere", v.controlTapAnywhere, { on -> s.update { it.copy(controlTapAnywhere = on) } },
                        summary = "Taps by position in apps that hide their buttons (Lieferando & co.). Screen readings get a bit longer")
                }
                if (v.controlTapAnywhere) {
                    item {
                        IntSliderSetting("Long press", v.controlLongPressMs, 300..2000, "ms") { n ->
                            s.update { it.copy(controlLongPressMs = (n / 50) * 50) }
                        }
                    }
                }
                item {
                    SwitchSetting("Shrink while working", v.controlCompact, { on -> s.update { it.copy(controlCompact = on) } },
                        summary = "The island becomes a small pill while it taps through apps, so it doesn't cover them")
                }
                if (v.controlCompact) {
                    item {
                        ChoiceSetting("Indicator", WorkIndicator.entries, v.controlIndicator, { it.label }) { c -> s.update { it.copy(controlIndicator = c) } }
                    }
                    item {
                        SwitchSetting("Count the steps", v.controlShowSteps, { on -> s.update { it.copy(controlShowSteps = on) } })
                    }
                }
                note("What it sees on screen goes to your AI provider. It never reads or types passwords and never touches this app.")
            }
            item {
                SwitchSetting("Read the screen", v.assistantReadScreen, { on -> s.update { it.copy(assistantReadScreen = on) } },
                    summary = "When you ask about what you're looking at; never in the background, never passwords")
            }
            if (v.assistantReadScreen) {
                item {
                    IntSliderSetting("Reads at most", v.screenReadMaxChars, 1000..15000, "characters") { n ->
                        s.update { it.copy(screenReadMaxChars = (n / 500) * 500) }
                    }
                }
            }
            item {
                SwitchSetting("Ends conversations itself", v.assistantSelfEnd, { on -> s.update { it.copy(assistantSelfEnd = on) } },
                    summary = "Stops listening when a request needs nothing more from you (\"timer for ten minutes\")")
            }
            if (v.assistantSelfEnd) {
                item {
                    IntSliderSetting("Closes after its answer", v.assistantEndDelayMs, 0..3000, "ms") { n ->
                        s.update { it.copy(assistantEndDelayMs = (n / 100) * 100) }
                    }
                }
                item {
                    SwitchSetting("\"Danke\" closes it", v.assistantEndOnThanks, { on -> s.update { it.copy(assistantEndOnThanks = on) } },
                        summary = "\"Danke\", \"das war's\", \"thanks, that's it\" end it at once. \"Danke, kannst du noch …\" doesn't")
                }
            }
            item {
                IntSliderSetting("Answer stays for", v.assistantDoneVisibleMs, 1000..10000, "ms") { n ->
                    s.update { it.copy(assistantDoneVisibleMs = (n / 250) * 250) }
                }
            }
            note("\"Answer stays for\": when it ends another way (no microphone, an error), so there's time to read it.")
            header("Messages")
            item {
                SwitchSetting("Ask before sending", v.messagesConfirm, { on -> s.update { it.copy(messagesConfirm = on) } },
                    summary = "Texts, replies and WhatsApp wait for your yes. Off: they go out as soon as you ask. Calls always ask")
            }
            item {
                SwitchSetting("Press send in WhatsApp", v.whatsappAutoSend, { on -> s.update { it.copy(whatsappAutoSend = on) } },
                    summary = "\"Write Anna on WhatsApp …\" opens the chat with the text and sends it. Off: it's typed in for you to send")
            }
            if (v.whatsappAutoSend) {
                item {
                    IntSliderSetting("Wait for WhatsApp", v.whatsappSendWaitMs, 2000..15000, "ms") { n ->
                        s.update { it.copy(whatsappSendWaitMs = (n / 500) * 500) }
                    }
                }
                item {
                    SwitchSetting("Back to where you were", v.messageReturnToApp, { on -> s.update { it.copy(messageReturnToApp = on) } },
                        summary = "After sending, the app you had open (Maps, …) comes back instead of staying in WhatsApp")
                }
                note("Pressing send needs the screen unlocked. If WhatsApp is slow to open on your phone, wait longer.")
            }
            header("More")
            item {
                val devices = v.wolDevices.size + v.httpActions.size
                NavRow("Devices & actions", if (devices == 0) "Wake-on-LAN, smart home and webhooks" else "$devices set up", null) {
                    onNavigate(Routes.NETWORK)
                }
            }
            item {
                NavRow("Habits", if (!v.habitsEnabled) "Off" else "Routines it noticed, and suggests", null) { onNavigate(Routes.HABITS) }
            }
            item {
                val count = LocalContext.current.appGraph.memory.memories.collectAsStateWithLifecycle().value.size
                NavRow("Memory", if (!v.assistantMemory) "Off" else if (count == 0) "Nothing remembered yet" else "$count remembered", null) {
                    onNavigate(Routes.MEMORY)
                }
            }
            item {
                NavRow("Tool permissions", "Location, calendar, contacts, calls, texts, Do Not Disturb", null) {
                    onNavigate(Routes.ASSISTANT_TOOLS)
                }
            }
        }
        note("Speech recognition comes from your phone's speech services and uses its language.")
        note("API keys are stored only on this phone and are excluded from backups.")
    }
}

/** The permissions the assistant's tools can use, one row each. */
@Composable
fun AssistantToolsScreen(s: SettingsHandle, onBack: () -> Unit) {
    SettingsScaffold("Tool permissions", onBack, s.value, preview = PreviewSample.ASSISTANT) {
        item { ToolPermissions() }
        item {
            Hint(
                "Each one unlocks what the assistant can do for you; without it, it just says it isn't allowed. " +
                    "Messages, replies and calls always wait for your yes, spoken or by tapping the card.",
            )
        }
    }
}

private data class ToolPermission(val title: String, val why: String, val permissions: List<String>)

private val TOOL_PERMISSIONS = listOf(
    ToolPermission(
        "Location", "Weather where you are; precise for remembering where you parked",
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
    ),
    ToolPermission("Calendar", "Your next appointments, adding events", listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)),
    ToolPermission("Contacts", "Finding who to call or text", listOf(Manifest.permission.READ_CONTACTS)),
    ToolPermission("Phone calls", "Calling someone, after your yes", listOf(Manifest.permission.CALL_PHONE)),
    ToolPermission("Answer & end calls", "\"Pick up\", \"hang up\"", listOf(Manifest.permission.ANSWER_PHONE_CALLS)),
    ToolPermission("Call history", "\"Who called earlier?\"", listOf(Manifest.permission.READ_CALL_LOG)),
    ToolPermission("Text messages", "Sending an SMS after your yes, reading your texts", listOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS)),
)

/** One row per permission the assistant's tools can use, each with its own "Allow". */
@Composable
private fun ToolPermissions() {
    val context = LocalContext.current
    fun granted(p: ToolPermission) = p.permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    fun dndAccess() = context.getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted
    var refresh by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        refresh++
        // Denied for good: Android won't ask again, so the app's settings page is the only way.
        val activity = context as? android.app.Activity
        val blocked = result.filterValues { !it }.keys.any { activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        if (blocked) context.startActivity(Permissions.appDetails(context))
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        key(refresh) {
            TOOL_PERMISSIONS.forEach { p ->
                SetupRow(p.title, p.why, granted(p), "Allow") { ask.launch(p.permissions.toTypedArray()) }
            }
            SetupRow("Do Not Disturb access", "Switching Do Not Disturb and silent mode", dndAccess(), "Allow") {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
            SetupRow("Display settings", "Brightness and screen timeout", Settings.System.canWrite(context), "Allow") {
                context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + context.packageName)))
            }
        }
    }
}

@Composable
private fun SetupRow(title: String, why: String, done: Boolean, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(why, fontSize = 13.sp, lineHeight = 18.sp, color = Term.Dim)
        }
        Spacer(Modifier.width(10.dp))
        if (done) Text("[ok]", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Term.Text)
        else TermButton(action.lowercase(), filled = true, onClick = onClick)
    }
}

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) =
    TermField(label, value, onChange, secret = true)

@Composable
private fun PlainField(
    label: String,
    value: String,
    placeholder: String,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) = TermField(label, value, onChange, placeholder, keyboard)

/** Speech engine and voice for the phone's own text-to-speech. */
@Composable
private fun PhoneVoiceSettings(s: SettingsHandle) {
    val v = s.value
    val context = LocalContext.current
    val engines = remember { PhoneVoices.engines(context) }
    val engine = remember(v.phoneTtsEngine, engines) { PhoneVoices.resolveEngine(context, v.phoneTtsEngine) }
    if (engines.size > 1) {
        val current = engines.firstOrNull { it.pkg == engine } ?: engines.first()
        ChoiceSetting("Speech engine", engines, current, { it.label }) { e ->
            s.update { it.copy(phoneTtsEngine = e.pkg, phoneVoice = "") }
        }
    }
    // Voices are only known once an engine instance is up.
    var voices by remember(engine) { mutableStateOf<List<Voice>?>(null) }
    DisposableEffect(engine) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context, { status ->
            voices = if (status == TextToSpeech.SUCCESS) PhoneVoices.usable(runCatching { tts?.voices }.getOrNull()) else emptyList()
        }, engine)
        onDispose { tts.shutdown() }
    }
    val list = voices
    when {
        list == null -> Hint("Loading voices…")
        list.isEmpty() -> Hint("This engine has no voices installed for ${LocalConfiguration.current.locales[0].displayLanguage}.")
        else -> {
            val options = listOf<Voice?>(null) + list
            val selected = list.firstOrNull { it.name == v.phoneVoice }
            ChoiceSetting("Voice", options, selected, { it?.let(PhoneVoices::label) ?: "Automatic (best)" }) { picked ->
                s.update { it.copy(phoneVoice = picked?.name.orEmpty()) }
            }
        }
    }
}

/** Which app "navigate to …" opens; only installed ones are offered. */
@Composable
fun NavigationAppSetting(s: SettingsHandle) {
    val context = LocalContext.current
    val options = remember {
        NavigationApp.entries.filter { app ->
            app.pkg == null || runCatching { context.packageManager.getPackageInfo(app.pkg, 0) }.isSuccess
        }
    }
    if (options.size < 2) return
    val current = s.value.navigationApp.takeIf { it in options } ?: options.first()
    ChoiceSetting("Navigation app", options, current, { it.label }) { app -> s.update { it.copy(navigationApp = app) } }
}

/** Piper voice picker, with downloading and removing the voice files. */
@Composable
private fun PiperVoiceSettings(s: SettingsHandle, store: PiperStore) {
    val v = s.value
    val installed by store.installed.collectAsStateWithLifecycle()
    val downloads by store.downloads.collectAsStateWithLifecycle()
    val custom by store.custom.collectAsStateWithLifecycle()
    val importing by store.importing.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val voices = remember(locale, custom) { custom + PiperVoices.forLocale(locale) }
    val current = remember(v.piperVoice, installed, custom, locale) { store.resolve(v.piperVoice, locale) }
    val context = LocalContext.current
    val pickArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) store.import { context.contentResolver.openInputStream(uri) }
    }
    // A freshly imported voice is the one they want to hear.
    LaunchedEffect(importing?.imported) {
        importing?.imported?.let { voice -> s.update { it.copy(piperVoice = voice.id) } }
    }
    val showCountry = voices.map { it.locale.country }.distinct().size > 1 || voices.any { it.locale.language != locale.language }
    fun label(p: PiperVoice) = buildString {
        append(p.name)
        if (showCountry) append(" · ").append(p.locale.country)
        if (p.id in installed) append(" ✓")
    }
    ChoiceSetting("Voice", voices, current, ::label) { picked -> s.update { it.copy(piperVoice = picked.id) } }
    Hint(current.description.replaceFirstChar { it.uppercase() })
    val download = downloads[current.id]
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            download != null && download.error == null -> {
                Column(Modifier.weight(1f)) {
                    val p = download.progress
                    Text(
                        when {
                            download.unpacking -> "Finishing…"
                            p == null -> "Starting download…"
                            else -> "Downloading… ${(p * 100).toInt()} %"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (p == null || download.unpacking) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                    } else {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                }
                TermButton("cancel") { store.cancel(current) }
            }
            current.id in installed -> {
                Text("On this phone", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TermButton("remove") { store.remove(current) }
            }
            else -> {
                TermButton(if (download?.error != null) "Try again" else "Download (${current.sizeMb} MB)", filled = true) { store.download(current) }
            }
        }
    }
    download?.error?.let { Hint(it) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (importing?.running == true) "Importing…" else "A voice you trained yourself",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
        )
        if (importing?.running != true) {
            TermButton("import voice") { pickArchive.launch(arrayOf("application/x-bzip2", "application/x-tar", "application/octet-stream", "*/*")) }
        }
    }
    importing?.error?.let { Hint(it) }
}

/** Chirp 3 HD voices for the phone's language, loaded with the API key. */
@Composable
private fun GoogleVoiceSettings(s: SettingsHandle, apiKey: String) {
    val v = s.value
    val locale = LocalConfiguration.current.locales[0]
    var voices by remember { mutableStateOf<List<GoogleVoice>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(apiKey, locale) {
        error = null
        if (apiKey.isBlank()) return@LaunchedEffect
        delay(600) // the key may still be being typed or pasted
        runCatching { withContext(Dispatchers.IO) { GoogleTts.voices(apiKey, locale) } }
            .onSuccess { voices = it }
            .onFailure { error = it.message ?: "Couldn't load the voices." }
    }
    val name = GoogleTts.resolve(v.googleVoice, locale)
    val current = voices.firstOrNull { it.name == name } ?: GoogleVoice(name, "")
    ChoiceSetting("Voice", voices.ifEmpty { listOf(current) }, current, { it.label }) { picked ->
        s.update { it.copy(googleVoice = picked.name) }
    }
    when {
        apiKey.isBlank() -> Hint("Add your API key to choose from Google's voices.")
        error != null -> Hint(error!!)
        current.gender.isNotBlank() -> Hint(current.gender.replaceFirstChar { it.uppercase() })
    }
}

/** Voice picker filled from the ElevenLabs account. */
@Composable
private fun ElevenVoiceSettings(s: SettingsHandle, apiKey: String) {
    val v = s.value
    var voices by remember { mutableStateOf<List<ElevenVoice>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(apiKey) {
        error = null
        if (apiKey.isBlank()) return@LaunchedEffect
        delay(600) // the key may still be being typed or pasted
        runCatching { withContext(Dispatchers.IO) { ElevenLabs.voices(apiKey) } }
            .onSuccess { voices = it }
            .onFailure { error = it.message ?: "Couldn't load the voices." }
    }
    val current = voices.firstOrNull { it.id == v.elevenVoiceId } ?: ElevenVoice(v.elevenVoiceId, v.elevenVoiceName, "")
    ChoiceSetting("Voice", voices.ifEmpty { listOf(current) }, current, { it.name }) { picked ->
        s.update { it.copy(elevenVoiceId = picked.id, elevenVoiceName = picked.name) }
    }
    when {
        apiKey.isBlank() -> Hint("Add your API key to choose from your ElevenLabs voices.")
        error != null -> Hint(error!!)
        current.description.isNotBlank() -> Hint(current.description.replaceFirstChar { it.uppercase() })
    }
}

/** What the assistant remembers: read, delete, or switch it off. */
@Composable
fun MemoryScreen(s: SettingsHandle, onBack: () -> Unit) {
    val v = s.value
    val memory = LocalContext.current.appGraph.memory
    val items by memory.memories.collectAsStateWithLifecycle()
    SettingsScaffold("Memory", onBack, v, preview = PreviewSample.ASSISTANT) {
        item {
            SwitchSetting("Remember things", v.assistantMemory, { on -> s.update { it.copy(assistantMemory = on) } },
                summary = "\"Remember where I parked\", \"my locker is 214\" … and ask for it later")
        }
        if (v.assistantMemory) {
            item { IntSliderSetting("Sent along with every question", v.memoryInPrompt, 0..100, "") { n -> s.update { it.copy(memoryInPrompt = n) } } }
            note("The newest ones. It can still look up older ones when you ask. Fewer means less sent to the AI provider.")
        }
        header("Remembered")
        if (items.isEmpty()) note("Nothing yet.")
        items.forEach { m ->
            item(m.id) {
                SettingRow(
                    m.text,
                    android.text.format.DateUtils.getRelativeTimeSpanString(m.createdAt).toString() + if (m.place != null) " · with the place" else "",
                ) { TermButton("forget") { memory.remove(m.id) } }
            }
        }
        if (items.isNotEmpty()) {
            item { TermButton("[ forget everything ]", Modifier.padding(start = 16.dp, top = 16.dp), danger = true) { memory.clear() } }
        }
        note("Stored only on this phone. What's sent along goes to your AI provider with the question.")
    }
}
