package com.ecency.core.app

import com.ecency.core.auth.HiveSignerConfig
import com.ecency.core.render.url.WebUrl

/** What the in-app HiveSigner web view does with a navigation (login and hot signing, both platforms). */
enum class HiveSignerNavigation {
    /** A page of HiveSigner itself over https: load it. */
    LOAD,

    /** The loopback redirect URI: never loaded; its query is the answer (code, or the signed transaction id). */
    REDIRECT,

    /** Anything else (another host, http, a custom scheme): cancelled. */
    BLOCK,
}

/**
 * The web view policy for HiveSigner's pages: only https on HiveSigner's own host loads, the redirect URI is
 * read and never loaded, everything else is cancelled. The web view uses a non-persistent data store, so no
 * HiveSigner session outlives the sheet.
 */
object HiveSignerPage {
    fun decide(url: String, config: HiveSignerConfig = HiveSignerConfig.RN): HiveSignerNavigation {
        if (url.startsWith(config.redirectUri)) {
            val next = url.getOrNull(config.redirectUri.length)
            if (next == null || next == '?' || next == '#') return HiveSignerNavigation.REDIRECT
        }
        val target = WebUrl.parse(url) ?: return HiveSignerNavigation.BLOCK
        val base = WebUrl.parse(config.baseUrl) ?: return HiveSignerNavigation.BLOCK
        return if (target.protocol == "https:" && target.host.lowercase() == base.host.lowercase()) HiveSignerNavigation.LOAD
        else HiveSignerNavigation.BLOCK
    }
}
