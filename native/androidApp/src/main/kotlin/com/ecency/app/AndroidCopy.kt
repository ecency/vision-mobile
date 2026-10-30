package com.ecency.app

import com.ecency.core.l10n.L

/** Copy only the Android app shows (system prompts, Play Billing, the image saver), read when shown (`native.android.*`). */
object AndroidCopy {
    val NO_BROWSER: String get() = L.native.android.noBrowser
    val BIOMETRIC_UNLOCK_TITLE: String get() = L.native.android.biometricUnlockTitle
    val BIOMETRIC_CONFIRM: String get() = L.native.android.biometricConfirm
    val BIOMETRIC_SCAN: String get() = L.pincode.biometricDesc
    val IMAGE_UNSUPPORTED_ADDRESS: String get() = L.native.android.imageUnsupportedAddress
    val IMAGE_CREATE_FAILED: String get() = L.native.android.imageCreateFailed
    val IMAGE_WRITE_FAILED: String get() = L.native.android.imageWriteFailed
    val IMAGE_TOO_LARGE: String get() = L.native.android.imageTooLarge
    val BILLING_DISCONNECTED: String get() = L.native.android.billingDisconnected
    val BILLING_NO_SCREEN: String get() = L.native.android.billingNoScreen

    fun imageDownloadFailed(status: Int): String = L.native.android.imageDownloadFailed(status = status.toString())
    fun billingError(code: Int): String = L.native.android.billingError(code = code.toString())
}
