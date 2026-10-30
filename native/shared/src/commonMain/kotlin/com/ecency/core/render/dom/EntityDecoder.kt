package com.ecency.core.render.dom

import com.ecency.core.render.js.Js
import kotlin.math.max
import kotlin.math.pow

/**
 * Port of the `EntityDecoder` from the `entities` package. Two versions are in play and they
 * differ in edge cases, so both are kept:
 *  - 6.0.1 ([V6]): render-helper's own `decodeHTML` (attribute checks in the sanitizer);
 *  - 7.0.1 ([V7]): htmlparser2's tokenizer (the malformed-HTML repair path).
 * The binary tries are the packages' own data ([EntityTables]).
 */
internal class EntityDecoder(private val version: Int, private val emit: (cp: Int, consumed: Int) -> Unit) {
    enum class Mode { LEGACY, STRICT, ATTRIBUTE }

    private val tree: String = if (version == V7) EntityTables.TREE_V7 else EntityTables.TREE_V6

    private var state = ENTITY_START
    private var consumed = 1
    private var numeric = 0.0
    private var result = 0
    private var treeIndex = 0
    private var excess = 1
    private var mode = Mode.STRICT
    private var runConsumed = 0

    fun startEntity(decodeMode: Mode) {
        mode = decodeMode
        state = ENTITY_START
        numeric = 0.0
        result = 0
        treeIndex = 0
        excess = 1
        consumed = 1
        runConsumed = 0
    }

    private fun t(i: Int): Int = tree[i].code

    /** Consumes [input] from [offset]; returns the entity length, 0 for "not an entity", -1 for "needs more". */
    fun write(input: String, offset: Int): Int = when (state) {
        ENTITY_START -> {
            if (offset < input.length && input[offset] == '#') {
                state = NUMERIC_START
                consumed += 1
                numericStart(input, offset + 1)
            } else {
                state = NAMED
                named(input, offset)
            }
        }
        NUMERIC_START -> numericStart(input, offset)
        NUMERIC_DECIMAL -> numericDigits(input, offset, 10)
        NUMERIC_HEX -> numericDigits(input, offset, 16)
        else -> named(input, offset)
    }

    private fun numericStart(input: String, offset: Int): Int {
        if (offset >= input.length) return -1
        if ((input[offset].code or 0x20) == 'x'.code) {
            state = NUMERIC_HEX
            consumed += 1
            return numericDigits(input, offset + 1, 16)
        }
        state = NUMERIC_DECIMAL
        return numericDigits(input, offset, 10)
    }

    private fun isDigitOf(c: Char, base: Int) = if (base == 10) Js.isDigit(c) else Js.isHexDigit(c)

    private fun numericDigits(input: String, start: Int, base: Int): Int {
        var offset = start
        while (offset < input.length && isDigitOf(input[offset], base)) {
            if (version == V7) {
                val c = input[offset]
                val digit = if (c <= '9') c - '0' else (c.code or 0x20) - 'a'.code + 10
                numeric = numeric * base + digit
                consumed++
            }
            offset++
        }
        if (version == V6 && offset != start) {
            // result * base^n + parseInt(digits, base): 0 * Infinity is NaN for overlong runs.
            val n = offset - start
            numeric = numeric * base.toDouble().pow(n) + Js.digitsToDouble(input.substring(start, offset), base)
            consumed += n
        }
        if (offset >= input.length) return -1
        return emitNumeric(input[offset].code, if (base == 10) 2 else 3)
    }

    private fun emitNumeric(lastCp: Int, expectedLength: Int): Int {
        if (consumed <= expectedLength) return 0
        if (lastCp == ';'.code) {
            consumed += 1
        } else if (mode == Mode.STRICT) {
            return 0
        }
        emit(replaceCodePoint(numeric), consumed)
        return consumed
    }

