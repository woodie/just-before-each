package com.netpress.kwick

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.SpecExecutionOrder
import io.kotest.core.test.TestCaseOrder

// Pins spec/test execution order so full-suite output is reproducible -- matches
// next-caltrain-kotlin's/humane-kotlin's ProjectConfig.
object ProjectConfig : AbstractProjectConfig() {
    override val specExecutionOrder = SpecExecutionOrder.Lexicographic
    override val testCaseOrder = TestCaseOrder.Sequential

    // Without this, justBeforeEach blocks are registered but never actually
    // run -- see JustBeforeEach.kt's own doc comment.
    override fun extensions() = listOf(JustBeforeEachExtension)
}
