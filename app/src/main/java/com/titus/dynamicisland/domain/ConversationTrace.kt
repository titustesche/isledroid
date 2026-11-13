package com.titus.dynamicisland.domain

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Why the assistant kept listening or closed, after each answer, kept on the phone (assistant-trace.txt,
 * the last [MAX_LINES] lines). Logcat forgets within minutes; this survives until the next days' questions.
 * Read with `adb shell dumpsys activity service com.titus.dynamicisland/.service.IslandAccessibilityService trace`.
 */
class ConversationTrace(context: Context) {
    private val file = File(context.filesDir, "assistant-trace.txt")
    private val writer = Executors.newSingleThreadExecutor()
    private val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)

    fun add(line: String) {
        val stamped = "${time.format(Date())}  $line"
        Log.i(TAG, line)
        writer.execute {
            runCatching {
                val lines = (if (file.exists()) file.readLines() else emptyList()) + stamped
                file.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
            }.onFailure { Log.w(TAG, "Couldn't write the trace", it) }
        }
    }

    fun text(): String = runCatching { writer.submit<String> { if (file.exists()) file.readText() else "" }.get() }.getOrDefault("")

    private companion object {
        const val TAG = "AssistantTrace"
        const val MAX_LINES = 400
    }
}
