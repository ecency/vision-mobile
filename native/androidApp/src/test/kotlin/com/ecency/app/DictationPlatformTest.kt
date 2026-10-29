package com.ecency.app

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

/** The Android recorder's file handling and permission gate (no audio is recorded in these tests). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DictationPlatformTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val dir = AndroidDictationRecorder.directory(context)
    private val recorder = AndroidDictationRecorder(context)

    @Test
    fun withoutThePermissionNothingStarts() {
        assertFalse(recorder.hasPermission())
        assertFalse(recorder.start(com.ecency.core.app.DictationClips.tag("spike-alice")))
        assertNull(recorder.stop())
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(recorder.hasPermission())
    }

    @Test
    fun clipsLiveInAPrivateUnpurgedFolderByNameAndOnlyTheyAreReadOrDeleted() {
        assertEquals(File(context.noBackupFilesDir, "dictation"), dir)
        dir.mkdirs()
        val clip = File(dir, "a.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val other = File(dir, "b.m4a").apply { writeBytes(byteArrayOf(4)) }
        val outside = File(context.filesDir, "secret.m4a").apply { writeBytes(byteArrayOf(9)) }
        assertArrayEquals(byteArrayOf(1, 2, 3), recorder.read(clip.name))
        assertNull("a path outside the folder is never read", recorder.read(outside.absolutePath))
        assertNull(recorder.read("../../files/secret.m4a"))
        assertNull("an absolute path is not a clip name", recorder.read(clip.absolutePath))
        recorder.delete(outside.absolutePath)
        assertTrue(outside.exists())
        // the clips on the device are listed by name; which of them go is the shared rule (DictationClips)
        assertEquals(setOf("a.m4a", "b.m4a"), recorder.clipNames().toSet())
        val clips = com.ecency.core.app.DictationClips
        val alices = File(dir, clips.tag("spike-alice") + "_one.m4a").apply { writeBytes(byteArrayOf(5)) }
        val bobs = File(dir, clips.tag("spike-bob") + "_two.m4a").apply { writeBytes(byteArrayOf(6)) }
        // bob leaves: his clip and the untagged ones of an older build go, alice's waiting clip stays
        clips.purgeLeft(recorder, listOf("spike-bob"))
        assertTrue(alices.exists())
        assertFalse(bobs.exists())
        assertFalse(other.exists())
        assertFalse(clip.exists())
        clip.writeBytes(byteArrayOf(1, 2, 3))
        // a name that is not a tag never becomes part of a path
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(recorder.start("../evil"))
        assertFalse(recorder.start("spike-alice"))
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        recorder.delete(clip.name)
        assertFalse(clip.exists())
        assertNull(recorder.read(clip.name))
    }

    @Test
    fun theMicrophonePermissionIsDeclaredAndOptional() {
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertTrue(Manifest.permission.RECORD_AUDIO in info.requestedPermissions.orEmpty())
    }
}
