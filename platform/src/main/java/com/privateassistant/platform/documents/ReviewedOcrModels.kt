package com.privateassistant.platform.documents

import android.content.Context
import android.system.Os
import com.privateassistant.platform.vault.DurableFiles
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Installs only public, reviewed OCR weights from this APK. No URI, download,
 * document bytes or vault credentials enter this store. Every use verifies the
 * installed bytes before native code receives their directory.
 */
class ReviewedOcrModels(context: Context) {
    private val applicationContext = context.applicationContext

    val available: Boolean
        get() = runCatching {
            val models = catalogue().models
            val names = applicationContext.assets.list("ocr/tessdata")?.toSet().orEmpty()
            models.all { it.fileName in names }
        }.getOrDefault(false)

    fun directory(): File = synchronized(installationMonitor) {
        try {
            val catalogue = catalogue()
            val root = File(applicationContext.noBackupFilesDir, "offline-ocr-v1")
            requireDirectory(root, applicationContext.noBackupFilesDir)
            val installed = File(root, catalogue.revision)
            if (installed.exists()) {
                verifyDirectory(installed, root, catalogue.models)
                return@synchronized installed
            }
            val staging = File(root, "install-${UUID.randomUUID()}")
            requireDirectory(staging, root)
            try {
                val data = File(staging, "tessdata")
                requireDirectory(data, staging)
                for (model in catalogue.models) {
                    val destination = File(data, model.fileName)
                    applicationContext.assets.open("ocr/tessdata/${model.fileName}").use { input ->
                        FileOutputStream(destination).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var count = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                count += read
                                check(count <= model.byteCount)
                                output.write(buffer, 0, read)
                            }
                            check(count == model.byteCount)
                            output.fd.sync()
                        }
                    }
                    check(verified(destination, data, model))
                }
                verifyDirectory(staging, root, catalogue.models)
                DurableFiles.syncDirectory(data)
                DurableFiles.syncDirectory(staging)
                Os.rename(staging.absolutePath, installed.absolutePath)
                DurableFiles.syncDirectory(root)
                installed
            } finally {
                // Only our own public model staging directory; never a document directory.
                staging.deleteRecursively()
            }
        } catch (_: Exception) {
            throw OcrRecognitionException()
        }
    }

    private fun catalogue(): Catalogue {
        val bytes = applicationContext.assets.open("ocr/catalogue.json").use { it.readBytesBounded() }
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        check(json.getInt("schemaVersion") == 1)
        check(json.getString("id") == "tessdata-fast-eng-hin-mar-v1")
        check(json.getString("repository") == "https://github.com/tesseract-ocr/tessdata_fast")
        val revision = json.getString("revision")
        check(revision.matches(Regex("[0-9a-f]{40}")))
        val engine = json.getJSONObject("engine")
        check(engine.getString("id") == "tesseract4android")
        check(engine.getString("version") == "4.9.0" && engine.getString("nativeVersion") == "5.5.1")
        check(json.getJSONObject("license").getString("identifier") == "Apache-2.0")
        val array = json.getJSONArray("models")
        check(array.length() == 3)
        val models = (0 until array.length()).map { index ->
            val model = array.getJSONObject(index)
            val language = model.getString("language")
            val name = model.getString("fileName")
            check(language in setOf("eng", "hin", "mar") && name == "$language.traineddata")
            val count = model.getLong("byteCount")
            val digest = model.getString("sha256")
            check(count in 1..32L * 1024 * 1024 && digest.matches(Regex("[0-9a-f]{64}")))
            Model(name, count, digest)
        }
        check(models.map { it.fileName }.toSet() == setOf("eng.traineddata", "hin.traineddata", "mar.traineddata"))
        return Catalogue(revision, models)
    }

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val buffer = ByteArray(16 * 1024 + 1)
        var count = 0
        while (count < buffer.size) {
            val read = read(buffer, count, buffer.size - count)
            if (read == -1) break
            if (read != 0) count += read
        }
        check(count <= 16 * 1024)
        return buffer.copyOf(count)
    }

    private fun requireDirectory(directory: File, parent: File) {
        check(directory.canonicalFile == File(parent.canonicalFile, directory.name))
        check(directory.isDirectory || directory.mkdir())
    }

    private fun verifyDirectory(directory: File, parent: File, models: List<Model>) {
        check(directory.canonicalFile == File(parent.canonicalFile, directory.name) && directory.isDirectory)
        val data = File(directory, "tessdata")
        check(data.canonicalFile == File(directory.canonicalFile, "tessdata") && data.isDirectory)
        check(directory.list()?.toSet() == setOf("tessdata"))
        check(data.list()?.toSet() == models.map { it.fileName }.toSet())
        check(models.all { verified(File(data, it.fileName), data, it) })
    }

    private fun verified(file: File, parent: File, model: Model): Boolean {
        if (file.canonicalFile != File(parent.canonicalFile, model.fileName) ||
            !file.isFile || file.length() != model.byteCount) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == model.sha256
    }

    private data class Model(val fileName: String, val byteCount: Long, val sha256: String)
    private data class Catalogue(val revision: String, val models: List<Model>)
    private companion object { val installationMonitor = Any() }
}
