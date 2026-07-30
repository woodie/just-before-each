package com.netpress.kwick

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay

class JustBeforeEachSpec :
    DescribeSpec({

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

                // Regression: TestCaseExtension.intercept fires for container
                // test cases too, not just `it`s. Every describe/context
                // between a justBeforeEach and the it underneath it is one
                // of those containers -- if JustBeforeEachExtension doesn't
                // skip them, this block runs during tree discovery, before
                // the nested beforeEach below has assigned `input`, and
                // throws UninitializedPropertyAccessException instead of
                // ever reaching the it.
                context("reading a lateinit var only set by a nested beforeEach") {
                    lateinit var input: String
                    lateinit var result: String
                    justBeforeEach { result = input.uppercase() }

                    context("input set by a nested beforeEach") {
                        beforeEach { input = "hi" }

                        it("waits for the nested beforeEach before running") {
                            result shouldBe "HI"
                        }
                    }
                }

                // CaltrainServiceSpec's #routes() (next-caltrain-kotlin/-swift)
                // hoists a whole result and has several `it`s underneath
                // asserting different facts about it, rather than one `it`
                // per context like every other real usage so far. Proves
                // that shape: multiple `it`s sharing one subject a parent's
                // justBeforeEach computed from this context's own
                // beforeEach-set input.
                context("several its reading one hoisted subject with multiple facts") {
                    data class Parsed(
                        val upper: String,
                        val length: Int,
                    )

                    lateinit var input: String
                    lateinit var parsed: Parsed
                    justBeforeEach { parsed = Parsed(upper = input.uppercase(), length = input.length) }

                    context("input set by a nested beforeEach") {
                        beforeEach { input = "hi" }

                        it("uppercases the input") {
                            parsed.upper shouldBe "HI"
                        }

                        it("also reports its length") {
                            parsed.length shouldBe 2
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
                // Result<T> is a Kotlin inline value class -- lateinit isn't
                // allowed on those, so a throwaway placeholder stands in
                // until justBeforeEach overwrites it, before any it runs.
                var result: Result<Int> = Result.success(0)
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

            // Proves the suspend-first signature actually suspends; see docs/COMMENTS.md.
            context("a real suspend call inside justBeforeEach") {
                class FakeAsyncClient {
                    suspend fun delete(id: String): String {
                        delay(1)
                        return "deleted:$id"
                    }
                }

                val client = FakeAsyncClient()
                lateinit var id: String
                lateinit var result: String
                justBeforeEach { result = client.delete(id) }

                context("id set by a nested beforeEach") {
                    beforeEach { id = "scan-42" }

                    it("awaits the suspend call before the it body runs") {
                        result shouldBe "deleted:scan-42"
                    }
                }
            }
        }
    })
