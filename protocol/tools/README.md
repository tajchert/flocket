# Flocket host tools

## `flocket-share`

A POSIX `sh` helper that an agent (or you) runs inside a herdr terminal to show files in
Flocket on your phone: `flocket-share [-m NOTE] PATH…`. It appends an entry to the
`flocket-share` document of the local herdr-core (convention in
[`../SPEC.md`](../SPEC.md) §9.2, decision in
[ADR-0007](../../docs/decisions/0007-transfer-connection-and-agent-shares.md)).

It needs `hc` (or `herdr-core`, or `$HC_BIN`), `sed`, `od`, `tr`, `wc` and `date`. It
runs only inside a herdr terminal (`HC_TERMINAL_ID` set).

Exit codes: 0 ok; 1 a path is missing or unreadable (nothing is shared); 2 not in a
herdr terminal; 3 could not reach the core (the message ends with a hint to rerun
outside a sandbox).

### Install

The Flocket app can install it for you from the host screen. By hand:

```sh
install -m 755 flocket-share ~/.local/bin/
```

Make sure `~/.local/bin` is on the `PATH` of the shell your agents use.

### Agent instruction

Paste this paragraph into your `AGENTS.md` or `CLAUDE.md`
([`agent-instruction.md`](agent-instruction.md) holds the exact text; the app bundles it
byte for byte):

> To show me a file on my phone (an image, text or anything else up to 8 MB), run
> `flocket-share PATH…` from this terminal, optionally with `-m "short note"`. If it
> fails because of a sandbox, rerun the same command outside the sandbox.

### Test

```sh
sh protocol/tools/test-flocket-share.sh
```

It starts an isolated `flocket-test-*` session, runs the helper against it, and stops
the session afterwards. It is skipped when `hc` or `python3` is missing.
