package com.privateassistant.platform.inference

import android.content.Context
import android.system.Os
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.streamingaead.StreamingAeadKeyTemplates
import com.privateassistant.platform.vault.DurableFiles
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Public weights only. No vault key, source URI, document text or chat is accepted. */
enum class InstalledModelStatus { ABSENT, VERIFIED, INVALID }
data class InstalledModelInfo(val modelId: String, val revision: String, val byteCount: Long,
    val sha256: String, val status: InstalledModelStatus)
enum class ModelInstallFailure { UNKNOWN_MODEL, LICENCE_REQUIRED, INTEGRITY, STORAGE, CANCELLED }
class ModelInstallException(val reason: ModelInstallFailure) : Exception(reason.name)

class InstalledModelStore internal constructor(private val root: File, private val catalogue: ModelCatalogue) {
    constructor(context: Context, catalogue: ModelCatalogue) : this(
        File(context.noBackupFilesDir.canonicalFile, "local-models-v1"), catalogue,
    )

    suspend fun installedModels(): List<InstalledModelInfo> = guarded {
        prepare()
        catalogue.models.map { model ->
            val slot = slot(model)
            val status = try {
                requireSafe(slot)
                if (!slot.exists()) InstalledModelStatus.ABSENT
                else { verify(model, slot); InstalledModelStatus.VERIFIED }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { InstalledModelStatus.INVALID }
            InstalledModelInfo(model.id, model.revision, model.byteCount, model.sha256, status)
        }
    }

    /** Caller closes the selected provider stream. An existing verified model is idempotent. */
    suspend fun install(modelId: String, input: InputStream, licenseAccepted: Boolean,
        cancellationRequested: () -> Boolean = { false }, onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): InstalledModelInfo = guarded {
        val model = find(modelId)
        if (!licenseAccepted) throw ModelInstallException(ModelInstallFailure.LICENCE_REQUIRED)
        prepare()
        checkCancelled(cancellationRequested)
        val target = slot(model)
        requireSafe(target)
        if (target.exists()) {
            verify(model, target, cancellationRequested)
        } else {
            if (root.usableSpace < model.byteCount * 2 + 64L * 1024 * 1024) throw ModelInstallException(ModelInstallFailure.STORAGE)
            val stage = File(root, ".staging-${UUID.randomUUID()}")
            requireSafe(stage)
            check(stage.mkdir())
            Os.chmod(stage.path, 448)
            try {
                // The selected file is untrusted: it could be a private document. Its bytes
                // may reach disk only as ciphertext until their approved public digest matches.
                StreamingAeadConfig.register()
                val ephemeral = KeysetHandle.generateNew(StreamingAeadKeyTemplates.AES256_GCM_HKDF_1MB)
                    .getPrimitive(StreamingAead::class.java)
                val aad = "unverified-model-staging-v1/${fingerprint(model)}".toByteArray(Charsets.UTF_8)
                val encryptedInput = File(stage, ENCRYPTED_INPUT)
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                FileOutputStream(encryptedInput).use { sink ->
                    Os.chmod(encryptedInput.path, 384)
                    val keepOpen = object : FilterOutputStream(sink) {
                        override fun write(b: ByteArray, offset: Int, length: Int) { sink.write(b, offset, length) }
                        override fun close() { flush() }
                    }
                    ephemeral.newEncryptingStream(keepOpen, aad).use { encrypted ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCancelled(cancellationRequested)
                        val remaining = model.byteCount - total
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining + 1).toInt())
                        if (count < 0) break
                        if (count == 0) throw ModelInstallException(ModelInstallFailure.INTEGRITY)
                        total += count
                        if (total > model.byteCount) throw ModelInstallException(ModelInstallFailure.INTEGRITY)
                        digest.update(buffer, 0, count)
                        encrypted.write(buffer, 0, count)
                        onProgress(total, model.byteCount)
                    }
                    }
                    sink.flush(); sink.fd.sync()
                }
                    if (total != model.byteCount || hex(digest.digest()) != model.sha256) {
                        throw ModelInstallException(ModelInstallFailure.INTEGRITY)
                    }
                // Only authenticated, hash-verified public weights are now allowed plaintext.
                val output = File(stage, MODEL_FILE)
                val verifiedDigest = MessageDigest.getInstance("SHA-256")
                var verifiedBytes = 0L
                FileOutputStream(output).use { sink ->
                    Os.chmod(output.path, 384)
                    encryptedInput.inputStream().use { encrypted ->
                        ephemeral.newDecryptingStream(encrypted, aad).use { plain ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                checkCancelled(cancellationRequested)
                                val count = plain.read(buffer)
                                if (count < 0) break
                                if (count == 0) throw ModelInstallException(ModelInstallFailure.INTEGRITY)
                                verifiedBytes += count
                                if (verifiedBytes > model.byteCount) throw ModelInstallException(ModelInstallFailure.INTEGRITY)
                                verifiedDigest.update(buffer, 0, count)
                                sink.write(buffer, 0, count)
                            }
                        }
                    }
                    if (verifiedBytes != model.byteCount || hex(verifiedDigest.digest()) != model.sha256) {
                        throw ModelInstallException(ModelInstallFailure.INTEGRITY)
                    }
                    sink.flush(); sink.fd.sync()
                }
                check(encryptedInput.delete())
                val receipt = File(stage, RECEIPT_FILE)
                FileOutputStream(receipt).use { sink ->
                    Os.chmod(receipt.path, 384)
                    sink.write(receiptBytes(model)); sink.flush(); sink.fd.sync()
                }
                Os.chmod(output.path, 256)
                DurableFiles.syncDirectory(stage)
                checkCancelled(cancellationRequested)
                // All store mutation/leases share the process mutex. Never replace a prior slot.
                if (target.exists()) throw ModelInstallException(ModelInstallFailure.INTEGRITY)
                Os.rename(stage.path, target.path)
                DurableFiles.syncDirectory(root)
            } finally {
                if (stage.exists()) deleteOwnedSlot(stage)
            }
        }
        InstalledModelInfo(model.id, model.revision, model.byteCount, model.sha256, InstalledModelStatus.VERIFIED)
    }

    /** The lease spans verification, native load/generation and cleanup, even across suspension. */
    suspend fun <T> withVerifiedModel(modelId: String, cancellationRequested: () -> Boolean = { false },
        block: suspend (RegisteredModel, VerifiedModelArtifact) -> T,
    ): T = withContext(Dispatchers.IO) {
        MUTEX.withLock {
            val model = find(modelId)
            prepare()
            val file = verify(model, slot(model), cancellationRequested)
            val live = AtomicBoolean(true)
            try { block(model, VerifiedModelArtifact(file, live::get)) }
            finally { live.set(false) }
        }
    }

    /** Invoked only after the model-manager's current explicit removal confirmation. */
    suspend fun remove(modelId: String) = guarded {
        val model = find(modelId)
        prepare()
        val target = slot(model)
        requireSafe(target)
        if (target.exists()) { deleteOwnedSlot(target); DurableFiles.syncDirectory(root) }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        MUTEX.withLock {
            try { block() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (safe: ModelInstallException) { throw safe }
            catch (_: Exception) { throw ModelInstallException(ModelInstallFailure.STORAGE) }
        }
    }
    private fun find(id: String): RegisteredModel = catalogue.find(id)
        ?: throw ModelInstallException(ModelInstallFailure.UNKNOWN_MODEL)
    private fun prepare() {
        requireSafe(root)
        if (!root.exists()) {
            check(root.mkdir())
            DurableFiles.syncDirectory(requireNotNull(root.parentFile))
        }
        check(root.isDirectory)
        Os.chmod(root.path, 448)
        // Only recognizable private staging directories may be reconciled. Unknown entries survive.
        root.listFiles()?.filter { it.name.matches(Regex("\\.staging-[a-f0-9-]{36}")) }?.forEach {
            deleteOwnedSlot(it)
        }
    }
    private fun slot(model: RegisteredModel): File = File(root, "m-${fingerprint(model)}")
    private suspend fun verify(model: RegisteredModel, directory: File,
        cancellationRequested: () -> Boolean = { false },
    ): File {
        requireSafe(directory)
        if (!directory.isDirectory) throw ModelInstallException(ModelInstallFailure.INTEGRITY)
        val children = directory.listFiles() ?: throw ModelInstallException(ModelInstallFailure.INTEGRITY)
        if (children.map { it.name }.toSet() != setOf(MODEL_FILE, RECEIPT_FILE)) {
            throw ModelInstallException(ModelInstallFailure.INTEGRITY)
        }
        val receipt = File(directory, RECEIPT_FILE); requireSafe(receipt)
        if (!receipt.isFile || receipt.length() != receiptBytes(model).size.toLong() ||
            !receipt.readBytes().contentEquals(receiptBytes(model))) throw ModelInstallException(ModelInstallFailure.INTEGRITY)
        val file = File(directory, MODEL_FILE); requireSafe(file)
        if (!file.isFile || file.length() != model.byteCount) throw ModelInstallException(ModelInstallFailure.INTEGRITY)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024); var total = 0L
            while (true) {
                checkCancelled(cancellationRequested)
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > model.byteCount) throw ModelInstallException(ModelInstallFailure.INTEGRITY)
                digest.update(buffer, 0, count)
            }
            if (total != model.byteCount || hex(digest.digest()) != model.sha256) {
                throw ModelInstallException(ModelInstallFailure.INTEGRITY)
            }
        }
        checkCancelled(cancellationRequested)
        return file
    }
    private fun requireSafe(file: File) {
        if (Files.isSymbolicLink(file.toPath()) || file.absoluteFile != file.canonicalFile) {
            throw ModelInstallException(ModelInstallFailure.INTEGRITY)
        }
    }
    private fun deleteOwnedSlot(directory: File) {
        requireSafe(directory)
        if (!directory.isDirectory) throw ModelInstallException(ModelInstallFailure.INTEGRITY)
        val files = directory.listFiles() ?: throw ModelInstallException(ModelInstallFailure.INTEGRITY)
        if (files.any { it.name !in setOf(MODEL_FILE, RECEIPT_FILE, ENCRYPTED_INPUT) }) throw ModelInstallException(ModelInstallFailure.INTEGRITY)
        files.forEach { requireSafe(it); check(it.isFile); check(it.delete()) }
        check(directory.delete())
    }
    private suspend fun checkCancelled(cancel: () -> Boolean) {
        currentCoroutineContext().ensureActive()
        if (cancel()) throw kotlinx.coroutines.CancellationException("Model operation revoked")
    }
    private fun receiptBytes(model: RegisteredModel) = "model-install-v1\n${fingerprint(model)}\nlicence-acknowledged\n".toByteArray(Charsets.UTF_8)
    private fun fingerprint(m: RegisteredModel): String {
        val fields = listOf(m.id, m.revision, m.fileName, m.byteCount.toString(), m.sha256,
            m.licenseIdentifier, m.licenseTermsUrl, m.requiresLicenseAcceptance.toString(),
            m.contextTokens.toString(), m.minimumRamBytes.toString(), m.displayName, m.downloadUrl,
            m.experimental.toString(), m.evaluatedLanguages.joinToString(","), m.evaluatedTasks.joinToString(","),
            m.backends.joinToString(","), LiteRtLmLoadProbe.RUNTIME_VERSION)
        return hex(MessageDigest.getInstance("SHA-256").digest(fields.joinToString("\u0000").toByteArray(Charsets.UTF_8)))
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    companion object {
        private val MUTEX = Mutex()
        private const val MODEL_FILE = "model.litertlm"
        private const val RECEIPT_FILE = "ack.txt"
        private const val ENCRYPTED_INPUT = "input.cipher"
    }
}
