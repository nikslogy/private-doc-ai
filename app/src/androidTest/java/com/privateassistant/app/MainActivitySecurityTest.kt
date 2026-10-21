package com.privateassistant.app

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a real emulator/device; host execution cannot establish these platform properties. */
@RunWith(AndroidJUnit4::class)
class MainActivitySecurityTest {
    @Test
    fun windowRemainsSecureAfterActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun installedVariantHasExpectedPrivacyPermissions() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertFalse(context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertEquals(context.resources.getBoolean(R.bool.connected_build), "android.permission.INTERNET" in permissions)
        assertFalse("android.permission.MANAGE_EXTERNAL_STORAGE" in permissions)
        assertFalse("android.permission.READ_EXTERNAL_STORAGE" in permissions)
        assertFalse("android.permission.WRITE_EXTERNAL_STORAGE" in permissions)
    }
}
