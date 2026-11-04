/*
 * Diff Match and Patch
 * Copyright 2018 The diff-match-patch Authors.
 * https://github.com/google/diff-match-patch
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ecency.core.text

import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Kotlin port of the JavaScript diff-match-patch (npm `diff-match-patch` 1.0.5, the one the
 * React Native app uses). Every routine follows the JS source line by line so the output is
 * identical: strings are UTF-16 in both languages and every index here is a UTF-16 code unit
 * index, exactly like JS `String.length` / `substring` / `charAt`.
 *
 * JS quirks kept on purpose: `substring` clamps and swaps its arguments ([sub]), `indexOf` /
 * `lastIndexOf` clamp their start ([jsIndexOf], [jsLastIndexOf]), patch text is escaped with
 * `encodeURI` and read back with `decodeURI` ([encodeUri], [decodeUri]).
 *
 * [diffTimeout] keeps the JS semantics: a value <= 0 means "no deadline" AND disables the
 * half-match speedup (as in JS); a positive value is the number of seconds before the diff
 * bails out with a coarser result. Use a very large value for a deterministic diff that still
 * uses half-match (identical to the JS default as long as the deadline is never reached).
 */
class DiffMatchPatch {
    /** Number of seconds to map a diff before giving up (0 for infinity). */
    var diffTimeout: Double = 1.0
    /** Cost of an empty edit operation in terms of edit characters. */
    var diffEditCost: Int = 4
    /** At what point is no match declared (0.0 = perfection, 1.0 = very loose). */
    var matchThreshold: Double = 0.5
    /** How far to search for a match (0 = exact location, 1000+ = broad match). */
    var matchDistance: Int = 1000
    /** How closely the contents of a large delete have to match. */
    var patchDeleteThreshold: Double = 0.5
    /** Chunk size for context length. */
    var patchMargin: Int = 4
    /** The number of bits in an int. */
    var matchMaxBits: Int = 32

    private class Deadline(val mark: TimeMark?) {
        fun passed(): Boolean = mark != null && mark.hasPassedNow()
    }

    private fun newDeadline(): Deadline =
        if (diffTimeout <= 0) Deadline(null) else Deadline(TimeSource.Monotonic.markNow() + diffTimeout.seconds)

    //  DIFF FUNCTIONS

    /**
     * Find the differences between two texts. [checkLines] false skips the line-level
     * pre-pass (slower, more optimal). The returned list is mutable: cleanups work in place.
     */
    fun diffMain(text1: String, text2: String, checkLines: Boolean = true): MutableList<Diff> =
        diffMain(text1, text2, checkLines, newDeadline())

    private fun diffMain(text1In: String, text2In: String, checkLines: Boolean, deadline: Deadline): MutableList<Diff> {
        var text1 = text1In
        var text2 = text2In
        if (text1 == text2) {
            return if (text1.isNotEmpty()) mutableListOf(Diff(Operation.EQUAL, text1)) else mutableListOf()
        }

        var commonlength = diffCommonPrefix(text1, text2)
        val commonprefix = sub(text1, 0, commonlength)
        text1 = sub(text1, commonlength)
        text2 = sub(text2, commonlength)

        commonlength = diffCommonSuffix(text1, text2)
        val commonsuffix = sub(text1, text1.length - commonlength)
        text1 = sub(text1, 0, text1.length - commonlength)
        text2 = sub(text2, 0, text2.length - commonlength)

        val diffs = diffCompute(text1, text2, checkLines, deadline)

        if (commonprefix.isNotEmpty()) diffs.add(0, Diff(Operation.EQUAL, commonprefix))
        if (commonsuffix.isNotEmpty()) diffs.add(Diff(Operation.EQUAL, commonsuffix))
        diffCleanupMerge(diffs)
        return diffs
    }

    private fun diffCompute(text1: String, text2: String, checkLines: Boolean, deadline: Deadline): MutableList<Diff> {
        if (text1.isEmpty()) return mutableListOf(Diff(Operation.INSERT, text2))
        if (text2.isEmpty()) return mutableListOf(Diff(Operation.DELETE, text1))

        val longtext = if (text1.length > text2.length) text1 else text2
        val shorttext = if (text1.length > text2.length) text2 else text1
        val i = jsIndexOf(longtext, shorttext, 0)
        if (i != -1) {
            val diffs = mutableListOf(
                Diff(Operation.INSERT, sub(longtext, 0, i)),
                Diff(Operation.EQUAL, shorttext),
                Diff(Operation.INSERT, sub(longtext, i + shorttext.length)),
            )
            if (text1.length > text2.length) {
                diffs[0].operation = Operation.DELETE
                diffs[2].operation = Operation.DELETE
            }
            return diffs
        }

        if (shorttext.length == 1) {
            return mutableListOf(Diff(Operation.DELETE, text1), Diff(Operation.INSERT, text2))
        }

        val hm = diffHalfMatch(text1, text2)
        if (hm != null) {
            val diffsA = diffMain(hm[0], hm[2], checkLines, deadline)
            val diffsB = diffMain(hm[1], hm[3], checkLines, deadline)
            diffsA.add(Diff(Operation.EQUAL, hm[4]))
            diffsA.addAll(diffsB)
            return diffsA
        }

        if (checkLines && text1.length > 100 && text2.length > 100) {
            return diffLineMode(text1, text2, deadline)
        }

        return diffBisect(text1, text2, deadline)
    }

    private fun diffLineMode(text1In: String, text2In: String, deadline: Deadline): MutableList<Diff> {
        val a = diffLinesToChars(text1In, text2In)
        val diffs = diffMain(a.chars1, a.chars2, false, deadline)
        diffCharsToLines(diffs, a.lineArray)
        diffCleanupSemantic(diffs)

        diffs.add(Diff(Operation.EQUAL, ""))
        var pointer = 0
        var countDelete = 0
        var countInsert = 0
        val textDelete = StringBuilder()
        val textInsert = StringBuilder()
        while (pointer < diffs.size) {
            when (diffs[pointer].operation) {
                Operation.INSERT -> {
                    countInsert++
                    textInsert.append(diffs[pointer].text)
                }
                Operation.DELETE -> {
                    countDelete++
                    textDelete.append(diffs[pointer].text)
                }
                Operation.EQUAL -> {
                    if (countDelete >= 1 && countInsert >= 1) {
                        val from = pointer - countDelete - countInsert
                        repeat(countDelete + countInsert) { diffs.removeAt(from) }
                        pointer = from
                        val subDiff = diffMain(textDelete.toString(), textInsert.toString(), false, deadline)
                        diffs.addAll(pointer, subDiff)
                        pointer += subDiff.size
                    }
                    countInsert = 0
                    countDelete = 0
                    textDelete.clear()
                    textInsert.clear()
                }
            }
            pointer++
        }
        diffs.removeAt(diffs.size - 1)
        return diffs
    }

