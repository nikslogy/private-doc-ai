package com.privateassistant.platform.inference

import android.os.Looper
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.ThinkingConfig
import com.privateassistant.platform.documents.HeavyWorkGate
import java.io.File
import java.security.MessageDigest

/** This token represents byte verification, not model quality, legal approval or device support. */
class VerifiedModelArtifact internal constructor(
    private val verifiedFile: File,
    private val leaseValid: () -> Boolean = { true },
) {
    internal val file: File get() {
        check(leaseValid()) { "Model lease expired" }
        return verifiedFile
    }
}

object ModelFileVerifier {
    fun verify(
        file: File,
        expectedByteCount: Long,
        expectedSha256: String,
        licenseAccepted: Boolean,
    ): VerifiedModelArtifact {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Model verification must run on a background thread" }
        require(licenseAccepted) { "Explicit model licence acknowledgement is required" }
        require(expectedByteCount > 0 && expectedSha256.matches(Regex("[a-f0-9]{64}")))
        require(file.isFile && file.length() == expectedByteCount) { "Model size verification failed" }
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= expectedByteCount) { "Model changed during verification" }
                hash.update(buffer, 0, read)
            }
            require(total == expectedByteCount) { "Model changed during verification" }
        }
        val digest = hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(digest == expectedSha256) { "Model digest verification failed" }
        return VerifiedModelArtifact(file)
    }
}

data class RuntimeLoadProbeResult(
    val runtimeVersion: String,
    val backend: String,
    val manualToolCallingConfigured: Boolean,
)

/**
 * A real M0 load/create/release probe. It provides no inference API or fabricated answer.
 * The supplied model must be an immutable, app-private, verified experimental artifact.
 * Calls are synchronous and must run on a worker thread. Native initialization has no cancel API;
 * active-generation cancellation and phone capability evaluation remain M4 acceptance gates.
 */
class LiteRtLmLoadProbe {
    fun loadAndRelease(artifact: VerifiedModelArtifact): RuntimeLoadProbeResult {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Model loading must run on a background thread" }
        return HeavyWorkGate.run {
            Engine.setNativeMinLogSeverity(LogSeverity.INFINITY)
            val engine = Engine(
                EngineConfig(
                    modelPath = artifact.file.absolutePath,
                    backend = Backend.CPU(threadCount = 2),
                    maxNumTokens = 512,
                    cacheDir = ":nocache",
                ),
            )
            try {
                engine.initialize()
                check(engine.isInitialized()) { "Native engine initialization did not complete" }
                engine.createConversation(ConversationConfig(automaticToolCalling = false,
                    tools = emptyList(), maxOutputToken = 128,
                    thinkingConfig = ThinkingConfig(enableThinking = false))).use { conversation ->
                    // Verifies the pinned native cancellation entry point only. Idle cancellation
                    // does not measure or establish active generation cancellation latency.
                    conversation.cancelProcess()
                }
                RuntimeLoadProbeResult(RUNTIME_VERSION, "CPU", manualToolCallingConfigured = true)
            } finally {
                if (engine.isInitialized()) engine.close()
            }
        }
    }

    companion object {
        const val RUNTIME_VERSION = "0.16.0"
    }
}
