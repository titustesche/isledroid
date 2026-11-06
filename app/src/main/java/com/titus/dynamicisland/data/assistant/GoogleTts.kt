package com.titus.dynamicisland.data.assistant

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.job
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.coroutineContext

data class GoogleVoice(val name: String, val gender: String) {
    /** "de-DE-Chirp3-HD-Charon" -> "Charon". */
    val label get() = name.substringAfterLast('-')
    val languageCode get() = name.split('-').take(2).joinToString("-")
}

/**
 * Google Cloud Text-to-Speech with its Chirp 3 HD voices (the first million characters each month
 * are free). Plain REST with an API key; each sentence comes back as one piece of 24 kHz PCM.
 */
object GoogleTts {
    private const val BASE = "https://texttospeech.googleapis.com/v1"
    const val SAMPLE_RATE = 24_000
    private const val MODEL = "Chirp3-HD"

    /** A calm, low male voice; the island's default when none is chosen. */
    fun defaultVoice(locale: Locale = Locale.getDefault()): String = "${languageTag(locale)}-$MODEL-Charon"

    /** [chosen] if set, else the default for the phone's language. */
    fun resolve(chosen: String, locale: Locale = Locale.getDefault()) = chosen.ifBlank { defaultVoice(locale) }

    private fun languageTag(locale: Locale): String {
        val country = locale.country.ifBlank {
            when (locale.language) {
                "en" -> "US"
                "de" -> "DE"
                "ja" -> "JP"
                "ko" -> "KR"
                "da" -> "DK"
                "sv" -> "SE"
                "cs" -> "CZ"
                "uk" -> "UA"
                else -> locale.language.uppercase()
            }
        }
        return "${locale.language}-$country"
    }

    /** The Chirp 3 HD voices for [locale]'s language and country. Blocking; call off the main thread. */
    fun voices(apiKey: String, locale: Locale = Locale.getDefault()): List<GoogleVoice> {
        val tag = languageTag(locale)
        val conn = (URL("$BASE/voices?languageCode=$tag&key=${enc(apiKey)}").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw AssistantException(errorMessage(code, conn.errorStream?.bufferedReader()?.use { it.readText() }))
            val arr = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("voices")
                ?: return emptyList()
            return (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.getString("name").startsWith("$tag-$MODEL-") }
                .map { GoogleVoice(it.getString("name"), genderLabel(it.optString("ssmlGender"))) }
                .sortedBy { it.label }
        } catch (e: IOException) {
            throw AssistantException("No connection to Google.", e)
        } finally {
            conn.disconnect()
        }
    }

    fun errorMessage(code: Int, body: String?): String = when {
        body?.contains("API_KEY_INVALID") == true -> "Google rejected the API key."
        body?.contains("BILLING_DISABLED") == true || body?.contains("billing", ignoreCase = true) == true && code == 403 ->
            "Google Cloud needs billing enabled for Text-to-Speech."
        body?.contains("SERVICE_DISABLED") == true || body?.contains("has not been used") == true ->
            "Enable the Cloud Text-to-Speech API in your Google Cloud project."
        body?.contains("API_KEY_SERVICE_BLOCKED") == true -> "This API key isn't allowed to use Text-to-Speech."
        code == 429 -> "Google's speech quota is used up for now."
        code == 400 && body?.contains("voice", ignoreCase = true) == true -> "That Google voice doesn't exist (any more)."
        else -> "Google answered $code."
    }

    internal fun synthesizeUrl(apiKey: String) = URL("$BASE/text:synthesize?key=${enc(apiKey)}")

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun genderLabel(g: String) = when (g) {
        "MALE" -> "male"
        "FEMALE" -> "female"
        else -> ""
    }

    /** LINEAR16 answers are WAV files: returns just the samples of the "data" chunk. */
    internal fun pcmFromWav(bytes: ByteArray): ByteArray {
        fun ascii(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        fun le32(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
        if (bytes.size < 12 || ascii(0) != "RIFF" || ascii(8) != "WAVE") return bytes
        var off = 12
        while (off + 8 <= bytes.size) {
            val size = le32(off + 4)
            if (ascii(off) == "data") {
                val start = off + 8
                val end = if (size <= 0 || start + size > bytes.size) bytes.size else start + size
                return bytes.copyOfRange(start, end - (end - start) % 2)
            }
            off += 8 + size + (size and 1)
        }
        return ByteArray(0)
    }
}

/** Google Cloud's Chirp 3 HD voices, played like ElevenLabs' stream. */
class GoogleTtsSpeaker(
    apiKey: String,
    voiceName: String,
    rate: () -> Float,
    listener: SpeakerListener,
) : Speaker by PcmSpeaker(GoogleTtsSource(apiKey, voiceName, rate), listener, "GoogleTtsSpeaker")

private class GoogleTtsSource(
    private val apiKey: String,
    private val voiceName: String,
    private val rate: () -> Float,
) : PcmSource {
    override val sampleRate = GoogleTts.SAMPLE_RATE
    override val failureReason = "Google's voice isn't reachable."

    override suspend fun render(text: String, previous: String?, emit: (ByteArray) -> Unit) {
        val voice = GoogleVoice(voiceName, "")
        val body = JSONObject()
            .put("input", JSONObject().put("text", text))
            .put("voice", JSONObject().put("languageCode", voice.languageCode).put("name", voiceName))
            .put(
                "audioConfig",
                JSONObject()
                    .put("audioEncoding", "LINEAR16")
                    .put("sampleRateHertz", GoogleTts.SAMPLE_RATE)
                    .put("speakingRate", rate().coerceIn(0.25f, 2f).toDouble()),
            )
            .toString()
        val conn = (GoogleTts.synthesizeUrl(apiKey).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        // Blocking reads don't notice cancellation; dropping the connection does.
        val hook = coroutineContext.job.invokeOnCompletion { conn.disconnect() }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "Google $code for \"${text.take(80)}\": ${detail?.take(500)}")
                throw AssistantException(GoogleTts.errorMessage(code, detail))
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val audio = Base64.decode(json.getString("audioContent"), Base64.DEFAULT)
            emit(GoogleTts.pcmFromWav(audio))
        } catch (e: IOException) {
            throw AssistantException("No connection to Google.", e)
        } finally {
            hook.dispose()
            conn.disconnect()
        }
    }

    private companion object {
        const val TAG = "GoogleTtsSpeaker"
    }
}
