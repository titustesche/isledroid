package com.titus.dynamicisland.data.assistant

import android.app.PendingIntent
import android.content.Intent

/** What a card's glyph shows. */
enum class CardIcon {
    TIMER, ALARM, FLASHLIGHT, DND, RING, VIBRATE, SILENT, VOLUME, MUSIC, APP, WEB, BATTERY,
    SUN, PARTLY_CLOUDY, CLOUD, FOG, RAIN, SNOW, STORM,
    CALENDAR, CONTACT, CALL, MESSAGE, NAVIGATION, NOTIFICATION, LOCATION, NOTE, POWER, HOME,
}

/** Cards that show the phone's current state instead of a frozen snapshot. */
enum class CardLive { TORCH, DND, MEDIA }

/** What tapping a card does. */
sealed interface CardAction {
    /** Opens something; the conversation is over at that point. */
    data class Launch(val intent: Intent) : CardAction
    data class Send(val intent: PendingIntent) : CardAction
    /** Opens whichever player is playing right now. */
    data object OpenPlayer : CardAction
    data object ToggleTorch : CardAction
    data object ToggleDnd : CardAction
    /** Opens the system volume panel for that stream. */
    data class ShowVolume(val stream: Int) : CardAction
    /** Carries out the action that waits for a yes (sending a message, calling). */
    data class Confirm(val id: Long) : CardAction
    /** Brings up the ongoing call's screen. */
    data object ShowCall : CardAction
}

/**
 * Something the assistant did or found, shown under its answer as a tappable item: the alarm it set,
 * the event it found, the message waiting to be sent …
 */
data class AssistantCard(
    /** A card with the same key replaces the earlier one (flashlight on, then dimmed: one card). */
    val key: String,
    val icon: CardIcon,
    val title: String,
    val subtitle: String? = null,
    /** Small caption above the title, in the assistant's color ("Alarm · tomorrow", "WhatsApp"). */
    val label: String? = null,
    /** A second, quieter line (the place for weather, the app that routes). */
    val detail: String? = null,
    /** 0..1: drawn as a level bar (volume, battery). */
    val level: Float? = null,
    /** Shows that app's icon instead of [icon]. */
    val appPackage: String? = null,
    /** Wall-clock end time: the title is replaced by the time left. */
    val countdownTo: Long? = null,
    /** With [countdownTo]: the whole duration in ms, so the time left can be drawn as a bar. */
    val countdownTotalMs: Long? = null,
    val live: CardLive? = null,
    val action: CardAction? = null,
    /** Waiting for a yes: highlighted, with "Tap to …" as its subtitle. */
    val awaitingConfirmation: Boolean = false,
)

/** A web page the answer was based on. */
data class WebSource(val title: String, val url: String)
