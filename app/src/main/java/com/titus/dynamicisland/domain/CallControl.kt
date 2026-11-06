package com.titus.dynamicisland.domain

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.titus.dynamicisland.data.notifications.CallRole

/**
 * Answers and ends phone calls through Android itself instead of the phone app's notification buttons.
 * Those buttons are often activities (Samsung's open the call screen instead of answering), while
 * Telecom does exactly what the button says. Only for the phone's own calls: VoIP apps (WhatsApp …)
 * keep their buttons, Telecom doesn't run their calls.
 */
class CallControl(private val context: Context) {

    private val telecom get() = context.getSystemService(TelecomManager::class.java)

    val allowed get() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED

    /** Whether [pkg] posts the phone's own (cellular) calls. */
    fun isPhoneApp(pkg: String): Boolean {
        if (pkg in PHONE_PACKAGES || "incallui" in pkg) return true
        return runCatching { telecom.defaultDialerPackage }.getOrNull() == pkg
    }

    /** Carries out [role] for a call of [pkg]; false if Telecom can't, and the app's own button should be used. */
    @SuppressLint("MissingPermission")
    fun perform(role: CallRole, pkg: String): Boolean {
        if (!allowed || !isPhoneApp(pkg)) return false
        return runCatching {
            when (role) {
                CallRole.ANSWER -> { @Suppress("DEPRECATION") telecom.acceptRingingCall(); true }
                CallRole.DECLINE, CallRole.HANG_UP -> @Suppress("DEPRECATION") telecom.endCall()
                CallRole.SPEAKER, CallRole.MUTE -> false
            }
        }.onFailure { Log.w(TAG, "Telecom couldn't $role", it) }
            .getOrDefault(false)
            .also { Log.d(TAG, "$role via Telecom for $pkg: $it") }
    }

    private companion object {
        const val TAG = "CallControl"
        val PHONE_PACKAGES = setOf(
            "com.samsung.android.incallui", "com.samsung.android.dialer", "com.android.incallui", "com.android.dialer",
            "com.google.android.dialer", "com.android.server.telecom", "com.android.phone",
        )
    }
}
