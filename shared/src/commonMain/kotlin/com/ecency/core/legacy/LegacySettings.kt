package com.ecency.core.legacy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The RN app's settings as it left them in AsyncStorage. Every field is null when RN had nothing usable for it;
 * values are RN's raw ones (the app checks them against its own option lists).
 */
data class LegacySettings(
    /** `settings` key (RN storage.ts SETTINGS_SCHEMA), `.server`: the node picked in settings. */
    val server: String? = null,
    /** `persist:root` application.currency.currency, e.g. "eur". */
    val currency: String? = null,
    /** application.language, e.g. "de-DE". */
    val language: String? = null,
    /** RN's `locale_auto_detected` key is set: its first-launch locale detection ran, so its language is a result, not the default. */
    val localeDetected: Boolean = false,
    /** application.nsfw: "0", "1" or "2" (stored as a string or a number). */
    val nsfw: String? = null,
    /** application.colorTheme: 0 device, 1 light, 2 dark. */
    val colorTheme: Int? = null,
    /** application.hidePostsThumbnails. */
    val hideImages: Boolean? = null,
    /** application.imageServer. */
    val imageServer: String? = null,
    /** application.isNotificationOpen: the push master switch. */
    val pushEnabled: Boolean? = null,
    /** application.notificationDetails without the "Notification" suffix: "vote" to true, "follow" to false... */
    val pushTypes: Map<String, Boolean> = emptyMap(),
    /** `persist:root` walkthrough.walkthroughMap (a Map persisted as `[[index, item]]`): the walkthrough steps already seen. */
    val walkthroughShown: Set<Int> = emptySet(),
)

/**
 * Reads [LegacySettings] from RN storage (`persist:root`, redux-persist v6 with each reducer as a JSON string, and
 * the old `settings` key). A field that is missing or malformed is left out; only the storage itself failing is
 * an error. Never reads or returns a secret.
 */
object LegacySettingsReader {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Null when neither key exists (nothing to import, ever).
     * @throws LegacyImportError.StorageUnreadable when the storage exists but cannot be read now.
     */
    @Throws(LegacyImportError::class)
    fun read(storage: LegacyStorage): LegacySettings? {
        fun get(key: String): String? = try {
            storage.read(key)
        } catch (e: LegacyImportError) {
            throw e
        } catch (e: Exception) {
            throw LegacyImportError.StorageUnreadable(e)
        }
        val settingsJson = get("settings")
        val detected = get("locale_auto_detected") != null
        val rootJson = get("persist:root")
        if (settingsJson == null && rootJson == null) return null
        val settings = parse(settingsJson)
        val app = parse(rootJson)?.string("application")?.let(::parse)
        val details = app?.get("notificationDetails") as? JsonObject
        return LegacySettings(
            server = settings?.string("server")?.trim()?.takeIf { it.isNotEmpty() },
            currency = (app?.get("currency") as? JsonObject)?.string("currency"),
            language = app?.string("language"),
            localeDetected = detected,
            // the settings dropdown stored the option index (a number), the initial state a string
            nsfw = (app?.get("nsfw") as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content,
            colorTheme = (app?.get("colorTheme") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull,
            hideImages = app?.bool("hidePostsThumbnails"),
            imageServer = app?.string("imageServer"),
            pushEnabled = app?.bool("isNotificationOpen"),
            pushTypes = details?.entries?.mapNotNull { (k, v) ->
                val on = (v as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return@mapNotNull null
                if (k.endsWith("Notification")) k.removeSuffix("Notification") to on else null
            }?.toMap().orEmpty(),
            walkthroughShown = parse(rootJson)?.string("walkthrough")?.let(::parse)?.let(::walkthrough).orEmpty(),
        )
    }

    /** RN transformWalkthroughMap: `walkthroughMap` is an array of `[index, {walkthroughIndex, isShown}]` pairs. */
    private fun walkthrough(o: JsonObject): Set<Int> =
        (o["walkthroughMap"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { pair ->
            val entry = pair as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
            val index = (entry.getOrNull(0) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return@mapNotNull null
            val shown = ((entry.getOrNull(1) as? JsonObject)?.get("isShown") as? JsonPrimitive)?.booleanOrNull ?: false
            index.takeIf { shown }
        }?.toSet().orEmpty()

    private fun parse(text: String?): JsonObject? = text?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private fun JsonObject.string(field: String): String? = (get(field) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.bool(field: String): Boolean? = (get(field) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
}
