package com.titus.dynamicisland.ui.settings

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.titus.dynamicisland.data.settings.ActionStyle
import com.titus.dynamicisland.data.settings.AssistantBackendType
import com.titus.dynamicisland.data.settings.ControlConfirm
import com.titus.dynamicisland.data.settings.FollowUpLevel
import com.titus.dynamicisland.data.settings.IslandSettings
import com.titus.dynamicisland.data.settings.WorkIndicator
import com.titus.dynamicisland.domain.PreviewSample
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The assistant's sub-pages: each capability's switch up top, its timing and visual
 * fine-tuning folded under "Advanced" at the bottom.
 */

private const val NEEDS_CLAUDE = "Needs Claude as the AI."

// ---------------- Read the screen ----------------

@Composable
fun ReadScreenScreen(s: SettingsHandle, onBack: () -> Unit) {
    val v = s.value
    var advanced by rememberSaveable { mutableStateOf(false) }
    val shots = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    SettingsScaffold("Read the screen", onBack, v, preview = PreviewSample.ASSISTANT) {
        item {
            SwitchSetting("Read the screen", v.assistantReadScreen, { on -> s.update { it.copy(assistantReadScreen = on) } },
                summary = "When you ask about what you're looking at; never in the background, never passwords")
        }
        if (shots) {
            item {
                SwitchSetting("Screenshots too", v.assistantScreenshots, { on -> s.update { it.copy(assistantScreenshots = on) } },
                    summary = "For what apps show only as pictures: route time in Waze, photos, charts. Also used by \"Use apps for you\"")
            }
        }
        advanced(if (shots) 2 else 1, if (shots) "how much it reads, screenshot size" else "how much it reads", advanced, { advanced = !advanced }) {
            item {
                IntSliderSetting("Reads at most", v.screenReadMaxChars, 1000..15000, "characters") { n ->
                    s.update { it.copy(screenReadMaxChars = (n / 500) * 500) }
                }
            }
            if (shots) {
                item {
                    IntSliderSetting("Screenshot size", v.screenshotMaxEdge, 640..1568, "px") { n ->
                        s.update { it.copy(screenshotMaxEdge = (n / 32) * 32) }
                    }
                }
            }
        }
        note("What it reads, and the screenshots, go to your AI provider with your question.")
        if (v.assistantBackend != AssistantBackendType.CLAUDE) note(NEEDS_CLAUDE)
    }
}

// ---------------- Use apps for you ----------------

@Composable
fun UseAppsScreen(s: SettingsHandle, onBack: () -> Unit) {
    val v = s.value
    var advanced by rememberSaveable { mutableStateOf(false) }
    SettingsScaffold("Use apps for you", onBack, v, preview = PreviewSample.ASSISTANT) {
        item {
            SwitchSetting("Use apps for you", v.assistantControl, { on -> s.update { it.copy(assistantControl = on) } },
                summary = "Taps, types and scrolls in other apps (\"turn on Wi-Fi\", \"order my usual\")")
        }
        item {
            ChoiceSetting("Asks before", ControlConfirm.entries, v.controlConfirm, { it.label }) { c -> s.update { it.copy(controlConfirm = c) } }
        }
        note("\"Only important ones\": sending, paying, deleting, confirming and all typing. Tapping its card says yes too.")
        item {
            SwitchSetting("Shrink while working", v.controlCompact, { on -> s.update { it.copy(controlCompact = on) } },
                summary = "The island becomes a small pill so it doesn't cover the app")
        }
        advanced(
            6, "steps per question, wait per step, tap anywhere, long press, indicator, step count",
            advanced, { advanced = !advanced },
        ) {
            item { IntSliderSetting("Steps per question", v.controlMaxSteps, 6..40, "") { n -> s.update { it.copy(controlMaxSteps = n) } } }
            item {
                IntSliderSetting("Wait after each step", v.controlStepDelayMs, 200..3000, "ms") { n ->
                    s.update { it.copy(controlStepDelayMs = (n / 100) * 100) }
                }
            }
            item {
                SwitchSetting("Tap anywhere", v.controlTapAnywhere, { on -> s.update { it.copy(controlTapAnywhere = on) } },
                    summary = "Taps by position in apps that hide their buttons (Lieferando & co.). Screen readings get a bit longer")
            }
            item {
                IntSliderSetting("Long press", v.controlLongPressMs, 300..2000, "ms") { n ->
                    s.update { it.copy(controlLongPressMs = (n / 50) * 50) }
                }
            }
            item {
                ChoiceSetting("Indicator", WorkIndicator.entries, v.controlIndicator, { it.label }, summary = "On the small pill") { c ->
                    s.update { it.copy(controlIndicator = c) }
                }
            }
            item { SwitchSetting("Count the steps", v.controlShowSteps, { on -> s.update { it.copy(controlShowSteps = on) } }, summary = "On the small pill") }
            note("Swipe the preview up to see the pill; swipe down or tap to open it again.")
        }
        note("What it sees on screen goes to your AI provider. It never reads or types passwords and never touches this app.")
        if (v.assistantBackend != AssistantBackendType.CLAUDE) note(NEEDS_CLAUDE)
    }
}

