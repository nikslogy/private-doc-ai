package com.privateassistant.app.ui

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Process
import androidx.annotation.StringRes
import com.privateassistant.app.R
import com.privateassistant.platform.inference.InstalledModelStatus
import com.privateassistant.platform.inference.InstalledModelStore
import com.privateassistant.platform.inference.LocalInferenceRunner
import com.privateassistant.platform.inference.ModelCatalogue
import com.privateassistant.platform.inference.ModelInstallException
import com.privateassistant.platform.inference.ModelInstallFailure
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ModelEntryUi(val id: String, val displayName: String, val byteCount: Long,
    val licenseIdentifier: String, val licenseTermsUrl: String, val downloadUrl: String,
    val installed: Boolean, val sha256: String, val experimental: Boolean, val invalid: Boolean = false)
data class ModelManagerUiState(val entries: List<ModelEntryUi> = emptyList(), val loading: Boolean = false,
    val busy: Boolean = false, val progressBytes: Long? = null, val progressTotal: Long? = null,
    val diagnosticText: String? = null, val diagnosticFailed: Boolean = false,
    @StringRes val message: Int? = null, val activeModelId: String? = null)

/** Only public model metadata and fixed synthetic diagnostic input reach this component. */
class ModelManagerUiModel(private val context: Context, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(ModelManagerUiState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    @Volatile private var revoked = AtomicBoolean(true)
    private var catalogue: ModelCatalogue? = null
    private var store: InstalledModelStore? = null
    private var pending: Pair<String, AtomicBoolean>? = null

    fun unlock() { revoked = AtomicBoolean(false) }
    fun lock() {
        revoked.set(true)
        operation?.cancel(); operation = null
        pending = null
        mutableState.value = ModelManagerUiState()
    }
    fun refresh() {
        if (revoked.get() || mutableState.value.busy || mutableState.value.loading) return
        launch(loading = true) { token -> loadEntries(token) }
    }
    fun prepareImport(id: String, acknowledged: Boolean): Boolean {
        if (revoked.get() || mutableState.value.busy || mutableState.value.loading || !acknowledged ||
            catalogue?.find(id) == null) return false
        pending = id to revoked
        return true
    }
    fun receiveImport(uri: Uri?) {
        val request = pending; pending = null
        if (uri == null) return
        if (request == null || request.second !== revoked || request.second.get()) return
        launch(modelId = request.first) { token ->
            val selectedStore = requireNotNull(store)
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    var lastUpdate = 0L
                    selectedStore.install(request.first, input, licenseAccepted = true,
                        cancellationRequested = token::get, onProgress = { bytes, total ->
                            val now = System.nanoTime()
                            if (!token.get() && (now - lastUpdate > 100_000_000 || bytes == total)) {
                                lastUpdate = now
                                mutableState.update { it.copy(progressBytes = bytes, progressTotal = total) }
                            }
                        })
                } ?: throw ModelInstallException(ModelInstallFailure.STORAGE)
            }
            loadEntries(token)
            if (!token.get()) mutableState.update { it.copy(message = R.string.models_install_complete) }
        }
    }
    fun remove(id: String) {
        if (revoked.get() || mutableState.value.busy || mutableState.value.loading || catalogue?.find(id) == null) return
        launch(modelId = id) { token ->
            requireNotNull(store).remove(id)
            loadEntries(token)
            if (!token.get()) mutableState.update { it.copy(message = R.string.models_removed) }
        }
    }
    fun diagnostic(id: String) {
        if (revoked.get() || mutableState.value.busy || mutableState.value.loading) return
        val entry = catalogue?.find(id) ?: return
        launch(modelId = id, diagnostic = true) { token ->
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            if (!Process.is64Bit() || memory.totalMem < entry.minimumRamBytes ||
                memory.availMem < entry.byteCount + 512L * 1024 * 1024 || memory.lowMemory) {
                if (!token.get()) mutableState.update { it.copy(message = R.string.models_memory_required, diagnosticFailed = true) }
                return@launch
            }
            val result = requireNotNull(store).withVerifiedModel(id, token::get) { _, artifact ->
                LocalInferenceRunner().generate(artifact, LocalInferenceRunner.SYNTHETIC_PROMPT, token::get)
            }
            if (!token.get()) mutableState.update { it.copy(diagnosticText = result.text, message = R.string.models_test_complete) }
        }
    }
    fun cancel() {
        operation?.cancel()
        mutableState.update { it.copy(message = R.string.models_cancelling) }
    }
    fun isReviewedLink(url: String): Boolean = !revoked.get() && catalogue?.models?.any {
        url == it.downloadUrl || url == it.licenseTermsUrl
    } == true

    private suspend fun loadEntries(token: AtomicBoolean) {
        val (loaded, selectedStore) = withContext(Dispatchers.IO) {
            val loaded = catalogue ?: ModelCatalogue.read(context)
            loaded to (store ?: InstalledModelStore(context, loaded))
        }
        if (token.get()) return
        catalogue = loaded; store = selectedStore
        val installed = selectedStore.installedModels().associateBy { it.modelId }
        if (token.get()) return
        mutableState.update { current -> current.copy(entries = loaded.models.map { m ->
            ModelEntryUi(m.id, m.displayName, m.byteCount, m.licenseIdentifier, m.licenseTermsUrl,
                m.downloadUrl, installed[m.id]?.status == InstalledModelStatus.VERIFIED,
                m.sha256, m.experimental, installed[m.id]?.status == InstalledModelStatus.INVALID)
        }) }
    }
    private fun launch(modelId: String? = null, loading: Boolean = false, diagnostic: Boolean = false,
        block: suspend (AtomicBoolean) -> Unit,
    ) {
        if (revoked.get() || mutableState.value.busy || mutableState.value.loading) return
        val token = revoked
        mutableState.update { it.copy(busy = !loading, loading = loading, activeModelId = modelId,
            message = null, progressBytes = null, progressTotal = null, diagnosticText = null, diagnosticFailed = false) }
        operation = scope.launch {
            try { block(token) }
            catch (cancelled: CancellationException) {
                if (!token.get()) mutableState.update { it.copy(message = R.string.models_cancelled) }
                throw cancelled
            } catch (_: Exception) {
                if (!token.get()) mutableState.update { it.copy(message = R.string.models_operation_failed,
                    diagnosticFailed = diagnostic) }
            } finally {
                if (!token.get()) mutableState.update { it.copy(busy = false, loading = false,
                    progressBytes = null, progressTotal = null, activeModelId = null) }
            }
        }
    }
}
