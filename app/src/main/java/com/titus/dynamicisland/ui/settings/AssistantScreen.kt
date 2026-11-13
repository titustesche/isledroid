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
import com.titus.dynamicisland.data.settings.AssistantBackendType
import com.titus.dynamicisland.data.settings.AssistantVoiceType
import com.titus.dynamicisland.data.settings.ElevenModel
import com.titus.dynamicisland.data.settings.NavigationApp
import com.titus.dynamicisland.domain.PreviewSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

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
    val memories by graph.memory.memories.collectAsStateWithLifecycle()
    var isDefault by remember { mutableStateOf(isDefaultAssistant(context)) }
    var mic by remember { mutableStateOf(hasMic(context)) }
    var permissions by remember { mutableStateOf(toolPermissionsGranted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isDefault = isDefaultAssistant(context)
        mic = hasMic(context)
        permissions = toolPermissionsGranted(context)
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { mic = it }
    val hasKey = when (v.assistantBackend) {
        AssistantBackendType.CLAUDE -> keys.claude.isNotBlank()
        AssistantBackendType.OPENAI_COMPATIBLE -> v.openAiBaseUrl.isNotBlank() && v.openAiModel.isNotBlank()
    }
    val claude = v.assistantBackend == AssistantBackendType.CLAUDE

    SettingsScaffold("Assistant", onBack, v, preview = PreviewSample.ASSISTANT) {
        if (!isDefault || !mic || !hasKey) item {
            val done = listOf(isDefault, mic, hasKey).count { it }
            TermCard("Setup · $done of 3") {
                SetupRow("Default assistant app", "Side key or swipe up from a bottom corner", isDefault, "Choose") {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                }
                if (!isDefault) {
                    Text(
                        "Default apps → \"Digital assistant app\" → Dynamic Island. On Samsung also: Advanced features → " +
                            "Side button → Press and hold → \"Digital assistant\".",
                        fontSize = 13.sp, lineHeight = 18.sp, color = Term.Dim,
                    )
                }
                SetupRow("Microphone", "Needed to hear you", mic, "Allow") { askMic.launch(Manifest.permission.RECORD_AUDIO) }
                SetupRow(
                    if (claude) "API key" else "Server and model", v.assistantBackend.label, hasKey, "Add",
                ) { onNavigate(Routes.ASSISTANT_AI) }
                Text("disappears once everything is done", fontSize = 12.sp, letterSpacing = 0.6.sp, color = Term.Faint)
            }
        }

        header("Brain & voice")
        item {
            ValueNavRow("AI", "Who answers", aiLabel(v)) { onNavigate(Routes.ASSISTANT_AI) }
        }
        item {
            ValueNavRow("Voice", "How it sounds", v.assistantVoice.label, extra = {
                RowPill("▶ hear") { graph.assistant.previewVoice() }
            }) { onNavigate(Routes.ASSISTANT_VOICE) }
        }

        if (claude) {
            header("What it can do")
            item {
                SwitchNavRow("Read the screen", "When you ask about what you see", v.assistantReadScreen,
                    { on -> s.update { it.copy(assistantReadScreen = on) } }) { onNavigate(Routes.ASSISTANT_SCREEN) }
            }
            item {
                SwitchNavRow("Use apps for you", "Taps, types and scrolls in other apps", v.assistantControl,
                    { on -> s.update { it.copy(assistantControl = on) } }) { onNavigate(Routes.ASSISTANT_APPS) }
            }
            item {
                ValueNavRow("Messages", if (v.messagesConfirm) "Asks before anything goes out" else "Texts go out as soon as you ask",
                    if (v.messagesConfirm) "ask first" else "right away") { onNavigate(Routes.ASSISTANT_MESSAGES) }
            }
            item {
                SwitchNavRow("Remember things", if (v.assistantMemory && memories.isNotEmpty()) "${memories.size} remembered" else "\"My locker is 214\"",
                    v.assistantMemory, { on -> s.update { it.copy(assistantMemory = on) } }) { onNavigate(Routes.MEMORY) }
            }
            item {
                SwitchNavRow("Learn routines", "Offers what you usually do now", v.habitsEnabled,
                    { on -> s.update { it.copy(habitsEnabled = on) } }) { onNavigate(Routes.HABITS) }
            }
            item {
                val n = v.wolDevices.size + v.httpActions.size
                ValueNavRow("Devices & actions", "Wake-on-LAN, smart home, webhooks", if (n == 0) "none yet" else "$n set up") {
                    onNavigate(Routes.NETWORK)
                }
            }

            header("Conversation")
            item {
                SwitchNavRow("Ends by itself", "When nothing more is needed, or on \"danke\"", v.assistantSelfEnd,
                    { on -> s.update { it.copy(assistantSelfEnd = on) } }) { onNavigate(Routes.ASSISTANT_CONVERSATION) }
            }
        } else {
            note("Reading the screen, using apps, messages, memory, routines and devices need Claude as the AI.")
        }

        header("Look")
        item {
            ValueNavRow("Style", "Edge, glow, what it did", v.assistantEdge().label.lowercase()) { onNavigate(Routes.ASSISTANT_LOOK) }
        }

        if (claude) {
            header("Privacy")
            item {
                ValueNavRow("Permissions", "Location, calendar, contacts, calls, texts, Do Not Disturb",
                    "${permissions.count { it }} of ${permissions.size}") { onNavigate(Routes.ASSISTANT_TOOLS) }
            }
        }
        note(
            "Keys stay on this phone and out of backups. Your question, and anything you let it read or remember, goes to " +
                "your AI provider. Speech recognition uses your phone's language.",
        )
    }
}

/** "Claude · opus 5.5", "OpenAI-compatible · llama3". */
private fun aiLabel(v: com.titus.dynamicisland.data.settings.IslandSettings): String {
    val model = when (v.assistantBackend) {
        AssistantBackendType.CLAUDE -> {
            val parts = v.claudeModel.removePrefix("claude-").split('-')
            val numbers = parts.drop(1).takeWhile { it.length <= 2 && it.all(Char::isDigit) }
            if (parts.first().isBlank()) "" else (listOf(parts.first()) + listOfNotNull(numbers.joinToString(".").ifEmpty { null })).joinToString(" ")
        }
        AssistantBackendType.OPENAI_COMPATIBLE -> v.openAiModel
    }
    return if (model.isBlank()) v.assistantBackend.label else "${v.assistantBackend.label} · $model"
}

/** Which AI answers, and how to reach it. */
@Composable
fun AssistantAiScreen(s: SettingsHandle, onBack: () -> Unit) {
    val v = s.value
    val graph = LocalContext.current.appGraph
    val keys by graph.assistantSecrets.keys.collectAsStateWithLifecycle()
    SettingsScaffold("AI", onBack, v, preview = PreviewSample.ASSISTANT) {
        item {
            SegmentedSetting("Provider", AssistantBackendType.entries, v.assistantBackend, { it.label }) { b ->
                s.update { it.copy(assistantBackend = b) }
            }
        }
        when (v.assistantBackend) {
            AssistantBackendType.CLAUDE -> {
                item { SecretField("Claude API key", keys.claude, graph.assistantSecrets::setClaudeKey) }
                item { PlainField("Model", v.claudeModel, "claude-opus-5-5") { m -> s.update { it.copy(claudeModel = m.trim()) } } }
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
                note(
                    "OpenAI, OpenRouter, Groq, Mistral, or a local Ollama (http://<pc-ip>:11434/v1) / LM Studio server " +
                        "reachable from the phone. It only talks: abilities need Claude.",
                )
            }
        }
    }
}

/** Who speaks, which voice, how fast; per-engine fields only for the engine picked. */
@Composable
fun AssistantVoiceScreen(s: SettingsHandle, onBack: () -> Unit) {
    val v = s.value
    val graph = LocalContext.current.appGraph
    val keys by graph.assistantSecrets.keys.collectAsStateWithLifecycle()
    SettingsScaffold("Voice", onBack, v, preview = PreviewSample.ASSISTANT) {
        item {
            SegmentedSetting("Spoken by", AssistantVoiceType.entries, v.assistantVoice, { it.label }) { t ->
                s.update { it.copy(assistantVoice = t) }
            }
        }
        when (v.assistantVoice) {
            AssistantVoiceType.PHONE -> item { PhoneVoiceChoice(s) }
            AssistantVoiceType.PIPER -> item { PiperVoiceSettings(s, graph.piper) }
            AssistantVoiceType.GOOGLE -> {
                item { SecretField("Google Cloud API key", keys.googleTts, graph.assistantSecrets::setGoogleTtsKey) }
                item { GoogleVoiceSettings(s, keys.googleTts) }
            }
            AssistantVoiceType.ELEVENLABS -> {
                item { SecretField("ElevenLabs API key", keys.elevenLabs, graph.assistantSecrets::setElevenLabsKey) }
                item { ElevenVoiceSettings(s, keys.elevenLabs) }
            }
        }
        item {
            val range = if (v.assistantVoice == AssistantVoiceType.ELEVENLABS) 0.7f..1.2f else 0.6f..1.6f
            SliderSetting("Speaking speed", v.speechRate.coerceIn(range), range, { f -> s.update { it.copy(speechRate = f) } },
                format = { String.format(Locale.ROOT, "%.1f×", it) })
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TermButton("▶ hear the voice") { graph.assistant.previewVoice() }
                TermButton("[ try it now ]", filled = true) { graph.assistant.start() }
            }
        }
        when (v.assistantVoice) {
            AssistantVoiceType.PHONE -> item { PhoneEngineChoice(s) }
            AssistantVoiceType.ELEVENLABS -> item {
                ChoiceSetting("Model", ElevenModel.entries, v.elevenModel, { it.label }, summary = "\"Fast\" starts talking sooner") { m ->
                    s.update { it.copy(elevenModel = m) }
                }
            }
            else -> {}
        }
        note(
            when (v.assistantVoice) {
                AssistantVoiceType.PHONE -> "More voices: Google Speech Services → Settings → Install voice data."
                AssistantVoiceType.PIPER -> "Runs on the phone: free, unlimited, offline. Until a voice is downloaded, the phone voice speaks."
                AssistantVoiceType.GOOGLE -> "Chirp 3 HD: a million characters a month free. Key from console.cloud.google.com with the " +
                    "Text-to-Speech API on. If Google fails, the phone voice speaks."
                AssistantVoiceType.ELEVENLABS -> "Key from elevenlabs.io (free plan: about ten minutes a month). Out of quota or offline, the phone voice speaks."
            },
        )
    }
}

