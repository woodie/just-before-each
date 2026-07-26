# just-before-each

[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?logo=kotlin&logoColor=white)](build.gradle.kts)
[![CI](https://github.com/woodie/just-before-each/actions/workflows/CI.yml/badge.svg)](https://github.com/woodie/just-before-each/actions/workflows/CI.yml)
[![License](https://img.shields.io/github/license/woodie/just-before-each.svg)](LICENSE)

Quick's `justBeforeEach` for Kotest's `DescribeSpec`.

Kotest's own `beforeEach` reruns fresh before every `it`, from the outermost
`describe` inward -- but there's no hook that runs *after* every `beforeEach`
at every nesting depth, immediately before the `it` itself. That's
`justBeforeEach`'s job: separate what varies (declared via ordinary
`beforeEach` in each `context`) from the action under test (declared once,
in the parent):

```kotlin
import com.netpress.justbeforeeach.justBeforeEach
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class CalculatorSpec : DescribeSpec({
    describe("#divide") {
        var numerator = 0
        var denominator = 1
        lateinit var result: Int
        justBeforeEach { result = Calculator.divide(numerator, denominator) }

        context("dividing evenly") {
            beforeEach { numerator = 10; denominator = 2 }
            it("returns the quotient") { result shouldBe 5 }
        }

        context("dividing with a remainder") {
            beforeEach { numerator = 7; denominator = 2 }
            it("truncates toward zero") { result shouldBe 3 }
        }
    }
})
```

Each `context` only states what's different about it; the call under test
is written once, in `justBeforeEach`, and Kotest's own guarantee that every
ancestor `beforeEach` completes before the `it` runs is what makes this
work -- no engine hook or wrapped `describe`/`context`/`it` needed. See
`docs/COWORK.md`'s "Core mechanism" for exactly how.

## Setup

`justBeforeEach` blocks are only *recorded* until `JustBeforeEachExtension`
is registered on the project -- add it to your `ProjectConfig`:

```kotlin
import com.netpress.justbeforeeach.JustBeforeEachExtension
import io.kotest.core.config.AbstractProjectConfig

object ProjectConfig : AbstractProjectConfig() {
    override fun extensions() = listOf(JustBeforeEachExtension)
}
```

Without this, every `justBeforeEach` block is a silent no-op.

## The `runCatching`-outcome convention

Hoisting an action that might throw into `justBeforeEach` breaks naively --
the exception fires during setup, outside any `it`, and fails the test as a
setup error rather than a normal assertion failure. Capture the outcome
instead of letting it throw, using the stdlib's own `runCatching`, so both a
happy-path and an error-path sibling `context` can share the same hoisted
action:

```kotlin
lateinit var result: Result<Scan>
justBeforeEach { result = runCatching { client.delete(scan) } }

context("when the server confirms the delete") {
    it("returns the deleted scan") { result.getOrThrow() shouldBe scan }
}

context("when the server rejects the delete") {
    it("captures the failure without failing setup") {
        result.exceptionOrNull().shouldBeInstanceOf<ScanNotFoundException>()
    }
}
```

## Requirements

JDK 17+, Kotlin 2.2.10, Kotest 5.9.1 -- matching this account's other
Kotlin repos (`next-caltrain-kotlin`, `humane-kotlin`, `kotidy`).

## Development

```
make build   # ./gradlew build -x test
make test    # ./gradlew clean test -- Kotest's real nested describe/context/it output
make lint    # ./gradlew ktlintCheck
make check   # ./gradlew check
```
