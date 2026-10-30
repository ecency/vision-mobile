package com.ecency.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.ecency.app.widgets.AndroidWidgetStore
import com.ecency.app.widgets.ComposeWidgetReceiver
import com.ecency.app.widgets.NotificationsWidgetReceiver
import com.ecency.app.widgets.StreakWidgetReceiver
import com.ecency.app.widgets.WidgetPrefs
import com.ecency.app.widgets.streakLines
import com.ecency.app.widgets.unreadLines
import com.ecency.core.app.WidgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** README "Background media" and "Widgets" on Android: the dictation service and its notification, the widgets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundMediaFlowsTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun theDictationServiceIsAPrivateMicrophoneService() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, DictationService::class.java), 0)
        assertFalse(info.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, info.foregroundServiceType)
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue(requested.containsAll(setOf("android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_MICROPHONE")))
    }

    @Test
    fun aRecordingStartsTheServiceWithItsStopNotificationAndItsEndStopsIt() {
        val host = AndroidDictationHost(context, onInterrupted = {})
        assertTrue("notifications on: the Stop is visible", host.recordingStarted())
        val started = shadowOf(context).nextStartedService
        assertEquals(DictationService::class.java.name, started.component?.className)
        val controller = Robolectric.buildService(DictationService::class.java, started).create().startCommand(0, 1)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val shown = shadowOf(manager).getNotification(DictationService.NOTIFICATION_ID)
        assertNotNull("the visible notification", shown)
        assertEquals(DictationService.TITLE, shown.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals(listOf(DictationService.STOP), shown.actions.map { it.title.toString() })
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, shown.visibility)
        assertNotNull(shown.publicVersion)
        assertTrue("the lock screen's version has no Stop", shown.publicVersion.actions.isNullOrEmpty())
        val stop = shadowOf(shown.actions.single().actionIntent).savedIntent
        assertEquals(DictationService.ACTION_STOP, stop.action)
        assertEquals(DictationService::class.java.name, stop.component?.className)
        host.recordingEnded()
        assertEquals(DictationService::class.java.name, shadowOf(context).nextStoppedService.component?.className)
        assertNull(shadowOf(manager).getNotification(DictationService.NOTIFICATION_ID))
        controller.destroy()
    }

    @Test
    fun aStaleStopFromTheNotificationEndsTheServiceAndNothingElse() {
        val env = TestNode().env()
        EcencyGraph.install(env)
        try {
            // no recording: the Stop of a stale notification does nothing and ends the service
            val service = Robolectric.buildService(DictationService::class.java, Intent(context, DictationService::class.java).setAction(DictationService.ACTION_STOP)).create()
            service.startCommand(0, 1)
            assertFalse(env.dictationSessions.isRecording)
            assertTrue(shadowOf(service.get()).isStoppedBySelf)
            service.destroy()
        } finally {
            EcencyGraph.install(null)
        }
    }

    @Test
    fun onAndroid10AndLaterOnlyATransientFocusLossHoldsTheRecording() {
        val loss = android.media.AudioManager.AUDIOFOCUS_LOSS
        val transient = android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        val duck = android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        assertFalse("music or Listen starting does not take the microphone", AndroidDictationHost.holdsOn(loss, 29))
        assertTrue("a call", AndroidDictationHost.holdsOn(transient, 29))
        assertFalse(AndroidDictationHost.holdsOn(duck, 35))
        assertTrue("no silenced report below 10: any loss holds", AndroidDictationHost.holdsOn(loss, 28))
        assertTrue(AndroidDictationHost.holdsOn(transient, 28))
        // through the real listener on this SDK (35): a plain loss keeps recording, a transient one holds
        var held = 0
        val host = AndroidDictationHost(context, onInterrupted = {}, onFocusLost = { held++ })
        host.recordingStarted()
        val focus = shadowOf(context.getSystemService(android.media.AudioManager::class.java)).lastAudioFocusRequest
        focus.listener.onAudioFocusChange(loss)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0, held)
        focus.listener.onAudioFocusChange(transient)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, held)
        host.recordingEnded()
    }

    @Test
    fun withNotificationsOffTheHostSaysTheBackgroundCannotBeShown() {
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        val host = AndroidDictationHost(context, onInterrupted = {})
        assertFalse(host.recordingStarted())
        host.recordingEnded()
    }

    @Test
    fun theWidgetsShowValuesOnlyUnlockedAndSignedIn() {
        val open = WidgetSnapshot(signedIn = true, locked = false, unread = 12, streak = 5)
        assertEquals("12", unreadLines(open).value)
        assertEquals("5", streakLines(open).value)
        val locked = WidgetSnapshot(signedIn = true, locked = true)
        assertNull(unreadLines(locked).value)
        assertEquals(WidgetSnapshot.LOCKED_TEXT, unreadLines(locked).caption)
        assertEquals(WidgetSnapshot.LOCKED_TEXT, streakLines(locked).caption)
        assertEquals(WidgetSnapshot.SIGNED_OUT_TEXT, unreadLines(WidgetSnapshot.EMPTY).caption)
        assertNull(streakLines(WidgetSnapshot(signedIn = true, locked = false)).value)
    }

    @Test
    fun theStoreKeepsTheSnapshotInTheAppsOwnPreferences() {
        assertEquals(WidgetSnapshot.EMPTY, WidgetPrefs.read(context))
        AndroidWidgetStore(context).write(WidgetSnapshot(signedIn = true, locked = false, unread = 3).encode())
        assertEquals(3, WidgetPrefs.read(context).unread)
        AndroidWidgetStore(context).write(WidgetSnapshot.EMPTY.encode())
        assertNull(WidgetPrefs.read(context).unread)
        val stored = context.getSharedPreferences("ecency.widgets", Context.MODE_PRIVATE).all
        assertEquals(setOf(WidgetSnapshot.KEY), stored.keys)
    }

    @Test
    fun theWidgetReceiversAreDeclaredWithTheirProviderInfo() {
        for (receiver in listOf(NotificationsWidgetReceiver::class.java, StreakWidgetReceiver::class.java, ComposeWidgetReceiver::class.java)) {
            val info = context.packageManager.getReceiverInfo(ComponentName(context, receiver), PackageManager.GET_META_DATA)
            assertNotNull(receiver.simpleName, info.metaData?.getInt("android.appwidget.provider")?.takeIf { it != 0 })
        }
    }
}