/** The permissions the assistant's tools can use, one row each. */
@Composable
fun AssistantToolsScreen(s: SettingsHandle, onBack: () -> Unit) {
    SettingsScaffold("Permissions", onBack, s.value, preview = PreviewSample.ASSISTANT) {
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

private fun granted(context: Context, p: ToolPermission) = p.permissions.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

private fun dndAccess(context: Context) =
    context.getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted

/** One entry per row of the permissions page: whether it's allowed. */
private fun toolPermissionsGranted(context: Context): List<Boolean> =
    TOOL_PERMISSIONS.map { granted(context, it) } + dndAccess(context) + Settings.System.canWrite(context)

/** One row per permission the assistant's tools can use, each with its own "Allow". */
@Composable
private fun ToolPermissions() {
    val context = LocalContext.current
    fun granted(p: ToolPermission) = granted(context, p)
    fun dndAccess() = dndAccess(context)
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

/** The voice of the phone's own text-to-speech, from the engine set below it. */
@Composable
private fun PhoneVoiceChoice(s: SettingsHandle) {
    val v = s.value
    val context = LocalContext.current
    val engine = remember(v.phoneTtsEngine) { PhoneVoices.resolveEngine(context, v.phoneTtsEngine) }
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

/** The phone's speech engine; only shown when there's more than one. Google's is preferred when installed. */
@Composable
private fun PhoneEngineChoice(s: SettingsHandle) {
    val v = s.value
    val context = LocalContext.current
    val engines = remember { PhoneVoices.engines(context) }
    if (engines.size < 2) return
    val engine = remember(v.phoneTtsEngine, engines) { PhoneVoices.resolveEngine(context, v.phoneTtsEngine) }
    val current = engines.firstOrNull { it.pkg == engine } ?: engines.first()
    ChoiceSetting("Speech engine", engines, current, { it.label }, summary = "Only if you have more than one") { e ->
        s.update { it.copy(phoneTtsEngine = e.pkg, phoneVoice = "") }
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
