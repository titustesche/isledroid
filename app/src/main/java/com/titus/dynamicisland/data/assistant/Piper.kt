package com.titus.dynamicisland.data.assistant

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.coroutineContext

// ---------------- Voices ----------------

/**
 * A Piper voice that can be downloaded, e.g. "de_DE-thorsten-medium", or one the user trained and
 * imported ([custom]; its id still starts with the language, e.g. "de_DE-myvoice-custom").
 */
data class PiperVoice(val id: String, val name: String, val description: String, val sizeMb: Int, val custom: Boolean = false) {
    val locale: Locale = Locale.forLanguageTag(id.substringBefore('-').replace('_', '-'))
    internal val url get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-$id.tar.bz2"
}

/** A downloaded voice, ready for [PiperSpeaker]. */
class InstalledPiperVoice(val voice: PiperVoice, internal val dir: File, internal val espeakData: File)

/** The voices offered, best first within each language. All run on the phone, without a network. */
object PiperVoices {
    val all = listOf(
        PiperVoice("de_DE-thorsten-medium", "Thorsten", "male, the most natural German voice", 67),
        PiperVoice("de_DE-thorsten-high", "Thorsten HQ", "male, a little clearer, slower to start", 115),
        PiperVoice("de_DE-kerstin-low", "Kerstin", "female", 67),
        PiperVoice("de_DE-ramona-low", "Ramona", "female", 67),
        PiperVoice("de_DE-eva_k-x_low", "Eva", "female, small and fast, plainer sound", 26),
        PiperVoice("de_DE-karlsson-low", "Karlsson", "male", 67),
        PiperVoice("de_DE-pavoque-low", "Pavoque", "male", 67),
        PiperVoice("en_GB-alan-medium", "Alan", "male, British", 67),
        PiperVoice("en_GB-jenny_dioco-medium", "Jenny", "female, British", 67),
        PiperVoice("en_GB-cori-medium", "Cori", "female, British", 67),
        PiperVoice("en_GB-northern_english_male-medium", "Northern", "male, Northern English", 67),
        PiperVoice("en_US-lessac-medium", "Lessac", "female, American", 67),
        PiperVoice("en_US-ryan-medium", "Ryan", "male, American", 67),
        PiperVoice("en_US-amy-medium", "Amy", "female, American", 67),
        PiperVoice("en_US-joe-medium", "Joe", "male, American", 67),
        PiperVoice("en_US-kristin-medium", "Kristin", "female, American", 67),
        PiperVoice("en_US-ryan-high", "Ryan HQ", "male, American, slower to start", 115),
        PiperVoice("en_US-lessac-high", "Lessac HQ", "female, American, slower to start", 115),
        PiperVoice("fr_FR-siwis-medium", "Siwis", "female", 67),
        PiperVoice("fr_FR-tom-medium", "Tom", "male", 67),
        PiperVoice("es_ES-davefx-medium", "Davefx", "male, Spain", 67),
        PiperVoice("es_MX-ald-medium", "Ald", "male, Mexico", 67),
        PiperVoice("it_IT-paola-medium", "Paola", "female", 67),
        PiperVoice("nl_NL-ronnie-medium", "Ronnie", "male", 67),
        PiperVoice("nl_BE-nathalie-medium", "Nathalie", "female, Flemish", 67),
        PiperVoice("pl_PL-gosia-medium", "Gosia", "female", 67),
        PiperVoice("pl_PL-darkman-medium", "Darkman", "male", 67),
        PiperVoice("pt_BR-faber-medium", "Faber", "male, Brazil", 67),
        PiperVoice("pt_PT-tugao-medium", "Tugão", "male, Portugal", 67),
        PiperVoice("ru_RU-irina-medium", "Irina", "female", 67),
        PiperVoice("ru_RU-dmitri-medium", "Dmitri", "male", 67),
        PiperVoice("sv_SE-lisa-medium", "Lisa", "female", 67),
        PiperVoice("da_DK-talesyntese-medium", "Talesyntese", "male", 67),
        PiperVoice("no_NO-talesyntese-medium", "Talesyntese", "male", 67),
        PiperVoice("fi_FI-harri-medium", "Harri", "male", 67),
        PiperVoice("cs_CZ-jirka-medium", "Jirka", "male", 67),
        PiperVoice("tr_TR-fahrettin-medium", "Fahrettin", "male", 67),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }

