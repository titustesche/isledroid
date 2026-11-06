package com.titus.dynamicisland

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import com.titus.dynamicisland.ui.settings.SettingsApp
import com.titus.dynamicisland.ui.theme.DynamicIslandTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Light status and navigation bar icons on the settings' black.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContent { DynamicIslandTheme { SettingsApp() } }
    }

    // "Try it now" in the settings: follow-up questions may use the microphone while this is in front.
    override fun onStart() {
        super.onStart()
        appGraph.assistant.hostShown()
    }

    override fun onStop() {
        appGraph.assistant.hostHidden()
        super.onStop()
    }
}