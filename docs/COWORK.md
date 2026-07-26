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

## Core mechanism: no engine hook needed

Kotest already guarantees every ancestor `beforeEach`/`beforeTest` at any
nesting depth completes before an `it`'s test body runs. `justBeforeEach`
doesn't need a `TestCaseExtension`/interceptor to get Quick's guarantee ("runs
after every `beforeEach` at every depth, immediately before the `it`") --
it just needs to run *inside* the test body, at the very start.

The implementation shadows three DSL entry points (`describe`, `context`,
`it`) plus adds `justBeforeEach`, threading a registration-time stack:
`context { ... }` pushes/pops a frame, `justBeforeEach { }` registers a
block on the current frame, and `it("...") { }` captures the full active
stack (root-to-leaf, at registration time) and wraps the real test body to
run those captured blocks first, then the actual assertion. Because that
wrapped body only executes after Kotest's own real `beforeEach` chain has
already fired, the captured `justBeforeEach` blocks run at exactly the right
moment for free.

One constraint this puts on the implementation, not a new feature: Kotest
builds a spec's whole test tree in one pass -- the closure passed to
`describe`/`context` runs exactly once per spec instance to register every
`it` beneath it, not once per `it` (see `kotidy`'s own `docs/FRAMEWORK.md`,
"Computed-once context locals vs. `subject`"). The stack push/pop has to
happen at that one-time registration pass, and `it`'s snapshot of the active
stack has to be captured then too -- not something that can be deferred to
actual test-run time. Needs a dedicated test: a `context` with two sibling
`it`s under one `justBeforeEach`, confirming the stack doesn't leak state
across sibling registrations or between spec instances.

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
CI, `Makefile`) but the DSL itself -- the shadowed `describe`/`context`/`it`/
`justBeforeEach` -- isn't written yet. No Maven Central publishing setup
yet either; see "Packaging" above for why that's deliberate. Built by
inspection with no local toolchain to confirm against (see
`~/workspace/woodie/docs/COWORK.md`'s "Working on unfamiliar stacks") --
`./gradlew clean check` needs a real Mac run before trusting any of this
compiles.