// ---------------- Messages ----------------

@Composable
fun MessagesScreen(s: SettingsHandle, onBack: () -> Unit) {
    val v = s.value
    var advanced by rememberSaveable { mutableStateOf(false) }
    SettingsScaffold("Messages", onBack, v, preview = PreviewSample.ASSISTANT) {
        item {
            SwitchSetting("Ask before sending", v.messagesConfirm, { on -> s.update { it.copy(messagesConfirm = on) } },
                summary = "Texts, replies and WhatsApp wait for your yes. Off: they go out as soon as you ask. Calls always ask")
        }
        item {
            SwitchSetting("Press send in WhatsApp", v.whatsappAutoSend, { on -> s.update { it.copy(whatsappAutoSend = on) } },
                summary = "Opens the chat with the text and sends it. Off: it's typed in for you to send")
        }
        item {
            SwitchSetting("Back to where you were", v.messageReturnToApp, { on -> s.update { it.copy(messageReturnToApp = on) } },
                summary = "After sending in WhatsApp, the app you had open (Maps, …) comes back")
        }
        advanced(1, "how long to wait for WhatsApp", advanced, { advanced = !advanced }) {
            item {
                IntSliderSetting("Wait for WhatsApp", v.whatsappSendWaitMs, 2000..15000, "ms") { n ->
                    s.update { it.copy(whatsappSendWaitMs = (n / 500) * 500) }
                }
            }
            note("If WhatsApp is slow to open on your phone, wait longer.")
        }
        note("Pressing send in WhatsApp needs the screen unlocked.")
    }
}

// ---------------- Conversation ----------------

