package com.privateassistant.platform.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.system.Os
import android.system.OsConstants
import com.google.crypto.tink.Aead
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.streamingaead.StreamingAeadKeyTemplates
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.KeyGenerator

/** Production uses only authenticated library primitives; clear keysets are never serialized. */
internal class VaultCrypto(private val context: Context, internal val directory: File) {
    internal data class Secrets(val vaultId: String, val databaseKey: ByteArray, val streamingAead: StreamingAead)

    private val bootstrap = File(directory, "root.envelope")
    private val databaseEnvelope = File(directory, "database.envelope")
    private val streamingEnvelope = File(directory, "streaming.envelope")
    private val keyAlias = "${context.packageName}.vault.root.v1"

    fun open(): Secrets {
        AeadConfig.register()
        StreamingAeadConfig.register()
        if (!directory.exists()) {
            check(directory.mkdirs())
            DurableFiles.syncDirectory(requireNotNull(directory.parentFile))
        }
        val existingArtifacts = directory.listFiles().orEmpty().any { !it.name.startsWith(".atomic-") }
        try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(keyAlias)) {
                // An absent key must never silently replace a key belonging to existing ciphertext.
                if (existingArtifacts) throw VaultIntegrityException()
                createWrappingKey()
            }
            val keystoreAead = AndroidKeystoreKmsClient().getAead("android-keystore://$keyAlias")
            val root = readOrCreateRoot(keystoreAead, existingArtifacts)
            val vaultId = root.first
            val rootAead = root.second.getPrimitive(Aead::class.java)
            val databaseAad = "private-assistant/database-secret/v1/$vaultId".toByteArray(Charsets.UTF_8)
            val databaseKey = if (databaseEnvelope.exists()) {
                require(databaseEnvelope.length() in 28L..MAX_ENVELOPE_BYTES.toLong())
                rootAead.decrypt(databaseEnvelope.readBytes(), databaseAad).also { bytes ->
                    if (bytes.size != 32) {
                        bytes.fill(0)
                        throw VaultIntegrityException()
                    }
                }
            } else {
                requireNoPrivateObjects()
                ByteArray(32).also { bytes ->
                    SecureRandom().nextBytes(bytes)
                    try {
                        val encrypted = rootAead.encrypt(bytes, databaseAad)
                        DurableFiles.atomicWrite(databaseEnvelope) { it.write(encrypted) }
                    } catch (failure: Throwable) {
                        bytes.fill(0)
                        throw failure
                    }
                }
            }
            try {
                val aad = "private-assistant/streaming-keyset/v1/$vaultId".toByteArray(Charsets.UTF_8)
                val streaming = if (streamingEnvelope.exists()) {
                    require(streamingEnvelope.length() in 28L..MAX_ENVELOPE_BYTES.toLong())
                    KeysetHandle.readWithAssociatedData(BinaryKeysetReader.withFile(streamingEnvelope), rootAead, aad)
                } else {
                    requireNoPrivateObjects()
                    KeysetHandle.generateNew(StreamingAeadKeyTemplates.AES256_GCM_HKDF_1MB).also { generated ->
                        DurableFiles.atomicWrite(streamingEnvelope) { output ->
                            generated.writeWithAssociatedData(BinaryKeysetWriter.withOutputStream(output), rootAead, aad)
                        }
                    }
                }
                return Secrets(vaultId, databaseKey, streaming.getPrimitive(StreamingAead::class.java))
            } catch (failure: Throwable) {
                databaseKey.fill(0)
                throw failure
            }
        } catch (failure: VaultException) {
            throw failure
        } catch (failure: Exception) {
            if (generateSequence<Throwable>(failure) { it.cause }
                    .any { it is UserNotAuthenticatedException }
            ) throw VaultAuthenticationRequiredException(failure)
            throw VaultIntegrityException(failure)
        }
    }

    private fun readOrCreateRoot(keystoreAead: Aead, existingArtifacts: Boolean): Pair<String, KeysetHandle> {
        if (bootstrap.exists()) {
            DataInputStream(bootstrap.inputStream().buffered()).use { input ->
                require(input.readInt() == ENVELOPE_MAGIC)
                require(input.readInt() == FORMAT_VERSION)
                val id = input.readUTF().also { UUID.fromString(it) }
                val encryptedLength = input.readInt()
                require(encryptedLength in 28..MAX_ENVELOPE_BYTES)
                val encrypted = ByteArray(encryptedLength).also(input::readFully)
                require(input.read() == -1)
                return id to KeysetHandle.readWithAssociatedData(
                    BinaryKeysetReader.withBytes(encrypted),
                    keystoreAead,
                    rootAad(id),
                )
            }
        }
        if (existingArtifacts) throw VaultIntegrityException()
        val id = UUID.randomUUID().toString()
        val handle = KeysetHandle.generateNew(AeadKeyTemplates.AES256_GCM)
        val encrypted = ByteArrayOutputStream().use { output ->
            handle.writeWithAssociatedData(BinaryKeysetWriter.withOutputStream(output), keystoreAead, rootAad(id))
            output.toByteArray()
        }
        DurableFiles.atomicWrite(bootstrap) { stream ->
            DataOutputStream(stream).apply {
                writeInt(ENVELOPE_MAGIC)
                writeInt(FORMAT_VERSION)
                writeUTF(id)
                writeInt(encrypted.size)
                write(encrypted)
                flush()
            }
        }
        return id to handle
    }

    private fun requireNoPrivateObjects() {
        if (File(directory, "metadata.db").exists() ||
            File(directory, "objects").listFiles().orEmpty().isNotEmpty()
        ) throw VaultIntegrityException()
    }

    private fun createWrappingKey() {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(
                    30,
                    KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG,
                )
                .setInvalidatedByBiometricEnrollment(false)
                .build(),
        )
        generator.generateKey()
    }

    private fun rootAad(id: String) = "private-assistant/root/v1/$id".toByteArray(Charsets.UTF_8)

    companion object {
        private const val FORMAT_VERSION = 1
        private const val ENVELOPE_MAGIC = 0x50415631 // PAV1
        private const val MAX_ENVELOPE_BYTES = 64 * 1024
        fun objectAad(vaultId: String, documentId: String, revisionId: String = documentId): ByteArray =
            "private-assistant/original/v1/$vaultId/$documentId/$revisionId".toByteArray(Charsets.UTF_8)
    }
}

internal object DurableFiles {
    fun atomicWrite(destination: File, write: (OutputStream) -> Unit) {
        val parent = requireNotNull(destination.parentFile)
        check(parent.exists() || parent.mkdirs())
        val temporary = File(parent, ".atomic-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { output ->
                val keepOpen = object : FilterOutputStream(output) {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        output.write(bytes, offset, length)
                    }
                    override fun close() { flush() }
                }
                write(keepOpen)
                output.flush()
                output.fd.sync()
            }
            Os.rename(temporary.absolutePath, destination.absolutePath)
            syncDirectory(parent)
        } finally {
            temporary.delete()
        }
    }

    fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
        try {
            // O_DIRECTORY is not part of Android's public SDK; check the opened inode instead.
            if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) {
                throw IOException("Directory sync requires a directory")
            }
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }
}