    private fun diffBisect(text1: String, text2: String, deadline: Deadline): MutableList<Diff> {
        val text1Length = text1.length
        val text2Length = text2.length
        val maxD = (text1Length + text2Length + 1) / 2
        val vOffset = maxD
        val vLength = 2 * maxD
        val v1 = IntArray(vLength) { -1 }
        val v2 = IntArray(vLength) { -1 }
        v1[vOffset + 1] = 0
        v2[vOffset + 1] = 0
        val delta = text1Length - text2Length
        val front = delta % 2 != 0
        var k1start = 0
        var k1end = 0
        var k2start = 0
        var k2end = 0
        for (d in 0 until maxD) {
            if (deadline.passed()) break

            var k1 = -d + k1start
            while (k1 <= d - k1end) {
                val k1Offset = vOffset + k1
                var x1 = if (k1 == -d || (k1 != d && v1[k1Offset - 1] < v1[k1Offset + 1])) {
                    v1[k1Offset + 1]
                } else {
                    v1[k1Offset - 1] + 1
                }
                var y1 = x1 - k1
                while (x1 < text1Length && y1 < text2Length && text1[x1] == text2[y1]) {
                    x1++
                    y1++
                }
                v1[k1Offset] = x1
                if (x1 > text1Length) {
                    k1end += 2
                } else if (y1 > text2Length) {
                    k1start += 2
                } else if (front) {
                    val k2Offset = vOffset + delta - k1
                    if (k2Offset in 0 until vLength && v2[k2Offset] != -1) {
                        val x2 = text1Length - v2[k2Offset]
                        if (x1 >= x2) return diffBisectSplit(text1, text2, x1, y1, deadline)
                    }
                }
                k1 += 2
            }

            var k2 = -d + k2start
            while (k2 <= d - k2end) {
                val k2Offset = vOffset + k2
                var x2 = if (k2 == -d || (k2 != d && v2[k2Offset - 1] < v2[k2Offset + 1])) {
                    v2[k2Offset + 1]
                } else {
                    v2[k2Offset - 1] + 1
                }
                var y2 = x2 - k2
                while (x2 < text1Length && y2 < text2Length &&
                    text1[text1Length - x2 - 1] == text2[text2Length - y2 - 1]
                ) {
                    x2++
                    y2++
                }
                v2[k2Offset] = x2
                if (x2 > text1Length) {
                    k2end += 2
                } else if (y2 > text2Length) {
                    k2start += 2
                } else if (!front) {
                    val k1Offset = vOffset + delta - k2
                    if (k1Offset in 0 until vLength && v1[k1Offset] != -1) {
                        val x1 = v1[k1Offset]
                        val y1 = vOffset + x1 - k1Offset
                        x2 = text1Length - x2
                        if (x1 >= x2) return diffBisectSplit(text1, text2, x1, y1, deadline)
                    }
                }
                k2 += 2
            }
        }
        return mutableListOf(Diff(Operation.DELETE, text1), Diff(Operation.INSERT, text2))
    }

    private fun diffBisectSplit(text1: String, text2: String, x: Int, y: Int, deadline: Deadline): MutableList<Diff> {
        val diffs = diffMain(sub(text1, 0, x), sub(text2, 0, y), false, deadline)
        val diffsb = diffMain(sub(text1, x), sub(text2, y), false, deadline)
        diffs.addAll(diffsb)
        return diffs
    }

    private class LinesToChars(val chars1: String, val chars2: String, val lineArray: List<String>)

    private fun diffLinesToChars(text1: String, text2: String): LinesToChars {
        val lineArray = mutableListOf("")
        val lineHash = HashMap<String, Int>()

        fun munge(text: String, maxLines: Int): String {
            val chars = StringBuilder()
            var lineStart = 0
            var lineEnd = -1
            while (lineEnd < text.length - 1) {
                lineEnd = jsIndexOf(text, "\n", lineStart)
                if (lineEnd == -1) lineEnd = text.length - 1
                var line = sub(text, lineStart, lineEnd + 1)
                val known = lineHash[line]
                if (known != null) {
                    chars.append(known.toChar())
                } else {
                    if (lineArray.size == maxLines) {
                        line = sub(text, lineStart)
                        lineEnd = text.length
                    }
                    chars.append(lineArray.size.toChar())
                    lineHash[line] = lineArray.size
                    lineArray.add(line)
                }
                lineStart = lineEnd + 1
            }
            return chars.toString()
        }

        val chars1 = munge(text1, 40000)
        val chars2 = munge(text2, 65535)
        return LinesToChars(chars1, chars2, lineArray)
    }

    private fun diffCharsToLines(diffs: List<Diff>, lineArray: List<String>) {
        for (diff in diffs) {
            val text = StringBuilder()
            for (c in diff.text) text.append(lineArray[c.code])
            diff.text = text.toString()
        }
    }

    /** Number of UTF-16 units common to the start of both strings. */
    fun diffCommonPrefix(text1: String, text2: String): Int {
        val n = minOf(text1.length, text2.length)
        for (i in 0 until n) if (text1[i] != text2[i]) return i
        return n
    }

    /** Number of UTF-16 units common to the end of both strings. */
    fun diffCommonSuffix(text1: String, text2: String): Int {
        val l1 = text1.length
        val l2 = text2.length
        val n = minOf(l1, l2)
        for (i in 1..n) if (text1[l1 - i] != text2[l2 - i]) return i - 1
        return n
    }

    private fun diffCommonOverlap(text1In: String, text2In: String): Int {
        var text1 = text1In
        var text2 = text2In
        val text1Length = text1.length
        val text2Length = text2.length
        if (text1Length == 0 || text2Length == 0) return 0
        if (text1Length > text2Length) {
            text1 = sub(text1, text1Length - text2Length)
        } else if (text1Length < text2Length) {
            text2 = sub(text2, 0, text1Length)
        }
        val textLength = minOf(text1Length, text2Length)
        if (text1 == text2) return textLength

        var best = 0
        var length = 1
        while (true) {
            val pattern = sub(text1, textLength - length)
            val found = jsIndexOf(text2, pattern, 0)
            if (found == -1) return best
            length += found
            if (found == 0 || sub(text1, textLength - length) == sub(text2, 0, length)) {
                best = length
                length++
            }
        }
    }