    /** Voices for [locale]'s language, its country first; every voice when there are none. */
    fun forLocale(locale: Locale): List<PiperVoice> {
        val same = all.filter { it.locale.language == locale.language }
        return same.sortedByDescending { it.locale.country == locale.country }.ifEmpty { all }
    }
}

// ---------------- Downloads ----------------

/**
 * Downloads, unpacks and removes Piper voices. Each voice lives in files/piper/<id>; the eSpeak
 * pronunciation data every voice needs (18 MB) is kept once, in files/piper/espeak-ng-data.
 */
class PiperStore(context: Context) {
    data class Download(val progress: Float? = null, val unpacking: Boolean = false, val error: String? = null)

    private val root = File(context.filesDir, "piper")
    private val espeak = File(root, "espeak-ng-data")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val oneAtATime = Mutex() // downloads share the eSpeak folder
    private val jobs = mutableMapOf<String, Job>()

    private val _custom = MutableStateFlow(scanCustom())
    /** Voices the user imported (see [import]). */
    val custom: StateFlow<List<PiperVoice>> = _custom.asStateFlow()

    private val _installed = MutableStateFlow(scanInstalled())
    /** Ids of the downloaded voices, imported ones included. */
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    /** Import in progress (null: none); an error stays until the next try. */
    data class Import(val running: Boolean, val error: String? = null, val imported: PiperVoice? = null)
    private val _import = MutableStateFlow<Import?>(null)
    val importing: StateFlow<Import?> = _import.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    /** Running downloads, and ones that failed (with [Download.error]). */
    val downloads: StateFlow<Map<String, Download>> = _downloads.asStateFlow()

    /**
     * The voice to use: [chosenId] if set, else a downloaded voice for the phone's language, else
     * the recommended one (which may still need downloading).
     */
    fun resolve(chosenId: String, locale: Locale = Locale.getDefault()): PiperVoice {
        PiperVoices.byId(chosenId)?.let { return it }
        _custom.value.firstOrNull { it.id == chosenId }?.let { return it }
        val options = PiperVoices.forLocale(locale)
        return options.firstOrNull { it.id in _installed.value } ?: options.first()
    }

    /** [chosenId]'s voice (see [resolve]) if it's downloaded. */
    fun installedVoice(chosenId: String): InstalledPiperVoice? {
        val voice = resolve(chosenId)
        if (voice.id !in _installed.value) return null
        return InstalledPiperVoice(voice, File(root, voice.id), espeak)
    }

    fun download(voice: PiperVoice) {
        if (jobs[voice.id]?.isActive == true) return
        _downloads.update { it + (voice.id to Download()) }
        jobs[voice.id] = scope.launch {
            try {
                withContext(Dispatchers.IO) { oneAtATime.withLock { fetch(voice) } }
                _installed.value = scanInstalled()
                _downloads.update { it - voice.id }
            } catch (e: CancellationException) {
                _downloads.update { it - voice.id }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Downloading ${voice.id} failed", e)
                val reason = if (e is IOException) "The download failed. Check the connection and storage space." else "The voice couldn't be unpacked."
                _downloads.update { it + (voice.id to Download(error = reason)) }
            } finally {
                jobs.remove(voice.id)
            }
        }
    }

    fun cancel(voice: PiperVoice) {
        jobs[voice.id]?.cancel()
        _downloads.update { it - voice.id }
    }

    fun remove(voice: PiperVoice) {
        scope.launch {
            withContext(Dispatchers.IO) {
                oneAtATime.withLock {
                    File(root, voice.id).deleteRecursively()
                    if (scanInstalled().isEmpty()) espeak.deleteRecursively()
                }
            }
            _custom.value = scanCustom()
            _installed.value = scanInstalled()
        }
    }

