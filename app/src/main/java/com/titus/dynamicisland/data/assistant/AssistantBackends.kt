package com.titus.dynamicisland.data.assistant

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import android.util.Log
import com.anthropic.core.JsonValue
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaToolChoice
import com.anthropic.models.beta.messages.BetaToolChoiceNone
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.BetaWebFetchTool20250910
import com.anthropic.models.beta.messages.BetaWebSearchTool20250305
import com.anthropic.models.beta.messages.MessageCreateParams
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration

/**
 * One message of the conversation. [lookups] (answers only) lists the tools the answer was based on:
 * earlier questions go back to the model as plain text, and without this note it sees answers full of
 * facts it can't account for, and starts confessing they were guesses.
 */
data class Turn(val fromUser: Boolean, val text: String, val lookups: List<String> = emptyList())

/** A user-readable failure; the island shows and speaks [message]. */
class AssistantException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One AI provider. [stream] blocks (call it off the main thread) and reports text as it arrives.
 * [tools] may be used if the provider supports them; [supportsTools] says whether it does.
 */
interface AssistantBackend {
    val supportsTools: Boolean get() = false
    fun stream(system: String, history: List<Turn>, tools: Toolbox?, onText: (String) -> Unit)
}

/** Claude through the official Anthropic SDK, streaming. */
class ClaudeBackend(private val apiKey: String, private val model: String) : AssistantBackend {

    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.ofSeconds(60))
            .build()
    }

    override val supportsTools get() = true

    /**
     * Streams the answer; when Claude calls tools, runs them and continues, until it has answered.
     * Earlier questions go in as plain text (no earlier reasoning blocks are replayed, so nothing can
     * fail the API's history check); within one question the tool rounds are replayed exactly as
     * received, reasoning blocks included.
     */
    override fun stream(system: String, history: List<Turn>, tools: Toolbox?, onText: (String) -> Unit) {
        val messages = history.mapIndexed { i, t ->
            if (!t.fromUser) return@mapIndexed BetaMessageParam.ofAssistant(t.text)
            // The lookups behind the previous answer, as context ahead of the next question.
            val before = history.getOrNull(i - 1)?.takeIf { !it.fromUser && it.lookups.isNotEmpty() }
            BetaMessageParam.ofUser(
                if (before == null) t.text
                else "<earlier_lookups>Your previous answer was based on: ${before.lookups.joinToString("; ")}</earlier_lookups>\n\n${t.text}",
            )
        }.toMutableList()
        var spokeBefore = false
        val rounds = AssistantLimits.toolRounds
        repeat(rounds) {
            // While it works in other apps, a round's text waits until it's clear it's the answer, not a note to itself.
            val held = if (tools?.holdText == true) StringBuilder() else null
            val emit = { text: String ->
                // Text from a later round starts a new sentence ("Moment." … "Der Timer läuft.").
                if (spokeBefore) onText(" ")
                spokeBefore = false
                onText(text)
            }
            // Out of steps: the last round gets no tools, so it says how far it got instead of stopping mid-task.
            val last = tools != null && it == rounds - 1
            val message = streamRound(system, messages, tools, wrapUp = last) { text -> if (held != null) held.append(text) else emit(text) }
            if (held != null && held.isNotBlank()) {
                if (message.stopReason().orElse(null) == BetaStopReason.TOOL_USE) Log.d(TAG, "held back: \"${held.take(200)}\"")
                else emit(held.toString())
            }
            if (message.content().any { it.text().isPresent } && (held == null || message.stopReason().orElse(null) != BetaStopReason.TOOL_USE)) {
                spokeBefore = true
            }
            tools?.let { t ->
                webSources(message).takeIf { it.isNotEmpty() }?.let(t.onSources)
                message.content().mapNotNull { it.serverToolUse().orElse(null) }.forEach { use ->
                    @Suppress("UNCHECKED_CAST")
                    t.noteUse(use.name().toString(), runCatching { use._input().convert(Map::class.java) as Map<String, Any?> }.getOrNull())
                }
            }
            Log.d(
                TAG,
                "round ${it + 1}: stop=${message.stopReason().orElse(null)} server tools=" +
                    message.content().mapNotNull { b -> b.serverToolUse().orElse(null)?.name()?.toString() } +
                    " text=\"" + message.content().mapNotNull { b -> b.text().orElse(null)?.text() }.joinToString("").take(300) + "\"",
            )
            message.content().mapNotNull { it.webSearchToolResult().orElse(null) }
                .filterNot { it.content().isResultBlocks() }
                .forEach { Log.w(TAG, "web search failed: ${it.content()}") }
            when (message.stopReason().orElse(null)) {
                BetaStopReason.REFUSAL -> throw AssistantException("Claude declined to answer that.")
                BetaStopReason.TOOL_USE -> {
                    val calls = message.content().mapNotNull { it.toolUse().orElse(null) }
                    if (tools == null || calls.isEmpty()) return
                    messages += message.toParam()
                    // All results go back together, in one message.
                    messages += BetaMessageParam.ofUserOfContentBlockParams(
                        calls.map { call ->
                            @Suppress("UNCHECKED_CAST")
                            val input = runCatching { call._input().convert(Map::class.java) as Map<String, Any?> }.getOrNull()
                            val result = if (input == null) Toolbox.Result("Unreadable arguments.", isError = true)
                            else tools.execute(call.name(), input)
                            BetaContentBlockParam.ofToolResult(
                                BetaToolResultBlockParam.builder()
                                    .toolUseId(call.id())
                                    .apply {
                                        val image = result.image
                                        if (image == null) content(result.text)
                                        else contentOfBlocks(listOf(
                                            BetaToolResultBlockParam.Content.Block.ofText(result.text),
                                            BetaToolResultBlockParam.Content.Block.ofImage(
                                                BetaBase64ImageSource.builder()
                                                    .mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG)
                                                    .data(android.util.Base64.encodeToString(image, android.util.Base64.NO_WRAP))
                                                    .build(),
                                            ),
                                        ))
                                    }
                                    .apply { if (result.isError) isError(true) }
                                    .build(),
                            )
                        },
                    )
                }
                // The web search loop on Anthropic's side paused; sending the turn back resumes it.
                BetaStopReason.PAUSE_TURN -> messages += message.toParam()
                // A cut-off answer is still worth speaking; a cut-off tool call must not run.
                else -> return
            }
        }
        Log.w(TAG, "Gave up after ${AssistantLimits.toolRounds} rounds")
    }

    /** The pages the answer cites, then the other search results. */
    private fun webSources(message: BetaMessage): List<WebSource> {
        val cited = message.content().flatMap { b ->
            b.text().orElse(null)?.citations()?.orElse(null).orEmpty().mapNotNull { c ->
                c.webSearchResultLocation().orElse(null)?.let { WebSource(it.title().orElse(it.url()), it.url()) }
            }
        }
        val found = message.content().flatMap { b ->
            b.webSearchToolResult().orElse(null)?.content()?.resultBlocks()?.orElse(null).orEmpty().map { WebSource(it.title(), it.url()) }
        }
        return (cited + found).distinctBy { it.url }
    }

    private fun streamRound(
        system: String,
        messages: List<BetaMessageParam>,
        tools: Toolbox?,
        wrapUp: Boolean = false,
        onText: (String) -> Unit,
    ): BetaMessage {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(64_000L) // the system prompt keeps answers short; the cap only guards against truncation
            .system(system)
            // Spoken answers to everyday questions: keep latency low.
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            // If a safety classifier declines, the API retries on a suitable model instead of just stopping.
            .addBeta(FALLBACK_BETA)
            .fallbacksDefault()
            .messages(messages)
            .apply {
                if (tools != null) {
                    tools.tools.forEach { addTool(it.toBeta()) }
                    // The basic versions: the newer ones filter results by running code first, which is
                    // slower for a spoken answer, and SDK 3.3.0's stream accumulator fails on those blocks
                    // ("Missing input JSON").
                    addTool(BetaWebSearchTool20250305.builder().maxUses(3L).build())
                    addTool(BetaWebFetchTool20250910.builder().maxUses(2L).build())
                    if (wrapUp) {
                        toolChoice(BetaToolChoice.ofNone(BetaToolChoiceNone.builder().build()))
                        // The history ends in tool results; this says why there's no next step.
                        system("$system\n\nYou're out of steps for this question. Don't try more: say in one short sentence how far you got.")
                    }
                }
            }
            .build()
        val accumulator = BetaMessageAccumulator.create()
        try {
            client.beta().messages().createStreaming(params).use { response ->
                response.stream().forEach { event ->
                    accumulator.accumulate(event)
                    event.contentBlockDelta().flatMap { it.delta().text() }.ifPresent { onText(it.text()) }
                }
            }
        } catch (e: UnauthorizedException) {
            throw AssistantException("The Claude API key was rejected.", e)
        } catch (e: PermissionDeniedException) {
            throw AssistantException("This Claude API key can't use $model.", e)
        } catch (e: NotFoundException) {
            throw AssistantException("Unknown Claude model \"$model\".", e)
        } catch (e: RateLimitException) {
            throw AssistantException("Claude is rate-limited right now. Try again in a moment.", e)
        } catch (e: BadRequestException) {
            // The details are JSON: into the log, not into the voice.
            Log.w(TAG, "Bad request: ${e.message}")
            throw AssistantException("Claude rejected the request.", e)
        } catch (e: AnthropicServiceException) {
            throw AssistantException("Claude is unavailable (${e.statusCode()}).", e)
        } catch (e: AnthropicIoException) {
            throw AssistantException("No connection to Claude.", e)
        }
        return accumulator.message()
    }

    /**
     * Arguments stream in as they're generated (eager input streaming); [ToolInput] validates them
     * before anything runs.
     */
    private fun AssistantTool.toBeta(): BetaTool = BetaTool.builder()
        .name(name)
        .description(description)
        .inputSchema(
            BetaTool.InputSchema.builder()
                .properties(
                    BetaTool.InputSchema.Properties.builder()
                        .apply { params.forEach { putAdditionalProperty(it.name, JsonValue.from(it.schema())) } }
                        .build(),
                )
                .required(params.filter { it.required }.map { it.name })
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build(),
        )
        .eagerInputStreaming(true)
        .build()

    private companion object {
        const val TAG = "ClaudeBackend"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    }
}

