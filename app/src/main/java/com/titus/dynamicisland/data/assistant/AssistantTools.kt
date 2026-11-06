package com.titus.dynamicisland.data.assistant

import android.util.Log
import kotlinx.coroutines.CancellationException

/** A failure the model should hear about (bad input, missing permission …): it can retry or explain. */
class ToolException(message: String) : Exception(message)

/** One parameter of a tool, described as JSON Schema. */
data class ToolParam(
    val name: String,
    /** JSON Schema type: "string", "integer", "number" or "boolean". */
    val type: String,
    val description: String,
    val required: Boolean = true,
    val enum: List<String>? = null,
    val minimum: Number? = null,
    val maximum: Number? = null,
) {
    fun schema(): Map<String, Any> = buildMap {
        put("type", type)
        put("description", description)
        enum?.let { put("enum", it) }
        minimum?.let { put("minimum", it) }
        maximum?.let { put("maximum", it) }
    }
}

/**
 * A function the assistant can call. [run] gets validated input and returns a short result for the
 * model; it can also put a card under the answer through [ToolInput.show].
 */
class AssistantTool(
    val name: String,
    val description: String,
    val params: List<ToolParam>,
    val run: (ToolInput) -> String,
)

/** The tools offered for one question. Provider-neutral: each backend translates them to its own format. */
class Toolbox(
    val tools: List<AssistantTool>,
    /** Called before every tool runs; throws [CancellationException] once the question was interrupted. */
    private val checkActive: () -> Unit = {},
    /** A tool's card for under the answer. */
    private val onCard: (AssistantCard) -> Unit = {},
    /** Pages a web search found (cited ones first). */
    val onSources: (List<WebSource>) -> Unit = {},
    /** See [ToolInput.atEnd]. */
    private val onEnd: (() -> Unit) -> Unit = { it() },
    /** Tools that work in other apps (tap, scroll …): once one ran, the assistant is [acting]. */
    private val actingTools: Set<String> = emptySet(),
    /** Each step it takes in other apps, counted from one. */
    private val onActing: (step: Int) -> Unit = {},
    /** Tools after which text between steps is held back too, without counting as acting (opening, reading apps). */
    private val quietTools: Set<String> = emptySet(),
    /** After a tool ran without an error (routines are learned from these). */
    private val onDone: (name: String, input: Map<String, Any?>) -> Unit = { _, _ -> },
) {
    data class Result(val text: String, val isError: Boolean)

    /**
     * Working its way through other apps. Text written between those steps is the model thinking aloud
     * ("Italian still on; turn it off."), not something to say: backends hold it back and speak only the end.
     * A round that ends without another tool call is the answer and is spoken as usual.
     */
    @Volatile var holdText = false
        private set

    /** Taking steps in other apps (not just reading them): the island shrinks out of the way. */
    @Volatile var acting = false
        private set
    private var actingSteps = 0

    /** Tool calls run so far (a request that only did something may end without words). */
    var calls = 0
        private set

    private val _used = mutableListOf<String>()
    /** What was looked up or done, e.g. `weather {place=Hamburg}`, for the conversation's history. */
    val used: List<String> get() = synchronized(_used) { _used.toList() }

    fun noteUse(name: String, input: Map<String, Any?>?) {
        val args = input.orEmpty().entries.joinToString(", ") { (k, v) -> "$k=$v" }.take(120)
        synchronized(_used) { _used += if (args.isEmpty()) name else "$name($args)" }
    }

    /** Runs a tool call. Never throws, except to cancel. */
    fun execute(name: String, input: Map<String, Any?>): Result {
        checkActive()
        val tool = tools.firstOrNull { it.name == name } ?: return Result("Unknown tool \"$name\".", isError = true)
        calls++
        noteUse(name, input)
        if (name in quietTools || name in actingTools) holdText = true
        if (name in actingTools) {
            acting = true
            onActing(++actingSteps)
        }
        return try {
            // Only the start of the result: some (the screen, texts) are private and don't belong in the system log.
            Result(tool.run(ToolInput(input, tool.params, onCard, onEnd)), isError = false)
                .also { Log.d(TAG, "$name $input -> ${it.text.take(120).replace('\n', ' ')}${if (it.text.length > 120) " …" else ""}") }
                .also { runCatching { onDone(name, input) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolException) {
            Log.d(TAG, "$name $input -> error: ${e.message}")
            Result(e.message ?: "Failed.", isError = true)
        } catch (e: Exception) {
            Log.w(TAG, "$name failed", e)
            Result("Failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    private companion object {
        const val TAG = "AssistantTools"
    }
}

/**
 * A tool call's arguments, checked against the tool's parameters before use. Arguments stream in
 * unvalidated, so every accessor checks presence, type, range and allowed values and throws a
 * [ToolException] (sent back to the model as an error) instead of running on bad input.
 */
class ToolInput(
    private val values: Map<String, Any?>,
    private val params: List<ToolParam>,
    /** Shows a card under the answer. */
    val show: (AssistantCard) -> Unit = {},
    /**
     * Runs an action once the answer has been spoken, and ends the conversation then instead of
     * listening again. For things that make sound, like music: it would play over the answer, and
     * the microphone opening for a follow-up would pause it again.
     */
    val atEnd: (() -> Unit) -> Unit = { it() },
) {

    fun string(name: String): String = optString(name) ?: missing(name)

    fun optString(name: String): String? {
        val v = values[name] ?: return null
        val s = (v as? String ?: invalid(name, "a string")).trim().takeIf { it.isNotEmpty() } ?: return null
        val allowed = param(name)?.enum
        if (allowed != null && s !in allowed) throw ToolException("\"$name\" must be one of ${allowed.joinToString()}.")
        return s
    }

    fun int(name: String): Int = optInt(name) ?: missing(name)

    fun optInt(name: String): Int? {
        val n = optNumber(name) ?: return null
        if (n % 1.0 != 0.0) invalid(name, "a whole number")
        return n.toInt()
    }

    fun number(name: String): Double = optNumber(name) ?: missing(name)

    fun optNumber(name: String): Double? {
        val n = when (val v = values[name]) {
            null -> return null
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull() ?: invalid(name, "a number")
            else -> invalid(name, "a number")
        }
        val p = param(name)
        p?.minimum?.let { if (n < it.toDouble()) throw ToolException("\"$name\" must be at least $it.") }
        p?.maximum?.let { if (n > it.toDouble()) throw ToolException("\"$name\" must be at most $it.") }
        return n
    }

    fun bool(name: String): Boolean = optBool(name) ?: missing(name)

    fun optBool(name: String): Boolean? = when (val v = values[name]) {
        null -> null
        is Boolean -> v
        "true" -> true
        "false" -> false
        else -> invalid(name, "true or false")
    }

    private fun param(name: String) = params.firstOrNull { it.name == name }
    private fun missing(name: String): Nothing = throw ToolException("Missing \"$name\".")
    private fun invalid(name: String, what: String): Nothing = throw ToolException("\"$name\" must be $what.")
}