    private fun scanInstalled(): Set<String> =
        (PiperVoices.all + scanCustom()).filter { File(root, "${it.id}/$MODEL").isFile && File(root, "${it.id}/$TOKENS").isFile }.map { it.id }.toSet()

    private fun scanCustom(): List<PiperVoice> =
        root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.endsWith(CUSTOM_SUFFIX) && File(it, MODEL).isFile && File(it, TOKENS).isFile }
            .sortedBy { it.name }
            .map { dir -> customVoice(dir.name, (File(dir, MODEL).length() / 1_000_000).toInt()) }

    /** "de_DE-glados-custom" → "Glados", imported. */
    private fun customVoice(id: String, sizeMb: Int): PiperVoice {
        val name = id.substringAfter('-').removeSuffix(CUSTOM_SUFFIX).replace('_', ' ').replaceFirstChar { it.uppercase() }
        return PiperVoice(id, name, "your own voice, imported", sizeMb, custom = true)
    }

    /**
     * Installs a voice trained on the user's PC: the archive the training kit's pack step writes,
     * laid out like sherpa-onnx's own ("vits-piper-<lang>-<name>-custom/" with model.onnx, tokens.txt
     * and the .onnx.json). The pronunciation data comes from the downloaded voices, or the archive.
     */
    fun import(open: () -> InputStream?) {
        if (_import.value?.running == true) return
        _import.value = Import(running = true)
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { oneAtATime.withLock { open()?.use { unpackImport(it) } ?: throw IOException("Can't read the file") } }
            }
            _custom.value = scanCustom()
            _installed.value = scanInstalled()
            _import.value = result.fold(
                onSuccess = { Import(running = false, imported = it) },
                onFailure = { e ->
                    Log.w(TAG, "Import failed", e)
                    Import(running = false, error = e.message ?: "The voice couldn't be imported.")
                },
            )
        }
    }

    private suspend fun unpackImport(input: InputStream): PiperVoice {
        root.mkdirs()
        val part = File(root, "import.part").apply { deleteRecursively(); mkdirs() }
        val needEspeak = !espeak.isDirectory
        val espeakPart = File(root, "espeak-ng-data.part").apply { if (needEspeak) { deleteRecursively(); mkdirs() } }
        try {
            val top = extract(input, part, espeakPart, needEspeak)
                ?: throw IOException("That's not a voice archive from the training kit.")
            val id = top.removePrefix("vits-piper-")
            if (!id.endsWith(CUSTOM_SUFFIX) || !Regex("[A-Za-z]{2}_[A-Za-z]{2}-[A-Za-z0-9_]+$CUSTOM_SUFFIX").matches(id)) {
                throw IOException("The archive's voice name \"$id\" isn't one the training kit writes.")
            }
            if (!File(part, MODEL).isFile || !File(part, TOKENS).isFile) throw IOException("The archive has no model or tokens.")
            if (needEspeak) {
                if (espeakPart.listFiles().isNullOrEmpty()) {
                    throw IOException("Download one of the regular Piper voices first; it brings the pronunciation data every voice needs.")
                }
                espeak.deleteRecursively()
                if (!espeakPart.renameTo(espeak)) throw IOException("Couldn't move eSpeak data")
            }
            val dest = File(root, id)
            dest.deleteRecursively()
            if (!part.renameTo(dest)) throw IOException("Couldn't move the voice")
            return customVoice(id, (File(dest, MODEL).length() / 1_000_000).toInt())
        } finally {
            part.deleteRecursively()
            if (needEspeak) espeakPart.deleteRecursively()
        }
    }

    /** Unpacks a sherpa-style voice archive into [part] (and eSpeak data into [espeakPart]); returns its top folder. */
    private suspend fun extract(input: InputStream, part: File, espeakPart: File, needEspeak: Boolean): String? {
        val rootPath = root.canonicalPath + File.separator
        var top: String? = null
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(input, 64 * 1024))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                coroutineContext.ensureActive()
                if (!entry.isFile) continue
                if (top == null) top = entry.name.substringBefore('/')
                val path = entry.name.substringAfter('/') // drop "vits-piper-<id>/"
                val out = when {
                    path.startsWith("espeak-ng-data/") -> if (needEspeak) File(espeakPart, path.removePrefix("espeak-ng-data/")) else null
                    path.endsWith(".onnx.json") -> File(part, CONFIG)
                    path.endsWith(".onnx") -> File(part, MODEL)
                    path == "tokens.txt" -> File(part, TOKENS)
                    else -> null
                } ?: continue
                if (!out.canonicalPath.startsWith(rootPath)) throw IOException("Bad path in archive: ${entry.name}")
                out.parentFile?.mkdirs()
                out.outputStream().use { tar.copyTo(it, 64 * 1024) }
            }
        }
        return top
    }

    /** Streams the archive straight into place: nothing but the files we need ever lands on disk. */
    private suspend fun fetch(voice: PiperVoice) {
        root.mkdirs()
        val dest = File(root, voice.id)
        val part = File(root, "${voice.id}.part").apply { deleteRecursively(); mkdirs() }
        val needEspeak = !espeak.isDirectory
        val espeakPart = File(root, "espeak-ng-data.part").apply { if (needEspeak) { deleteRecursively(); mkdirs() } }
        val conn = (URL(voice.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true // GitHub hands the file out from another host
        }
        val hook = coroutineContext.job.invokeOnCompletion { conn.disconnect() }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (voice.sizeMb * 1_000_000L)
            var lastShown = -1
            val counted = CountingStream(conn.inputStream) { read ->
                val percent = (read * 100 / total).toInt().coerceIn(0, 99)
                if (percent != lastShown) {
                    lastShown = percent
                    _downloads.update { it + (voice.id to Download(progress = percent / 100f)) }
                }
            }
            extract(counted, part, espeakPart, needEspeak)
            _downloads.update { it + (voice.id to Download(progress = 1f, unpacking = true)) }
            if (!File(part, MODEL).isFile || !File(part, TOKENS).isFile) throw IllegalStateException("Archive without model")
            if (needEspeak) {
                espeak.deleteRecursively()
                if (!espeakPart.renameTo(espeak)) throw IOException("Couldn't move eSpeak data")
            }
            dest.deleteRecursively()
            if (!part.renameTo(dest)) throw IOException("Couldn't move voice")
        } finally {
            hook.dispose()
            conn.disconnect()
            part.deleteRecursively()
            if (needEspeak) espeakPart.deleteRecursively()
        }
    }

    private class CountingStream(input: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) onRead(++count) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also {
            if (it > 0) {
                count += it
                onRead(count)
            }
        }
    }

    internal companion object {
        const val TAG = "PiperStore"
        const val MODEL = "model.onnx"
        const val CONFIG = "model.onnx.json"
        const val TOKENS = "tokens.txt"
        const val CUSTOM_SUFFIX = "-custom"
    }
}

