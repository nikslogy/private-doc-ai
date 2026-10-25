package com.privateassistant.platform.inference

import android.content.Context
import java.net.URI
import java.util.Collections
import org.json.JSONObject

/** Reviewed public metadata. Experimental registration never enables document Q&A. */
class RegisteredModel internal constructor(
    val id: String, val revision: String, val fileName: String, val byteCount: Long,
    val sha256: String, val licenseIdentifier: String, val licenseTermsUrl: String,
    val requiresLicenseAcceptance: Boolean, val contextTokens: Int, val minimumRamBytes: Long,
    evaluatedLanguages: List<String>, evaluatedTasks: List<String>, backends: List<String>,
    val displayName: String = id, val downloadUrl: String = "", val experimental: Boolean = true,
    val runtimeVersion: String = LiteRtLmLoadProbe.RUNTIME_VERSION,
) {
    val evaluatedLanguages: List<String> = Collections.unmodifiableList(ArrayList(evaluatedLanguages))
    val evaluatedTasks: List<String> = Collections.unmodifiableList(ArrayList(evaluatedTasks))
    val backends: List<String> = Collections.unmodifiableList(ArrayList(backends))
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}")))
        require(revision.matches(Regex("[a-f0-9]{40}")))
        require(fileName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.litertlm")))
        require(byteCount in 1..MAX_MODEL_BYTES && sha256.matches(Regex("[a-f0-9]{64}")))
        require(contextTokens in 128..32768 && minimumRamBytes in 1..(64L * 1024 * 1024 * 1024))
        require(displayName.isNotBlank() && displayName.length <= 100 && displayName.none(Char::isISOControl))
        require(licenseIdentifier.matches(Regex("[A-Za-z0-9][A-Za-z0-9.+_-]{0,63}")))
        require(validPublicUrl(licenseTermsUrl))
        require(downloadUrl.isEmpty() || validPublicUrl(downloadUrl))
        require(this.backends.isNotEmpty() && this.backends.size <= 3 && this.backends.distinct().size == this.backends.size)
        require(this.backends.all { it in setOf("CPU", "GPU", "NPU") })
        require(listOf(this.evaluatedLanguages, this.evaluatedTasks).all { list ->
            list.size <= 32 && list.distinct().size == list.size && list.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) }
        })
        // This build only admits explicitly experimental entries. Evaluation/QA needs another gate.
        require(experimental)
        require(this.evaluatedLanguages.isEmpty() && this.evaluatedTasks.isEmpty())
        // Retain the previous public model's installation identity for explicit
        // removal. A historical runtime record does not authorize native load.
        require(runtimeVersion in setOf(LiteRtLmLoadProbe.RUNTIME_VERSION, "0.9.0"))
    }
    companion object { const val MAX_MODEL_BYTES = 4L * 1024 * 1024 * 1024 }
}

internal fun validPublicUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    value.length <= 1024 && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.port == -1 && uri.rawFragment == null &&
        value.none { it.isWhitespace() || it.isISOControl() }
}.getOrDefault(false)

class ModelCatalogue private constructor(models: List<RegisteredModel>) {
    val models: List<RegisteredModel> = Collections.unmodifiableList(ArrayList(models))
    fun find(id: String): RegisteredModel? = models.singleOrNull { it.id == id }
    fun availability(): ModelAvailability = ModelAvailability.Unavailable(
        "Document questions require a validated model and device evaluation.",
    )
    companion object {
        fun read(context: Context): ModelCatalogue = context.assets.open("models/catalogue.json").use { input ->
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(count > 0 && count <= MAX_CATALOGUE_BYTES - result.size())
                result.write(buffer, 0, count)
            }
            parse(result.toByteArray().toString(Charsets.UTF_8))
        }
        fun parse(json: String): ModelCatalogue {
            require(json.length <= MAX_CATALOGUE_BYTES)
            val root = JSONObject(json)
            require(root.getInt("schemaVersion") == 1)
            val runtime = root.getJSONObject("runtime")
            require(runtime.getString("id") == "litert-lm" && runtime.getString("version") == LiteRtLmLoadProbe.RUNTIME_VERSION)
            require(runtime.getString("format") == "litertlm")
            val records = root.getJSONArray("models")
            require(records.length() <= 32)
            val models = (0 until records.length()).map { index ->
                val m = records.getJSONObject(index)
                require(m.getString("format") == "litertlm")
                val license = m.getJSONObject("license")
                RegisteredModel(m.getString("id"), m.getString("revision"), m.getString("fileName"),
                    m.getLong("byteCount"), m.getString("sha256"), license.getString("identifier"),
                    license.getString("termsUrl"), license.getBoolean("requiresAcceptance"),
                    m.getInt("contextTokens"), m.getLong("minimumRamBytes"),
                    strings(m, "evaluatedLanguages"), strings(m, "evaluatedTasks"), strings(m, "backends"),
                    m.getString("displayName"), m.getString("downloadUrl"), m.getString("assessment") == "experimental",
                    m.getString("runtimeVersion"))
            }
            require(models.map { it.id }.distinct().size == models.size)
            return ModelCatalogue(models)
        }
        private fun strings(m: JSONObject, key: String): List<String> {
            val array = m.getJSONArray(key)
            require(array.length() <= 32)
            return (0 until array.length()).map(array::getString)
        }
        private const val MAX_CATALOGUE_BYTES = 256 * 1024
    }
}
sealed interface ModelAvailability { data class Unavailable(val reason: String) : ModelAvailability }
