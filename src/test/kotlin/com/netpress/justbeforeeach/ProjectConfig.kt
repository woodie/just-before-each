package com.netpress.justbeforeeach

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.SpecExecutionOrder
import io.kotest.core.test.TestCaseOrder

// Pins spec/test execution order so full-suite output is reproducible -- matches
// next-caltrain-kotlin's/humane-kotlin's ProjectConfig.
object ProjectConfig : AbstractProjectConfig() {
    override val specExecutionOrder = SpecExecutionOrder.Lexicographic
    override val testCaseOrder = TestCaseOrder.Sequential
}
