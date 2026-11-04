package com.ecency.core.esie

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

/**
 * Copies a whole frame into Swift memory in one call, so the iOS renderer reads plain floats instead of crossing
 * into Kotlin per value every frame.
 */
class EsieFrameCopier {
    /** The number of floats [write] writes for any frame. */
    val floatCount: Int get() = EsieRig.layerCount * EsieFrameBuffer.STRIDE

    /** Writes [buffer]'s [EsieFrameBuffer.data] to [target], which holds [floatCount] floats. */
    @OptIn(ExperimentalForeignApi::class)
    fun write(buffer: EsieFrameBuffer, target: CPointer<FloatVar>) {
        buffer.data.usePinned { pinned -> memcpy(target, pinned.addressOf(0), (buffer.data.size * 4).convert()) }
    }
}
