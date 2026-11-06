package com.titus.dynamicisland.ui.settings

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.titus.dynamicisland.appGraph

/**
 * The "Replace system pop-ups" switch. Turning it on the first time pairs a companion device:
 * Android only lets a companion app change other apps' notification channels. Any device will
 * do, it's never connected to or used.
 */
@Composable
fun PopupReplacementSetting(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val pairing = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        // The result arrives through the association callback below; a cancel just leaves it off.
    }
    SwitchSetting(
        "Replace system pop-ups",
        enabled,
        { on ->
            if (!on || context.appGraph.popups.hasAssociation()) {
                onChange(on)
                return@SwitchSetting
            }
            val cdm = context.getSystemService(CompanionDeviceManager::class.java)
            cdm.associate(
                AssociationRequest.Builder().build(),
                context.mainExecutor,
                object : CompanionDeviceManager.Callback() {
                    override fun onAssociationPending(intentSender: IntentSender) {
                        pairing.launch(IntentSenderRequest.Builder(intentSender).build())
                    }

                    override fun onAssociationCreated(associationInfo: AssociationInfo) = onChange(true)

                    override fun onFailure(error: CharSequence?) {
                        Toast.makeText(context, "Pairing failed${error?.let { ": $it" } ?: ""}", Toast.LENGTH_LONG).show()
                    }
                },
            )
        },
        summary = "Stops Android's own pop-up for notifications the island shows. They still sound, vibrate and " +
            "stay in the notification shade. Calls and alarms keep theirs.",
    )
    if (!enabled) {
        Hint(
            "Turning this on asks you to pick a Bluetooth or Wi-Fi device, once. Any device works; it's never " +
                "connected or used. Android only lets apps paired with a device change other apps' pop-up settings.",
        )
    } else {
        Hint("Turning this off puts every app's pop-up settings back the way they were.")
    }
}
