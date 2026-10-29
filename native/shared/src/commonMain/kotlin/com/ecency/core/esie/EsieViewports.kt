package com.ecency.core.esie

/**
 * Each mood's framing (left, top, right, bottom in art units), in [EsieMood] order: what [EsieRig.sampleViewport]
 * finds by sampling the rig over every cycle, baked so the first Esie on screen samples nothing.
 * `EsieViewportsTest` resamples and fails with the new values after an art or motion change.
 */
internal object EsieViewports {
    val ALL: List<FloatArray> = listOf(
        floatArrayOf(597.98706f, 268.0f, 1835.5774f, 1768.0f), // REST
        floatArrayOf(196.42545f, 268.0f, 1835.5774f, 1768.0f), // THUMBS_UP
        floatArrayOf(211.02596f, 268.0f, 1835.5774f, 1768.0f), // WAVE
        floatArrayOf(211.02596f, 174.8251f, 1835.5774f, 1772.3641f), // HAPPY
        floatArrayOf(598.8225f, 268.0f, 1834.0144f, 1775.829f), // SAD
        floatArrayOf(568.2747f, 246.00687f, 1856.6112f, 1771.6663f), // THINKING
        floatArrayOf(597.98737f, 168.43448f, 1835.5767f, 1772.472f), // SURPRISED
    )
}
