package com.privateassistant.platform.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic admission tests only; no fixture is sent to a native engine. */
@RunWith(AndroidJUnit4::class)
class LocalChatGatewayInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun root() = File(context.noBackupFilesDir.canonicalFile, "chat-gateway-test-${UUID.randomUUID()}")
    private fun gateway(root: File, catalogue: ModelCatalogue = ModelCatalogue.read(context)) =
        LocalChatGateway(context, catalogue, InstalledModelStore(root, catalogue))

    @Test fun readinessRequiresAnActualVerifiedInstallation() = runBlocking {
        val root = root()
        try {
            assertFalse(gateway(root).isReady())
            assertTrue(root.isDirectory)
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun absentModelCannotBecomeAGeneratedReply() = runBlocking {
        val root = root()
        try {
            try {
                gateway(root).generate("A synthetic user asks about a blue triangle.")
                fail("Missing weights must not produce a response or run native inference")
            } catch (failure: LocalChatException) {
                assertEquals(LocalChatFailure.MODEL_REQUIRED, failure.reason)
            }
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun revokedRequestReturnsBeforeModelStorageIsTouched() = runBlocking {
        val root = root()
        try {
            try {
                gateway(root).generate("Synthetic cancellation request", cancellationRequested = { true })
                fail("A revoked conversation cannot start model work")
            } catch (_: CancellationException) { }
            assertFalse(root.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun invalidPromptBudgetsFailBeforeModelStorageOrNativeWork() = runBlocking {
        val root = root()
        try {
            for (prompt in listOf("", "   ", "x".repeat(LocalInferenceRunner.MAX_INPUT_UTF16 + 1))) {
                try {
                    gateway(root).generate(prompt)
                    fail("An invalid prompt must fail at the gateway boundary")
                } catch (failure: LocalChatException) {
                    assertEquals(LocalChatFailure.GENERATION, failure.reason)
                }
                assertFalse(root.exists())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun historicalRuntimeRecordDoesNotEnableChat() = runBlocking {
        val root = root()
        val metadata = context.assets.open("models/catalogue.json").bufferedReader().use { it.readText() }
        val json = JSONObject(metadata)
        val records = json.getJSONArray("models")
        val old = (0 until records.length()).map(records::getJSONObject)
            .single { it.getString("runtimeVersion") == "0.9.0" }
        json.put("models", JSONArray().put(old))
        val historical = ModelCatalogue.parse(json.toString())
        try {
            val gateway = gateway(root, historical)
            assertFalse(gateway.isReady())
            try {
                gateway.generate("Synthetic user message")
                fail("A retired runtime cannot silently become the chat model")
            } catch (failure: LocalChatException) {
                assertEquals(LocalChatFailure.MODEL_REQUIRED, failure.reason)
            }
            assertFalse(root.exists())
        } finally { root.deleteRecursively() }
    }
}
