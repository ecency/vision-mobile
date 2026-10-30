package com.ecency.core.app

import com.ecency.core.hive.KeyRole
import com.ecency.core.l10n.L

/** Joins short copy fragments with the catalog's words, so a sentence built from a list reads right in every language. */
object CopyLists {
    /** "a and b" ("a and b and c" for three, as the English copy had it); empty for none. */
    /** A key role's name in a sentence ("posting"); the chain value [KeyRole.value] stays English. */
    fun role(role: KeyRole): String = when (role) {
        KeyRole.OWNER -> L.native.common.roleOwner
        KeyRole.ACTIVE -> L.native.common.roleActive
        KeyRole.POSTING -> L.native.common.rolePosting
        KeyRole.MEMO -> L.native.common.roleMemo
    }

    fun and(items: List<String>): String = items.reduceOrNull { a, b -> L.native.common.listAnd(first = a, second = b) }.orEmpty()
}
