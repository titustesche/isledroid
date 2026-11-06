package com.titus.dynamicisland.domain

import android.Manifest
import android.app.RemoteInput
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.os.Looper
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import android.text.format.DateUtils
import androidx.core.content.ContextCompat
import com.titus.dynamicisland.data.assistant.AssistantCard
import com.titus.dynamicisland.data.assistant.AssistantTool
import com.titus.dynamicisland.data.assistant.CardAction
import com.titus.dynamicisland.data.assistant.CardIcon
import com.titus.dynamicisland.data.assistant.ToolException
import com.titus.dynamicisland.data.assistant.ToolInput
import com.titus.dynamicisland.data.assistant.ToolParam
import com.titus.dynamicisland.data.settings.IslandSettings
import com.titus.dynamicisland.service.ScreenReader
import java.util.Locale
import com.titus.dynamicisland.data.notifications.NotificationRepository
import com.titus.dynamicisland.data.notifications.RecentNotification

/**
 * Actions that reach other people (texts, replies, calls) never run straight away: the tool prepares
 * them and the user has to say yes in their *next* turn, or tap the card. That's enforced here, not
 * just asked for in the prompt: [confirm] refuses in the same turn the action was prepared.
 */
class Confirmations {
    class Pending(
        val id: Long,
        /** The question it was prepared in; the yes has to come in a later one. */
        val question: Int,
        val card: AssistantCard,
        /** Carries it out; returns the result for the model and the card showing it's done. */
        val run: () -> Pair<String, AssistantCard>,
    )

    var question = 0
        private set
    var pending: Pending? = null
        private set
    private var nextId = 1L

    fun nextQuestion() { question++ }
    fun clear() { pending = null }

    /** Replaces whatever waited before. Returns the card to show. */
    fun prepare(card: (id: Long) -> AssistantCard, run: () -> Pair<String, AssistantCard>): AssistantCard {
        val id = nextId++
        val c = card(id)
        pending = Pending(id, question, c, run)
        return c
    }

    /** Carries out the pending action, if it's still [id]. Throws [ToolException] if it fails. */
    fun execute(id: Long): Pair<String, AssistantCard>? {
        val p = pending?.takeIf { it.id == id } ?: return null
        pending = null
        return p.run()
    }
}

