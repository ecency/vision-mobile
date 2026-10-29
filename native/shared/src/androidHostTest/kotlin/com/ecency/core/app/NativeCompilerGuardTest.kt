package com.ecency.core.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Kotlin/Native 2.3.10 crashes for every arm64 Apple target ("IrClassSymbolImpl is already bound. Signature:
 * kotlin/Function6") on a suspend lambda of 5 parameters (5 plus the continuation), a 5-flow `combine { _, _, _, _, _ -> }`.
 * The JVM and iosX64 builds do not show it, so this host test keeps such combines out of the shared code (plain lambdas
 * of 5 parameters, Function5, link fine). The proof that the
 * shared code links is `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 :shared:linkReleaseFrameworkIosArm64`.
 */
class NativeCompilerGuardTest {
    @Test
    fun noCombineTakesFiveOrMoreFlows() {
        val lambda = Regex("""\{\s*\w+(\s*:\s*[\w.<>?]+)?(\s*,\s*\w+(\s*:\s*[\w.<>?]+)?){4,}\s*->""")
        val offenders = File("src/commonMain/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if ("combine" in line && lambda.containsMatchIn(line)) "${f.name}:${i + 1}: ${line.trim()}" else null }
        }.toList()
        assertEquals(emptyList<String>(), offenders, "a 5-flow combine breaks the arm64 Apple builds; combine two flows at a time")
    }
}
