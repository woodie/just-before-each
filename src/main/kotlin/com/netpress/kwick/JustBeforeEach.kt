package com.netpress.kwick

import io.kotest.core.descriptors.Descriptor
import io.kotest.core.extensions.TestCaseExtension
import io.kotest.core.spec.style.scopes.ContainerScope
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.core.test.TestScope
import io.kotest.core.test.TestType
import io.kotest.core.test.parents
import java.util.concurrent.ConcurrentHashMap

/**
 * Registers [block] to run after every `beforeEach`/`beforeTest` at any
 * nesting depth beneath the container it's declared in, immediately before
 * each `it` underneath it -- Quick's `justBeforeEach` guarantee, ported to
 * Kotest. See this repo's own docs/COWORK.md for why no engine hook or
 * shadowed `describe`/`context`/`it` is needed to get that guarantee, and
 * this repo's own JustBeforeEachSpec.kt for a worked example.
 *
 * Must be paired with [JustBeforeEachExtension] registered in the consuming
 * project's `ProjectConfig` -- without it, registered blocks are recorded
 * but never actually run. See README's "Setup".
 */
suspend fun ContainerScope.justBeforeEach(block: suspend TestScope.() -> Unit) {
    JustBeforeEachRegistry.register(testCase.descriptor, block)
}

/**
 * Holds justBeforeEach blocks keyed by the descriptor of the container they
 * were registered under. Registration happens once, during Kotest's single
 * tree-building pass over a spec's describe/context blocks (the same pass
 * that registers every `it`), so this is fully populated before any test in
 * that spec actually runs. `ConcurrentHashMap` because Kotest can build/run
 * specs from more than one spec instance concurrently.
 */
internal object JustBeforeEachRegistry {
    private val blocks = ConcurrentHashMap<Descriptor.TestDescriptor, MutableList<suspend TestScope.() -> Unit>>()

    fun register(
        descriptor: Descriptor.TestDescriptor,
        block: suspend TestScope.() -> Unit,
    ) {
        blocks.getOrPut(descriptor) { mutableListOf() }.add(block)
    }

    // Root-to-leaf, using TestCase's own parent chain -- TestCase.parents()
    // (io.kotest.core.test) already returns ancestors root-first, so this
    // only needs to append the test case itself and look up each descriptor
    // in registration order. Descriptor has no public parent-walking API of
    // its own in Kotest 5.9.1; TestCase does.
    fun blocksFor(testCase: TestCase): List<suspend TestScope.() -> Unit> {
        val chain = testCase.parents() + testCase
        return chain.flatMap { blocks[it.descriptor].orEmpty() }
    }
}

/**
 * Wraps every leaf test case so registered justBeforeEach blocks run
 * immediately before the real test body. Kotest's own beforeEach/beforeTest
 * chain still fires unmodified as part of [execute] -- this only replaces
 * the leaf `test` closure passed into it, so the ordering is: real
 * beforeEach chain, then these blocks (root-to-leaf), then the real test
 * body.
 *
 * Container test cases (every `describe`/`context`, not just `it`) are
 * intentionally skipped -- TestCaseExtension.intercept fires for those too,
 * and a container's own `test` closure is the code that discovers its
 * children, run well before any descendant `beforeEach` has set up the
 * values a hoisted action usually reads. Wrapping it would run the action
 * during tree discovery instead of before each real test, throwing on any
 * `lateinit var` a block reads but hasn't been assigned yet.
 *
 * Add to the consuming project's `ProjectConfig.extensions()`:
 * ```kotlin
 * override fun extensions() = listOf(JustBeforeEachExtension)
 * ```
 */
object JustBeforeEachExtension : TestCaseExtension {
    override suspend fun intercept(
        testCase: TestCase,
        execute: suspend (TestCase) -> TestResult,
    ): TestResult {
        if (testCase.type != TestType.Test) return execute(testCase)

        val blocks = JustBeforeEachRegistry.blocksFor(testCase)
        if (blocks.isEmpty()) return execute(testCase)

        val wrappedTest: suspend TestScope.() -> Unit = {
            blocks.forEach { it() }
            testCase.test(this)
        }
        return execute(testCase.copy(test = wrappedTest))
    }
}
