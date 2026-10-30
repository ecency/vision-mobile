package com.ecency.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ecency.core.secure.Pbkdf2
import com.ecency.core.secure.PinLock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** The platform PBKDF2 (SecretKeyFactory, Android 8+) gives the common implementation's bytes on a device. */
@RunWith(AndroidJUnit4::class)
class Pbkdf2DeviceTest {
    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun platformMatchesCommonAndTheRfcVector() {
        val rfc = Pbkdf2.platform("Password".encodeToByteArray(), "NaCl".encodeToByteArray(), 80_000, 64)
        assertNotNull("no platform PBKDF2 on API 26+", rfc)
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            rfc!!.hex(),
        )
        for (pin in listOf("0000", "1234", "9999")) {
            val salt = ByteArray(16) { (it * 31 + pin.hashCode()).toByte() }
            assertArrayEquals(
                Pbkdf2.hmacSha256(pin.encodeToByteArray(), salt, 1000, 32),
                Pbkdf2.platform(pin.encodeToByteArray(), salt, 1000, 32),
            )
        }
    }

    @Test
    fun defaultCostIsReported() {
        val salt = ByteArray(16)
        var t = System.nanoTime()
        Pbkdf2.hmacSha256("1234".encodeToByteArray(), salt, PinLock.DEFAULT_ITERATIONS, 32)
        val common = (System.nanoTime() - t) / 1_000_000
        t = System.nanoTime()
        Pbkdf2.platform("1234".encodeToByteArray(), salt, PinLock.DEFAULT_ITERATIONS, 32)
        val platform = (System.nanoTime() - t) / 1_000_000
        Log.i("Pbkdf2DeviceTest", "PBKDF2 ${PinLock.DEFAULT_ITERATIONS}: common $common ms, platform $platform ms")
        println("PBKDF2 ${PinLock.DEFAULT_ITERATIONS}: common $common ms, platform $platform ms")
    }
}