// ---------------- Speaking ----------------

/**
 * A Piper voice, rendered on the phone by sherpa-onnx and played like ElevenLabs' stream. The model
 * (about 60 MB) loads in the background as soon as this is created and is unloaded again after a
 * few idle minutes so it doesn't sit in memory all day.
 */
class PiperSpeaker(voice: InstalledPiperVoice, rate: () -> Float, listener: SpeakerListener) : Speaker {
    private val engine = PiperEngine(voice).also { it.warmUp() }
    private val player = PcmSpeaker(PiperSource(engine, readSampleRate(voice), rate), listener, "PiperSpeaker")

    override val busy get() = player.busy

    override fun speak(text: String) = player.speak(text)

    override fun stop() = player.stop()

    override fun release() {
        player.release()
        engine.release()
    }

    private companion object {
        /** Piper voices are 22.05 kHz, the "x_low" ones 16 kHz; their config says which. */
        fun readSampleRate(voice: InstalledPiperVoice): Int = runCatching {
            JSONObject(File(voice.dir, PiperStore.CONFIG).readText()).getJSONObject("audio").getInt("sample_rate")
        }.getOrElse { if (voice.voice.id.endsWith("x_low")) 16_000 else 22_050 }
    }
}

private class PiperSource(
    private val engine: PiperEngine,
    override val sampleRate: Int,
    private val rate: () -> Float,
) : PcmSource {
    override val failureReason = "The Piper voice couldn't speak."

    override suspend fun render(text: String, previous: String?, emit: (ByteArray) -> Unit) =
        engine.generate(text, rate().coerceIn(0.5f, 2f), sampleRate, emit)
}

