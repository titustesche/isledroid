package com.titus.dynamicisland

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.lifecycleScope
import com.titus.dynamicisland.service.AssistantMicService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Started by the system when Dynamic Island is the default digital assistant (side key / corner swipe).
 * It is fully transparent and only exists so the app is "in the foreground" and may use the
 * microphone — Android doesn't let background apps listen. It hands over to [AssistantMicService]
 * and closes; only if Android refuses that does it stay for the conversation.
 */
class AssistActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        val assistant = appGraph.assistant
        setContent {
            // Tapping anywhere outside the island ends the conversation, like dismissing a system assistant.
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { assistant.stop("tapped outside"); finish() } })
            BackHandler { assistant.stop("back"); finish() }
        }
        assistant.start()
        lifecycleScope.launch {
            // Leave as soon as the microphone service has taken over, so the app below keeps running.
            // If Android refuses the service, stay until the conversation ends (or listening breaks).
            combine(assistant.state, AssistantMicService.running) { st, mic -> st == null || mic }.first { it }
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        appGraph.assistant.hostShown()
    }

    override fun onStop() {
        appGraph.assistant.hostHidden()
        super.onStop()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
