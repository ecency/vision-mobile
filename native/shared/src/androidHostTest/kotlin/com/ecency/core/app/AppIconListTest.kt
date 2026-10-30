package com.ecency.core.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** scripts/icons/icons.tsv is the list both platforms are generated and checked from: it must be exactly [AppIcon]. */
class AppIconListTest {
    private val rows = File("../scripts/icons/icons.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }

    @Test
    fun theListHasEveryIconInOrderWithItsDirection() {
        assertEquals(AppIcon.entries.map { it.name }, rows.map { it[0] })
        assertEquals(AppIcon.entries.map { if (it.isDirectional) "yes" else "no" }, rows.map { it[5] })
    }

    @Test
    fun everyIconHasAMaterialAndAnSfName() {
        rows.forEach { r ->
            assertEquals(6, r.size, r[0])
            assert(r[1].isNotBlank() && r[3].isNotBlank() && r[3] != "-") { "${r[0]} needs both names" }
        }
    }
}
