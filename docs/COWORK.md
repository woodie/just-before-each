# Picking up kwick in a new Cowork session

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
here first, consume it from `next-caltrain-kotlin` (and later `huck`) as a
composite build (`pluginManagement`-free `includeBuild("../kwick")`, a plain
dependency substitution -- not the `pluginManagement { includeBuild(...) }`
form `kotidy` needed, since this isn't a plugin), then add
`com.vanniktech.maven.publish` + Central Portal signing once it's stable
enough to publish for real, the same way `humane-kotlin` went from
composite-build-only at `v0.1.0` to Maven Central at `v0.1.1`. That condition
is now met (see issue #2 and `docs/releases/v0.1.2.md`) -- `build.gradle.kts`
carries the real `mavenPublishing {}` block and `CI.yml` has the tag-triggered
`publish` job; both `next-caltrain-kotlin` and `huck` switch off their own
composite build separately, once this release is confirmed live on the
Central Portal (issue #3).

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
the original inspection-only guess needed checking.

Two more rounds after that: a ktlint style violation (`class
JustBeforeEachSpec : DescribeSpec({` needed the supertype on its own line,
official style -- matches `humane-kotlin`'s `HumanSizeSpec.kt` exactly),
then a real compile error (`lateinit` doesn't work on `Result<T>` -- it's a
Kotlin inline value class, and `lateinit` is restricted to non-null,
non-primitive reference types, which also silently ruled out the README's
`lateinit var result: Int` example the same way, just not yet caught by a
compiler since that one's doc-only). Both fixed; a throwaway-placeholder
`var` replaces `lateinit` in both the spec and the README, with a new
README "Gotchas" section explaining why.

**`./gradlew clean check` is green** -- confirmed on a real Mac, all 4
`JustBeforeEachSpec` examples passing: the ordering guarantee (`beforeEach`
then `justBeforeEach` then `it`, and `justBeforeEach` declared above a
context with its own deeper `beforeEach` still running after it) and the
`runCatching`-outcome convention (happy path and error path sharing one
hoisted action). This is the first real, verified confirmation that the
whole mechanism actually works, not just that it compiles.

## Real bug, found post-v0.1.0: container test cases were getting wrapped too

`next-caltrain-kotlin` pushing `justBeforeEach` into more of its specs (see
its own commit adding it to `CaltrainScheduleSpec.kt`'s `.optionIndexFor()`
and `CaltrainServiceSpec.kt`'s `.direction()`) hit a real `make test`
failure: `kotlin.UninitializedPropertyAccessException` on a `lateinit var`
that a nested `beforeEach` was supposed to set before `justBeforeEach` ever
read it. Reported as an `initializationError` on the *container* itself
(`with no special dates`, `when traveling from San Francisco to Gilroy`),
not on any individual `it` -- the tell that something was running during
tree discovery, not during a real test.

Root cause: `TestCaseExtension.intercept` fires for every `TestCase`
Kotest builds, and Kotest represents *every* `describe`/`context` as a
`TestCase` too (`type = TestType.Container`), not just `it` (`type =
TestType.Test`). `JustBeforeEachExtension.intercept` never checked
`testCase.type`, so it wrapped container test cases exactly the same way
as leaf ones -- and a container's own `test` closure is the code that
*discovers its children*, which runs well before any descendant
`beforeEach` has assigned anything. Wrapping it means the hoisted action
runs during that discovery pass, reading whatever's already been assigned
(so a `var` with a real default value, like `debugOverrideDotw`'s `var dotw
= 0` in `next-caltrain-kotlin`'s `GoodTimesSpec.kt`, silently no-ops and
gets overwritten later, no crash) or crashing outright on an unassigned
`lateinit var`.

This bug existed since `v0.1.0` -- it just never showed up. Every real
usage up to this point either didn't read an uninitialized value inside
`justBeforeEach` (`GoodTimesSpec.kt`'s `dotw` has a default of `0`) or had
its side effects erased before the real assertion ran (the dogfood spec's
`log.clear()` in a `beforeEach` above the affected block). The new
`next-caltrain-kotlin` usages were the first to hoist a `lateinit var` read
with a container sitting between the `justBeforeEach` and the `it` --
which, per "Core mechanism" above, is the normal shape, not an edge case.

Fixed in `JustBeforeEach.kt`'s `JustBeforeEachExtension.intercept`: skip
entirely (`return execute(testCase)`) unless `testCase.type == TestType.Test`.
Container test cases now pass through completely untouched; only real leaf
`it`s get their `test` closure wrapped. Added a regression case to
`JustBeforeEachSpec.kt` ("reading a lateinit var only set by a nested
beforeEach") that reproduces the exact shape -- would throw
`UninitializedPropertyAccessException` without the fix, passes with it.

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

## Naming: checked for collisions, twice

First pass, before creating the repo (under the original name,
`just-before-each`):

- GitHub: `just-before-each` returns `"total_count": 0` from GitHub's own
  repository search API -- unused.
- Maven Central: no group or artifact matching `justbeforeeach`/
  `just-before-each` on Maven Central's search index.
- Kotlin symbol: Kotest's lifecycle-hooks doc has no `justBeforeEach` or
  equivalent; Spek (the other Kotlin BDD framework) doesn't have one either.
  No existing library defines a top-level `justBeforeEach` an import could
  collide with.

Second pass, once `com.netpress.justbeforeeach.justBeforeEach` proved
painful to read as an import and the repo was renamed. `komplete`/
`Komplement` (woodie's own suggestions) were ruled out first -- Native
Instruments' "Komplete Kontrol" product line is a real collision risk.
`kwick` (a pun on [Quick](https://github.com/Quick/Quick), the Swift
framework this is ported from) checked clean instead:

- GitHub: ~271 substring hits on `kwick`, none of them Kotlin or
  testing-related.
- Maven Central: no group or artifact matching `kwick`.

Decision (resolved, not open): rename to `kwick`. Note for later: every
other repo in this family (`kotidy`/`gorderly`/`xctidy`) is a coined
portmanteau of tool + tidy/orderly; `kwick` breaks that pattern slightly
(named after Quick, not after "tidy"), which is fine but worth knowing if a
`kotidy`-style name ever feels better later -- woodie's call.

The extension itself stays `JustBeforeEachExtension`/`justBeforeEach` --
considered renaming to `BeforeExtension` alongside the package, rejected
because it collides conceptually with Kotest's own `Before*` vocabulary
(`BeforeTest`/`BeforeEach`/`BeforeAny`/`BeforeContainer` are real Kotest
typealiases).

## Package naming

`com.netpress.kwick` -- no dashes to drop this time (unlike
`humane-kotlin` -> `com.netpress.humane`), since `kwick` was chosen without
any.

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

`./gradlew clean check` is green on a real Mac (JDK 17, Gradle 9.4.1, Kotlin
2.1.0 via the Gradle toolchain) -- all 4 `JustBeforeEachSpec` examples
passing, ktlint clean. Repo scaffolded (Gradle/Kotlin project, Kotest +
ktlint + `kotidy` wired up, CI, `Makefile`); the DSL itself is written and
working: `justBeforeEach` + `JustBeforeEachExtension` in
`src/main/kotlin/.../JustBeforeEach.kt`, wired into the test
`ProjectConfig`, with a dogfood spec (`JustBeforeEachSpec.kt`) covering the
ordering guarantee and the `runCatching`-outcome convention end to end. No
Maven Central publishing setup yet; see "Packaging" above for why that's
deliberate -- next real step once this is ready to be consumed by
`next-caltrain-kotlin`.

Took four real-Mac rounds to get here from the first inspection-only pass
(see "Core mechanism" above for the specific bugs each round caught) --
consistent with this account's "Working on unfamiliar stacks" convention:
the sandbox can build by inspection and reason about design, but only a
real toolchain run can actually confirm Kotest's exact API surface.

## Consumed by

[`next-caltrain-kotlin`](https://github.com/woodie/next-caltrain-kotlin) --
via a composite build (`includeBuild("../kwick")` in its
`settings.gradle.kts`, `testImplementation("com.netpress:kwick:0.1.0")` in
`app/build.gradle.kts`), not a published artifact, so both
repos need to sit as sibling directories on disk. `GoodTimesSpec.kt`'s
`debugOverrideDotw` context is the concrete, motivating rewrite -- see its
own commit for the before/after. CI there needs a temporary sibling-
checkout step for the same reason (see its `CI.yml`); both repos also need
to actually be pushed before that CI run can go green, since it checks out
`woodie/kwick` fresh rather than reading the local mount.

Per this account's own "Releasing across multiple repos" convention: prove
this out for real in `next-caltrain-kotlin` first (real `./gradlew clean
test` run, not just `check` here), then tag a release -- not before. See
"Packaging" above for the same sequencing applied to publishing.

**Confirmed, real Mac, `make test`:** all 81 examples pass, including the
`debugOverrideDotw` context exercising `justBeforeEach` for real (not just
in this repo's own dogfood spec) -- `beforeEach` setting `dotw` in each
sibling context, `justBeforeEach` at the parent consuming it, in the
Friday/Saturday/Sunday cases. `make lint` clean too. This is the real,
cross-repo confirmation the sequencing above was waiting on -- tagging a
release here is unblocked as of this run.

**Real CI failure, since fixed:** the original sibling-checkout step used
`path: ../kwick` directly in `actions/checkout@v4`, on the assumption that a
`path` outside `$GITHUB_WORKSPACE` would just resolve to that sibling
directory. It doesn't -- `actions/checkout@v4` refuses any `path` that
isn't under the workspace ("Repository path ... is not under ..."), so the
step failed outright the first time CI actually ran (never caught by
`make test` on a real Mac, since that only exercises the Gradle side, not
the checkout step itself). Fixed in `next-caltrain-kotlin`'s `CI.yml` by
checking out into a workspace-relative subdirectory (`path:
kwick-checkout`) and adding a plain `mv kwick-checkout ../kwick` step
after it -- `run:` steps default to `$GITHUB_WORKSPACE`, so that lands
exactly at the sibling path Gradle's `includeBuild` expects. Worth knowing
before reaching for this same trick in any other consumer (`kotidy`'s,
`gorderly`'s, or `xctidy`'s, if one of them ever needs an unpublished
sibling dependency) -- the direct `path: ../...` form doesn't work at all.

## Tag/push status

`v0.1.0` is tagged locally (annotated, `git rev-parse HEAD` and
`git rev-parse v0.1.0^{commit}` both confirmed matching), with
`docs/releases/v0.1.0.md` written for `gh release create --notes-file`.
**Nothing has been pushed to GitHub yet** -- neither this repo nor
`next-caltrain-kotlin`. Until both are pushed: the README's CI/Release
badges will show broken/gray, `next-caltrain-kotlin`'s own CI can't run at
all (its workflow checks out `woodie/kwick` fresh, which doesn't
exist remotely yet), and the `gh release create` command above has nothing
to attach to. Push `kwick` first (tag included), then
`next-caltrain-kotlin`, then run the `gh release create` command.

## Next steps (drafted this session, not filed as real issues yet)

Seven follow-ups were written up as GitHub-issue-shaped markdown files
during this session, one per repo/topic, but they only exist as session
scratch output right now -- not committed anywhere, not filed via `gh issue
create`. If they're not filed for real before this thread is gone, a future
cold session has no way to find them except this paragraph, so treat filing
them (or at least committing the drafts somewhere durable) as the actual
next action, not an optional cleanup:

- **`kwick`**: publish to Maven Central (sign and publish,
  following `humane-kotlin`'s `v0.1.0` -> `v0.1.1` bootstrap exactly),
  under the `com.netpress:kwick` coordinate -- the naming question is
  resolved (see "Naming" above), so this is unblocked.
- **`next-caltrain-kotlin`**: switch off the composite build once the Maven
  Central publish lands (drop `includeBuild`, pin a real version, drop the
  CI sibling-checkout step).
- **`kotidy`**: `docs/FRAMEWORK.md` gets a new `justBeforeEach` section
  (mirroring `xctidy`'s) plus a named `afterEach`-for-cleanup section.
- **`gorderly`** and **`xctidy`**: `docs/FRAMEWORK.md` both get the
  direct-assign-vs-closure-`subject` clarification from above.
- **`huck`**: revisit `ScanClientSpec.kt`'s inline-per-`it` suspend setup
  once this library is published -- the concrete first real use case for
  the suspend support already built in.
