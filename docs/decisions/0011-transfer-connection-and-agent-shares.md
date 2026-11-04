# ADR-0011: Separate transfer connection and agent shares

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

### 4. Federated transfers stall that machine's terminals on a slow link
Transfers to `rohan/…` go through the local core's link to rohan, which the app does not
control. Measured on 2026-11-01 with `hc` 0.1.16 (`TransferRealCoreIntegrationTest` in
`core:session`): rohan is a second isolated core added with `hc machine add --via`. A
rohan terminal and a local one print a line every 20 ms, and a third rohan terminal is
typed into every 100 ms. The table gives the longest gap in output and the slowest
keystroke echo while the transfer runs.

| Link to rohan | Transfer (8 MiB) | Duration | Rohan output gap | Rohan echo | Local output gap |
|---|---|---|---|---|---|
| pipe (`sh -c`) | none (baseline) | 2.0 s | 43 ms | 31 ms | 42 ms |
| pipe (`sh -c`) | `readFile` from rohan | 0.09 s | 51 ms | 21 ms | 31 ms |
| pipe (`sh -c`) | `writeFile` to rohan | 0.21 s | 33 ms | 30 ms | 32 ms |
| 10 Mbit/s each way | none (baseline) | 2.0 s | 43 ms | 30 ms | 43 ms |
| 10 Mbit/s each way | `readFile` from rohan | 12.0 s | 11 962 ms | 11 855 ms | 45 ms |
| 10 Mbit/s each way | `writeFile` to rohan | 12.1 s | 47 ms | 11 782 ms | 44 ms |

Result: the core's link to a machine carries one frame at a time. While a large transfer
crosses it, every other terminal on that machine stops for the whole transfer. A download
freezes their output and echo; an upload freezes their input. The stall lasts as long as
the transfer, about 10.7 MB of base64 divided by the link's bandwidth. On a fast link it
is not noticeable. The app's own transfer connection still works: local terminals were
never affected. We accept this for v1, with the 8 MB cap on every machine. A later change
should lower the cap for remote machines, or send large remote files in chunks, so that
a stall stays under about a second on a typical link.

## Consequences
- A second connection per host while a transfer runs; the transfer code must not share
  state with the main session.
- `flocket-share` depends on the document's read-back shape (sorted keys), which is
  pinned by `protocol/fixtures/transfers-0.1.16.json` and tested by
  `protocol/tools/test-flocket-share.sh`.
- The helper keeps stdin open until the reply line arrives (10 s at most); an empty `doc.set` reply is reported as "may or may not have been recorded" (exit 3).
- The share list is bounded (64 KiB); the app keeps its own history.