    private fun diffHalfMatch(text1: String, text2: String): Array<String>? {
        if (diffTimeout <= 0) return null
        val longtext = if (text1.length > text2.length) text1 else text2
        val shorttext = if (text1.length > text2.length) text2 else text1
        if (longtext.length < 4 || shorttext.length * 2 < longtext.length) return null

        fun halfMatchI(longtext: String, shorttext: String, i: Int): Array<String>? {
            val seed = sub(longtext, i, i + longtext.length / 4)
            var j = -1
            var bestCommon = ""
            var bestLongtextA = ""
            var bestLongtextB = ""
            var bestShorttextA = ""
            var bestShorttextB = ""
            while (true) {
                j = jsIndexOf(shorttext, seed, j + 1)
                if (j == -1) break
                val prefixLength = diffCommonPrefix(sub(longtext, i), sub(shorttext, j))
                val suffixLength = diffCommonSuffix(sub(longtext, 0, i), sub(shorttext, 0, j))
                if (bestCommon.length < suffixLength + prefixLength) {
                    bestCommon = sub(shorttext, j - suffixLength, j) + sub(shorttext, j, j + prefixLength)
                    bestLongtextA = sub(longtext, 0, i - suffixLength)
                    bestLongtextB = sub(longtext, i + prefixLength)
                    bestShorttextA = sub(shorttext, 0, j - suffixLength)
                    bestShorttextB = sub(shorttext, j + prefixLength)
                }
            }
            return if (bestCommon.length * 2 >= longtext.length) {
                arrayOf(bestLongtextA, bestLongtextB, bestShorttextA, bestShorttextB, bestCommon)
            } else {
                null
            }
        }

        val hm1 = halfMatchI(longtext, shorttext, (longtext.length + 3) / 4)
        val hm2 = halfMatchI(longtext, shorttext, (longtext.length + 1) / 2)
        val hm = when {
            hm1 == null && hm2 == null -> return null
            hm2 == null -> hm1!!
            hm1 == null -> hm2
            else -> if (hm1[4].length > hm2[4].length) hm1 else hm2
        }

        return if (text1.length > text2.length) {
            arrayOf(hm[0], hm[1], hm[2], hm[3], hm[4])
        } else {
            arrayOf(hm[2], hm[3], hm[0], hm[1], hm[4])
        }
    }

    /** Reduce the number of edits by eliminating semantically trivial equalities. In place. */
    fun diffCleanupSemantic(diffs: MutableList<Diff>) {
        var changes = false
        // JS lets equalitiesLength drop below zero and then reads/writes equalities[-1] as a plain
        // property, so the stack is a map keyed by the (possibly negative) index.
        val eq = HashMap<Int, Int>()
        var equalitiesLength = 0
        var lastEquality: String? = null
        var pointer = 0
        var lengthInsertions1 = 0
        var lengthDeletions1 = 0
        var lengthInsertions2 = 0
        var lengthDeletions2 = 0
        while (pointer < diffs.size) {
            if (diffs[pointer].operation == Operation.EQUAL) {
                eq[equalitiesLength++] = pointer
                lengthInsertions1 = lengthInsertions2
                lengthDeletions1 = lengthDeletions2
                lengthInsertions2 = 0
                lengthDeletions2 = 0
                lastEquality = diffs[pointer].text
            } else {
                if (diffs[pointer].operation == Operation.INSERT) {
                    lengthInsertions2 += diffs[pointer].text.length
                } else {
                    lengthDeletions2 += diffs[pointer].text.length
                }
                if (!lastEquality.isNullOrEmpty() &&
                    lastEquality.length <= maxOf(lengthInsertions1, lengthDeletions1) &&
                    lastEquality.length <= maxOf(lengthInsertions2, lengthDeletions2)
                ) {
                    val at = eq.getValue(equalitiesLength - 1)
                    diffs.add(at, Diff(Operation.DELETE, lastEquality))
                    diffs[at + 1].operation = Operation.INSERT
                    equalitiesLength--
                    equalitiesLength--
                    pointer = if (equalitiesLength > 0) eq.getValue(equalitiesLength - 1) else -1
                    lengthInsertions1 = 0
                    lengthDeletions1 = 0
                    lengthInsertions2 = 0
                    lengthDeletions2 = 0
                    lastEquality = null
                    changes = true
                }
            }
            pointer++
        }

        if (changes) diffCleanupMerge(diffs)
        diffCleanupSemanticLossless(diffs)

        pointer = 1
        while (pointer < diffs.size) {
            if (diffs[pointer - 1].operation == Operation.DELETE && diffs[pointer].operation == Operation.INSERT) {
                val deletion = diffs[pointer - 1].text
                val insertion = diffs[pointer].text
                val overlapLength1 = diffCommonOverlap(deletion, insertion)
                val overlapLength2 = diffCommonOverlap(insertion, deletion)
                if (overlapLength1 >= overlapLength2) {
                    if (overlapLength1 >= deletion.length / 2.0 || overlapLength1 >= insertion.length / 2.0) {
                        diffs.add(pointer, Diff(Operation.EQUAL, sub(insertion, 0, overlapLength1)))
                        diffs[pointer - 1].text = sub(deletion, 0, deletion.length - overlapLength1)
                        diffs[pointer + 1].text = sub(insertion, overlapLength1)
                        pointer++
                    }
                } else {
                    if (overlapLength2 >= deletion.length / 2.0 || overlapLength2 >= insertion.length / 2.0) {
                        diffs.add(pointer, Diff(Operation.EQUAL, sub(deletion, 0, overlapLength2)))
                        diffs[pointer - 1].operation = Operation.INSERT
                        diffs[pointer - 1].text = sub(insertion, 0, insertion.length - overlapLength2)
                        diffs[pointer + 1].operation = Operation.DELETE
                        diffs[pointer + 1].text = sub(deletion, overlapLength2)
                        pointer++
                    }
                }
                pointer++
            }
            pointer++
        }
    }

    /** Shift single edits sideways to align them to word or line boundaries. In place. */
    fun diffCleanupSemanticLossless(diffs: MutableList<Diff>) {
        var pointer = 1
        while (pointer < diffs.size - 1) {
            if (diffs[pointer - 1].operation == Operation.EQUAL && diffs[pointer + 1].operation == Operation.EQUAL) {
                var equality1 = diffs[pointer - 1].text
                var edit = diffs[pointer].text
                var equality2 = diffs[pointer + 1].text

                val commonOffset = diffCommonSuffix(equality1, edit)
                if (commonOffset != 0) {
                    val commonString = sub(edit, edit.length - commonOffset)
                    equality1 = sub(equality1, 0, equality1.length - commonOffset)
                    edit = commonString + sub(edit, 0, edit.length - commonOffset)
                    equality2 = commonString + equality2
                }

                var bestEquality1 = equality1
                var bestEdit = edit
                var bestEquality2 = equality2
                var bestScore = semanticScore(equality1, edit) + semanticScore(edit, equality2)
                while (charAt(edit, 0) == charAt(equality2, 0)) {
                    equality1 += charAt(edit, 0)
                    edit = sub(edit, 1) + charAt(equality2, 0)
                    equality2 = sub(equality2, 1)
                    val score = semanticScore(equality1, edit) + semanticScore(edit, equality2)
                    if (score >= bestScore) {
                        bestScore = score
                        bestEquality1 = equality1
                        bestEdit = edit
                        bestEquality2 = equality2
                    }
                }

                if (diffs[pointer - 1].text != bestEquality1) {
                    if (bestEquality1.isNotEmpty()) {
                        diffs[pointer - 1].text = bestEquality1
                    } else {
                        diffs.removeAt(pointer - 1)
                        pointer--
                    }
                    diffs[pointer].text = bestEdit
                    if (bestEquality2.isNotEmpty()) {
                        diffs[pointer + 1].text = bestEquality2
                    } else {
                        diffs.removeAt(pointer + 1)
                        pointer--
                    }
                }
            }
            pointer++
        }
    }

