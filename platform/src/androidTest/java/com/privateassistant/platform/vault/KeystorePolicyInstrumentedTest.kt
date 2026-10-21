package com.privateassistant.platform.vault

import android.app.KeyguardManager
import android.content.ContextWrapper
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

@RunWith(AndroidJUnit4::class)
class KeystorePolicyInstrumentedTest {
    /** Inspects the actual production key-generation path on a device with a configured screen lock. */
    @Test fun rootWrapperRequiresStrongSystemAuthenticationAndIsNotExportable() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Configure a secure device screen lock for this probe", base.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        val testPackage = "${base.packageName}.crypto-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getPackageName(): String = testPackage
        }
        val directory = File(base.noBackupFilesDir, "key-policy-test-${UUID.randomUUID()}")
        val alias = "$testPackage.vault.root.v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            try {
                VaultCrypto(context, directory).open().databaseKey.fill(0)
            } catch (_: VaultAuthenticationRequiredException) {
                // No authentication token is also a valid state for this policy inspection.
            }
            val key = store.getKey(alias, null) as SecretKey
            val information = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
            assertTrue(information.isUserAuthenticationRequired)
            assertEquals(30, information.userAuthenticationValidityDurationSeconds)
            assertEquals(
                KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG,
                information.userAuthenticationType,
            )
            assertEquals(256, information.keySize)
            assertNull("Android Keystore must not export the wrapping secret", key.encoded)
        } finally {
            store.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }
}