@Composable
fun ConversationScreen(s: SettingsHandle, onBack: () -> Unit) {
    val v = s.value
    var advanced by rememberSaveable { mutableStateOf(false) }
    SettingsScaffold("Conversation", onBack, v, preview = PreviewSample.ASSISTANT) {
        item {
            SwitchSetting("Ends by itself", v.assistantSelfEnd, { on -> s.update { it.copy(assistantSelfEnd = on) } },
                summary = "Stops listening when an answer doesn't end with a question for you")
        }
        item {
            SwitchSetting("Any answer can close it", v.assistantEndAfterAnswer, { on -> s.update { it.copy(assistantEndAfterAnswer = on) } },
                summary = if (v.assistantEndAfterAnswer) "Facts and chat close too, unless it asks something back"
                else "Only finished tasks close it; facts and chat keep listening", enabled = v.assistantSelfEnd)
        }
        if (!v.assistantEndAfterAnswer) item {
            SwitchSetting("Closes after a finished task", v.assistantEndAfterTask, { on -> s.update { it.copy(assistantEndAfterTask = on) } },
                summary = "A timer set or a route started ends it, unless the answer asks something", enabled = v.assistantSelfEnd)
        }
        item {
            IntSliderSetting("Waits for your reply", v.assistantReplyWaitS, 3..30, "s") { n -> s.update { it.copy(assistantReplyWaitS = n) } }
        }
        note("After it asked you something, silence this long still counts as thinking, not leaving.")
        item {
            SegmentedSetting("Offers a next step", FollowUpLevel.entries, v.assistantFollowUps, { it.label }) { l ->
                s.update { it.copy(assistantFollowUps = l) }
            }
        }
        note("\"Route's running. Music?\" A preference you tell it (\"in the car, always play X\") is remembered and used instead of asking.")
        item {
            SwitchSetting("\"Danke\" closes it", v.assistantEndOnThanks, { on -> s.update { it.copy(assistantEndOnThanks = on) } },
                summary = "\"Danke\", \"das war's\", \"thanks, that's it\". \"Danke, kannst du noch …\" doesn't", enabled = v.assistantSelfEnd)
        }
        header("When you open it")
        item {
            SwitchSetting("Speaks first", v.openerEnabled, { on -> s.update { it.copy(openerEnabled = on) } },
                summary = "Before you say anything it offers what fits the moment, or just greets you. Talk over it any time.")
        }
        if (v.openerEnabled) {
            item {
                SwitchSetting("Routines", v.openerRoutines, { on -> s.update { it.copy(openerRoutines = on) } },
                    summary = "What you usually do around now (see Habits)")
            }
            item {
                SwitchSetting("Situation", v.openerSituation, { on -> s.update { it.copy(openerSituation = on) } },
                    summary = "Car or headphones connected, what's playing, a route running, battery")
            }
            item {
                SwitchSetting("Calendar", v.openerCalendar, { on -> s.update { it.copy(openerCalendar = on) } },
                    summary = "Events coming up soon")
            }
            if (v.openerCalendar) {
                item { IntSliderSetting("Coming up means within", v.openerCalendarHours, 1..12, "h") { n -> s.update { it.copy(openerCalendarHours = n) } } }
            }
            item {
                SwitchSetting("Messages", v.openerMessages, { on -> s.update { it.copy(openerMessages = on) } },
                    summary = "Who wrote in the last hours (names and apps only)")
            }
            item {
                SwitchSetting("Greets when nothing's up", v.openerGreets, { on -> s.update { it.copy(openerGreets = on) } },
                    summary = if (v.openerGreets) "A few words of its own choosing" else "Stays quiet and just listens")
            }
            item { IntSliderSetting("Gives up after", v.openerMaxWaitS, 2..10, "s") { n -> s.update { it.copy(openerMaxWaitS = n) } } }
            note("Only what's switched on here goes along, once per opening, as an extra request to your AI provider. If you start talking first, it keeps quiet.")
        }
        advanced(2, "pause before closing, how long an answer stays", advanced, { advanced = !advanced }) {
            item {
                IntSliderSetting("Closes after its answer", v.assistantEndDelayMs, 0..3000, "ms") { n ->
                    s.update { it.copy(assistantEndDelayMs = (n / 100) * 100) }
                }
            }
            item {
                IntSliderSetting("Answer stays for", v.assistantDoneVisibleMs, 1000..10000, "ms") { n ->
                    s.update { it.copy(assistantDoneVisibleMs = (n / 250) * 250) }
                }
            }
            note("\"Answer stays for\": when it ends another way (no microphone, an error), so there's time to read it.")
        }
    }
}

// ---------------- Look ----------------

/** One choice for how the open assistant's island meets the screen; each maps onto the effect flags it switches. */
enum class AssistantEdge(val label: String) { FEATHERED("Feathered"), SOFT_FADE("Soft fade"), GLOW("Glow"), HARD("Hard") }