/** Limits shared by the backends. */
object AssistantLimits {
    /** Tool rounds per question; a voice answer that needs more is going wrong anyway. Using apps takes more (settings). */
    @Volatile var toolRounds = 6
    /** Debug builds: overrides [toolRounds] when set (`dumpsys … rounds <n>`, `rounds off`). */
    @Volatile var debugRounds: Int? = null
}

/**
 * Any OpenAI-compatible chat-completions endpoint (OpenAI, OpenRouter, Groq, Mistral, Ollama,
 * LM Studio …), streaming via server-sent events.
 */
class OpenAiCompatibleBackend(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : AssistantBackend {

    override fun stream(system: String, history: List<Turn>, tools: Toolbox?, onText: (String) -> Unit) {
        if (model.isBlank()) throw AssistantException("Set a model name for the OpenAI-compatible backend.")
        val url = URL(baseUrl.trimEnd('/') + "/chat/completions")
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach { t -> messages.put(JSONObject().put("role", if (t.fromUser) "user" else "assistant").put("content", t.text)) }
        val body = JSONObject().put("model", model).put("stream", true).put("messages", messages).toString()

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200)
                throw AssistantException(
                    when (code) {
                        401, 403 -> "The API key was rejected."
                        404 -> "Endpoint or model \"$model\" not found."
                        429 -> "Rate-limited right now. Try again in a moment."
                        else -> "The server answered $code${detail?.let { ": $it" } ?: ""}"
                    },
                )
            }
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val delta = runCatching {
                        JSONObject(data).getJSONArray("choices").getJSONObject(0).optJSONObject("delta")?.optString("content", "")
                    }.getOrNull()
                    if (!delta.isNullOrEmpty()) onText(delta)
                }
            }
        } catch (e: IOException) {
            throw AssistantException("No connection to ${url.host}.", e)
        } finally {
            conn.disconnect()
        }
    }
}
