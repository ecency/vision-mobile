package com.ecency.app

import android.annotation.SuppressLint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.graphics.Color as AndroidColor
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.activity.SystemBarStyle
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.ecency.app.ui.durationScale
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.ecency.app.ui.NavigationViewModel
import com.ecency.core.app.PerfEvent
import com.ecency.core.app.PerfMarks
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import com.ecency.app.render.PostActions
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.LegacyMigrationSource
import com.ecency.core.legacy.AsyncStorageReader
import com.ecency.core.secure.BiometricBinder
import com.ecency.core.render.RenderHelper
import com.ecency.core.render.blocks.SafeTarget
import com.ecency.core.secure.AndroidKeystoreSecureStore
import com.ecency.core.app.UiCopy

/** Shared non-secret preferences ([com.ecency.core.app.Prefs]) over SharedPreferences. */
class SharedPrefs(private val prefs: android.content.SharedPreferences) : com.ecency.core.app.Prefs {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    override fun keys(): Set<String> = prefs.all.keys.toSet()
}

/** The process-wide app layer: one [AppEnvironment] shared by every activity. */
object EcencyGraph {
    @Volatile private var env: AppEnvironment? = null

    /** The app's preferences file (settings, the language among them; the widgets read it too). */
    const val PREFS_FILE = "ecency.prefs"

    /** Tests: the environment every activity of this process gets (fake transport, in-memory vault). */
    @androidx.annotation.VisibleForTesting
    fun install(testEnv: AppEnvironment?) {
        env = testEnv
    }

    /** The environment if this process already built one (the messaging service never builds it). */
    fun current(): AppEnvironment? = env

    /** True while the activity is started: only then does a received push show the in-app banner. */
    @Volatile var foreground = false

    /** Firebase push for this process; inactive without a Firebase configuration in the build. */
    @Volatile internal var pushPlatform: com.ecency.app.push.FirebasePushPlatform? = null

    /** Debug only (`--ez demoAuth true`): offline token endpoint and HiveAuth relay, for screenshots. */
    @Volatile internal var demoAuth = false

    /** The activity in front, for the Play purchase sheet ([com.ecency.app.iap.PlayStore]). */
    @Volatile internal var frontActivity: java.lang.ref.WeakReference<Activity>? = null

    /** Google Play's rating prompt, shown over whichever activity is in front; never in demo runs. */
    internal val playReview = PlayReview()

    /** The environment when this process made one (the debug renderer and galleries do not). */
    fun started(): AppEnvironment? = env

    fun env(context: Context): AppEnvironment = env ?: synchronized(this) {
        env ?: AppEnvironment.create(
            AndroidKeystoreSecureStore(context.applicationContext),
            debugKeyImport = BuildConfig.DEBUG,
            platform = RenderHelper.Platform.ANDROID,
            // the RN app's accounts and PIN, imported once by the app lock's start, before it decides anything
            legacy = LegacyMigrationSource(AsyncStorageReader(context.applicationContext), BuildConfig.PIN_KEY, BuildConfig.DEFAULT_PIN),
            auth = if (BuildConfig.DEBUG && demoAuth) {
                com.ecency.core.app.DemoAuthServices.create({ System.currentTimeMillis() }, 300_000)
            } else {
                null
            },
            userAgent = BuildConfig.USER_AGENT,
            // demo runs are offline as a whole: the seeded accounts do not exist on chain, and no real node is asked
            hive = if (BuildConfig.DEBUG && demoAuth) com.ecency.core.app.DemoHiveTransport { System.currentTimeMillis() / 1000 } else null,
        ).also {
            it.appLock.biometricBinder = BiometricBinder { BiometricKey.create() }
            it.prefs = SharedPrefs(context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE))
            // the UI copy follows the language setting, synced with Android 13+'s per-app language (README
            // "Localization"); demo runs leave the device's per-app setting alone
            it.drivesLanguageCatalog = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !(BuildConfig.DEBUG && demoAuth)) {
                it.systemLanguage = com.ecency.app.ui.AndroidSystemLanguage(context)
            }
            it.pushPlatform = com.ecency.app.push.FirebasePushPlatform(context).also { p -> pushPlatform = p }
            it.activityWebsocketUrl = BuildConfig.ACTIVITY_WEBSOCKET_URL.ifBlank { null }
            it.setChatHost(BuildConfig.CHAT_BACKEND_URL.ifBlank { null })
            it.updateSource = com.ecency.core.app.UpdateSource.of(BuildConfig.UPDATE_RELEASES_URL, BuildConfig.UPDATE_TAG_PREFIX)
            it.draftStorage = FileDraftStorage(
                java.io.File(context.applicationContext.filesDir, "drafts"),
                tempDirs = listOf(java.io.File(context.applicationContext.cacheDir, "camera"), AndroidDictationRecorder.directory(context)),
            )
            it.dictationRecorder = AndroidDictationRecorder(context)
            // a recording goes on in the background under a microphone foreground service (README "Background media")
            it.dictationSessions.host = AndroidDictationHost(context, it.dictationSessions)
            // the home screen widgets read the snapshot this writes (README "Widgets")
            it.widgets.store = com.ecency.app.widgets.AndroidWidgetStore(context)
            // what Android shows outside the app (widgets, notification channels) follows the app language
            SystemLanguageSurfaces.follow(context)
            // RN maybeRequestReview: the Play rating prompt, asked for by the shared rules (never in demo runs, which are offline)
            if (!(BuildConfig.DEBUG && demoAuth)) it.requestReviewAsync = { playReview.request() }
            // the wave composer attaches images through the editor's upload
            com.ecency.core.app.WaveUploadAttacher.install(it)
            // in-app purchases: Google Play, or the offline demo store in debug fake-data runs (README "In-app purchases")
            it.store = if (BuildConfig.DEBUG && demoAuth) com.ecency.core.app.DemoStore(com.ecency.core.app.PurchaseService.PLAY)
            else com.ecency.app.iap.PlayStore(context) { frontActivity?.get() }
            // crash reports and analytics (README "Crash reporting", "Analytics"); demo runs report nothing
            if (com.ecency.app.crash.SentryReporting.started && !(BuildConfig.DEBUG && demoAuth)) it.crashReporter = com.ecency.app.crash.SentryReporting.Reporter
            it.analytics.config = com.ecency.core.app.AnalyticsConfig(
                analyticsHost(BuildConfig.PLAUSIBLE_HOST, debug = BuildConfig.DEBUG || demoAuth), "android",
                com.ecency.core.app.PlausibleAnalytics.userAgent(
                    "Ecency", BuildConfig.VERSION_NAME, "Android", android.os.Build.VERSION.RELEASE.orEmpty(), android.os.Build.MODEL.orEmpty(),
                ),
            )
            env = it
        }
    }
}

