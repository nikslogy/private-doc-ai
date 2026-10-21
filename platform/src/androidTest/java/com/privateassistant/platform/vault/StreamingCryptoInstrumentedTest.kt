package com.privateassistant.platform.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.crypto.tink.Aead
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.streamingaead.StreamingAeadKeyTemplates
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Real pinned Tink primitives. Device-authentication integration is a separate manual gate. */
@RunWith(AndroidJUnit4::class)
class StreamingCryptoInstrumentedTest {
    @Before fun register() {
        AeadConfig.register()
        StreamingAeadConfig.register()
    }

    @Test fun encryptedKeysetReopenPreservesByteIdenticalOriginal() {
        val wrapping = KeysetHandle.generateNew(AeadKeyTemplates.AES256_GCM).getPrimitive(Aead::class.java)
        val originalHandle = newHandle()
        val keysetAad = "test/keyset/v1".toByteArray()
        val encryptedKeyset = ByteArrayOutputStream().apply {
            originalHandle.writeWithAssociatedData(BinaryKeysetWriter.withOutputStream(this), wrapping, keysetAad)
        }.toByteArray()
        val aad = VaultCrypto.objectAad(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val original = ByteArray(2 * 1024 * 1024 + 31) { (it * 17).toByte() }
        val encrypted = encrypt(originalHandle.getPrimitive(StreamingAead::class.java), original, aad)
        // Recreate the handle from its persisted encrypted representation, as after process death.
        val reopened = KeysetHandle.readWithAssociatedData(BinaryKeysetReader.withBytes(encryptedKeyset), wrapping, keysetAad)
        assertArrayEquals(original, decrypt(reopened.getPrimitive(StreamingAead::class.java), encrypted, aad))
    }

    @Test fun wrongDocumentVaultAndKeyRejectCiphertext() {
        val primitive = newHandle().getPrimitive(StreamingAead::class.java)
        val vault = UUID.randomUUID().toString()
        val document = UUID.randomUUID().toString()
        val revision = UUID.randomUUID().toString()
        val aad = VaultCrypto.objectAad(vault, document, revision)
        val encrypted = encrypt(primitive, "synthetic private canary".toByteArray(), aad)
        expectFailure { decrypt(primitive, encrypted, VaultCrypto.objectAad(vault, UUID.randomUUID().toString(), revision)) }
        expectFailure { decrypt(primitive, encrypted, VaultCrypto.objectAad(UUID.randomUUID().toString(), document, revision)) }
        expectFailure { decrypt(primitive, encrypted, VaultCrypto.objectAad(vault, document, UUID.randomUUID().toString())) }
        expectFailure { decrypt(newHandle().getPrimitive(StreamingAead::class.java), encrypted, aad) }
    }

    @Test fun tamperedAndTruncatedFinalSegmentRejectAtEof() {
        val primitive = newHandle().getPrimitive(StreamingAead::class.java)
        val aad = VaultCrypto.objectAad(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val encrypted = encrypt(primitive, ByteArray(2 * 1024 * 1024 + 37) { it.toByte() }, aad)
        val changed = encrypted.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        val changedHeader = encrypted.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        expectFailure { decrypt(primitive, changed, aad) }
        expectFailure { decrypt(primitive, changedHeader, aad) }
        expectFailure { decrypt(primitive, encrypted.copyOf(encrypted.size - 1), aad) }
    }

    @Test fun encryptedKeysetRejectsWrongAadAndWrongRoot() {
        val root = KeysetHandle.generateNew(AeadKeyTemplates.AES256_GCM).getPrimitive(Aead::class.java)
        val aad = "keyset/v1/vault-a".toByteArray()
        val envelope = ByteArrayOutputStream().apply {
            newHandle().writeWithAssociatedData(BinaryKeysetWriter.withOutputStream(this), root, aad)
        }.toByteArray()
        expectFailure { KeysetHandle.readWithAssociatedData(BinaryKeysetReader.withBytes(envelope), root, "keyset/v1/vault-b".toByteArray()) }
        val wrongRoot = KeysetHandle.generateNew(AeadKeyTemplates.AES256_GCM).getPrimitive(Aead::class.java)
        expectFailure { KeysetHandle.readWithAssociatedData(BinaryKeysetReader.withBytes(envelope), wrongRoot, aad) }
    }

    private fun newHandle() = KeysetHandle.generateNew(StreamingAeadKeyTemplates.AES256_GCM_HKDF_1MB)
    private fun encrypt(primitive: StreamingAead, bytes: ByteArray, aad: ByteArray): ByteArray =
        ByteArrayOutputStream().apply { primitive.newEncryptingStream(this, aad).use { it.write(bytes) } }.toByteArray()

    private fun decrypt(primitive: StreamingAead, bytes: ByteArray, aad: ByteArray): ByteArray =
        primitive.newDecryptingStream(ByteArrayInputStream(bytes), aad).use { it.readBytes() }

    private fun expectFailure(block: () -> Any?) {
        try {
            block()
        } catch (_: Exception) {
            return
        }
        fail("Unauthenticated ciphertext must be rejected")
    }
}
