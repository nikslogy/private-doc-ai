package com.privateassistant.platform.inference

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class LocalChatFailure { MODEL_REQUIRED, MEMORY, GENERATION }

/** Only safe categories cross the app boundary; native messages can contain input or paths. */
class LocalChatException(val reason: LocalChatFailure) : Exception(reason.name)

/**
 * Experimental local conversation input, separate from the fixed runtime diagnostic.
 *
 * The caller owns the unlocked session and cancellation token. This adapter has no vault,
 * document repository, URI, mutation tool, network or durable conversation storage.
 * Readiness means a verified runnable installation, not language quality or available RAM.
 */
class LocalChatGateway internal constructor(
    context: Context,
    private val catalogueOverride: ModelCatalogue?,
    private val storeOverride: InstalledModelStore?,
) {
    constructor(context: Context) : this(context, null, null)

    private val applicationContext = context.applicationContext

    suspend fun isReady(): Boolean = withContext(Dispatchers.IO) {
        try {
            val catalogue = catalogueOverride ?: ModelCatalogue.read(applicationContext)
            val runnable = runnableModel(catalogue) ?: return@withContext false
            val store = storeOverride ?: InstalledModelStore(applicationContext, catalogue)
            // Probe only the runnable slot; a retired large installation does not
            // need another full hash merely to open the compact-model chat screen.
            store.withVerifiedModel(runnable.id) { selected, _ ->
                selected.runtimeVersion == runnable.runtimeVersion &&
                    selected.revision == runnable.revision && selected.sha256 == runnable.sha256 &&
                    selected.byteCount == runnable.byteCount && CpuModelDiagnosticPolicy.requirements(selected) != null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    suspend fun generate(
        prompt: String,
        cancellationRequested: () -> Boolean = { false },
    ): LocalInferenceResult = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (cancellationRequested()) throw CancellationException("Local chat revoked")
        if (prompt.length !in 1..LocalInferenceRunner.MAX_INPUT_UTF16 || prompt.isBlank()) {
            throw LocalChatException(LocalChatFailure.GENERATION)
        }
        try {
            val catalogue = catalogueOverride ?: ModelCatalogue.read(applicationContext)
            val runnable = runnableModel(catalogue)
                ?: throw LocalChatException(LocalChatFailure.MODEL_REQUIRED)
            val store = storeOverride ?: InstalledModelStore(applicationContext, catalogue)
            // Reverify instead of trusting isReady's earlier snapshot. The scoped token and
            // process-wide store mutex remain live through native generation and cleanup.
            store.withVerifiedModel(runnable.id, cancellationRequested) { selected, artifact ->
                if (selected.runtimeVersion != runnable.runtimeVersion ||
                    selected.sha256 != runnable.sha256 || selected.byteCount != runnable.byteCount ||
                    CpuModelDiagnosticPolicy.requirements(selected) == null) {
                    throw LocalChatException(LocalChatFailure.MODEL_REQUIRED)
                }
                LocalInferenceRunner().generate(artifact, prompt, cancellationRequested,
                    beforeNativeLoad = {
                        !cancellationRequested() && hasMemoryHeadroom(selected)
                    })
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (safe: LocalChatException) {
            throw safe
        } catch (_: ModelInstallException) {
            throw LocalChatException(LocalChatFailure.MODEL_REQUIRED)
        } catch (failure: LocalInferenceException) {
            currentCoroutineContext().ensureActive()
            if (cancellationRequested()) throw CancellationException("Local chat revoked")
            throw LocalChatException(if (failure.reason == LocalInferenceFailure.ADMISSION) {
                LocalChatFailure.MEMORY
            } else LocalChatFailure.GENERATION)
        } catch (_: Exception) {
            throw LocalChatException(LocalChatFailure.GENERATION)
        }
    }

    private fun runnableModel(catalogue: ModelCatalogue): RegisteredModel? =
        catalogue.models.singleOrNull {
            it.runtimeVersion == LiteRtLmLoadProbe.RUNTIME_VERSION &&
                CpuModelDiagnosticPolicy.requirements(it) != null
        }

    private fun hasMemoryHeadroom(model: RegisteredModel): Boolean {
        val manager = applicationContext.getSystemService(ActivityManager::class.java) ?: return false
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        return CpuModelDiagnosticPolicy.permits(model, memory.totalMem, memory.availMem,
            Process.is64Bit(), memory.lowMemory)
    }
}
