# ADR-0001: App identity

Status: accepted (2026-11-01)

## Decision
The product is called **Flocket**, and it describes itself as "a native client for
herdr-core". Identifiers come from flocket.mtajchert.com:

- Android `applicationId` and root Kotlin package: `com.mtajchert.flocket`
- Debug builds use the `.debug` suffix, so both builds can be installed side by side.
- iOS bundle id (future): `com.mtajchert.flocket`

## Why
Using "Herdr" in the name of an unofficial client risks a trademark conflict, and makes
the app look like an official herdr product. A reverse-DNS id we control avoids
collisions on Google Play and the App Store.

## Consequences
Every module namespace is `com.mtajchert.flocket.<layer>.<name>`, for example
`com.mtajchert.flocket.core.protocol`.