    /**
     * Boundary score (6 best, 0 worst) between [one] and [two], with the exact JS regexes:
     * /[^a-zA-Z0-9]/, /\s/, /[\r\n]/, /\n\r?\n$/ and /^\r?\n\r?\n/.
     */
    private fun semanticScore(one: String, two: String): Int {
        if (one.isEmpty() || two.isEmpty()) return 6
        val char1 = one[one.length - 1]
        val char2 = two[0]
        val nonAlphaNumeric1 = !isAsciiAlphaNumeric(char1)
        val nonAlphaNumeric2 = !isAsciiAlphaNumeric(char2)
        val whitespace1 = nonAlphaNumeric1 && isJsWhitespace(char1)
        val whitespace2 = nonAlphaNumeric2 && isJsWhitespace(char2)
        val lineBreak1 = whitespace1 && (char1 == '\r' || char1 == '\n')
        val lineBreak2 = whitespace2 && (char2 == '\r' || char2 == '\n')
        val blankLine1 = lineBreak1 && (one.endsWith("\n\n") || one.endsWith("\n\r\n"))
        val blankLine2 = lineBreak2 &&
            (two.startsWith("\n\n") || two.startsWith("\r\n\n") || two.startsWith("\n\r\n") || two.startsWith("\r\n\r\n"))
        return when {
            blankLine1 || blankLine2 -> 5
            lineBreak1 || lineBreak2 -> 4
            nonAlphaNumeric1 && !whitespace1 && whitespace2 -> 3
            whitespace1 || whitespace2 -> 2
            nonAlphaNumeric1 || nonAlphaNumeric2 -> 1
            else -> 0
        }
    }

