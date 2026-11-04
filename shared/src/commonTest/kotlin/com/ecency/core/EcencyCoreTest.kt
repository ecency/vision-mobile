package com.ecency.core

import kotlin.test.Test
import kotlin.test.assertTrue

class EcencyCoreTest {
    @Test
    fun greetingNamesTheVersion() {
        assertTrue(EcencyCore.greeting().contains(EcencyCore.VERSION))
    }
}