fun IslandSettings.assistantEdge(): AssistantEdge = when {
    assistantFeather -> AssistantEdge.FEATHERED
    assistantSoftEdge -> AssistantEdge.SOFT_FADE
    assistantGlowSpill -> AssistantEdge.GLOW
    else -> AssistantEdge.HARD
}

fun IslandSettings.withEdge(edge: AssistantEdge) = copy(
    assistantFeather = edge == AssistantEdge.FEATHERED,
    assistantSoftEdge = edge == AssistantEdge.SOFT_FADE,
    assistantGlowSpill = edge == AssistantEdge.GLOW,
)

@Composable
fun AssistantLookScreen(s: SettingsHandle, onBack: () -> Unit) {
    val v = s.value
    var advanced by rememberSaveable { mutableStateOf(false) }
    val edge = v.assistantEdge()
    val edgeKnobs = when (edge) {
        AssistantEdge.FEATHERED -> 4
        AssistantEdge.SOFT_FADE, AssistantEdge.GLOW -> 1
        AssistantEdge.HARD -> 0
    }
    SettingsScaffold("Look", onBack, v, preview = PreviewSample.ASSISTANT) {
        item { SegmentedSetting("Edge", AssistantEdge.entries, edge, { it.label }) { e -> s.update { it.withEdge(e) } } }
        item {
            ColorSetting("Color", v.assistantColor, { c -> s.update { it.copy(assistantColor = c) } }, summary = "Voice glow and cards")
        }
        item {
            SegmentedSetting("What it did", ActionStyle.entries, v.assistantActionStyle, { it.label }) { st ->
                s.update { it.copy(assistantActionStyle = st) }
            }
        }
        item {
            SwitchSetting("ASCII background", v.assistantAscii, { on -> s.update { it.copy(assistantAscii = on) } },
                summary = "Drifting characters behind the text")
        }
        val edgeHint = when (edge) {
            AssistantEdge.FEATHERED -> "reach, softness and strength of the feathered edge · "
            AssistantEdge.SOFT_FADE -> "where the fade starts · "
            AssistantEdge.GLOW -> "how far the glow reaches · "
            AssistantEdge.HARD -> ""
        }
        advanced(edgeKnobs + 5, edgeHint + "ascii opacity, size, speed, grain, color", advanced, { advanced = !advanced }) {
            when (edge) {
                AssistantEdge.FEATHERED -> {
                    item { IntSliderSetting("Halo reach", v.featherSpreadDp, 0..40) { n -> s.update { it.copy(featherSpreadDp = n) } } }
                    item { IntSliderSetting("Halo below", v.featherBottomDp, 0..40) { n -> s.update { it.copy(featherBottomDp = n) } } }
                    item { IntSliderSetting("Softness", v.featherBlurDp, 2..40) { n -> s.update { it.copy(featherBlurDp = n) } } }
                    item {
                        SliderSetting("Halo strength", v.featherOpacity, 0.1f..1f, { f -> s.update { it.copy(featherOpacity = f) } },
                            format = { "${(it * 100).roundToInt()}%" })
                    }
                }
                AssistantEdge.SOFT_FADE -> item {
                    SliderSetting("Fade starts at", v.assistantFadeStart, 0.1f..0.9f, { f -> s.update { it.copy(assistantFadeStart = f) } },
                        format = { "${(it * 100).roundToInt()}% of the height" })
                }
                AssistantEdge.GLOW -> item {
                    IntSliderSetting("Glow reach", v.assistantGlowReachDp, 0..40) { n -> s.update { it.copy(assistantGlowReachDp = n) } }
                }
                AssistantEdge.HARD -> {}
            }
            item {
                SliderSetting("ASCII opacity", v.asciiOpacity, 0.05f..1f, { f -> s.update { it.copy(asciiOpacity = f) } },
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
                SwitchSetting("ASCII in the assistant's color", v.asciiAccent, { on -> s.update { it.copy(asciiAccent = on) } },
                    summary = "Otherwise white")
            }
            if (!v.assistantAscii) note("The ASCII settings apply once the ASCII background is on.")
        }
    }
}
