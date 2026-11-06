package com.titus.dynamicisland.data.assistant

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AssistantKeys(
    val claude: String = "",
    val openAi: String = "",
    val elevenLabs: String = "",
    val googleTts: String = "",
)

/**
 * API keys live in their own preferences file, kept out of the settings DataStore and excluded
 * from cloud backup / device transfer (see res/xml/backup_rules.xml and data_extraction_rules.xml).
 */
class AssistantSecrets(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _keys = MutableStateFlow(
        AssistantKeys(
            prefs.getString(CLAUDE, "").orEmpty(),
            prefs.getString(OPENAI, "").orEmpty(),
            prefs.getString(ELEVENLABS, "").orEmpty(),
            prefs.getString(GOOGLE_TTS, "").orEmpty(),
        ),
    )
    val keys: StateFlow<AssistantKeys> = _keys.asStateFlow()

    fun setClaudeKey(key: String) {
        prefs.edit { putString(CLAUDE, key.trim()) }
        _keys.value = _keys.value.copy(claude = key.trim())
    }

    fun setOpenAiKey(key: String) {
        prefs.edit { putString(OPENAI, key.trim()) }
        _keys.value = _keys.value.copy(openAi = key.trim())
    }

    fun setElevenLabsKey(key: String) {
        prefs.edit { putString(ELEVENLABS, key.trim()) }
        _keys.value = _keys.value.copy(elevenLabs = key.trim())
    }

    fun setGoogleTtsKey(key: String) {
        prefs.edit { putString(GOOGLE_TTS, key.trim()) }
        _keys.value = _keys.value.copy(googleTts = key.trim())
    }

    companion object {
        const val FILE = "assistant_secrets"
        private const val CLAUDE = "claude_key"
        private const val OPENAI = "openai_key"
        private const val ELEVENLABS = "elevenlabs_key"
        private const val GOOGLE_TTS = "google_tts_key"
    }
}
