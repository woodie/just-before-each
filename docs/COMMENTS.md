# Comments

Rationale, history, and design notes that used to live as multi-line
comments in the source. Organized by file, then by the type, property, or
function each note is attached to. The source itself now carries at most
one short line at any given spot -- anything longer that would previously
have been a `///` doc comment or a multi-line `//` note lives here instead.

## src/test/kotlin/com/netpress/kwick/JustBeforeEachSpec.kt

### `context("a real suspend call inside justBeforeEach")`
Kept a one-line comment in place: "Proves the suspend-first signature
actually suspends; see docs/COMMENTS.md."

Full history: regression/proof case for the suspend-first API --
`justBeforeEach`'s signature (`suspend TestScope.() -> Unit`) was written
to support a real suspend call from the start (see `docs/COWORK.md`,
"Scope for v1: suspend support is not a follow-up"), but nothing before
this exercised an actual suspension point -- every other case in this
file is synchronous (`result = input.uppercase()`, `runCatching { act(...) }`).
`delay()` forces a real coroutine suspend/resume, mirroring zouk's
`ScanClientSpec` shape (`justBeforeEach { try await client.delete(scan) }`).
