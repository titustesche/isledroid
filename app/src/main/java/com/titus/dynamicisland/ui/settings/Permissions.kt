package com.titus.dynamicisland.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.titus.dynamicisland.service.IslandAccessibilityService

data class PermissionStatus(
    val accessibility: Boolean,
    val notificationAccess: Boolean,
    val batteryUnrestricted: Boolean,
) {
    val ready get() = accessibility && notificationAccess
}

object Permissions {
    fun status(context: Context) = PermissionStatus(
        accessibility = isAccessibilityEnabled(context),
        notificationAccess = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context),
        batteryUnrestricted = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName),
    )

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val ours = ComponentName(context, IslandAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
    }

    fun accessibilitySettings() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    fun notificationAccessSettings() = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    fun batterySettings() = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
}
