# ADR-0004: Native code per platform, with shared protocol fixtures

Status: accepted (2026-11-01)

## Decision
Android (Kotlin and Compose) and iOS (Swift and SwiftUI) each implement the herdr-core
protocol natively. They share **`/protocol`**:

- `SPEC.md`: the implementer-ready protocol description.
- `schema/`: snapshots of `hc schema`.
- `fixtures/`: captured wire exchanges and frame byte dumps.

Both test suites must pass against the same fixtures.

## Why
The handoff recommends this approach. Each app stays idiomatic, the iOS team does not
depend on the Kotlin toolchain, and shared fixtures still catch drift between the two
implementations.

## Rules
- A protocol behaviour change starts with a fixture, and both platforms adopt it.
- Fixtures record the `hc --version` they were captured from.
- The Kotlin protocol and session modules stay pure Kotlin/JVM, so they remain quick to
  test. They avoid Android APIs, and could move to KMP later if that is ever re-decided.
