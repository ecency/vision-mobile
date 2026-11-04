package com.ecency.core.app

import java.text.Normalizer

internal actual object UnicodeText {
    actual fun nfkd(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKD)

    actual fun isMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    actual fun isLetterOrNumber(codePoint: Int): Boolean = Character.isLetter(codePoint) || when (Character.getType(codePoint)) {
        Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
        else -> false
    }

    actual fun isFormat(codePoint: Int): Boolean = Character.getType(codePoint) == Character.FORMAT.toInt()
}
