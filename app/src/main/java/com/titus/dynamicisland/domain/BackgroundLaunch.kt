package com.titus.dynamicisland.domain

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log

/** Lets our background-activity-start privilege (granted via the accessibility service) apply. */
@Suppress("DEPRECATION")
fun backgroundStartOptions(): Bundle? = when {
    Build.VERSION.SDK_INT >= 36 -> ActivityOptions.makeBasic()
        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
        .toBundle()
    Build.VERSION.SDK_INT >= 34 -> ActivityOptions.makeBasic()
        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        .toBundle()
    else -> null
}

/** Starts an activity from the island (not from an activity of ours). False if nothing could open it. */
fun Context.startFromBackground(intent: Intent): Boolean = try {
    startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), backgroundStartOptions())
    true
} catch (e: ActivityNotFoundException) {
    Log.w("BackgroundLaunch", "Nothing handles $intent")
    false
} catch (e: SecurityException) {
    Log.w("BackgroundLaunch", "Not allowed to start $intent", e)
    false
}

fun PendingIntent.sendFromBackground(context: Context, fillIn: Intent? = null): Boolean = try {
    send(context, 0, fillIn, null, null, null, backgroundStartOptions())
    true
} catch (e: PendingIntent.CanceledException) {
    Log.w("BackgroundLaunch", "PendingIntent cancelled", e)
    false
}
