package com.ecency.core.app

import platform.Foundation.NSCharacterSet
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.decomposedStringWithCompatibilityMapping

internal actual object UnicodeText {
    // Foundation's sets: nonBase is M*, alphanumeric is L*, M* and N*, control is Cc and Cf
    private val marks = NSCharacterSet.nonBaseCharacterSet
    private val alphanumerics = NSCharacterSet.alphanumericCharacterSet
    private val controls = NSCharacterSet.controlCharacterSet

    actual fun nfkd(text: String): String = NSString.create(string = text).decomposedStringWithCompatibilityMapping

    actual fun isMark(codePoint: Int): Boolean = marks.longCharacterIsMember(codePoint.toUInt())

    actual fun isLetterOrNumber(codePoint: Int): Boolean =
        alphanumerics.longCharacterIsMember(codePoint.toUInt()) && !isMark(codePoint)

    actual fun isFormat(codePoint: Int): Boolean =
        controls.longCharacterIsMember(codePoint.toUInt()) && codePoint >= 0x20 && codePoint !in 0x7F..0x9F
}
