.PHONY: build test lint format check

# ktlintFormat runs first in its own Gradle invocation, so it's fully done before
# build/check runs ktlintCheck against the result -- see humane-kotlin's own
# Makefile (and huck's docs/COWORK.md) for why listing both tasks in one
# gradlew call doesn't guarantee that ordering.
build:
	./gradlew ktlintFormat
	./gradlew build -x test

# clean, not just test -- Gradle otherwise marks the test task UP-TO-DATE on
# an unchanged run and skips re-executing it, which also skips kotidy's
# tree-rendering TestListener output entirely (it only prints on real
# execution). Matches next-caltrain-kotlin's/humane-kotlin's test.sh/Makefile.
test:
	./gradlew ktlintFormat
	./gradlew clean test

# Check-only, no formatting -- fails loudly on style violations instead of
# silently fixing them.
lint:
	./gradlew ktlintCheck

# Auto-fixes the mechanical stuff ktlintCheck flags. build/test/check
# already run this first; call it directly only if you want formatting alone.
format:
	./gradlew ktlintFormat

check:
	./gradlew ktlintFormat
	./gradlew clean check
