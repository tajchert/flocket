# Flocket — guide for agents and contributors

Flocket is a set of native mobile clients for **herdr-core** (`hc`). This file is
shared by every platform. Read it first, then the guide for your platform:

- Android: [`android/AGENTS.md`](android/AGENTS.md)
- iOS: `ios/AGENTS.md` (future)

## Repository map

| Path | What lives there |
|---|---|
| `protocol/` | The herdr-core wire protocol: `SPEC.md`, `hc schema` snapshots, and wire fixtures shared by every platform |
| `docs/decisions/` | Architecture Decision Records (ADRs). These are binding. Read them before changing architecture. |
| `docs/ROADMAP.md` | Next milestones: scope, open decisions, and where to start in the code |
| `docs/design/` | Milestone design documents |
| `docs/reference/` | Background research (the herdr-core handoff). Context only, not rules. |
| `docs/readme/` | README art (banner, divider, glyph, icon SVGs) and screenshots |
| `android/` | Native Android app (Kotlin, Compose). A self-contained Gradle project. |

## Product rules (all platforms)

1. **Only herdr-core.** Support the latest `hc`. Negotiate `hello`: refuse an
   incompatible *generation*, and accept additive versions and unknown fields. Never
   check for an exact version.
2. **The core owns runtime.** Closing or detaching the app never stops a terminal or an
   agent.
3. **Mutations are at most once.** Never replay input, keys, run, kill or approvals
   after a connection drops. Show an *unknown outcome* and re-read the state.
4. **Status is not liveness.** Agent status comes from the program and may be unknown.
   Process state is separate.
5. **Addresses are opaque and federated.** Keep `t7` and `rohan/t7` distinct, and never
   strip a machine prefix.
6. **Trust before credentials.** Verify the SSH host key before authenticating. A changed
   key is a hard stop.
7. **Hosts are independent.** One host failing or reconnecting never blocks another.
   Stale data is labelled as stale, and writes are disabled while stale.

## Working rules

- **Protocol changes start in `protocol/`.** Update `SPEC.md` and add or refresh a
  fixture, then change each platform. Fixtures record the `hc --version` they came from.
- **Do not touch a developer's real herdr session.** Tests and experiments use an
  isolated session: `hc ... --session flocket-test-<something>`, or
  `HC_SESSION=flocket-test-<something>`. Stop it when you are done.
- **Licensing** ([ADR-0002](docs/decisions/0002-license.md)): Apache-2.0. Keep headers
  on adapted code and record it in the notices. Never copy from GPL or AGPL sources.
- **Commits:** use Conventional Commits (`feat(android): …`, `fix(protocol): …`,
  `docs: …`). Keep each commit small and green, and run the platform's verification
  commands first.
- **Docs:** update the README and AGENTS.md closest to your change when behaviour,
  commands or conventions change. Keep each command in one canonical place and link to it.
- **Committed docs never link to untracked or ignored files** (for example `.work/`).
- **New architecture-wide choices** need a new ADR in `docs/decisions/`.
