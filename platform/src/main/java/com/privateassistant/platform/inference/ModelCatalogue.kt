package com.privateassistant.platform.inference

import org.json.JSONObject

/** No model is shipped or implicitly trusted by the initial build. */
data class RegisteredModel(
    val id: String,
    val revision: String,
    val fileName: String,
    val byteCount: Long,
    val sha256: String,
    val licenseIdentifier: String,
    val licenseTermsUrl: String,
    val requiresLicenseAcceptance: Boolean,
    val contextTokens: Int,
    val minimumRamBytes: Long,
    val evaluatedLanguages: List<String>,
    val evaluatedTasks: List<String>,
    val backends: List<String>,
)

class ModelCatalogue private constructor(val models: List<RegisteredModel>) {
    fun availability(): ModelAvailability = if (models.isEmpty()) {
        ModelAvailability.Unavailable("No model has been selected, licensed and validated for this build.")
    } else {
        ModelAvailability.Unavailable("A verified model installation and device probe are required.")
    }

    companion object {
        fun parse(json: String): ModelCatalogue {
            val root = JSONObject(json)
            require(root.getInt("schemaVersion") == 1)
            val runtime = root.getJSONObject("runtime")
            require(runtime.getString("id") == "litert-lm")
            require(runtime.getString("version") == LiteRtLmLoadProbe.RUNTIME_VERSION)
            require(runtime.getString("format") == "litertlm")
            val entries = root.getJSONArray("models")
            val ids = mutableSetOf<String>()
            val models = (0 until entries.length()).map { index ->
                val model = entries.getJSONObject(index)
                val license = model.getJSONObject("license")
                val id = model.getString("id")
                require(id.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}")) && ids.add(id))
                require(model.getString("runtimeVersion") == LiteRtLmLoadProbe.RUNTIME_VERSION)
                require(model.getString("format") == "litertlm")
                val fileName = model.getString("fileName")
                require(fileName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.litertlm")))
                val hash = model.getString("sha256")
                require(hash.matches(Regex("[a-f0-9]{64}")))
                val revision = model.getString("revision")
                require(revision.isNotBlank())
                val byteCount = model.getLong("byteCount")
                val contextTokens = model.getInt("contextTokens")
                val minimumRam = model.getLong("minimumRamBytes")
                require(byteCount > 0 && contextTokens > 0 && minimumRam > 0)
                val licenseIdentifier = license.getString("identifier")
                val licenseTermsUrl = license.getString("termsUrl")
                require(licenseIdentifier.isNotBlank() && licenseTermsUrl.startsWith("https://"))
                RegisteredModel(
                    id, revision, fileName, byteCount, hash,
                    licenseIdentifier, licenseTermsUrl, license.getBoolean("requiresAcceptance"),
                    contextTokens, minimumRam,
                    stringList(model, "evaluatedLanguages"),
                    stringList(model, "evaluatedTasks"),
                    stringList(model, "backends").also { values -> require(values.all { it in setOf("CPU", "GPU", "NPU") }) },
                )
            }
            return ModelCatalogue(models)
        }

        private fun stringList(parent: JSONObject, key: String): List<String> {
            val values = parent.getJSONArray(key)
            return (0 until values.length()).map { values.getString(it).also { value -> require(value.isNotBlank()) } }
        }
    }
}

sealed interface ModelAvailability {
    data class Unavailable(val reason: String) : ModelAvailability
}