class CommsTools(
    private val context: Context,
    private val notifications: NotificationRepository,
    private val confirmations: Confirmations,
    private val settings: () -> IslandSettings,
) {
    fun all(): List<AssistantTool> = listOf(readNotifications, replyToNotification, findContact, call, sendText, sendMessage, confirm)

    /**
     * A message goes out on a yes (see [Confirmations]) unless the user switched that off for messages;
     * then right away. Calls always wait.
     */
    private fun dispatch(i: ToolInput, card: (id: Long) -> AssistantCard, run: () -> Pair<String, AssistantCard>): String {
        if (settings().messagesConfirm) {
            i.show(confirmations.prepare(card, run))
            return AWAITING
        }
        val (result, done) = run()
        i.show(done)
        return result
    }

    // ---------------- Notifications ----------------

    /** Short, stable numbers for notifications, so the model can refer to one. */
    private val ids = mutableMapOf<String, Int>()
    private fun idOf(key: String) = ids.getOrPut(key) { ids.size + 1 }

    private val readNotifications = assistantTool(
        "read_notifications",
        "The notifications in the phone's shade, newest first: messages, mails, app updates. Each has an id for " +
            "reply_to_notification.",
        ToolParam("filter", "string", "Optional: only those from this person or app, e.g. \"Anna\" or \"WhatsApp\".", required = false),
    ) { i ->
        val filter = i.optString("filter")?.lowercase()
        val all = notifications.recent
        val matching = if (filter == null) all else all.filter { n ->
            listOfNotNull(n.title, n.appLabel, n.text).any { filter in it.lowercase() } ||
                n.messages.any { filter in it.lowercase().substringBefore(':') }
        }
        if (matching.isEmpty()) return@assistantTool if (filter == null) "No notifications." else "Nothing from \"${i.optString("filter")}\"."
        matching.take(3).forEach { n ->
            i.show(
                AssistantCard(
                    "notification-${n.key}", CardIcon.NOTIFICATION, n.title ?: n.appLabel,
                    n.messages.lastOrNull()?.substringAfter(": ") ?: n.text ?: n.appLabel,
                    label = n.appLabel,
                    appPackage = n.packageName,
                    action = n.contentIntent?.let { CardAction.Send(it) } ?: launchAction(n.packageName),
                ),
            )
        }
        matching.take(MAX_LISTED).joinToString("\n") { n -> describe(n) } +
            if (matching.size > MAX_LISTED) "\n… and ${matching.size - MAX_LISTED} more." else ""
    }

    private fun describe(n: RecentNotification): String {
        val ago = DateUtils.getRelativeTimeSpanString(n.postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        val content = n.messages.takeIf { it.isNotEmpty() }?.joinToString(" | ") ?: n.text.orEmpty()
        return "id ${idOf(n.key)} · ${n.appLabel} · ${n.title.orEmpty()} ($ago): $content" +
            if (n.reply != null) " [can reply]" else ""
    }

    private val replyToNotification = assistantTool(
        "reply_to_notification",
        "Reply to a message notification (from read_notifications). If the result says it's prepared, it is NOT sent " +
            "yet: read the reply back briefly and ask whether to send it, then call confirm with the user's answer.",
        ToolParam("id", "integer", "The notification's id from read_notifications."),
        ToolParam("message", "string", "The reply, exactly as it should be sent."),
    ) { i ->
        val id = i.int("id")
        val message = i.string("message")
        val n = notifications.recent.firstOrNull { idOf(it.key) == id }
            ?: throw ToolException("No notification with id $id any more. Call read_notifications again.")
        val reply = n.reply ?: throw ToolException("${n.appLabel} doesn't allow replying to this notification from outside the app.")
        val to = n.title ?: n.appLabel
        dispatch(
            i,
            card = { cid ->
                AssistantCard(
                    "reply-${n.key}", CardIcon.MESSAGE, to, message, label = n.appLabel, appPackage = n.packageName,
                    action = CardAction.Confirm(cid), awaitingConfirmation = true,
                )
            },
        ) {
            val input = reply.remoteInput ?: throw ToolException("This notification can't take a reply.")
            val fillIn = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            RemoteInput.addResultsToIntent(arrayOf(input), fillIn, Bundle().apply { putCharSequence(input.resultKey, message) })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
            if (!reply.intent.sendFromBackground(context, fillIn)) throw ToolException("${n.appLabel} didn't take the reply.")
            "Reply sent to $to." to AssistantCard(
                "reply-${n.key}", CardIcon.MESSAGE, to, message, label = n.appLabel, appPackage = n.packageName,
                action = n.contentIntent?.let { CardAction.Send(it) },
            )
        }
    }

    // ---------------- Contacts, calls, texts ----------------

    private data class Number(val number: String, val type: Int, val label: String?, val primary: Boolean) {
        val typeName get() = when (type) {
            Phone.TYPE_MOBILE -> "mobile"
            Phone.TYPE_HOME -> "home"
            Phone.TYPE_WORK, Phone.TYPE_WORK_MOBILE -> "work"
            else -> label?.lowercase() ?: "other"
        }
    }

    private data class Contact(val id: Long, val lookupKey: String, val name: String, val numbers: List<Number>)

    private val findContact = assistantTool(
        "find_contact",
        "Look up a contact's phone numbers by name.",
        ToolParam("name", "string", "The name as the user said it."),
    ) { i ->
        val found = contacts(i.string("name"))
        if (found.isEmpty()) throw ToolException("No contact matches \"${i.string("name")}\".")
        found.take(2).forEach { c ->
            i.show(AssistantCard("contact-${c.id}", CardIcon.CONTACT, c.name, c.numbers.firstOrNull()?.number, label = "Contact", action = viewContact(c)))
        }
        found.take(5).joinToString("\n") { c ->
            "${c.name}: " + c.numbers.joinToString(", ") { "${it.typeName} ${it.number}" }.ifEmpty { "no phone number" }
        }
    }

    private val numberType = ToolParam(
        "number_type", "string", "Optional: which of the contact's numbers, if the user said.",
        required = false, enum = listOf("mobile", "home", "work", "other"),
    )

    private val call = assistantTool(
        "call",
        "Prepare a phone call to a contact or number. It does NOT dial yet: ask \"Call Anna?\" (or similar) and then " +
            "call confirm with the user's answer.",
        ToolParam("contact", "string", "A contact's name, or a phone number."),
        numberType,
    ) { i ->
        if (!granted(Manifest.permission.CALL_PHONE)) throw ToolException(missing("phone calls"))
        val (name, number) = resolve(i.string("contact"), i.optString("number_type"))
        i.show(
            confirmations.prepare(
                card = { id -> AssistantCard("call-$number", CardIcon.CALL, name, number, label = "Call", action = CardAction.Confirm(id), awaitingConfirmation = true) },
            ) {
                if (!context.startFromBackground(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)))) {
                    throw ToolException("Android didn't let the call start.")
                }
                "Calling $name." to AssistantCard("call-$number", CardIcon.CALL, name, number, label = "Calling", action = CardAction.ShowCall)
            },
        )
        AWAITING
    }

    private val sendText = assistantTool(
        "send_text",
        "Text message (SMS) to a contact or number. If the result says it's prepared, it is NOT sent yet: read it back " +
            "briefly and ask whether to send it, then call confirm with the user's answer. For WhatsApp use send_message; " +
            "to answer a chat notification, reply_to_notification.",
        ToolParam("contact", "string", "A contact's name, or a phone number."),
        ToolParam("message", "string", "The text, exactly as it should be sent."),
        numberType,
    ) { i ->
        if (!granted(Manifest.permission.SEND_SMS)) throw ToolException(missing("text messages"))
        val (name, number) = resolve(i.string("contact"), i.optString("number_type"))
        val message = i.string("message")
        dispatch(
            i,
            card = { id ->
                AssistantCard("sms-$number", CardIcon.MESSAGE, name, message, label = "SMS", action = CardAction.Confirm(id), awaitingConfirmation = true)
            },
        ) {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
            runCatching { sms.sendMultipartTextMessage(number, null, sms.divideMessage(message), null, null) }
                .onFailure { throw ToolException("The text couldn't be sent: ${it.message}") }
            "Text sent to $name." to AssistantCard(
                "sms-$number", CardIcon.MESSAGE, name, message, label = "SMS",
                action = CardAction.Launch(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))),
            )
        }
    }

    private val sendMessage = assistantTool(
        "send_message",
        "Write someone on WhatsApp, on your own initiative or when asked, also when there's no notification to reply " +
            "to. If the result says it's prepared, it is NOT sent yet: read it back briefly and ask, then call confirm " +
            "with the user's answer.",
        ToolParam("contact", "string", "A contact's name, or a phone number."),
        ToolParam("message", "string", "The message, exactly as it should be sent."),
        numberType,
        onMain = false, // sending waits for WhatsApp's chat to open
    ) { i ->
        val pkg = WHATSAPP_PACKAGES.firstOrNull(::installed) ?: throw ToolException("WhatsApp isn't installed.")
        val (name, number) = resolve(i.string("contact"), i.optString("number_type"))
        val phone = internationalDigits(number)
            ?: throw ToolException("$number has no country code and the phone's country is unknown; ask for the full number.")
        val message = i.string("message")
        dispatch(
            i,
            card = { id ->
                AssistantCard(
                    "wa-$phone", CardIcon.MESSAGE, name, message, label = "WhatsApp", appPackage = pkg,
                    action = CardAction.Confirm(id), awaitingConfirmation = true,
                )
            },
        ) { sendWhatsApp(pkg, name, phone, message) }
    }

    /** Opens the chat with the text typed in and, unless switched off, presses send. */
    private fun sendWhatsApp(pkg: String, name: String, phone: String, message: String): Pair<String, AssistantCard> {
        val chat = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=${Uri.encode(message)}"))
            .setPackage(pkg)
        val before = ScreenReader.foregroundPackage()?.takeIf { it != pkg }
        if (!context.startFromBackground(chat)) throw ToolException("WhatsApp didn't open.")
        val s = settings()
        val card = AssistantCard("wa-$phone", CardIcon.MESSAGE, name, message, label = "WhatsApp", appPackage = pkg)
        if (!s.whatsappAutoSend) {
            return "WhatsApp is open with the message typed in; the user sends it." to card.copy(label = "WhatsApp · not sent")
        }
        val press = {
            ScreenReader.clickWhenShown(pkg, WHATSAPP_SEND_IDS, SEND_LABELS, s.whatsappSendWaitMs.toLong()).also { sent ->
                if (sent && s.messageReturnToApp) returnTo(before)
            }
        }
        // A tap on the card runs this on the main thread, which must not wait for WhatsApp.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Thread { if (!press()) Log.w(TAG, "WhatsApp's send button didn't show") }.start()
            return "Sending to $name." to card
        }
        if (!press()) {
            throw ToolException(
                "WhatsApp opened with the message, but its send button didn't show (the phone may be locked, or $name " +
                    "isn't on WhatsApp). The text is typed in there.",
            )
        }
        return "Sent to $name on WhatsApp." to card
    }

    /** Brings back the app the user was in (its task, as they left it); the home screen if that's where they were. */
    private fun returnTo(pkg: String?) {
        Thread.sleep(RETURN_DELAY_MS) // let the message leave before WhatsApp goes to the background
        val launch = pkg?.let { context.packageManager.getLaunchIntentForPackage(it) }
        if (launch != null) context.startFromBackground(launch)
        else ScreenReader.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
    }

    /** "0176 123", "+49 176 123" → "49176123", using the SIM's country for local numbers. */
    private fun internationalDigits(number: String): String? {
        val trimmed = number.trim()
        if (trimmed.startsWith("+")) return trimmed.filter(Char::isDigit)
        if (trimmed.startsWith("00")) return trimmed.filter(Char::isDigit).removePrefix("00")
        val tm = context.getSystemService(TelephonyManager::class.java)
        val iso = listOfNotNull(tm?.simCountryIso, tm?.networkCountryIso, Locale.getDefault().country)
            .firstOrNull { it.isNotBlank() }?.uppercase() ?: return null
        return PhoneNumberUtils.formatNumberToE164(trimmed, iso)?.filter(Char::isDigit)
    }

    private fun installed(pkg: String) =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private val confirm = assistantTool(
        "confirm",
        "Carry out the prepared action (a message, reply or call, or a step in an app) once the user answered your question about it, or drop it. " +
            "Only in the user's next turn, never in the turn you prepared it.",
        ToolParam("approved", "boolean", "true if the user said yes; false if no, or they changed their mind."),
        onMain = false, // sending on WhatsApp waits for its chat to open
    ) { i ->
        val p = confirmations.pending ?: throw ToolException("Nothing is waiting for confirmation (it may have been sent with a tap already).")
        if (p.question == confirmations.question) {
            throw ToolException("The user hasn't answered yet. Ask them and wait for their reply.")
        }
        if (!i.bool("approved")) {
            confirmations.clear()
            i.show(p.card.copy(subtitle = "Cancelled", action = null, awaitingConfirmation = false))
            return@assistantTool "Dropped. Nothing was sent."
        }
        val (result, card) = confirmations.execute(p.id) ?: throw ToolException("Nothing is waiting for confirmation.")
        i.show(card)
        result
    }

    /** A contact's name (or a raw number) → the name to say and the number to use. */
    private fun resolve(who: String, type: String?): Pair<String, String> {
        val digits = who.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 3 && who.none { it.isLetter() }) return digits to digits
        val found = contacts(who)
        if (found.isEmpty()) throw ToolException("No contact matches \"$who\".")
        if (found.size > 1 && !found[0].name.equals(who, ignoreCase = true)) {
            throw ToolException("Several contacts match: ${found.take(5).joinToString { it.name }}. Ask which one.")
        }
        val c = found[0]
        if (c.numbers.isEmpty()) throw ToolException("${c.name} has no phone number.")
        val choice = when {
            type != null -> c.numbers.firstOrNull { it.typeName == type }
                ?: throw ToolException("${c.name} has no $type number, only: ${c.numbers.joinToString { it.typeName }}.")
            c.numbers.size == 1 -> c.numbers[0]
            else -> c.numbers.firstOrNull { it.primary } ?: c.numbers.firstOrNull { it.type == Phone.TYPE_MOBILE }
                ?: throw ToolException("${c.name} has several numbers: ${c.numbers.joinToString { it.typeName }}. Ask which one.")
        }
        return c.name to choice.number
    }

    /** Contacts whose name contains every word of [query], best match first. */
    private fun contacts(query: String): List<Contact> {
        if (!granted(Manifest.permission.READ_CONTACTS)) throw ToolException(missing("contacts"))
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val byId = LinkedHashMap<Long, Contact>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.LOOKUP_KEY, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.IS_SUPER_PRIMARY),
            "${Phone.DISPLAY_NAME_PRIMARY} LIKE ?", arrayOf("%${words.first()}%"), null,
        )?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getLong(0)
                val name = cur.getString(2) ?: continue
                if (!words.all { it in name.lowercase() }) continue
                val number = Number(cur.getString(3) ?: continue, cur.getInt(4), cur.getString(5), cur.getInt(6) != 0)
                val c = byId[id] ?: Contact(id, cur.getString(1).orEmpty(), name, emptyList())
                if (c.numbers.none { it.number.filter(Char::isDigit) == number.number.filter(Char::isDigit) }) {
                    byId[id] = c.copy(numbers = c.numbers + number)
                }
            }
        }
        val q = query.lowercase().trim()
        return byId.values.sortedWith(
            compareBy({ c -> val n = c.name.lowercase(); if (n == q) 0 else if (n.startsWith(q)) 1 else 2 }, { it.name.length }),
        )
    }

    private fun viewContact(c: Contact) = CardAction.Launch(
        Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.getLookupUri(c.id, c.lookupKey) ?: ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, c.id)),
    )

    private fun launchAction(pkg: String) = context.packageManager.getLaunchIntentForPackage(pkg)?.let { CardAction.Launch(it) }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    internal companion object {
        const val MAX_LISTED = 8
        const val TAG = "CommsTools"
        const val RETURN_DELAY_MS = 700L
        val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
        val WHATSAPP_SEND_IDS = listOf("send", "send_container")
        val SEND_LABELS = listOf("Send", "Senden", "Enviar", "Envoyer", "Invia", "Verzenden", "Wyślij", "Gönder")
        const val AWAITING: String = "Prepared, NOT done yet. Ask the user to confirm (a few words) and wait for their answer."
        fun missing(what: String) = "Dynamic Island isn't allowed to use $what. It can be allowed in Dynamic Island → Assistant → Tool permissions."
    }
}
