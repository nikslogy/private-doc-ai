package com.privateassistant.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.privateassistant.app.ui.PrivateAssistantApp
import com.privateassistant.app.ui.VaultUiModel
import com.privateassistant.platform.vault.VaultController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private lateinit var model: VaultUiModel
    private lateinit var biometricPrompt: BiometricPrompt
    private var backgroundLock: Job? = null
    private var backgroundedAt: Long? = null

    private val pickDocuments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        model.receiveImports(uris)
    }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) model.receiveExport(result.data?.data)
        else model.cancelExport()
    }
    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        model.models.receiveImport(uri)
    }
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) lockVault()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        model = VaultUiModel(applicationContext, VaultController(applicationContext), lifecycleScope)
        biometricPrompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.unlockAfterAuthentication()
                    else model.authenticationFinished()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    model.authenticationFinished()
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) model.showMessage(R.string.authentication_failed)
                }
            })
        ContextCompat.registerReceiver(this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            PrivateAssistantApp(
                model = model,
                onUnlock = ::authenticate,
                onLock = ::lockVault,
                onImport = { pickDocuments.launch(arrayOf("application/pdf", "image/jpeg", "image/png", "text/plain")) },
                onExport = { document ->
                    if (model.prepareExport(document)) {
                        exportDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = document.mimeType
                            putExtra(Intent.EXTRA_TITLE, document.displayName)
                        })
                    }
                },
                onDeviceSecurity = {
                    startActivity(Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                        putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, authenticators)
                    })
                },
                onImportModel = { id, acknowledged ->
                    if (model.models.prepareImport(id, acknowledged)) pickModel.launch(arrayOf("*/*"))
                },
                onOpenModelLink = { url ->
                    if (model.models.isReviewedLink(url)) {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                    }
                },
            )
        }
    }

    private fun authenticate() {
        val available = BiometricManager.from(this).canAuthenticate(authenticators)
        if (available != BiometricManager.BIOMETRIC_SUCCESS) {
            model.requireDeviceSecurity()
            return
        }
        if (!model.beginAuthentication()) return
        biometricPrompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.authentication_title))
            .setSubtitle(getString(R.string.authentication_subtitle))
            .setAllowedAuthenticators(authenticators)
            .build())
    }

    private fun lockVault() {
        biometricPrompt.cancelAuthentication()
        model.lock()
    }

    override fun onStart() {
        super.onStart()
        if ((model.state.value.unlocked || model.state.value.busy) &&
            backgroundedAt?.let { SystemClock.elapsedRealtime() - it >= BACKGROUND_LOCK_MILLIS } == true
        ) {
            model.lock()
        }
        backgroundedAt = null
        backgroundLock?.cancel()
        backgroundLock = null
    }

    override fun onStop() {
        super.onStop()
        backgroundedAt = SystemClock.elapsedRealtime()
        // Device-credential authentication can cover/stop this activity. Let BiometricPrompt
        // own its lifecycle while the vault is locked; cancelling here can break that flow.
        if (model.state.value.unlocked || model.state.value.busy) {
            backgroundLock = lifecycleScope.launch {
                delay(BACKGROUND_LOCK_MILLIS)
                model.lock()
            }
        }
    }

    override fun onDestroy() {
        backgroundLock?.cancel()
        unregisterReceiver(screenOffReceiver)
        model.lock()
        super.onDestroy()
    }

    companion object {
        private const val BACKGROUND_LOCK_MILLIS = 15_000L
        private val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}
