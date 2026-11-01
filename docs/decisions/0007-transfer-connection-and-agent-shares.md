# ADR-0007: Separate transfer connection and agent shares

Status: accepted (2026-11-01)

## Context
Flocket now moves files: it pastes images and files into a terminal (`terminal.put`),
sends a file to a session's folder (`file.write`), and downloads files that an agent
shared (`machine.exec cat`). A file of 8 MB is about 10.7 MB as base64 in one JSON frame.
Design: [`docs/design/2026-11-01-paste-files-share.md`](../design/2026-11-01-paste-files-share.md).
Wire facts: [`protocol/SPEC.md`](../../protocol/SPEC.md) §4.17–§4.20 and §9.2.

## Decision

### 1. Transfers use their own core connection
Uploads and downloads run on a second, short-lived `hc connect` (another exec channel on
the host's existing SSH session, or a new transport as a fallback). The main connection
carries terminal output, input and events in order. A 10.7 MB frame on it would block
everything behind it (head-of-line blocking), so a paste would freeze every terminal
until it finished. SSH channel flow control interleaves the transfer channel with the
terminal channel instead. The transfer connection opens on demand and closes when idle.
SPEC §1 allows it explicitly.

Mutations (`terminal.put`, `file.write`, `chmod`) are never retried: a loss after the
write gives an unknown outcome. Reads (`cat`, `wc`, `test -e`, `command -v`, `doc.get`)
may retry once.

### 2. Agents share through the `flocket-share` document
An agent in a herdr terminal runs `flocket-share PATH…`, which prepends an entry to the
`flocket-share` document of the local core (SPEC §9.2). The app watches the document
(including remote machines, with `on`) and shows a card on the matching terminal. A
document is used rather than a new core method, because documents need no change to
`hc`, survive restarts, and already notify subscribers.

### 3. The helper is user-installed and fails clearly when sandboxed
`protocol/tools/flocket-share` is POSIX `sh` with no `jq` or `python`. The app bundles
the same file and installs it only on an explicit user action. Sandboxed agents (Codex,
Claude Code sandbox mode) may be blocked from the core's socket. The helper then exits 3
with a message telling the agent to rerun the command outside the sandbox, which both
tools already ask the user to approve. There is no fallback channel (user decision,
2026-11-01).

### 4. Federated transfers: measured later
Transfers to `rohan/…` go through the local core's link to rohan, which the app does not
control. We accept this for v1. Task T7 measures with a real core whether an 8 MB remote
transfer stalls other `rohan/*` terminals, and records the result here. If it does, a
later change lowers the size cap for remote machines.

## Consequences
- A second connection per host while a transfer runs; the transfer code must not share
  state with the main session.
- `flocket-share` depends on the document's read-back shape (sorted keys), which is
  pinned by `protocol/fixtures/transfers-0.1.16.json` and tested by
  `protocol/tools/test-flocket-share.sh`.
- The helper keeps stdin open until the reply line arrives (10 s at most); an empty `doc.set` reply is reported as "may or may not have been recorded" (exit 3).
- The share list is bounded (64 KiB); the app keeps its own history.
