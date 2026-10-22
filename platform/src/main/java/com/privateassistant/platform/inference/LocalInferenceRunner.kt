package com.privateassistant.platform.inference

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.privateassistant.platform.documents.HeavyWorkGate
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class LocalInferenceResult(val text: String, val runtimeVersion: String, val backend: String) {
    override fun toString(): String = "LocalInferenceResult"
}
enum class LocalInferenceFailure { INPUT_LIMIT, OUTPUT_LIMIT, RUNTIME, TIMEOUT, UNSUPPORTED_OUTPUT }
class LocalInferenceException(val reason: LocalInferenceFailure) : Exception(reason.name)

/** Real CPU generation. No cache, tools, chat persistence, document repository or network capability. */
class LocalInferenceRunner(private val maxDurationMillis: Long = 90_000) {
    init { require(maxDurationMillis in 1..300_000) }
    suspend fun generate(artifact: VerifiedModelArtifact, prompt: String,
        cancellationRequested: () -> Boolean = { false }, onGenerationStarted: () -> Unit = {},
        onNativeCancellationRequested: () -> Unit = {}, onNativeSendFailed: () -> Unit = {},
    ): LocalInferenceResult = withContext(Dispatchers.IO) {
        if (prompt.isBlank() || prompt.length > MAX_INPUT_UTF16) throw LocalInferenceException(LocalInferenceFailure.INPUT_LIMIT)
        val context = currentCoroutineContext()
        val started = System.nanoTime()
        fun cancelled() = !context.isActive || cancellationRequested()
        fun expired() = (System.nanoTime() - started) / 1_000_000 >= maxDurationMillis
        fun checkRequest() {
            if (cancelled()) throw CancellationException("Local inference revoked")
            if (expired()) throw LocalInferenceException(LocalInferenceFailure.TIMEOUT)
        }
        try { HeavyWorkGate.run {
            checkRequest()
            Engine.setNativeMinLogSeverity(LogSeverity.INFINITY)
            val engine = Engine(EngineConfig(modelPath = artifact.file.absolutePath,
                backend = Backend.CPU(numOfThreads = 2), maxNumTokens = 512, cacheDir = ":nocache"))
            try {
                // The runtime has no interruptible initialize entry point. Recheck after return.
                engine.initialize()
                check(engine.isInitialized())
                checkRequest()
                engine.createConversation(ConversationConfig(automaticToolCalling = false,
                    tools = emptyList(), samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0))).use { conversation ->
                    val finished = AtomicBoolean(false)
                    val stopFailed = AtomicBoolean(false)
                    val watchdog = Thread({
                        try {
                            while (!finished.get()) {
                                if (cancelled() || expired()) {
                                    // Repeat to cover a cancellation that preceded native send's start.
                                    try {
                                        conversation.cancelProcess()
                                        onNativeCancellationRequested()
                                    } catch (_: Exception) { stopFailed.set(true) }
                                }
                                Thread.sleep(25)
                            }
                        } catch (_: InterruptedException) { /* Owner joins before deleting native handles. */ }
                    }, "local-inference-deadline").apply { isDaemon = true }
                    checkRequest()
                    onGenerationStarted()
                    watchdog.start()
                    val response = try {
                        conversation.sendMessage(Message.user(prompt))
                    } catch (failure: Exception) {
                        onNativeSendFailed()
                        throw failure
                    } finally {
                        finished.set(true)
                        watchdog.interrupt()
                        // Returning from synchronous send and joining avoids a callback/JNI close race.
                        var interrupted = false
                        while (watchdog.isAlive) {
                            try { watchdog.join() } catch (_: InterruptedException) { interrupted = true }
                        }
                        if (interrupted) Thread.currentThread().interrupt()
                    }
                    checkRequest()
                    if (stopFailed.get()) throw LocalInferenceException(LocalInferenceFailure.RUNTIME)
                    if (response.toolCalls.isNotEmpty() || response.channels.isNotEmpty() ||
                        response.contents.contents.any { it !is Content.Text }) {
                        throw LocalInferenceException(LocalInferenceFailure.UNSUPPORTED_OUTPUT)
                    }
                    // Native context is 512 tokens. This text budget is checked after native allocation.
                    val output = StringBuilder()
                    for (part in response.contents.contents) {
                        val text = (part as Content.Text).text
                        if (text.length > MAX_OUTPUT_UTF16 - output.length) throw LocalInferenceException(LocalInferenceFailure.OUTPUT_LIMIT)
                        output.append(text)
                    }
                    if (output.isBlank()) throw LocalInferenceException(LocalInferenceFailure.UNSUPPORTED_OUTPUT)
                    LocalInferenceResult(output.toString(), LiteRtLmLoadProbe.RUNTIME_VERSION, "CPU")
                }
            } catch (cancelledFailure: CancellationException) {
                throw cancelledFailure
            } catch (safe: LocalInferenceException) {
                throw safe
            } catch (_: Exception) {
                checkRequest()
                throw LocalInferenceException(LocalInferenceFailure.RUNTIME)
            } finally {
                try {
                    if (engine.isInitialized()) engine.close()
                } catch (_: Exception) {
                    if (!cancelled()) throw LocalInferenceException(LocalInferenceFailure.RUNTIME)
                }
            }
        } } catch (cancelledFailure: CancellationException) {
            throw cancelledFailure
        } catch (safe: LocalInferenceException) {
            throw safe
        } catch (_: LinkageError) {
            throw LocalInferenceException(LocalInferenceFailure.RUNTIME)
        } catch (_: Exception) {
            checkRequest()
            throw LocalInferenceException(LocalInferenceFailure.RUNTIME)
        }
    }
    companion object {
        const val MAX_INPUT_UTF16 = 1024
        const val MAX_OUTPUT_UTF16 = 2048
        const val SYNTHETIC_PROMPT = "This is a synthetic local runtime test. Reply with one short sentence about a blue triangle. Do not use tools."
    }
}
