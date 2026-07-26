package com.netpress.justbeforeeach

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class JustBeforeEachSpec : DescribeSpec({

    describe("justBeforeEach") {

        context("ordering") {
            val log = mutableListOf<String>()
            beforeEach { log.clear() }

            context("declared alongside a beforeEach at the same level") {
                beforeEach { log.add("beforeEach") }
                justBeforeEach { log.add("justBeforeEach") }

                it("runs after beforeEach, immediately before the it body") {
                    log.add("it")
                    log shouldBe listOf("beforeEach", "justBeforeEach", "it")
                }
            }

            context("declared above a context with its own beforeEach") {
                justBeforeEach { log.add("outer justBeforeEach") }

                context("inner context, its own beforeEach") {
                    beforeEach { log.add("inner beforeEach") }

                    it("still runs after the inner beforeEach, not before it") {
                        log.add("it")
                        log shouldBe listOf("inner beforeEach", "outer justBeforeEach", "it")
                    }
                }
            }
        }

        context("the runCatching-outcome convention") {
            fun act(shouldThrow: Boolean): Int {
                if (shouldThrow) error("boom")
                return 42
            }

            var shouldThrow = false
            lateinit var result: Result<Int>
            justBeforeEach { result = runCatching { act(shouldThrow) } }

            context("happy path") {
                beforeEach { shouldThrow = false }

                it("captures the success value") {
                    result.getOrThrow() shouldBe 42
                }
            }

            context("error path") {
                beforeEach { shouldThrow = true }

                it("captures the exception instead of failing setup") {
                    result.exceptionOrNull()?.message shouldBe "boom"
                }
            }
        }
    }
})
