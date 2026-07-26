# Picking up just-before-each in a new Cowork session

Context for whoever opens this repo cold, with none of the prior conversation
history. Cross-project conventions (git locks, sandbox toolchain gaps,
pushing, comments, code style) are in `~/workspace/woodie/docs/COWORK.md`.

## What this is, and why it exists

A small Kotlin/JVM library adding `justBeforeEach` to Kotest's `DescribeSpec`
-- the one piece of Quick's `describe`/`context`/`it`/`beforeEach`/
`justBeforeEach` shape (see `xctidy`'s own `docs/FRAMEWORK.md`, "`justBeforeEach`:
separate 'what varies' from 'the action under test'") that Kotest has no
native equivalent for. Kotest's own lifecycle-hooks doc lists `before-each`/
`before-container`/`before-any`/`before-test`/`before-invocation` -- nothing
that runs after every `beforeEach` at every nesting depth, immediately before
the `it`.

The gap shows up concretely in `next-caltrain-kotlin`'s `GoodTimesSpec.kt`
(`context("when 'today' is fixed via debugOverrideDotw")`): a local
`setDotw(dotw)` helper has to be declared and called from each sibling
context's own `beforeEach` (`setDotw(5)`, `setDotw(6)`, `setDotw(0)`),
duplicating the actual "act" step three times. The Swift sibling
(`next-caltrain-swift`'s `GoodTimesSpec.swift`) hoists the same act step into
one `justBeforeEach { GoodTimes.debugOverrideDotw = dotw; gt = GoodTimes() }`
instead -- each sibling `context`'s `beforeEach` only sets the input
(`dotw = 5`). This library exists to let Kotlin do the same thing, so Kotlin
specs stop needing little one-off helper functions that Go/Swift specs never
had to write, and porting a spec between languages stays closer to a literal
translation.

## Packaging: a library (like `humane-kotlin`), not a plugin (like `kotidy`)

Decided by asking one question: does this need to hook Gradle's own build/
task lifecycle, or is it just Kotlin code a spec file imports and calls?
`kotidy` is a Gradle plugin because it has no other option -- it hooks
Gradle's `TestListener` API directly to render test output as tests run,
which only resolves via a real `plugins { id("com.netpress.kotidy") }`
block (see `kotidy`'s own `docs/COWORK.md`). `humane-kotlin` is a plain
library -- `Humane.humanSize()` is just a function a consumer calls -- so it
ships as a normal Maven Central artifact (`com.vanniktech.maven.publish`,
GPG-signed, Central Portal; see `humane-kotlin`'s `docs/releases/v0.1.1.md`),
resolved with an ordinary dependency coordinate.

`justBeforeEach` is the second shape: DSL sugar a spec file imports and
calls directly (a shadowed `describe`/`context`/`it` plus the new hook),
with nothing touching Gradle's task graph or build lifecycle. One wrinkle
versus `humane-kotlin`: this only ever gets called from spec files, never
from production code, so it belongs on `testImplementation`, not
`implementation`, once it's consumed as a real dependency rather than
`implementation` inside this repo's own `build.gradle.kts` (which needs the
real types to compile the library itself, not just test it).

Bootstrap path, mirroring `humane-kotlin`'s own history: prove the DSL out
here first, consume it from `next-caltrain-kotlin` as a composite build
(`pluginManagement`-free `includeBuild("../just-before-each")`, a plain
dependency substitution -- not the `pluginManagement { includeBuild(...) }`
form `kotidy` needed, since this isn't a plugin), then add
`com.vanniktech.maven.publish` + Central Portal signing once it's stable
enough to publish for real, the same way `humane-kotlin` went from
composite-build-only at `v0.1.0` to Maven Central at `v0.1.1`. Not done yet
in this repo's `build.gradle.kts` on purpose -- see the comment there.

## Core mechanism: `TestCaseExtension`, not shadowed `describe`/`context`/`it`

**Revised from the original plan below after actually writing the code --
the first design didn't survive contact with how Kotest's DSL is really
implemented.** The original idea was to shadow `describe`/`context`/`it`
with wrapped versions that thread a registration-time stack, on the theory
that Kotest already guarantees every ancestor `beforeEach` completes before
an `it` body runs, so no engine hook would be needed -- just embed the
captured blocks at the start of a wrapped `it` body. That guarantee is real,
but the shadowing mechanism isn't: Kotest's `describe`/`context`/`it` are
default-implemented members of interfaces (`DescribeSpecRootScope`,
`DescribeSpecContainerScope`), and a *nested* `describe`/`context` block
runs against a Kotest-internal scope object, not against this library's
code or the consumer's own `Spec` subclass -- there's no supertype of ours
in that chain to override, and Kotlin resolves a member function over a
same-named top-level one regardless of import, so a same-named top-level
`it` would just never fire. Shadowing genuinely doesn't work here.

What does work, and is what's actually implemented in
`JustBeforeEach.kt`: `describe`/`context`/`it` stay completely vanilla
Kotest -- no wrapper, no import swap. Only two new pieces exist:

- `justBeforeEach(block)`, a real extension function on `ContainerScope`
  (Kotest's own container-block receiver type, no subclassing involved).
  Calling it inside a `describe`/`context` block registers `block` into
  `JustBeforeEachRegistry`, keyed by that container's own `Descriptor`
  (`ContainerScope.testCase.descriptor`).
- `JustBeforeEachExtension`, a `TestCaseExtension` -- Kotest's own,
  documented extension point for wrapping test-case execution. For every
  `TestCase` about to run, it walks the test case's own ancestor chain via
  `TestCase.parents()` (a real Kotest utility, root-first already -- see
  `io.kotest.core.test.TestCase.kt`), looks up any registered blocks for
  each ancestor plus the test case itself, and replaces the `TestCase`'s
  `test` closure with one that runs those blocks first, then calls the
  original. Kotest's real `beforeEach`/`beforeTest` chain is
  untouched and still fires as part of `execute(testCase)` -- swapping in a
  wrapped `test` closure before calling `execute` doesn't skip or reorder
  that, it just runs *inside* the slot Kotest was already going to invoke
  after those hooks complete. That's what gets Quick's "after every
  `beforeEach` at any depth, immediately before the `it`" guarantee, without
  needing to touch `describe`/`context`/`it` at all.

Consumers need one extra step this design implies: registering
`JustBeforeEachExtension` in their own `ProjectConfig.extensions()` (see
this repo's own `src/test/kotlin/.../ProjectConfig.kt`). Without it,
`justBeforeEach` blocks are recorded but never actually run -- a real
footgun worth calling out prominently in the README, not just here.

`JustBeforeEachSpec.kt` (this repo's own dogfood test) proves the ordering
directly: a `log` list mutated by `beforeEach`/`justBeforeEach`/`it`,
asserting the exact sequence, plus a case where `justBeforeEach` is declared
*above* a nested context that has its own `beforeEach`, confirming the inner
hook still runs first.

**First real-Mac run caught two bugs, both now fixed.** `./gradlew clean
check` (Kotlin `2.1.0` via the Gradle toolchain, ktlint official style)
failed with: a real compile error on the original `blocksFor(descriptor:
Descriptor)` -- `Descriptor` has no public `parent()` in Kotest `5.9.1`, so
the ancestor walk was rewritten against `TestCase.parents()` instead
(confirmed real, from Kotest's own `io.kotest.core.test.TestCase.kt` --
root-first already, no reversing needed); a type mismatch on the wrapped
`test` closure (`{ scope -> ... }` was inferred as a two-argument function
instead of the expected `suspend TestScope.() -> Unit`, since that type
takes no explicit parameter, only a receiver -- fixed by using the implicit
`this` receiver and extracting a separately-typed `wrappedTest` local
instead of an inline lambda); plus two ktlint parameter-list-wrapping
violations from functions written on one line. `Descriptor`'s own package
(`io.kotest.core.descriptors`), `ContainerScope.testCase`,
`TestCaseExtension.intercept`'s signature, and `TestCase` being a real data
class with a copyable `test` field were all confirmed correct by reading
Kotest's actual `v5.9.1` source directly (`TestCase.kt`, `ContainerScope.kt`,
`TestCaseExtension.kt`) once real compiler errors made clear which parts of
the original inspection-only guess needed checking. `./gradlew clean check`
still needs to be re-run to confirm this round is actually green.

## Scope for v1: suspend support is not a follow-up

Pulled every real `justBeforeEach` call site across `next-caltrain-swift`
and `zouk` before starting this. Of 8 real usages, the `zouk` ones
(`ScanClientSpec.swift` x3, `AppModelSpec.swift`) are all
`justBeforeEach { try await ... }` or `await ...` -- cache lookups, saves,
deletes. Only the sync ones (`next-caltrain-swift`, `ExtensionEnforcingPanelDelegateSpec`)
skip `await`. So suspend-aware `justBeforeEach` is most of how the real
thing gets used, not an edge case layered on top later -- `beforeEach`/
`justBeforeEach` need to accept `suspend` blocks and run inside whatever
coroutine test scope Kotest already provides, from the start.

This also closes a real, separately-documented gap: `kotidy`'s own
`docs/FRAMEWORK.md` notes "mixing `beforeEach` with suspend setup isn't
wired up in this account yet" -- `next-caltrain-kotlin`'s `ScanClientSpec.kt`-
style specs (see `huck`'s own) do their setup inline inside each `it`'s own
`runTest` block instead of a shared `beforeEach`, for exactly this reason.
Same duplication problem `justBeforeEach` fixes for `GoodTimesSpec.kt`,
just triggered by suspend functions instead of a duplicated act step.

Real `justBeforeEach` bodies are also often multi-statement arrange+act, not
a single call -- `next-caltrain-swift`'s `TripViewModelSpec.swift` sets two
debug overrides, constructs the view model, sets origin/destination, and
calls `refresh()`, six lines under one `justBeforeEach`. The Kotlin wrapper
needs to take an arbitrary block, not special-case a single expression.

## Convention: one `justBeforeEach` per chain

Across every real usage found (`next-caltrain-swift`'s `TripViewModelSpec`/
`CaltrainServiceSpec`/`CaltrainScheduleSpec` x2/`GoodTimesSpec`, `zouk`'s
`ScanClientSpec` x3/`AppModelSpec`), nobody ever stacks two `justBeforeEach`
at different depths in the same nesting chain -- always exactly one per
"describe the action" grouping, with sibling `context`s underneath
contributing only varying inputs. Document this as a rule once the DSL
makes stacking them syntactically possible, so nobody's tempted to nest them
just because the tool allows it.

## Direct-assign vs. closure-`subject`

`gorderly`'s and `kotidy`'s `docs/FRAMEWORK.md` both document a closure-based
`subject := { ... }` pattern for sharing a computed value across sibling
`it`s. But in every real Swift `justBeforeEach` usage, the block assigns
straight into a shared `var` declared next to it (`var result: ScheduleType!`,
`var direction: String!`) -- nobody routes through a `subject`-style closure
once `justBeforeEach` exists. That closure pattern is really a workaround for
frameworks with no `justBeforeEach` (Go's `spec` has no such hook at all).

Once this library exists, direct-assign-in-`justBeforeEach` should be the
default for the common "one action, one result" case -- it's the eager-
`subject!` shape RSpec itself prefers. Reserve closure-`subject` for the
narrower case where an `it` needs to invoke the action more than once, or
with a locally-tweaked argument. Worth a line in all three `FRAMEWORK.md`s
(`gorderly`, `kotidy`, `xctidy`) clarifying this split once the library
ships, not just this repo's own docs -- see "FRAMEWORK.md follow-ups" below.

## The `runCatching`-outcome convention

Found by reading `expect`'s own `docs/COWORK.md` and its `Panic` matcher
(`Matcher[func()]`, `expect.go`): `Panic()` only works because the closure
under test is passed *uninvoked* -- `Expect(func() { mustParse("bad") },
t).To(Panic())` -- the matcher calls it and recovers, inside the assertion.
That's the only design that works for Go's flat `Expect().To()` style, which
has no hoisted "act" step to worry about.

That's exactly the case naive `justBeforeEach` hoisting breaks. Once the act
step lives in a shared `justBeforeEach { result = client.delete(scan) }`
above several sibling contexts, a context that expects the call to *throw*
can't use it -- the exception fires during setup, outside any `it`, and
fails the test run as a setup error instead of landing as a normal assertion
failure. No real usage found yet that needs an error-path sibling under a
shared `justBeforeEach` (every real example is happy-path-only), but it's a
foreseeable gap worth designing for now rather than discovering later.

The fix: have `justBeforeEach` capture the outcome, not just the success
value -- Kotlin's `runCatching { }` gives this for free.
`justBeforeEach { result = runCatching { client.delete(scan) } }`, then a
happy-path context asserts `result.getOrThrow() shouldBe ...` and an
error-path sibling asserts on `result.exceptionOrNull()` -- same hoisted
action, both branches testable, no eager-throw crashing setup. Same problem
`Panic()` solves for Go's flat style, solved the way a hoisted-action
framework has to solve it: capture-as-value instead of defer-as-closure.
Ship this as a documented convention (or a small `justBeforeEachCatching`-
style helper) alongside plain `justBeforeEach`, with its own dogfooded test
in this repo's suite -- same instinct as `expect_test.go`'s dedicated
`describe("Panic")` block, which exists to prove the pattern compiles and
behaves, not just to read well in a README.

## Naming: checked for collisions before creating the repo

- GitHub: `just-before-each` returns `"total_count": 0` from GitHub's own
  repository search API -- unused.
- Maven Central: no group or artifact matching `justbeforeeach`/
  `just-before-each` on Maven Central's search index.
- Kotlin symbol: Kotest's lifecycle-hooks doc has no `justBeforeEach` or
  equivalent; Spek (the other Kotlin BDD framework) doesn't have one either.
  No existing library defines a top-level `justBeforeEach` an import could
  collide with.

Open naming question, not a collision: `kotidy`/`gorderly`/`xctidy` are all
coined portmanteaus (tool + tidy/orderly); `just-before-each` would be the
first repo in this family named literally after the feature instead. Not a
problem, just noted in case a `kotidy`-style name is ever preferred instead
-- woodie's call.

## Package naming

`com.netpress.justbeforeeach` (dashes dropped, matching how `humane-kotlin`
maps to `com.netpress.humane` -- Kotlin/Java package segments can't contain
hyphens).

## FRAMEWORK.md follow-ups, once the DSL ships

- `kotidy`'s `docs/FRAMEWORK.md` gets a new `justBeforeEach` section
  mirroring `xctidy`'s, using this library, plus the direct-assign-vs-
  closure-`subject` clarification above.
- `gorderly`'s and `xctidy`'s `docs/FRAMEWORK.md` both get the same direct-
  assign-vs-closure-`subject` clarification, since the split applies to
  Quick's real `justBeforeEach` too, not just a Kotlin-specific nuance.
- `kotidy`'s `docs/FRAMEWORK.md` gets a named "`afterEach` for cleanup"
  section (mirroring `xctidy`'s), since `next-caltrain-kotlin` already
  pairs `debugOverrideDotw` stubbing with `afterEach { ... = null }` but the
  convention isn't documented as its own subsection yet.
- `next-caltrain-kotlin`'s `GoodTimesSpec.kt` gets its `debugOverrideDotw`
  context rewritten to use `justBeforeEach`, dropping the local `setDotw()`
  helper -- the concrete motivating example for this whole library.

## Current status

Repo scaffolded (Gradle/Kotlin project, Kotest + ktlint + `kotidy` wired up,
CI, `Makefile`), and the DSL itself is written: `justBeforeEach` +
`JustBeforeEachExtension` in `src/main/kotlin/.../JustBeforeEach.kt`, wired
into the test `ProjectConfig`, with a dogfood spec
(`JustBeforeEachSpec.kt`) covering ordering and the `runCatching`-outcome
convention. No Maven Central publishing setup yet; see "Packaging" above
for why that's deliberate.

Built entirely by inspection, no local toolchain to confirm against (see
`~/workspace/woodie/docs/COWORK.md`'s "Working on unfamiliar stacks") --
`./gradlew clean check` needs a real Mac run before trusting any of this
actually compiles. See "Core mechanism" above for the specific API surface
most likely to need a small fix once real Kotest `5.9.1` jars are involved.
