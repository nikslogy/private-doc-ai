package com.privateassistant.platform.inference

import android.app.ActivityManager
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real public-model execution. Synthetic input only; absence is never a model pass. */
@RunWith(AndroidJUnit4::class)
class LocalInferenceInstrumentedTest {
    @Test
    fun verifiedSyntheticGenerationAndCancellationReturnWithoutPlaintextFiles() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Real model probe is opt-in; this is not a passed model check",
            args.getString("runModelGeneration") == "true")
        require(args.getString("modelLicenseAccepted") == "true")
        fun phase(name: String) {
            instrumentation.sendStatus(2, Bundle().apply {
                putString("modelProbePhase", name)
                putInt("modelProbePid", Process.myPid())
                putLong("modelProbeElapsedRealtimeMillis", SystemClock.elapsedRealtime())
            })
        }
        phase("ARGUMENTS_ACCEPTED")
        val context = instrumentation.targetContext
        val model = requireNotNull(ModelCatalogue.read(context).find(requireNotNull(args.getString("modelId"))))
        require(model.experimental && model.backends.contains("CPU"))
        require(model.runtimeVersion == LiteRtLmLoadProbe.RUNTIME_VERSION)
        require(args.getString("runtimeVersion") == LiteRtLmLoadProbe.RUNTIME_VERSION)
        require(args.getString("modelSha256") == model.sha256)
        require(args.getString("modelByteCount") == model.byteCount.toString())
        val suppliedFile = File(requireNotNull(args.getString("modelPath")))
        val suppliedRoot = File(context.filesDir, "model-probes")
        require(!Files.isSymbolicLink(suppliedFile.toPath())) { "Symbolic model file rejected" }
        require(!Files.isSymbolicLink(suppliedRoot.toPath())) { "Symbolic probe directory rejected" }
        // Android's shell and app namespaces may spell /data/data and /data/user aliases
        // differently. Compare canonical objects inside this process, retaining exact containment.
        val probeRoot = suppliedRoot.canonicalFile
        val file = suppliedFile.canonicalFile
        require(probeRoot.isDirectory && file.parentFile == probeRoot) { "Model must be directly inside the app-owned probe directory" }
        require(file.name.matches(Regex("probe-[0-9]+-[0-9]+\\.litertlm"))) { "Unexpected controlled probe filename" }
        phase("MODEL_PATH_VALIDATED")
        phase("DEVICE_MODEL_VERIFY_START")
        val artifact = ModelFileVerifier.verify(file, model.byteCount, model.sha256, licenseAccepted = true)
        phase("DEVICE_MODEL_VERIFY_RETURNED")
        val canary = "LOCALMODELPRIVATECANARY815429"
        phase("CANARY_BASELINE_START")
        scanPrivateFiles(context.filesDir, file, canary)
        scanPrivateFiles(context.cacheDir, file, canary)
        scanPrivateFiles(context.noBackupFilesDir, file, canary)
        phase("CANARY_BASELINE_RETURNED")
        // Exercise the same admission hook as the app, including after waiting
        // for the heavy-work gate. This test never overrides a resource denial.
        fun admitsNativeLoad(): Boolean {
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            return CpuModelDiagnosticPolicy.permits(model, memory.totalMem, memory.availMem,
                Process.is64Bit(), memory.lowMemory)
        }
        assertTrue("The explicit target must pass this reviewed model's application memory policy", admitsNativeLoad())
        var deniedSendEntered = false
        try {
            LocalInferenceRunner().generate(artifact, LocalInferenceRunner.SYNTHETIC_PROMPT,
                onGenerationStarted = { deniedSendEntered = true }, beforeNativeLoad = { false })
            fail("A denied admission hook must return before native initialization")
        } catch (denied: LocalInferenceException) {
            assertEquals(LocalInferenceFailure.ADMISSION, denied.reason)
            assertFalse(deniedSendEntered)
        }
        phase("ADMISSION_DENIAL_VERIFIED")
        val started = SystemClock.elapsedRealtime()
        phase("NATIVE_GENERATION_START")
        val result = LocalInferenceRunner(maxDurationMillis = 300_000).generate(artifact,
            LocalInferenceRunner.SYNTHETIC_PROMPT + "\nSynthetic marker: " + canary,
            onGenerationStarted = { phase("NATIVE_SEND_STARTED") },
            beforeNativeLoad = ::admitsNativeLoad)
        phase("NATIVE_GENERATION_RETURNED")
        val generationMillis = SystemClock.elapsedRealtime() - started
        assertEquals(LiteRtLmLoadProbe.RUNTIME_VERSION, result.runtimeVersion)
        assertEquals("CPU", result.backend)
        assertTrue("Actual native generation must return bounded nonempty text",
            result.text.isNotBlank() && result.text.length <= LocalInferenceRunner.MAX_OUTPUT_UTF16)

        val generationEntered = AtomicLong(0)
        val cancellationObserved = AtomicInteger(0)
        val nativeCancellationCalls = AtomicInteger(0)
        val nativeSendFailures = AtomicInteger(0)
        val cancellationStarted = SystemClock.elapsedRealtime()
        phase("CANCELLATION_OPERATION_START")
        try {
            LocalInferenceRunner(maxDurationMillis = 300_000).generate(artifact,
                "Synthetic cancellation test. Write a long list of numbered blue triangles, at least 100 items. Do not use tools.",
                cancellationRequested = {
                    val entered = generationEntered.get()
                    val cancel = entered > 0 && System.nanoTime() - entered >= 100_000_000L
                    if (cancel) cancellationObserved.incrementAndGet()
                    cancel
                }, onGenerationStarted = {
                    phase("CANCELLATION_SEND_STARTED")
                    generationEntered.set(System.nanoTime())
                },
                onNativeCancellationRequested = {
                    if (nativeCancellationCalls.getAndIncrement() == 0) phase("NATIVE_CANCEL_RETURNED")
                },
                onNativeSendFailed = {
                    if (nativeSendFailures.getAndIncrement() == 0) phase("NATIVE_SEND_FAILED")
                }, beforeNativeLoad = ::admitsNativeLoad)
            fail("The actual synchronous generation window must observe the cancellation request")
        } catch (_: CancellationException) {
            assertTrue("Initialization must return before the generation-phase hook", generationEntered.get() > 0)
            assertTrue("A cancellation request must actually be observed", cancellationObserved.get() > 0)
            assertTrue("The live native cancel API must actually return", nativeCancellationCalls.get() > 0)
            assertTrue("The synchronous native send must actually reject the interrupted operation", nativeSendFailures.get() > 0)
            phase("CANCELLATION_RETURNED")
        }
        val cancellationMillis = SystemClock.elapsedRealtime() - cancellationStarted
        // Synchronous native return + joined cancellation watchdog precede runner cleanup.
        // This is an emulator observation, not a hard native timeout or phone latency claim.
        ModelFileVerifier.verify(file, model.byteCount, model.sha256, licenseAccepted = true)
        phase("POST_MODEL_VERIFY_RETURNED")
        scanPrivateFiles(context.filesDir, file, canary)
        scanPrivateFiles(context.cacheDir, file, canary)
        scanPrivateFiles(context.noBackupFilesDir, file, canary)
        phase("CANARY_FINAL_RETURNED")
        instrumentation.sendStatus(2, Bundle().apply {
            putString("modelProbeScope", "REAL_SYNTHETIC_GENERATION_AND_CANCELLATION")
            putLong("generationElapsedMillis", generationMillis)
            putLong("cancellationOperationMillis", cancellationMillis)
            putInt("generatedUtf16", result.text.length)
            putInt("nativeCancellationCalls", nativeCancellationCalls.get())
            putInt("nativeSendFailures", nativeSendFailures.get())
        })
    }

    /** Bounded file-canary scan. Only the exact separately hash-verified public model is excluded. */
    private fun scanPrivateFiles(root: File, publicModel: File, canary: String) {
        val needle = canary.toByteArray(Charsets.US_ASCII)
        if (!root.exists()) return
        val canonicalRoot = root.canonicalFile
        var total = 0L
        var entries = 0
        val visited = mutableSetOf<String>()
        val tree = root.walkTopDown().onEnter { directory ->
            assertFalse("Unexpected symbolic directory in private-file probe", Files.isSymbolicLink(directory.toPath()))
            val canonical = directory.canonicalPath
            assertTrue(canonical == canonicalRoot.path || canonical.startsWith(canonicalRoot.path + File.separator))
            assertTrue("Repeated private directory", visited.add(canonical))
            assertTrue("Private directory budget exceeded", visited.size <= 128)
            true
        }
        for (file in tree.filter { it.isFile }) {
            assertFalse("Unexpected symbolic private file", Files.isSymbolicLink(file.toPath()))
            assertTrue(file.canonicalPath.startsWith(canonicalRoot.path + File.separator))
            if (file.canonicalFile == publicModel.canonicalFile) continue
            entries++
            assertTrue("Private file entry budget exceeded", entries <= 256)
            assertTrue("Unexpected large non-model file", file.length() <= 16L * 1024 * 1024)
            total += file.length()
            assertTrue("Private file byte budget exceeded", total <= 64L * 1024 * 1024)
            val buffer = ByteArray(64 * 1024 + needle.size)
            var carried = 0
            var readBytes = 0L
            file.inputStream().use { input ->
                while (true) {
                    val count = input.read(buffer, carried, buffer.size - carried)
                    if (count < 0) break
                    assertTrue("Unexpected zero-length read", count > 0)
                    readBytes += count
                    assertTrue("Private-file read budget exceeded", readBytes <= 16L * 1024 * 1024)
                    val size = carried + count
                    for (start in 0..(size - needle.size)) {
                        var matched = 0
                        while (matched < needle.size && buffer[start + matched] == needle[matched]) matched++
                        assertFalse("Synthetic model input canary reached private files", matched == needle.size)
                    }
                    carried = minOf(needle.size - 1, size)
                    buffer.copyInto(buffer, 0, size - carried, size)
                }
            }
        }
    }
}
