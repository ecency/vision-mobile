package com.ecency.app

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The update keeps the RN app's advertising ID permission: Play checks one advertising ID declaration against every
 * bundle it still serves, and the RN bundles declare it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AdIdPermissionTest {
    @Test
    fun theAppDeclaresTheAdvertisingIdPermissionAsRnDid() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toList()
        assertTrue("requested: $requested", "com.google.android.gms.permission.AD_ID" in requested)
    }
}