/** Owns the native sherpa-onnx engine: one generation at a time, never freed while one runs. */
private class PiperEngine(private val voice: InstalledPiperVoice) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var tts: OfflineTts? = null // guarded by lock
    private var released = false // guarded by lock
    private var unloadJob: Job? = null // guarded by this

    fun warmUp() {
        scope.launch {
            runCatching { lock.withLock { obtain() } }
            scheduleUnload()
        }
    }

    suspend fun generate(text: String, speed: Float, expectedRate: Int, emit: (ByteArray) -> Unit) {
        val job = coroutineContext.job
        cancelUnload()
        try {
            lock.withLock {
                val t = obtain()
                if (t.sampleRate() != expectedRate) Log.w(TAG, "Voice is ${t.sampleRate()} Hz, playing at $expectedRate Hz")
                var emitted = false
                // sherpa-onnx's JNI calls invoke(float[]): Integer on the callback's own class, which
                // only a classic lambda class has (not Kotlin 2's invokedynamic lambdas).
                val callback = @JvmSerializableLambda { samples: FloatArray ->
                    if (job.isActive) {
                        emit(toPcm16(samples))
                        emitted = true
                        1
                    } else {
                        0 // stop rendering, nobody listens
                    }
                }
                val audio = t.generateWithCallback(text, 0, speed, callback)
                job.ensureActive()
                if (!emitted) emit(toPcm16(audio.samples))
            }
        } finally {
            scheduleUnload()
        }
    }

    fun release() {
        scope.launch {
            lock.withLock {
                released = true
                tts?.release()
                tts = null
            }
        }.invokeOnCompletion { scope.cancel() }
    }

    /** Call with [lock] held. */
    private fun obtain(): OfflineTts {
        if (released) throw CancellationException("Piper released")
        tts?.let { return it }
        val started = System.nanoTime()
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = File(voice.dir, PiperStore.MODEL).path,
                    tokens = File(voice.dir, PiperStore.TOKENS).path,
                    dataDir = voice.espeakData.path,
                ),
                numThreads = THREADS,
                debug = false,
                provider = "cpu",
            ),
        )
        val loaded = try {
            OfflineTts(config = config)
        } catch (e: Throwable) { // also UnsatisfiedLinkError on a processor sherpa-onnx isn't built for
            Log.w(TAG, "Loading ${voice.voice.id} failed", e)
            throw AssistantException("The Piper voice couldn't be loaded.", e)
        }
        Log.i(TAG, "Loaded ${voice.voice.id} in ${(System.nanoTime() - started) / 1_000_000} ms")
        tts = loaded
        return loaded
    }

    private fun cancelUnload() = synchronized(this) { unloadJob?.cancel() }

    private fun scheduleUnload() = synchronized(this) {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(IDLE_UNLOAD_MS)
            lock.withLock {
                tts?.release()
                tts = null
            }
            Log.i(TAG, "Unloaded ${voice.voice.id} after being idle")
        }
    }

    private companion object {
        const val TAG = "PiperSpeaker"
        const val THREADS = 2
        const val IDLE_UNLOAD_MS = 5 * 60_000L

        fun toPcm16(samples: FloatArray): ByteArray {
            val out = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val s = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
                out[2 * i] = s.toByte()
                out[2 * i + 1] = (s shr 8).toByte()
            }
            return out
        }
    }
}
