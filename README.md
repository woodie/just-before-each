# kwick

[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?logo=kotlin&logoColor=white)](build.gradle.kts)
[![CI](https://github.com/woodie/kwick/actions/workflows/CI.yml/badge.svg)](https://github.com/woodie/kwick/actions/workflows/CI.yml)
[![Release](https://img.shields.io/github/v/release/woodie/kwick.svg)](https://github.com/woodie/kwick/releases/latest)
[![License](https://img.shields.io/github/license/woodie/kwick.svg)](LICENSE)

Better, cleaner tests for Kotest's `DescribeSpec` with real nested context
with less duplication with `justBeforeEach` (inspired by
[Quick](https://github.com/Quick/Quick)).

## `justBeforeEach`

Kotest's own `beforeEach` reruns fresh before every `it`, from the outermost
`describe` inward -- but there's no hook that runs *after* every `beforeEach`
at every nesting depth, immediately before the `it` itself. That's
`justBeforeEach`'s job: separate what varies (declared via ordinary
`beforeEach` in each `context`) from the action under test (declared once,
in the parent):

```kotlin
import com.netpress.kwick.justBeforeEach
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class PrimeSpec : DescribeSpec({
    describe("#remainder") {
        context("with the prime number 5") {
            lateinit var subject: Calculator
            var divisor = 1
            var remainder = 0
            beforeEach { subject = Calculator(5) }
            justBeforeEach { remainder = subject.remainder(divisor) }

            context("when divided by 1") {
                beforeEach { divisor = 1 }
                it("has no remainder") { remainder shouldBe 0 }
            }

            context("when divided by 3") {
                beforeEach { divisor = 3 }
                it("has a remainder of 2") { remainder shouldBe 2 }
            }
        }
    }
})
```

Which renders as:

```
#remainder
  with the prime number 5
    when divided by 1
      ✔ has no remainder
    when divided by 3
      ✔ has a remainder of 2
```

Each `context` only states what's different about it (here, just `divisor`);
`subject` is rebuilt fresh in `beforeEach`, and the actual call under test is
written once, in `justBeforeEach` -- Kotest's own guarantee that every
ancestor `beforeEach` completes before the `it` runs is what makes this work,
no engine hook or wrapped `describe`/`context`/`it` needed.

## Setup

`justBeforeEach` blocks are only *recorded* until `JustBeforeEachExtension`
is registered on the project -- add it to your `ProjectConfig`:

```kotlin
import com.netpress.kwick.JustBeforeEachExtension
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
var result: Result<Scan> = Result.failure(IllegalStateException("not yet run"))
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

## Scoping Variables

`beforeEach`/`justBeforeEach` rebuild their value fresh before every `it`, so
how you declare that variable just depends on its type:

- **Reference types**, like `Calculator` above, can use
  `lateinit var subject: Calculator` -- it forces every `it` to see a real,
  freshly-built value, with nothing to initialize by hand.
- **Primitives and inline value classes** (`Int`, `Boolean`, `Result<T>`, ...)
  can't use `lateinit` -- it's restricted to non-null, non-primitive reference
  types. Declare a plain `var` initialized to a throwaway placeholder instead
  (`remainder = 0` above, `result = Result.failure(...)` in the `runCatching`
  example) -- `justBeforeEach` overwrites it before any `it` runs, so the
  placeholder value itself never matters.

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
