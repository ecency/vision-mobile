package com.ecency.core.app

import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * How this build records analytics (RN providers/plausible). [host] is the Plausible instance (RN `pl.ecency.com`),
 * empty to send nothing; [os] is `android` or `ios` (RN `Platform.OS`); [userAgent] the RN-shaped agent Plausible needs
 * to count the event as a mobile app ([PlausibleAnalytics.userAgent]). Plausible's event endpoint takes no key.
 */
data class AnalyticsConfig(val host: String = "", val os: String = "", val userAgent: String = "") {
    companion object {
        /** RN's instance; a build setting can name another or turn analytics off. */
        const val DEFAULT_HOST = "https://pl.ecency.com"

        /** RN `SITE_ID`. */
        const val SITE_ID = "ecency.com"

        /**
         * The host a build setting names: empty uses [DEFAULT_HOST], `off` (or `none`) sends nothing, anything else
         * must be a bare `https://host` (no path, query or credentials) or analytics stay off.
         */
        fun hostOf(setting: String?): String {
            val s = setting?.trim().orEmpty()
            if (s.isEmpty()) return DEFAULT_HOST
            if (s.equals("off", ignoreCase = true) || s.equals("none", ignoreCase = true)) return ""
            val ok = Regex("^https://[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$").matches(s.lowercase().trimEnd('/'))
            return if (ok) s.lowercase().trimEnd('/') else ""
        }
    }
}

/**
 * RN `recordPlausibleEvent` and `usePlausibleTracker`: a post screen records one `pageview` when its post first loads
 * (`POST {host}/api/event {name: "pageview", url: "app://<os>.ecency.com/<post url>", domain, force: true}`), fire and
 * forget. Nothing is recorded while the app lock hides content. RN has no opt-out, so neither has this; a build turns it
 * off with its host setting (debug and fake-data builds do).
 */
class PlausibleAnalytics internal constructor(private val env: AppEnvironment, private val http: AuthHttp) {
    /** Set by each app at start, before any screen. Main thread. */
    var config: AnalyticsConfig = AnalyticsConfig()

    /** RN `recordEvent(post.url, true)`. Empty paths are skipped (RN's guard for its top crash). Main thread. */
    fun pageview(urlPath: String?) {
        val c = config
        if (c.host.isEmpty() || c.os.isEmpty() || urlPath.isNullOrEmpty()) return
        if (env.appLock.current.hidesContent) return
        val body = body(urlPath, c.os, "pageview")
        env.background.launch {
            try {
                withTimeoutOrNull(TIMEOUT_MILLIS) {
                    http.send(AuthHttpRequest("POST", "${c.host}/api/event", mapOf("Content-Type" to "application/json", "User-Agent" to c.userAgent), body))
                }
            } catch (e: Exception) {
                // RN: fire and forget; transport failures are not reported (one per screen view on a broken path)
            }
        }
    }

    companion object {
        /** RN `DEFAULT_TIMEOUT_MS` for this axios instance. */
        const val TIMEOUT_MILLIS = 30_000L

        fun body(urlPath: String, os: String, name: String): String = buildJsonObject {
            put("name", name)
            put("url", "app://$os.${AnalyticsConfig.SITE_ID}/${urlPath.removePrefix("/")}")
            put("domain", AnalyticsConfig.SITE_ID)
            put("force", true)
        }.toString()

        /**
         * RN `getEcencyUserAgent`: `<app>/<version> (<iOS|Android> <os version>; <model>) Version/4.0 Chrome/<version> Mobile`;
         * the `Version/4.0 ... Mobile` tail is what makes Plausible count a mobile app.
         */
        fun userAgent(appName: String, appVersion: String, system: String, systemVersion: String, model: String): String =
            "$appName/$appVersion ($system $systemVersion; $model) Version/4.0 Chrome/$appVersion Mobile"
    }
}