    private fun determineBranch(current: Int, nodeIndex: Int, char: Int): Int {
        val branchCount = (current and 0x1F80) shr 7
        val jumpOffset = current and 0x7F
        if (branchCount == 0) return if (jumpOffset != 0 && char == jumpOffset) nodeIndex else -1
        if (jumpOffset != 0) {
            val value = char - jumpOffset
            return if (value < 0 || value >= branchCount) -1 else t(nodeIndex + value) - 1
        }
        if (version == V6) {
            var lo = nodeIndex
            var hi = lo + branchCount - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val midValue = t(mid)
                when {
                    midValue < char -> lo = mid + 1
                    midValue > char -> hi = mid - 1
                    else -> return t(mid + branchCount)
                }
            }
            return -1
        }
        val packedKeySlots = (branchCount + 1) shr 1
        var lo = 0
        var hi = branchCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val packed = t(nodeIndex + (mid shr 1))
            val midKey = (packed shr ((mid and 1) * 8)) and 0xFF
            when {
                midKey < char -> lo = mid + 1
                midKey > char -> hi = mid - 1
                else -> return t(nodeIndex + packedKeySlots + mid)
            }
        }
        return -1
    }

    private fun failBranch(valueLength: Int, char: Int): Int =
        if (result == 0 || (mode == Mode.ATTRIBUTE && (valueLength == 0 || isAttributeInvalidEnd(char)))) {
            0
        } else {
            emitNotTerminated()
        }

    private fun named(input: String, start: Int): Int = if (version == V6) namedV6(input, start) else namedV7(input, start)

    private fun namedV6(input: String, start: Int): Int {
        var offset = start
        var current = t(treeIndex)
        var valueLength = (current and 0xC000) shr 14
        while (offset < input.length) {
            val char = input[offset].code
            treeIndex = determineBranch(current, treeIndex + max(1, valueLength), char)
            if (treeIndex < 0) return failBranch(valueLength, char)
            current = t(treeIndex)
            valueLength = (current and 0xC000) shr 14
            if (valueLength != 0) {
                if (char == ';'.code) return emitNamedData(treeIndex, valueLength, consumed + excess)
                if (mode != Mode.STRICT) {
                    result = treeIndex
                    consumed += excess
                    excess = 0
                }
            }
            offset++
            excess++
        }
        return -1
    }

    private fun namedV7(input: String, start: Int): Int {
        var offset = start
        var current = t(treeIndex)
        var valueLength = (current and 0xC000) shr 14
        while (offset < input.length) {
            if (valueLength == 0 && (current and 0x2000) != 0) {
                val runLength = (current and 0x1F80) shr 7
                if (runConsumed == 0) {
                    val firstChar = current and 0x7F
                    if (input[offset].code != firstChar) return if (result == 0) 0 else emitNotTerminated()
                    offset++
                    excess++
                    runConsumed++
                }
                while (runConsumed < runLength) {
                    if (offset >= input.length) return -1
                    val charIndexInPacked = runConsumed - 1
                    val packedWord = t(treeIndex + 1 + (charIndexInPacked shr 1))
                    val expected = if (charIndexInPacked % 2 == 0) packedWord and 0xFF else (packedWord shr 8) and 0xFF
                    if (input[offset].code != expected) {
                        runConsumed = 0
                        return if (result == 0) 0 else emitNotTerminated()
                    }
                    offset++
                    excess++
                    runConsumed++
                }
                runConsumed = 0
                treeIndex += 1 + (runLength shr 1)
                current = t(treeIndex)
                valueLength = (current and 0xC000) shr 14
            }
            if (offset >= input.length) break
            val char = input[offset].code
            if (char == ';'.code && valueLength != 0 && (current and 0x2000) != 0) {
                return emitNamedData(treeIndex, valueLength, consumed + excess)
            }
            treeIndex = determineBranch(current, treeIndex + max(1, valueLength), char)
            if (treeIndex < 0) return failBranch(valueLength, char)
            current = t(treeIndex)
            valueLength = (current and 0xC000) shr 14
            if (valueLength != 0) {
                if (char == ';'.code) return emitNamedData(treeIndex, valueLength, consumed + excess)
                if (mode != Mode.STRICT && (current and 0x2000) == 0) {
                    result = treeIndex
                    consumed += excess
                    excess = 0
                }
            }
            offset++
            excess++
        }
        return -1
    }

    private fun emitNotTerminated(): Int {
        val valueLength = (t(result) and 0xC000) shr 14
        emitNamedData(result, valueLength, consumed)
        return consumed
    }

    private fun emitNamedData(at: Int, valueLength: Int, consumedCount: Int): Int {
        val mask = if (version == V7) 0x1FFF else 0x3FFF
        emit(if (valueLength == 1) t(at) and mask else t(at + 1), consumedCount)
        if (valueLength == 3) emit(t(at + 2), consumedCount)
        return consumedCount
    }

    /** Flushes a pending entity at end of input. */
    fun end(): Int = when (state) {
        NAMED -> if (result != 0 && (mode != Mode.ATTRIBUTE || result == treeIndex)) emitNotTerminated() else 0
        NUMERIC_DECIMAL -> emitNumeric(0, 2)
        NUMERIC_HEX -> emitNumeric(0, 3)
        else -> 0
    }

    companion object {
        const val V6 = 6
        const val V7 = 7
        private const val ENTITY_START = 0
        private const val NUMERIC_START = 1
        private const val NUMERIC_DECIMAL = 2
        private const val NUMERIC_HEX = 3
        private const val NAMED = 4

        private fun isAttributeInvalidEnd(c: Int): Boolean =
            c == '='.code || c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code

        private val C1 = intArrayOf(
            8364, 129, 8218, 402, 8222, 8230, 8224, 8225, 710, 8240, 352, 8249, 338, 141, 381, 143,
            144, 8216, 8217, 8220, 8221, 8226, 8211, 8212, 732, 8482, 353, 8250, 339, 157, 382, 376,
        )

        /** `replaceCodePoint`; a NaN code point makes `fromCodePoint` throw in JS, so it throws here too. */
        fun replaceCodePoint(cp: Double): Int {
            if (cp.isNaN()) throw IllegalArgumentException("Invalid code point NaN")
            if ((cp >= 55296 && cp <= 57343) || cp > 1114111) return 65533
            val i = cp.toInt()
            if (i == 0) return 65533
            if (i in 128..159) return C1[i - 128]
            return i
        }

        /** `decodeHTML(input, mode)` of the given entities version. */
        fun decode(input: String, version: Int, mode: Mode = Mode.LEGACY): String {
            if (input.indexOf('&') < 0) return input
            val out = StringBuilder(input.length)
            val decoder = EntityDecoder(version) { cp, _ -> Js.appendCodePoint(out, cp) }
            var lastIndex = 0
            var offset = 0
            while (true) {
                offset = input.indexOf('&', offset)
                if (offset < 0) break
                out.append(input, lastIndex, offset)
                decoder.startEntity(mode)
                val length = decoder.write(input, offset + 1)
                if (length < 0) {
                    lastIndex = offset + decoder.end()
                    break
                }
                lastIndex = offset + length
                offset = if (length == 0) lastIndex + 1 else lastIndex
            }
            out.append(input, lastIndex, input.length)
            return out.toString()
        }
    }
}