/**
 * Keeps app content out of the recents thumbnail while the app lock is on. Android 13+ only leaves the app
 * out of recents (screenshots and recording stay allowed, as in the RN app); below 13 there is no such
 * switch, so FLAG_SECURE covers it, which also blocks screenshots and screen recording while the lock is on.
 * [sdk] is only overridden by tests, so the API 33 call never runs on an older device.
 */
@SuppressLint("NewApi")
internal fun Activity.applyHideInRecents(hide: Boolean, sdk: Int = Build.VERSION.SDK_INT) = applyWindowPrivacy(hide, sensitive = false, sdk)

/**
 * The window's privacy for both reasons at once: [hideInRecents] (lock on, see [applyHideInRecents]) and
 * [sensitive] (a screen that shows or takes private keys and passwords: login, keys, the auth upgrade sheet),
 * which sets FLAG_SECURE on every Android version, blocking screenshots and recording while it shows.
 */
@SuppressLint("NewApi")
internal fun Activity.applyWindowPrivacy(hideInRecents: Boolean, sensitive: Boolean, sdk: Int = Build.VERSION.SDK_INT) {
    if (sdk >= 33) setRecentsScreenshotEnabled(!hideInRecents)
    if (sensitive || (sdk < 33 && hideInRecents)) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/**
 * The analytics host of this build: the build setting, else RN's instance in release builds; debug builds send
 * nothing unless the setting names a host.
 */
internal fun analyticsHost(setting: String, debug: Boolean): String =
    if (debug && setting.isBlank()) "" else com.ecency.core.app.AnalyticsConfig.hostOf(setting)

/** Marks [PerfEvent.APP_START]: the app's first own code in the process, before any activity or UI. Starts Sentry. */
class EcencyApplication : Application() {
    override fun onCreate() {
        PerfMarks.markOnce(PerfEvent.APP_START)
        super.onCreate()
        // before anything else can crash: RN's Sentry project in release builds unless the build names another or "off"
        com.ecency.app.crash.SentryReporting.start(
            this, com.ecency.core.app.CrashDsn.of(BuildConfig.SENTRY_DSN, BuildConfig.DEBUG), BuildConfig.APPLICATION_ID, BuildConfig.VERSION_NAME, BuildConfig.BUILD_NUMBER, BuildConfig.DEBUG,
            BuildConfig.PROGUARD_UUID,
        )
    }
}

/** A FragmentActivity (a ComponentActivity) because androidx BiometricPrompt hosts its UI in a fragment. */
class MainActivity : FragmentActivity() {
    private companion object {
        const val NOTIFICATION_PERMISSION_REQUEST = 4101
        const val STORAGE_PERMISSION_REQUEST = 4102
    }

    /** The bar style the app asked for last ([AppHost.setSystemBarsDark]); null before the first ask. */
    private var barsDark: Boolean? = null

    /** Whether this create animates the launch screen out (a fresh start only). For tests. */
    internal var splashAnimated = false

    internal fun applySystemBars(dark: Boolean) {
        barsDark = dark
        val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        enableEdgeToEdge(style, style)
    }

    /**
     * Removes the launch screen, then puts the app's bar style back: on Android 12+ the library's remove applies
     * the post-splash theme's bar appearance (light bars off), which would leave white icons on a light page.
     */
    internal fun finishSplash(remove: () -> Unit) {
        remove()
        restoreSystemBars()
    }

    /**
     * The exit listener: core-splashscreen applies the post-splash theme's system UI before calling it (light bars
     * off, decor fitting system windows again), so the app's bars come back first, then the fade, then [finishSplash].
     */
    internal fun onSplashExit(view: android.view.View, remove: () -> Unit) {
        restoreSystemBars()
        LaunchSplash.fadeOut(view, durationScale(contentResolver)) { finishSplash(remove) }
    }

    /** The app's last bar style, or edge to edge with the default style before the app asked for one. */
    private fun restoreSystemBars() {
        barsDark?.let(::applySystemBars) ?: enableEdgeToEdge()
    }

    /** A "save image" waiting for the storage permission (Android 9 and older). */
    private var pendingSave: Pair<String, (String?) -> Unit>? = null

    /** RN imageViewer _onSavePress: the permission where Android needs it, then [ImageSaver]. */
    private fun saveImage(url: String, done: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingSave = url to done
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_REQUEST)
            return
        }
        lifecycleScope.launch {
            val error = try {
                ImageSaver.save(this@MainActivity, url)
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: com.ecency.core.app.UiCopy.IMAGE_SAVE_ERROR
            }
            done(error)
        }
    }

    /** Survives rotation and other configuration changes; its back stack is saved for process death. */
    private val navigation: NavigationViewModel by viewModels()
    private var hideInRecents = false
    private var sensitiveScreen = false

    // The Android 13+ notification permission dialog, for the shared push model's prompt at start. The classic
    // permission call: the ActivityResult API needs a newer Fragment library than androidx.biometric brings. The
    // waiting callbacks live in the push platform, so an answer given while the activity is recreated is kept.
    private val askNotificationPermission: () -> Boolean = {
        runCatching {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        }.isSuccess
    }

    @Deprecated("Deprecated in the platform; see askNotificationPermission")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            val (url, done) = pendingSave ?: return
            pendingSave = null
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) saveImage(url, done)
            else done(com.ecency.core.app.UiCopy.IMAGE_SAVE_ERROR)
            return
        }
        if (requestCode != NOTIFICATION_PERMISSION_REQUEST) return
        val platform = EcencyGraph.pushPlatform ?: return
        // an empty result is an interrupted request, not an answer
        if (grantResults.isEmpty()) platform.abandoned()
        else platform.answered(grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    override fun onStart() {
        super.onStart()
        EcencyGraph.foreground = true
        EcencyGraph.frontActivity = java.lang.ref.WeakReference(this)
    }

    override fun onResume() {
        super.onResume()
        // the per-app language may have been changed in the system settings meanwhile (the activity handles that
        // configuration change itself, so this is where it is adopted); before the settings started this does nothing
        EcencyGraph.started()?.settings?.syncSystemLanguage()
    }

    override fun onStop() {
        EcencyGraph.foreground = false
        if (EcencyGraph.frontActivity?.get() === this) EcencyGraph.frontActivity = null
        super.onStop()
    }

    override fun onDestroy() {
        EcencyGraph.playReview.detach(this)
        EcencyGraph.pushPlatform?.let {
            if (it.requester === askNotificationPermission) it.requester = null
            // recreation keeps the dialog and its waiting callers; only a finished activity gives up on it
            if (isFinishing) it.abandoned()
        }
        super.onDestroy()
    }

    // "Record Video": registered with the activity (not a composable), so the result still arrives when the app
    // lock covered the screen meanwhile or the process was recreated while the camera app was open
    // Under a key of its own in the activity's result registry (what registerForActivityResult does with a counted
    // key): the same key in every process, so a recreated activity is handed the result of the one before it.
    private val videoCaptureLauncher: androidx.activity.result.ActivityResultLauncher<Uri> =
        activityResultRegistry.register("ecency.videoCapture", this, androidx.activity.result.contract.ActivityResultContracts.CaptureVideo()) { recorded ->
            videoCaptures.onResult(recorded)
        }
    private val videoCaptures: VideoCaptures by lazy {
        VideoCaptures(this) { uri: Uri ->
            // the camera app needs the camera permission the QR scanner declared: asked first when not held
            if (com.ecency.app.ui.QrCamera.hasPermission(this)) {
                videoCaptureLauncher.launch(uri)
            } else {
                videoAwaitingPermission = uri
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
            true
        }
    }
    private var videoAwaitingPermission: Uri? = null
    private val cameraPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String> =
        activityResultRegistry.register("ecency.cameraPermission", this, androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
            val uri = videoAwaitingPermission
            videoAwaitingPermission = null
            when {
                uri == null -> Unit
                ok -> videoCaptureLauncher.launch(uri)
                else -> videoCaptures.onResult(false)
            }
        }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        videoCaptures.save(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // the launch screen (README "Launch screen"): the logo on the page colour until the first frame, then a fade
        // like RN's bootsplash. Installed on every create (it swaps in the app theme). Animated out on a fresh start,
        // and on Android 12+ always (the system shows its splash on a restore after process death too); not on API
        // 24-30 recreations (dark mode, font scale, multi-window), where an exit listener builds the splash view again
        val splash = installSplashScreen()
        if (savedInstanceState == null || Build.VERSION.SDK_INT >= 31) {
            splashAnimated = true
            splash.setOnExitAnimationListener { provider -> onSplashExit(provider.view, provider::remove) }
        }
        super.onCreate(savedInstanceState)
        // a recording the camera app is still making (or just made) for the previous process; on a cold start
        // nothing can be waiting, and whatever an earlier run left behind is deleted
        if (savedInstanceState == null) VideoCaptures.sweep(this) else videoCaptures.restore(savedInstanceState)
        enableEdgeToEdge()
        // Coil decodes GIF and animated WebP only with an animated decoder registered.
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components { add(if (Build.VERSION.SDK_INT >= 28) AnimatedImageDecoder.Factory() else GifDecoder.Factory()) }
                .build()
        }
        // Debug only: `--ez demo true` shows the renderer demo post; add `--ez showImages false` for "Show
        // images" off. The activity is exported, so release builds must never skip the app (and its lock).
        if (BuildConfig.DEBUG && intent.getBooleanExtra("demo", false)) {
            val showImages = intent.getBooleanExtra("showImages", true)
            setContent {
                MaterialTheme {
                    val actions = remember {
                        PostActions(
                            onParagraphLongPress = { toast(it) },
                            onImage = { url, all -> toast(UiCopy.imageOf(all.indexOf(url) + 1, all.size)) },
                            showImages = showImages,
                        )
                    }
                    HomeScreen(actions = actions)
                }
            }
            return
        }
        // Debug only: `--ez esieGallery true` shows every Esie mood side by side (README "Esie")
        if (BuildConfig.DEBUG && intent.getBooleanExtra("esieGallery", false)) {
            setContent { com.ecency.app.ui.EcencyTheme { com.ecency.app.ui.EsieGallery() } }
            return
        }
        // Debug only: `--ez iconGallery true` shows every app icon (ui/IconGallery.kt), for checking the set by eye
        if (BuildConfig.DEBUG && intent.getBooleanExtra("iconGallery", false)) {
            setContent { com.ecency.app.ui.EcencyTheme { com.ecency.app.ui.IconGallery() } }
            return
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("demoAuth", false)) EcencyGraph.demoAuth = true
        // `--ez acceptTerms true` (debug only): skip the first-run welcome, for screenshots
        if (BuildConfig.DEBUG && intent.getBooleanExtra("acceptTerms", false)) {
            getSharedPreferences("ecency.ui", MODE_PRIVATE).edit().putBoolean("termsAccepted", true).apply()
        }
        // `--es pseudoLocale ar-XB|en-XA` (debug only): the mirrored or the long accented pseudo locale over whatever
        // language the settings hold, for the RTL and long text screenshot runs (README "Localization")
        if (BuildConfig.DEBUG) intent.getStringExtra("pseudoLocale")?.let { com.ecency.core.l10n.L10n.usePseudo(it) }
        val env = EcencyGraph.env(this)
        env.appVersion = BuildConfig.VERSION_NAME
        if (BuildConfig.DEBUG && intent.getBooleanExtra("acceptTerms", false)) env.welcome.accept(consent = true)
        // debug demo runs never load the hosted signup challenge
        if (BuildConfig.DEBUG && EcencyGraph.demoAuth) {
            env.captchaStandIn = "demo-captcha"
            // `--ez demoAnnouncement true` / `--ez demoUpdate true`: the prompts, from synthetic answers
            com.ecency.core.app.DemoAuthServices.demoAnnouncements =
                if (intent.getBooleanExtra("demoAnnouncement", false)) com.ecency.core.app.DemoAuthServices.SAMPLE_ANNOUNCEMENT else "[]"
            // `--ez demoSpotlight true`: a feature spotlight on the home feed; `--ez demoRcOffer true`: the RC offer
            com.ecency.core.app.DemoAuthServices.demoSpotlights =
                if (intent.getBooleanExtra("demoSpotlight", false)) com.ecency.core.app.DemoAuthServices.SAMPLE_SPOTLIGHT else "[]"
            if (intent.getBooleanExtra("demoRcOffer", false)) env.rcOffer.raise()
            // `--es demoToast <text>`: a toast through the shared channel (toast layout screenshots); with
            // `--el demoToastDelay <ms>` it comes later, so a sheet or the keyboard can be opened first
            intent.getStringExtra("demoToast")?.let { text ->
                val delay = intent.getLongExtra("demoToastDelay", 0L)
                if (delay > 0) window.decorView.postDelayed({ env.ui.toast(text) }, delay) else env.ui.toast(text)
            }
            // `--ez demoDigests true`: two synthetic email digest subscriptions (settings screenshots)
            com.ecency.core.app.DemoAuthServices.demoDigests =
                if (intent.getBooleanExtra("demoDigests", false)) com.ecency.core.app.DemoAuthServices.SAMPLE_DIGESTS else "[]"
            // `--ez demoStreak true`: quests with a streak and a freeze (perks screenshots)
            if (intent.getBooleanExtra("demoStreak", false)) com.ecency.core.app.DemoAuthServices.demoQuests = com.ecency.core.app.DemoAuthServices.SAMPLE_QUESTS
            // `--ez demoNoPro true`: nobody is on the Pro roster until a demo Pro purchase (Pro screenshots)
            if (intent.getBooleanExtra("demoNoPro", false)) com.ecency.core.app.DemoAuthServices.clearDemoPro()
            com.ecency.core.app.DemoAuthServices.demoReleaseTag = if (intent.getBooleanExtra("demoUpdate", false)) "99.1.0" else null
        }
        EcencyGraph.playReview.attach(this)
        // `--ez seedAccounts true` (debug only, with demoAuth): two throwaway accounts signed in, for screenshots.
        // Never into a vault that talks to the real token endpoint.
        if (BuildConfig.DEBUG && EcencyGraph.demoAuth && intent.getBooleanExtra("seedAccounts", false)) seedAccounts(env)
        // `--ez lockDemo true` (debug only): a throwaway account with PIN 1234 and the lock on, for screenshots
        if (BuildConfig.DEBUG && intent.getBooleanExtra("lockDemo", false)) seedLockDemo(env)
        // `--ez demoBiometrics true` (debug demo runs only): biometrics count as enrolled and the PIN enable offer's
        // prompt succeeds, for screenshots on an emulator without an enrolled fingerprint
        val demoBiometrics = BuildConfig.DEBUG && EcencyGraph.demoAuth && intent.getBooleanExtra("demoBiometrics", false)
        val host = object : AppHost {
            override fun openExternal(url: String) = openInCustomTab(url)
            // the shared toast channel (MessagesLayer), never a system toast with its own layout
            override fun toast(text: String) { env.ui.toast(text) }
            override val videoCaptures: VideoCaptures get() = this@MainActivity.videoCaptures
            override fun copy(text: String) {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("post", text))
            }

            override fun saveImage(url: String, done: (String?) -> Unit) = this@MainActivity.saveImage(url, done)

            override fun share(text: String) {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, text)
                runCatching { startActivity(android.content.Intent.createChooser(send, null)) }
            }

            // RN appLang: the language picked in settings, else the device's
            override fun readerLanguage(): String = env.settings.contentLanguage

            override fun biometricAvailable(): Boolean = demoBiometrics ||
                BiometricManager.from(this@MainActivity).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

            override fun authenticateBiometric(onSuccess: (String) -> Unit, onInvalidated: () -> Unit) =
                showBiometricPrompt(onSuccess, onInvalidated)

            override fun confirmBiometric(onSuccess: () -> Unit) = if (demoBiometrics) onSuccess() else showBiometricOfferPrompt(onSuccess)

            override fun setHideInRecents(hide: Boolean) {
                hideInRecents = hide
                applyWindowPrivacy(hideInRecents, sensitiveScreen)
            }

            override fun setSensitiveScreen(sensitive: Boolean) {
                sensitiveScreen = sensitive
                applyWindowPrivacy(hideInRecents, sensitiveScreen)
            }

            private val prefs get() = getSharedPreferences("ecency.ui", MODE_PRIVATE)

            override fun voteWeightPercent(): Float = prefs.getFloat("voteWeightPercent", 100f)

            override fun setVoteWeightPercent(percent: Float) = prefs.edit().putFloat("voteWeightPercent", percent).apply()

            // earlier preview builds kept the theme here; it moves to the shared settings once
            override fun legacyThemeName(): String? =
                prefs.getString("themeMode", null)?.also { prefs.edit().remove("themeMode").apply() }

            override fun openMail(url: String): Boolean = runCatching {
                startActivity(android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse(url)))
            }.isSuccess

            override fun openStoreListing() {
                fun view(link: String) = runCatching {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                // RN openStoreListing: the Play app, else the listing on the web
                if (!view(com.ecency.core.app.StoreListing.ANDROID)) view(com.ecency.core.app.UpdateLinks.PLAY_WEB)
            }

            override fun feedbackPlatform(): String = "${android.os.Build.MODEL} - Android ${android.os.Build.VERSION.SDK_INT}"

            override fun setSystemBarsDark(dark: Boolean) = applySystemBars(dark)

            override fun buildNumber(): String = BuildConfig.BUILD_NUMBER.toString()

            override fun openCallback(url: String) { if (!openOtherApp(url)) env.ui.toast(com.ecency.core.app.ShellUiCopy.NO_APP_FOR_LINK) }

            override fun openWallet(link: String): Boolean = openOtherApp(link)

            // RN: the welcome screen shows on the first launch and again after each update
            override fun termsAccepted(): Boolean = !env.welcome.shouldShow()

            override fun setTermsAccepted() { env.acceptWelcome(consent = true) }

            // rotation and other configuration changes stop the activity without the app leaving the foreground
            override fun isChangingConfigurations(): Boolean = this@MainActivity.isChangingConfigurations

            override fun openStoreForUpdate() {
                val installer = runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= 30) packageManager.getInstallSourceInfo(packageName).installingPackageName
                    else @Suppress("DEPRECATION") packageManager.getInstallerPackageName(packageName)
                }.getOrNull()
                val url = com.ecency.core.app.UpdateLinks.android(installer)
                fun view(link: String) = runCatching {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                // no app for market:// (a Play install without the store app): the listing on the web
                if (!view(url) && url.startsWith("market://")) view(com.ecency.core.app.UpdateLinks.PLAY_WEB)
            }

            override fun termsPreviouslyAccepted(): Boolean = env.welcome.termsAccepted() || prefs.getBoolean("termsAccepted", false)

            override fun openInbox() {
                runCatching {
                    startActivity(android.content.Intent.makeMainSelectorActivity(android.content.Intent.ACTION_MAIN, android.content.Intent.CATEGORY_APP_EMAIL).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }

            override fun copySensitive(text: String) = KeyClipboard.copy(this@MainActivity, text)
        }
        watchConnectivity(env)
        EcencyGraph.pushPlatform?.let {
            it.requester = askNotificationPermission
            // granted while the previous activity instance was going away
            it.recheck()
        }
        val navigator = navigation.navigator
        // `--es openScreen login|hiveauth|accounts` and `--es demoSigning upgrade` (debug only): screenshots
        val openScreen = if (BuildConfig.DEBUG && savedInstanceState == null) intent.getStringExtra("openScreen") else null
        if (openScreen == "login" || openScreen == "hiveauth") navigator.push(com.ecency.core.app.AppScreen.Login)
        if (openScreen == "hiveauth") env.session.startHiveAuth("spike-alice")
        // profile area screenshots: `--es openScreen bookmarks|editProfile|profile:<user>|follows:<user>`
        when {
            openScreen == "bookmarks" -> navigator.push(com.ecency.core.app.AppScreen.Bookmarks(0))
            openScreen == "editProfile" -> navigator.push(com.ecency.core.app.AppScreen.ProfileEdit)
            // settings screenshots: `--es openScreen settings|emailDigests`
            openScreen == "settings" -> navigator.push(com.ecency.core.app.AppScreen.Settings)
            // perks screenshots: `--es openScreen perks|spinGame`
            openScreen == "perks" -> navigator.push(com.ecency.core.app.AppScreen.Perks)
            openScreen == "spinGame" -> navigator.push(com.ecency.core.app.AppScreen.SpinGame)
            // platform screenshots: `--es openScreen explore|qr|dapp` (dapp: the synthetic dApp page, which asks for a vote)
            openScreen == "explore" -> navigator.push(com.ecency.core.app.AppScreen.Explore)
            openScreen == "qr" -> navigator.push(com.ecency.core.app.AppScreen.QrScan)
            openScreen == "dapp" -> {
                com.ecency.app.ui.DappWeb.demo = true
                navigator.push(com.ecency.core.app.AppScreen.DappBrowser(com.ecency.core.app.DemoDapp.URL))
            }
            // purchase screenshots: `--es openScreen getPoints|accountBoost|getPoints:<user>:<product>`
            openScreen == "getPoints" -> navigator.push(com.ecency.core.app.AppScreen.GetPoints())
            openScreen == "accountBoost" -> navigator.push(com.ecency.core.app.AppScreen.AccountBoost())
            openScreen?.startsWith("getPoints:") == true -> openScreen.split(':').let { navigator.push(com.ecency.core.app.AppScreen.GetPoints(it[1], it.getOrElse(2) { "" })) }
            openScreen == "emailDigests" -> navigator.push(com.ecency.core.app.AppScreen.Settings).also { navigator.push(com.ecency.core.app.AppScreen.EmailDigests) }
            openScreen?.startsWith("profile:") == true -> navigator.push(com.ecency.core.app.AppScreen.Profile(openScreen.substringAfter(':')))
            openScreen?.startsWith("follows:") == true -> navigator.push(com.ecency.core.app.AppScreen.Follows(openScreen.substringAfter(':'), false))
        }
        if (BuildConfig.DEBUG && savedInstanceState == null && intent.getStringExtra("demoSigning") == "upgrade") demoUpgrade(env)
        if (openScreen == "accounts") com.ecency.app.ui.DebugLaunch.openAccounts = true
        // `--es demoShare <text>` (debug only): a share of that text, as if another app sent it
        if (BuildConfig.DEBUG && savedInstanceState == null) intent.getStringExtra("demoShare")?.let { env.share.receive(listOf(it), emptyList()) }
        if (BuildConfig.DEBUG && savedInstanceState == null) com.ecency.app.ui.DebugLaunch.demoScrollTop = intent.getBooleanExtra("demoScrollTop", false)
        // `--es editorSheet dictation|translate|video|aiAssist|aiImage` and `--ez openDigest true` (debug only, demo runs): screenshots
        if (BuildConfig.DEBUG && savedInstanceState == null && EcencyGraph.demoAuth) {
            com.ecency.app.ui.DebugLaunch.editorSheet = intent.getStringExtra("editorSheet")
            com.ecency.app.ui.DebugLaunch.editorText = intent.getStringExtra("editorText")
            if (intent.getBooleanExtra("openDigest", false)) {
                lifecycleScope.launch {
                    val user = env.session.state.first { !it.isLoading }.username
                    env.appLock.state.first { !it.hidesContent }
                    if (user != null) env.digest.show("own", user, firstPublish = true)
                }
            }
        }
        // wallet screenshots (debug only): `--es walletScreen transfer,transfer,HIVE,hive,spike-bob` pushes a saved-navigation line
        // (',' for its separator), `--ez walletTab true` selects the Wallet tab, `--ez demoUndecided true` holds broadcasts open
        if (BuildConfig.DEBUG && savedInstanceState == null && EcencyGraph.demoAuth) {
            if (intent.getBooleanExtra("demoUndecided", false)) com.ecency.core.app.DemoWallet.hangBroadcasts = true
            if (intent.getBooleanExtra("walletTab", false)) navigator.selectTab(com.ecency.core.app.AppTab.WALLET)
            intent.getStringExtra("walletScreen")?.let { line ->
                com.ecency.core.app.NavState.decodeScreen(line.replace(',', '\u001F'))?.let { navigator.push(it) }
            }
        }
        // `--es openScreen route:COMMUNITY:hive-125125` (debug only, screenshots): any RN route by name, with its argument
        if (openScreen != null && openScreen.startsWith("route:")) {
            val parts = openScreen.split(':', limit = 3)
            com.ecency.core.app.RouteName.entries.firstOrNull { it.name == parts.getOrNull(1) }
                ?.let { navigator.push(com.ecency.core.app.AppScreen.of(it, parts.getOrNull(2).orEmpty())) }
        }
        // `--es openRoute VotersScreen|ReblogsScreen|EditHistoryScreen|BotCommentsModal|PostScreen --es arg author/permlink` (debug only)
        val openRoute = if (BuildConfig.DEBUG && savedInstanceState == null) intent.getStringExtra("openRoute") else null
        if (openRoute != null) {
            val arg = intent.getStringExtra("arg").orEmpty()
            val key = com.ecency.core.api.model.PostKey.parse(arg)
            val screen = if (openRoute == "PostScreen" && key != null) com.ecency.core.app.AppScreen.Post(key.author, key.permlink)
            else com.ecency.core.app.RouteName.ofRnName(openRoute)?.let { com.ecency.core.app.AppScreen.of(it, arg) }
            screen?.let { navigator.push(it) }
        }
        // `--es openTab NOTIFICATION` (debug only): start on that tab; `--es demoPush tap|banner` (with demoAuth): a
        // tapped reply notification, or one arriving in front, as the push service would deliver it (screenshots)
        if (BuildConfig.DEBUG && savedInstanceState == null) {
            intent.getStringExtra("openTab")?.let { name ->
                com.ecency.core.app.AppTab.entries.firstOrNull { it.name == name }?.let { navigator.selectTab(it) }
            }
            val reply = mapOf("type" to "reply", "source" to "spike-bob", "target" to "spike-alice", "permlink1" to "fake-post-2", "id" to "demo-push-1")
            when (intent.getStringExtra("demoPush").takeIf { EcencyGraph.demoAuth }) {
                "tap" -> env.push.opened(reply)
                "banner" -> lifecycleScope.launch {
                    env.session.state.first { !it.isLoading }
                    env.appLock.state.first { !it.hidesContent }
                    kotlinx.coroutines.delay(1500)
                    env.push.received(reply, null, null)
                }
            }
        }
        // `--es video <embed url>` (debug only): opens the video sheet at launch, for player checks
        val startVideo = if (BuildConfig.DEBUG && savedInstanceState == null) intent.getStringExtra("video") else null
        // a recording belongs to the account that made it: with any account change an untaken one is deleted
        lifecycleScope.launch { env.events.collect { if (it is com.ecency.core.app.AppEvent.SessionChanged || it is com.ecency.core.app.AppEvent.DataWiped) videoCaptures.discard() } }
        setContent { EcencyApp(env, host, navigator, startVideo) }
        // a link that launched the app; a recreation (rotation) must not handle it twice
        if (savedInstanceState == null) {
            handleLink(intent)
            handlePush(intent)
            handleShare(intent)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
        handlePush(intent)
        handleShare(intent)
    }

    /**
     * Text and images another app shared (ACTION_SEND, ACTION_SEND_MULTIPLE): the shared model asks where they go
     * once the app is unlocked and the accounts were read ([ShareIntents] decides what may be read at all).
     */
    internal fun handleShare(intent: android.content.Intent?) {
        val shared = ShareIntents.parse(this, intent) ?: return
        EcencyGraph.env(this).share.receive(shared.texts, shared.images.map { it.toString() })
    }

    /**
     * A tapped system notification: its data goes to the shared push model, which validates every field, waits
     * for the accounts and the app lock, and only then hands a destination to the navigation.
     */
    internal fun handlePush(intent: android.content.Intent?) {
        val data = com.ecency.app.push.PushIntents.data(intent) ?: return
        com.ecency.app.push.PushIntents.consume(intent)
        EcencyGraph.env(this).push.opened(data)
    }

    /**
     * ecency:// and hive:// links, and ecency.com /auth and /signup, go to the shared link model, which asks the
     * user before anything happens. ecency.com posts, profiles and feeds open in the navigation (gated like
     * every other entry); other ecency.com pages open in the in-app browser. While locked the screen opens behind the lock.
     */
    internal fun handleLink(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_VIEW) return
        val url = intent.dataString ?: return
        val env = EcencyGraph.env(this)
        if (env.authLinks.handle(url)) return
        // the widgets' shortcuts (README "Widgets"): a place in the app, gated like every entry
        env.shortcutEntry(url)?.let {
            openEntry(it)
            return
        }
        env.waves.openLink(url) // ecency.com/waves?text=...: the composer opens with the text
        val entry = com.ecency.core.app.ContentLinks.entry(url)
        if (entry != null) openEntry(entry)
        // a first-party page no screen claims (App Links hand over every listed path): the in-app browser, not nothing
        else com.ecency.core.app.ContentLinks.browserFallback(url)?.let(::openInCustomTab)
    }

    /**
     * Opens [entry] once the accounts were read: on a cold start the session is still loading here, and gating on
     * it then would send a signed-in reader to the login screen.
     */
    internal fun openEntry(entry: com.ecency.core.app.NavEntry) {
        val env = EcencyGraph.env(this)
        lifecycleScope.launch {
            env.session.state.first { !it.isLoading }
            navigation.navigator.open(entry, env.session.current.isLoggedIn)
            env.entryOpened()
        }
    }

    /** Hands [url] to another app (a link's callback, the HiveAuth wallet); false when none takes it. */
    private fun openOtherApp(url: String): Boolean = try {
        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)).addCategory(android.content.Intent.CATEGORY_BROWSABLE))
        true
    } catch (_: android.content.ActivityNotFoundException) {
        false
    }

    /** Debug only: throwaway posting keys that control nothing. */
    private fun seedAccounts(env: AppEnvironment) {
        val vault = env.vault.vault
        listOf("spike-bob", "spike-alice").forEach { user ->
            // the posting key the demo node lists for the account (a public throwaway password, controls nothing)
            val wif = com.ecency.core.hive.PrivateKey.fromLogin(user, com.ecency.core.app.DemoHiveTransport.PASSWORD, com.ecency.core.hive.KeyRole.POSTING).toWif()
            vault.save(com.ecency.core.secure.VaultAccount(user, com.ecency.core.secure.AuthType.POSTING_KEY, postingKey = wif, accessToken = "demo"))
        }
        vault.setCurrentUsername("spike-alice")
    }

    /** Debug only: a transfer needs the active key, so the upgrade sheet shows; nothing is sent without one. */
    private fun demoUpgrade(env: AppEnvironment) {
        kotlinx.coroutines.MainScope().launch {
            env.session.state.first { !it.isLoading }
            com.ecency.core.app.DemoAuthServices.demoUpgrade(env)
        }
    }


    /** Feeds the shared connectivity state (RN NetInfo) while the activity lives. */
    private fun watchConnectivity(env: AppEnvironment) {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        fun online() = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        env.connectivity.setOnline(online())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = post(true)
            override fun onLost(network: Network) = post(online())
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = post(online())
            private fun post(value: Boolean) = runOnUiThread { env.connectivity.setOnline(value) }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }.onSuccess {
            lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                    runCatching { cm.unregisterNetworkCallback(callback) }
                }
            })
        }
    }

    /** Debug only. The key derives from a public seed and controls nothing. Blocks briefly (Keystore, PIN hash). */
    private fun seedLockDemo(env: AppEnvironment) {
        val vault = env.vault.vault
        if (vault.currentUsername() == null) {
            val wif = com.ecency.core.hive.PrivateKey.fromSeed("ecency-native-spike-lock-demo-throwaway").toWif()
            vault.save(com.ecency.core.secure.VaultAccount("spike-alice", com.ecency.core.secure.AuthType.POSTING_KEY, postingKey = wif))
            vault.setCurrentUsername("spike-alice")
        }
        if (!env.pinLock.hasPin()) env.pinLock.setPin("1234")
    }

    /**
     * Strong biometrics only, no device-credential fallback: a convenience next to the PIN, never a
     * second way around it. Failure or cancel leaves the PIN pad (and its attempt counter) as it was.
     * The prompt unlocks a Keystore key ([BiometricKey]) that a new enrollment invalidates; an invalidated
     * key turns biometric unlock off ([onInvalidated]) instead of prompting.
     */
    private fun showBiometricPrompt(onSuccess: (String) -> Unit, onInvalidated: () -> Unit) {
        if (isFinishing || supportFragmentManager.isStateSaved) return
        val cipher = when (val use = BiometricKey.use()) {
            is BiometricKey.Use.Ready -> use.cipher
            BiometricKey.Use.Invalidated -> return onInvalidated()
            BiometricKey.Use.Unavailable -> return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (BiometricKey.proves(result.cryptoObject?.cipher)) onSuccess(BiometricKey.BINDING)
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(UiCopy.UNLOCK_ECENCY)
            .setSubtitle(AndroidCopy.BIOMETRIC_SCAN)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButtonText(UiCopy.USE_PIN)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    /**
     * The prompt of the biometric offer when the PIN lock is turned on: a strong biometric (no device credential)
     * proves the owner can use it. No key yet: [BiometricKey.create] makes the enrollment-bound key once the
     * backing PIN is confirmed, and every unlock after that runs [showBiometricPrompt] over it.
     */
    private fun showBiometricOfferPrompt(onSuccess: () -> Unit) {
        if (isFinishing || supportFragmentManager.isStateSaved) return
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(AndroidCopy.BIOMETRIC_UNLOCK_TITLE)
            .setSubtitle(AndroidCopy.BIOMETRIC_CONFIRM)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButtonText(UiCopy.CANCEL)
            .build()
        prompt.authenticate(info)
    }

    /**
     * http(s) only (checked again here); other schemes are never handed to the system. Never a bare VIEW: this app is
     * the verified App Link handler for ecency.com, so Android would hand an ecency.com page straight back to
     * [handleLink], which falls back to the browser again, in a loop. The tab is pinned to a Custom Tabs browser;
     * without one the page goes to a browser app through a browser-only selector, and only when no browser app
     * resolves does a chooser open with this app left out.
     */
    internal fun openInCustomTab(url: String) {
        val safe = SafeTarget.httpUrl(url) ?: return
        val uri = Uri.parse(safe)
        runCatching {
            val browser = androidx.browser.customtabs.CustomTabsClient.getPackageName(this, null)
            if (browser != null) {
                val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
                tab.intent.setPackage(browser)
                tab.launchUrl(this, uri)
            } else {
                val view = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                // The default browser without Custom Tabs: a selector for browser apps only (never this app) honours the
                // user's default, so they do not get a chooser on every link. The page goes in a VIEW (a bare MAIN with
                // data would only open the browser's start page).
                val browsers = android.content.Intent.makeMainSelectorActivity(android.content.Intent.ACTION_MAIN, android.content.Intent.CATEGORY_APP_BROWSER)
                val browserOnly = browsers.selector ?: browsers
                val resolved = packageManager.resolveActivity(browserOnly, 0)?.activityInfo?.packageName
                if (resolved != null && resolved != packageName) {
                    startActivity(view.apply { selector = browserOnly })
                    return@runCatching
                }
                startActivity(
                    android.content.Intent.createChooser(view, null).putExtra(
                        android.content.Intent.EXTRA_EXCLUDE_COMPONENTS,
                        arrayOf(android.content.ComponentName(this, MainActivity::class.java)),
                    ),
                )
            }
        }.onFailure { EcencyGraph.env(this).ui.toast(AndroidCopy.NO_BROWSER) }
    }

    /** Only the debug renderer demo, which runs without the app (and its toast channel). */
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