    private fun isAsciiAlphaNumeric(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    /** JS `\s`: WhiteSpace plus LineTerminator code units. */
    private fun isJsWhitespace(c: Char): Boolean = when (c.code) {
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
        in 0x2000..0x200A -> true
        else -> false
    }

    /** Reduce the number of edits by eliminating operationally trivial equalities. In place. */
    fun diffCleanupEfficiency(diffs: MutableList<Diff>) {
        var changes = false
        val equalities = HashMap<Int, Int>() // see diffCleanupSemantic: indices may go negative
        var equalitiesLength = 0
        var lastEquality: String? = null
        var pointer = 0
        var preIns = false
        var preDel = false
        var postIns = false
        var postDel = false
        while (pointer < diffs.size) {
            if (diffs[pointer].operation == Operation.EQUAL) {
                if (diffs[pointer].text.length < diffEditCost && (postIns || postDel)) {
                    equalities[equalitiesLength++] = pointer
                    preIns = postIns
                    preDel = postDel
                    lastEquality = diffs[pointer].text
                } else {
                    equalitiesLength = 0
                    lastEquality = null
                }
                postIns = false
                postDel = false
            } else {
                if (diffs[pointer].operation == Operation.DELETE) postDel = true else postIns = true
                val count = (if (preIns) 1 else 0) + (if (preDel) 1 else 0) +
                    (if (postIns) 1 else 0) + (if (postDel) 1 else 0)
                if (!lastEquality.isNullOrEmpty() &&
                    ((preIns && preDel && postIns && postDel) ||
                        (lastEquality.length < diffEditCost / 2.0 && count == 3))
                ) {
                    val at = equalities.getValue(equalitiesLength - 1)
                    diffs.add(at, Diff(Operation.DELETE, lastEquality))
                    diffs[at + 1].operation = Operation.INSERT
                    equalitiesLength--
                    lastEquality = null
                    if (preIns && preDel) {
                        postIns = true
                        postDel = true
                        equalitiesLength = 0
                    } else {
                        equalitiesLength--
                        pointer = if (equalitiesLength > 0) equalities.getValue(equalitiesLength - 1) else -1
                        postIns = false
                        postDel = false
                    }
                    changes = true
                }
            }
            pointer++
        }
        if (changes) diffCleanupMerge(diffs)
    }

    /** Reorder and merge like edit sections. Merge equalities. In place. */
    fun diffCleanupMerge(diffs: MutableList<Diff>) {
        diffs.add(Diff(Operation.EQUAL, ""))
        var pointer = 0
        var countDelete = 0
        var countInsert = 0
        var textDelete = ""
        var textInsert = ""
        var commonlength: Int
        while (pointer < diffs.size) {
            when (diffs[pointer].operation) {
                Operation.INSERT -> {
                    countInsert++
                    textInsert += diffs[pointer].text
                    pointer++
                }
                Operation.DELETE -> {
                    countDelete++
                    textDelete += diffs[pointer].text
                    pointer++
                }
                Operation.EQUAL -> {
                    if (countDelete + countInsert > 1) {
                        if (countDelete != 0 && countInsert != 0) {
                            commonlength = diffCommonPrefix(textInsert, textDelete)
                            if (commonlength != 0) {
                                val before = pointer - countDelete - countInsert
                                if (before > 0 && diffs[before - 1].operation == Operation.EQUAL) {
                                    diffs[before - 1].text += sub(textInsert, 0, commonlength)
                                } else {
                                    diffs.add(0, Diff(Operation.EQUAL, sub(textInsert, 0, commonlength)))
                                    pointer++
                                }
                                textInsert = sub(textInsert, commonlength)
                                textDelete = sub(textDelete, commonlength)
                            }
                            commonlength = diffCommonSuffix(textInsert, textDelete)
                            if (commonlength != 0) {
                                diffs[pointer].text = sub(textInsert, textInsert.length - commonlength) + diffs[pointer].text
                                textInsert = sub(textInsert, 0, textInsert.length - commonlength)
                                textDelete = sub(textDelete, 0, textDelete.length - commonlength)
                            }
                        }
                        pointer -= countDelete + countInsert
                        repeat(countDelete + countInsert) { diffs.removeAt(pointer) }
                        if (textDelete.isNotEmpty()) {
                            diffs.add(pointer, Diff(Operation.DELETE, textDelete))
                            pointer++
                        }
                        if (textInsert.isNotEmpty()) {
                            diffs.add(pointer, Diff(Operation.INSERT, textInsert))
                            pointer++
                        }
                        pointer++
                    } else if (pointer != 0 && diffs[pointer - 1].operation == Operation.EQUAL) {
                        diffs[pointer - 1].text += diffs[pointer].text
                        diffs.removeAt(pointer)
                    } else {
                        pointer++
                    }
                    countInsert = 0
                    countDelete = 0
                    textDelete = ""
                    textInsert = ""
                }
            }
        }
        if (diffs[diffs.size - 1].text.isEmpty()) diffs.removeAt(diffs.size - 1)

        var changes = false
        pointer = 1
        while (pointer < diffs.size - 1) {
            if (diffs[pointer - 1].operation == Operation.EQUAL && diffs[pointer + 1].operation == Operation.EQUAL) {
                val prev = diffs[pointer - 1].text
                val cur = diffs[pointer].text
                val next = diffs[pointer + 1].text
                if (sub(cur, cur.length - prev.length) == prev) {
                    diffs[pointer].text = prev + sub(cur, 0, cur.length - prev.length)
                    diffs[pointer + 1].text = prev + next
                    diffs.removeAt(pointer - 1)
                    changes = true
                } else if (sub(cur, 0, next.length) == next) {
                    diffs[pointer - 1].text += next
                    diffs[pointer].text = sub(cur, next.length) + next
                    diffs.removeAt(pointer + 1)
                    changes = true
                }
            }
            pointer++
        }
        if (changes) diffCleanupMerge(diffs)
    }

    /** Location in text2 equivalent to [loc] in text1. */
    fun diffXIndex(diffs: List<Diff>, loc: Int): Int {
        var chars1 = 0
        var chars2 = 0
        var lastChars1 = 0
        var lastChars2 = 0
        var x = 0
        while (x < diffs.size) {
            if (diffs[x].operation != Operation.INSERT) chars1 += diffs[x].text.length
            if (diffs[x].operation != Operation.DELETE) chars2 += diffs[x].text.length
            if (chars1 > loc) break
            lastChars1 = chars1
            lastChars2 = chars2
            x++
        }
        if (diffs.size != x && diffs[x].operation == Operation.DELETE) return lastChars2
        return lastChars2 + (loc - lastChars1)
    }

    /** HTML report of a diff, byte for byte the JS `diff_prettyHtml`. */
    fun diffPrettyHtml(diffs: List<Diff>): String {
        val html = StringBuilder()
        for (diff in diffs) {
            val text = diff.text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\n", "&para;<br>")
            when (diff.operation) {
                Operation.INSERT -> html.append("<ins style=\"background:#e6ffe6;\">").append(text).append("</ins>")
                Operation.DELETE -> html.append("<del style=\"background:#ffe6e6;\">").append(text).append("</del>")
                Operation.EQUAL -> html.append("<span>").append(text).append("</span>")
            }
        }
        return html.toString()
    }

    /** Source text (all equalities and deletions). */
    fun diffText1(diffs: List<Diff>): String {
        val text = StringBuilder()
        for (d in diffs) if (d.operation != Operation.INSERT) text.append(d.text)
        return text.toString()
    }

    /** Destination text (all equalities and insertions). */
    fun diffText2(diffs: List<Diff>): String {
        val text = StringBuilder()
        for (d in diffs) if (d.operation != Operation.DELETE) text.append(d.text)
        return text.toString()
    }

    /** Levenshtein distance of a diff in UTF-16 units. */
    fun diffLevenshtein(diffs: List<Diff>): Int {
        var levenshtein = 0
        var insertions = 0
        var deletions = 0
        for (d in diffs) {
            when (d.operation) {
                Operation.INSERT -> insertions += d.text.length
                Operation.DELETE -> deletions += d.text.length
                Operation.EQUAL -> {
                    levenshtein += maxOf(insertions, deletions)
                    insertions = 0
                    deletions = 0
                }
            }
        }
        return levenshtein + maxOf(insertions, deletions)
    }

    //  MATCH FUNCTIONS

    /** Best instance of [pattern] in [text] near [loc], or -1. */
    fun matchMain(text: String, pattern: String, loc: Int): Int {
        val l = maxOf(0, minOf(loc, text.length))
        return when {
            text == pattern -> 0
            text.isEmpty() -> -1
            sub(text, l, l + pattern.length) == pattern -> l
            else -> matchBitap(text, pattern, l)
        }
    }

    private fun matchBitap(text: String, pattern: String, loc: Int): Int {
        if (pattern.length > matchMaxBits) throw IllegalArgumentException("Pattern too long for this browser.")

        val s = matchAlphabet(pattern)

        fun score(e: Int, x: Int): Double {
            val accuracy = e.toDouble() / pattern.length
            val proximity = kotlin.math.abs(loc - x)
            if (matchDistance == 0) return if (proximity != 0) 1.0 else accuracy
            return accuracy + (proximity.toDouble() / matchDistance)
        }

        var scoreThreshold = matchThreshold
        var bestLoc = jsIndexOf(text, pattern, loc)
        if (bestLoc != -1) {
            scoreThreshold = minOf(score(0, bestLoc), scoreThreshold)
            bestLoc = jsLastIndexOf(text, pattern, loc + pattern.length)
            if (bestLoc != -1) scoreThreshold = minOf(score(0, bestLoc), scoreThreshold)
        }

        val matchmask = 1 shl (pattern.length - 1)
        bestLoc = -1

        var binMin: Int
        var binMid: Int
        var binMax = pattern.length + text.length
        // JS reads holes of the previous row as undefined, which bitwise ops treat as 0.
        var lastRd = IntArray(0)
        for (d in 0 until pattern.length) {
            binMin = 0
            binMid = binMax
            while (binMin < binMid) {
                if (score(d, loc + binMid) <= scoreThreshold) binMin = binMid else binMax = binMid
                binMid = (binMax - binMin) / 2 + binMin
            }
            binMax = binMid
            var start = maxOf(1, loc - binMid + 1)
            val finish = minOf(loc + binMid, text.length) + pattern.length

            val rd = IntArray(finish + 2)
            rd[finish + 1] = (1 shl d) - 1
            var j = finish
            while (j >= start) {
                val charMatch = if (j - 1 < text.length) s[text[j - 1]] ?: 0 else 0
                if (d == 0) {
                    rd[j] = ((rd[j + 1] shl 1) or 1) and charMatch
                } else {
                    val lj = lastRd.getOrElse(j) { 0 }
                    val lj1 = lastRd.getOrElse(j + 1) { 0 }
                    rd[j] = (((rd[j + 1] shl 1) or 1) and charMatch) or (((lj1 or lj) shl 1) or 1) or lj1
                }
                if ((rd[j] and matchmask) != 0) {
                    val sc = score(d, j - 1)
                    if (sc <= scoreThreshold) {
                        scoreThreshold = sc
                        bestLoc = j - 1
                        if (bestLoc > loc) {
                            start = maxOf(1, 2 * loc - bestLoc)
                        } else {
                            break
                        }
                    }
                }
                j--
            }
            if (score(d + 1, loc) > scoreThreshold) break
            lastRd = rd
        }
        return bestLoc
    }

    private fun matchAlphabet(pattern: String): Map<Char, Int> {
        val s = HashMap<Char, Int>()
        for (c in pattern) s[c] = 0
        for (i in pattern.indices) {
            val c = pattern[i]
            s[c] = s.getValue(c) or (1 shl (pattern.length - i - 1))
        }
        return s
    }

    //  PATCH FUNCTIONS

    private fun patchAddContext(patch: Patch, text: String) {
        if (text.isEmpty()) return
        var pattern = sub(text, patch.start2, patch.start2 + patch.length1)
        var padding = 0

        while (jsIndexOf(text, pattern, 0) != jsLastIndexOf(text, pattern, Int.MAX_VALUE) &&
            pattern.length < matchMaxBits - patchMargin - patchMargin
        ) {
            padding += patchMargin
            pattern = sub(text, patch.start2 - padding, patch.start2 + patch.length1 + padding)
        }
        padding += patchMargin

        val prefix = sub(text, patch.start2 - padding, patch.start2)
        if (prefix.isNotEmpty()) patch.diffs.add(0, Diff(Operation.EQUAL, prefix))
        val suffix = sub(text, patch.start2 + patch.length1, patch.start2 + patch.length1 + padding)
        if (suffix.isNotEmpty()) patch.diffs.add(Diff(Operation.EQUAL, suffix))

        patch.start1 -= prefix.length
        patch.start2 -= prefix.length
        patch.length1 += prefix.length + suffix.length
        patch.length2 += prefix.length + suffix.length
    }

    /** Patches turning [text1] into [text2] (JS `patch_make(text1, text2)`). */
    fun patchMake(text1: String, text2: String): MutableList<Patch> {
        val diffs = diffMain(text1, text2, true)
        if (diffs.size > 2) {
            diffCleanupSemantic(diffs)
            diffCleanupEfficiency(diffs)
        }
        return patchMake(text1, diffs)
    }

    /** Patches from a source text and its diff (JS `patch_make(text1, diffs)`). */
    fun patchMake(text1: String, diffs: List<Diff>): MutableList<Patch> {
        val patches = mutableListOf<Patch>()
        if (diffs.isEmpty()) return patches
        var patch = Patch()
        var patchDiffLength = 0
        var charCount1 = 0
        var charCount2 = 0
        var prepatchText = text1
        var postpatchText = text1
        for (x in diffs.indices) {
            val diffType = diffs[x].operation
            val diffText = diffs[x].text

            if (patchDiffLength == 0 && diffType != Operation.EQUAL) {
                patch.start1 = charCount1
                patch.start2 = charCount2
            }

            when (diffType) {
                Operation.INSERT -> {
                    patch.diffs.add(diffs[x]); patchDiffLength++
                    patch.length2 += diffText.length
                    postpatchText = sub(postpatchText, 0, charCount2) + diffText + sub(postpatchText, charCount2)
                }
                Operation.DELETE -> {
                    patch.length1 += diffText.length
                    patch.diffs.add(diffs[x]); patchDiffLength++
                    postpatchText = sub(postpatchText, 0, charCount2) + sub(postpatchText, charCount2 + diffText.length)
                }
                Operation.EQUAL -> {
                    if (diffText.length <= 2 * patchMargin && patchDiffLength != 0 && diffs.size != x + 1) {
                        patch.diffs.add(diffs[x]); patchDiffLength++
                        patch.length1 += diffText.length
                        patch.length2 += diffText.length
                    } else if (diffText.length >= 2 * patchMargin) {
                        if (patchDiffLength != 0) {
                            patchAddContext(patch, prepatchText)
                            patches.add(patch)
                            patch = Patch()
                            patchDiffLength = 0
                            prepatchText = postpatchText
                            charCount1 = charCount2
                        }
                    }
                }
            }

            if (diffType != Operation.INSERT) charCount1 += diffText.length
            if (diffType != Operation.DELETE) charCount2 += diffText.length
        }
        if (patchDiffLength != 0) {
            patchAddContext(patch, prepatchText)
            patches.add(patch)
        }
        return patches
    }

    /** Deep copy of a patch list. */
    fun patchDeepCopy(patches: List<Patch>): MutableList<Patch> = patches.mapTo(mutableListOf()) { p ->
        Patch().also { c ->
            p.diffs.forEach { c.diffs.add(Diff(it.operation, it.text)) }
            c.start1 = p.start1
            c.start2 = p.start2
            c.length1 = p.length1
            c.length2 = p.length2
        }
    }

    /**
     * Apply [patches] onto [text], fuzzily when the text drifted. Returns the new text and,
     * per patch (after splitting oversized ones), whether it applied.
     */
    fun patchApply(patches: List<Patch>, text: String): Pair<String, List<Boolean>> {
        if (patches.isEmpty()) return Pair(text, emptyList())

        val ps = patchDeepCopy(patches)
        val nullPadding = patchAddPadding(ps)
        var t = nullPadding + text + nullPadding
        patchSplitMax(ps)

        var delta = 0
        val results = ArrayList<Boolean>(ps.size)
        for (x in ps.indices) {
            val expectedLoc = ps[x].start2 + delta
            val text1 = diffText1(ps[x].diffs)
            var startLoc: Int
            var endLoc = -1
            if (text1.length > matchMaxBits) {
                startLoc = matchMain(t, sub(text1, 0, matchMaxBits), expectedLoc)
                if (startLoc != -1) {
                    endLoc = matchMain(t, sub(text1, text1.length - matchMaxBits), expectedLoc + text1.length - matchMaxBits)
                    if (endLoc == -1 || startLoc >= endLoc) startLoc = -1
                }
            } else {
                startLoc = matchMain(t, text1, expectedLoc)
            }
            if (startLoc == -1) {
                results.add(false)
                delta -= ps[x].length2 - ps[x].length1
            } else {
                results.add(true)
                delta = startLoc - expectedLoc
                val text2 = if (endLoc == -1) {
                    sub(t, startLoc, startLoc + text1.length)
                } else {
                    sub(t, startLoc, endLoc + matchMaxBits)
                }
                if (text1 == text2) {
                    t = sub(t, 0, startLoc) + diffText2(ps[x].diffs) + sub(t, startLoc + text1.length)
                } else {
                    val diffs = diffMain(text1, text2, false)
                    if (text1.length > matchMaxBits &&
                        diffLevenshtein(diffs).toDouble() / text1.length > patchDeleteThreshold
                    ) {
                        results[x] = false
                    } else {
                        diffCleanupSemanticLossless(diffs)
                        var index1 = 0
                        var index2 = 0
                        for (mod in ps[x].diffs) {
                            if (mod.operation != Operation.EQUAL) index2 = diffXIndex(diffs, index1)
                            if (mod.operation == Operation.INSERT) {
                                t = sub(t, 0, startLoc + index2) + mod.text + sub(t, startLoc + index2)
                            } else if (mod.operation == Operation.DELETE) {
                                t = sub(t, 0, startLoc + index2) +
                                    sub(t, startLoc + diffXIndex(diffs, index1 + mod.text.length))
                            }
                            if (mod.operation != Operation.DELETE) index1 += mod.text.length
                        }
                    }
                }
            }
        }
        t = sub(t, nullPadding.length, t.length - nullPadding.length)
        return Pair(t, results)
    }

    /** Pads the first and last patch so edges can match. Returns the padding string. */
    fun patchAddPadding(patches: MutableList<Patch>): String {
        val paddingLength = patchMargin
        val nullPadding = buildString { for (x in 1..paddingLength) append(x.toChar()) }

        for (p in patches) {
            p.start1 += paddingLength
            p.start2 += paddingLength
        }

        var patch = patches[0]
        var diffs = patch.diffs
        if (diffs.isEmpty() || diffs[0].operation != Operation.EQUAL) {
            diffs.add(0, Diff(Operation.EQUAL, nullPadding))
            patch.start1 -= paddingLength
            patch.start2 -= paddingLength
            patch.length1 += paddingLength
            patch.length2 += paddingLength
        } else if (paddingLength > diffs[0].text.length) {
            val extraLength = paddingLength - diffs[0].text.length
            diffs[0].text = sub(nullPadding, diffs[0].text.length) + diffs[0].text
            patch.start1 -= extraLength
            patch.start2 -= extraLength
            patch.length1 += extraLength
            patch.length2 += extraLength
        }

        patch = patches[patches.size - 1]
        diffs = patch.diffs
        if (diffs.isEmpty() || diffs[diffs.size - 1].operation != Operation.EQUAL) {
            diffs.add(Diff(Operation.EQUAL, nullPadding))
            patch.length1 += paddingLength
            patch.length2 += paddingLength
        } else if (paddingLength > diffs[diffs.size - 1].text.length) {
            val extraLength = paddingLength - diffs[diffs.size - 1].text.length
            diffs[diffs.size - 1].text += sub(nullPadding, 0, extraLength)
            patch.length1 += extraLength
            patch.length2 += extraLength
        }
        return nullPadding
    }

    /** Splits patches longer than [matchMaxBits]. In place. */
    fun patchSplitMax(patches: MutableList<Patch>) {
        val patchSize = matchMaxBits
        var x = 0
        while (x < patches.size) {
            if (patches[x].length1 <= patchSize) {
                x++
                continue
            }
            val bigpatch = patches.removeAt(x)
            x--
            var start1 = bigpatch.start1
            var start2 = bigpatch.start2
            var precontext = ""
            while (bigpatch.diffs.isNotEmpty()) {
                val patch = Patch()
                var empty = true
                patch.start1 = start1 - precontext.length
                patch.start2 = start2 - precontext.length
                if (precontext.isNotEmpty()) {
                    patch.length1 = precontext.length
                    patch.length2 = precontext.length
                    patch.diffs.add(Diff(Operation.EQUAL, precontext))
                }
                while (bigpatch.diffs.isNotEmpty() && patch.length1 < patchSize - patchMargin) {
                    val diffType = bigpatch.diffs[0].operation
                    var diffText = bigpatch.diffs[0].text
                    if (diffType == Operation.INSERT) {
                        patch.length2 += diffText.length
                        start2 += diffText.length
                        patch.diffs.add(bigpatch.diffs.removeAt(0))
                        empty = false
                    } else if (diffType == Operation.DELETE && patch.diffs.size == 1 &&
                        patch.diffs[0].operation == Operation.EQUAL && diffText.length > 2 * patchSize
                    ) {
                        patch.length1 += diffText.length
                        start1 += diffText.length
                        empty = false
                        patch.diffs.add(Diff(diffType, diffText))
                        bigpatch.diffs.removeAt(0)
                    } else {
                        diffText = sub(diffText, 0, patchSize - patch.length1 - patchMargin)
                        patch.length1 += diffText.length
                        start1 += diffText.length
                        if (diffType == Operation.EQUAL) {
                            patch.length2 += diffText.length
                            start2 += diffText.length
                        } else {
                            empty = false
                        }
                        patch.diffs.add(Diff(diffType, diffText))
                        if (diffText == bigpatch.diffs[0].text) {
                            bigpatch.diffs.removeAt(0)
                        } else {
                            bigpatch.diffs[0].text = sub(bigpatch.diffs[0].text, diffText.length)
                        }
                    }
                }
                precontext = diffText2(patch.diffs)
                precontext = sub(precontext, precontext.length - patchMargin)
                val postcontext = sub(diffText1(bigpatch.diffs), 0, patchMargin)
                if (postcontext.isNotEmpty()) {
                    patch.length1 += postcontext.length
                    patch.length2 += postcontext.length
                    if (patch.diffs.isNotEmpty() && patch.diffs[patch.diffs.size - 1].operation == Operation.EQUAL) {
                        patch.diffs[patch.diffs.size - 1].text += postcontext
                    } else {
                        patch.diffs.add(Diff(Operation.EQUAL, postcontext))
                    }
                }
                if (!empty) patches.add(++x, patch)
            }
            x++
        }
    }

    /** GNU diff style text of a patch list (JS `patch_toText`). */
    fun patchToText(patches: List<Patch>): String = patches.joinToString("") { it.toString() }

    /** Parses the textual patch format (JS `patch_fromText`). Throws on malformed input. */
    fun patchFromText(text: String): List<Patch> {
        val patches = mutableListOf<Patch>()
        if (text.isEmpty()) return patches
        val lines = text.split("\n")
        var textPointer = 0
        while (textPointer < lines.size) {
            val m = PATCH_HEADER.matchEntire(lines[textPointer])
                ?: throw IllegalArgumentException("Invalid patch string: " + lines[textPointer])
            val patch = Patch()
            patches.add(patch)
            val g = m.groupValues
            patch.start1 = g[1].toInt()
            when {
                g[2].isEmpty() -> { patch.start1--; patch.length1 = 1 }
                g[2] == "0" -> patch.length1 = 0
                else -> { patch.start1--; patch.length1 = g[2].toInt() }
            }
            patch.start2 = g[3].toInt()
            when {
                g[4].isEmpty() -> { patch.start2--; patch.length2 = 1 }
                g[4] == "0" -> patch.length2 = 0
                else -> { patch.start2--; patch.length2 = g[4].toInt() }
            }
            textPointer++

            while (textPointer < lines.size) {
                val raw = lines[textPointer]
                val sign = charAt(raw, 0)
                val line = try {
                    decodeUri(sub(raw, 1))
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("Illegal escape in patch_fromText: " + sub(raw, 1))
                }
                when (sign) {
                    "-" -> patch.diffs.add(Diff(Operation.DELETE, line))
                    "+" -> patch.diffs.add(Diff(Operation.INSERT, line))
                    " " -> patch.diffs.add(Diff(Operation.EQUAL, line))
                    "@" -> break
                    "" -> {}
                    else -> throw IllegalArgumentException("Invalid patch mode \"$sign\" in: $line")
                }
                textPointer++
            }
        }
        return patches
    }

    companion object {
        private val PATCH_HEADER = Regex("@@ -([0-9]+),?([0-9]*) \\+([0-9]+),?([0-9]*) @@")

        /** JS `String.prototype.substring`: clamps both ends to [0, length] and swaps if reversed. */
        internal fun sub(s: String, start: Int, end: Int = s.length): String {
            var a = start.coerceIn(0, s.length)
            var b = end.coerceIn(0, s.length)
            if (a > b) { val t = a; a = b; b = t }
            return s.substring(a, b)
        }

        /** JS `charAt`: a one unit string, or "" out of range. */
        internal fun charAt(s: String, i: Int): String = if (i in s.indices) s[i].toString() else ""

        /** JS `indexOf(search, from)`. */
        internal fun jsIndexOf(s: String, search: String, from: Int): Int {
            val start = from.coerceIn(0, s.length)
            if (search.isEmpty()) return start
            return s.indexOf(search, start)
        }

        /** JS `lastIndexOf(search, from)`. */
        internal fun jsLastIndexOf(s: String, search: String, from: Int): Int {
            val start = minOf(from.coerceIn(0, s.length), s.length - search.length)
            if (start < 0) return -1
            var k = start
            while (k >= 0) {
                if (s.regionMatches(k, search, 0, search.length)) return k
                k--
            }
            return -1
        }

        private const val URI_UNESCAPED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789;,/?:@&=+\$-_.!~*'()#"
        private const val URI_RESERVED = ";/?:@&=+\$,#"
        private const val HEX = "0123456789ABCDEF"

        /** JS `encodeURI`. Throws on a lone surrogate, like the JS URIError. */
        internal fun encodeUri(s: String): String {
            val out = StringBuilder()
            var k = 0
            while (k < s.length) {
                val c = s[k]
                if (URI_UNESCAPED.indexOf(c) >= 0) {
                    out.append(c)
                    k++
                    continue
                }
                val cp: Int
                if (c.isHighSurrogate()) {
                    if (k + 1 >= s.length || !s[k + 1].isLowSurrogate()) throw IllegalArgumentException("URI malformed")
                    cp = 0x10000 + ((c.code - 0xD800) shl 10) + (s[k + 1].code - 0xDC00)
                    k += 2
                } else if (c.isLowSurrogate()) {
                    throw IllegalArgumentException("URI malformed")
                } else {
                    cp = c.code
                    k++
                }
                val bytes = when {
                    cp < 0x80 -> intArrayOf(cp)
                    cp < 0x800 -> intArrayOf(0xC0 or (cp shr 6), 0x80 or (cp and 0x3F))
                    cp < 0x10000 -> intArrayOf(0xE0 or (cp shr 12), 0x80 or ((cp shr 6) and 0x3F), 0x80 or (cp and 0x3F))
                    else -> intArrayOf(
                        0xF0 or (cp shr 18), 0x80 or ((cp shr 12) and 0x3F),
                        0x80 or ((cp shr 6) and 0x3F), 0x80 or (cp and 0x3F),
                    )
                }
                for (b in bytes) out.append('%').append(HEX[b shr 4]).append(HEX[b and 0xF])
            }
            return out.toString()
        }

        private fun hexVal(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }

        /** JS `decodeURI` (ECMA-262 Decode with the reserved set). Throws on malformed input. */
        internal fun decodeUri(s: String): String {
            val len = s.length
            val out = StringBuilder()
            var k = 0
            fun byteAt(i: Int): Int {
                if (s[i] != '%') throw IllegalArgumentException("URI malformed")
                val h = hexVal(s[i + 1])
                val l = hexVal(s[i + 2])
                if (h < 0 || l < 0) throw IllegalArgumentException("URI malformed")
                return (h shl 4) or l
            }
            while (k < len) {
                val c = s[k]
                if (c != '%') {
                    out.append(c)
                    k++
                    continue
                }
                val start = k
                if (k + 2 >= len) throw IllegalArgumentException("URI malformed")
                val b = byteAt(k)
                k += 2
                if (b and 0x80 == 0) {
                    val ch = b.toChar()
                    if (URI_RESERVED.indexOf(ch) < 0) out.append(ch) else out.append(s, start, k + 1)
                } else {
                    val n = when {
                        b and 0xE0 == 0xC0 -> 2
                        b and 0xF0 == 0xE0 -> 3
                        b and 0xF8 == 0xF0 -> 4
                        else -> throw IllegalArgumentException("URI malformed")
                    }
                    if (k + 3 * (n - 1) >= len) throw IllegalArgumentException("URI malformed")
                    var cp = b and (0xFF shr (n + 1))
                    for (j in 1 until n) {
                        k++
                        val cb = byteAt(k)
                        if (cb and 0xC0 != 0x80) throw IllegalArgumentException("URI malformed")
                        k += 2
                        cp = (cp shl 6) or (cb and 0x3F)
                    }
                    val min = when (n) { 2 -> 0x80; 3 -> 0x800; else -> 0x10000 }
                    if (cp < min || cp > 0x10FFFF || cp in 0xD800..0xDFFF) throw IllegalArgumentException("URI malformed")
                    if (cp < 0x10000) {
                        out.append(cp.toChar())
                    } else {
                        val v = cp - 0x10000
                        out.append((0xD800 + (v shr 10)).toChar())
                        out.append((0xDC00 + (v and 0x3FF)).toChar())
                    }
                }
                k++
            }
            return out.toString()
        }
    }
}

/** Diff operation; [sign] matches the JS constants DIFF_DELETE / DIFF_INSERT / DIFF_EQUAL. */
enum class Operation(val sign: Int) { DELETE(-1), INSERT(1), EQUAL(0) }

/** One diff tuple. Mutable on purpose: the cleanup passes edit diffs in place, as in JS. */
data class Diff(var operation: Operation, var text: String)

/** One patch: a list of diffs plus its coordinates in the source and destination texts. */
class Patch {
    val diffs: MutableList<Diff> = mutableListOf()
    var start1: Int = 0
    var start2: Int = 0
    var length1: Int = 0
    var length2: Int = 0

    /** GNU diff header plus `encodeURI` escaped body, like the JS `patch_obj.toString`. */
    override fun toString(): String {
        val coords1 = when (length1) {
            0 -> "$start1,0"
            1 -> "${start1 + 1}"
            else -> "${start1 + 1},$length1"
        }
        val coords2 = when (length2) {
            0 -> "$start2,0"
            1 -> "${start2 + 1}"
            else -> "${start2 + 1},$length2"
        }
        val text = StringBuilder("@@ -").append(coords1).append(" +").append(coords2).append(" @@\n")
        for (d in diffs) {
            val op = when (d.operation) {
                Operation.INSERT -> '+'
                Operation.DELETE -> '-'
                Operation.EQUAL -> ' '
            }
            text.append(op).append(DiffMatchPatch.encodeUri(d.text)).append('\n')
        }
        return text.toString().replace("%20", " ")
    }
}
