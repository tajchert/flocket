# herdr-core wire fixtures

Captured 2026-11-01 from **hc 0.1.15** (except where a file says 0.1.16) (`hc --version`; protocol generation 1) on macOS,
against an isolated session (`hc connect --session flocket-research`, core started with
`HC_SESSION=flocket-research HC_HEARTBEAT_MS=2000 hc server start`, so heartbeats arrive every 2 s
instead of 15 s). The session was stopped and its state deleted afterwards; no default-session
terminal was touched. The schema of the same binary is in `../schema/hc-0.1.15.openrpc.json`
(identical to the e07c2a1 / 0.1.16 source schema except `info.version`).

Core identity in every fixture: `{"id":"86bd470d4c557443479c94e20b614001","name":"tajchertminim6"}`.
Timestamps (`created_ms`, ...) are real epoch ms. `t_ms` is ms since that connection's process started.

## File formats

| Suffix | Format |
|---|---|
| `*.chunks.jsonl` | Raw bytes exactly as written to / read from `hc connect` stdin/stdout. One JSON object per OS read or write: `{"t_ms","dir":"in"/"out","len","hex"}`. Read boundaries are real: use these to test the incremental reader (plain→framed switch, frames split or coalesced across reads). Last line is `{"stderr": ...}` (always empty). |
| `*.decoded.jsonl` | The same traffic decoded per message. Plain mode: `{"mode":"plain","msg":{...}}`. Framed mode: `{"mode":"framed","channel","kind","kind_name","len","header_hex", ...}` plus `json` (channel 0), `credit` (CREDIT), `cols`/`rows` (RESIZE), `focused` (FOCUS), `text` (CLOSE/FACTS and stream DATA, UTF-8 lossy), `hex` (stream DATA, exact bytes). |
| `*.json` | Request/response summaries produced by the capture scripts (keys name the scenario). |
| `*.hex` | One hex string: an exact payload. |

## Files

| File | What it shows |
|---|---|
| `plain-session.chunks.jsonl` / `.decoded.jsonl` | Plain NDJSON mode: `hello` without framing, `hello` with `generation: 2` (error `unsupported_generation`), `terminal.list`, errors `not_found`, `machine_unknown`, `needs_framed`, `unknown_method` (-32601), `invalid_params` (-32602), `parse_error` (-32700, `id: null`), a request with no `params` member (accepted), `server.status`, `templates.list`, `machine.list`, then plain `events.subscribe` (`channel: null`) followed by a `doc` event and a `heartbeat`. A request sent after the plain subscribe (id 500) gets **no reply**. |
| `plain-helper.decoded.jsonl` | Second plain connection that wrote the doc that produced the `doc` event above. |
| `framed-session.chunks.jsonl` / `.decoded.jsonl` | Main framed scenario, in order: `hello {framed:true}` (plain line) → framed `events.subscribe` (channel 1) → `terminal.list {all:true}` → `server.status` → `terminal.run {argv:["sh"], name:"probe", labels, env}` → `created` + `foreground` events → `terminal.attach` (channel 2, cols 80 rows 24, display facts) → first paint → DATA `echo hello-flocket\r` and echoed output → CREDIT → program emits OSC 7501 `working` then `idle` → `status`, `turn` events → RESIZE 100x30 → `size` event → FOCUS 1, FACTS, FOCUS 0 → `terminal.read`, `terminal.label`, `terminal.keys` → `doc.get herdr-app` (unwritten: `{value:null,version:0}`), `doc.set` v0→1, stale `doc.set` (`conflict`), `doc.set null` (delete keeps version 2), bad doc name → viewer CLOSE + empty CLOSE echo + `size` back to headless 200x120 → observe attach with `history: 50` (channel 3) → `terminal.send`, `terminal.interrupt`, `terminal.kill` → core CLOSE on channel 3 with `{"exit_code":null,"finished_ms":…,"reason":"killed","signal":1}` → `status` (cleared) then `finished` events → attach on finished (`finished` error) → `terminal.rm` → `removed` event → `machine_unknown` for `rohan/t7` → filtered subscribe (channel 4) that does **not** receive the `doc` event the unfiltered channel 1 receives → CLOSE on an event channel (unsubscribe) → heartbeats. |
| `attach-first-paint.hex` | Exact first DATA payload of the attach in `framed-session` (0.1.15: starts with `ESC [ ! p`; 0.1.16 prefixes a `0x18` CAN byte). It already contains a live prompt redraw after `ESC[?9001l`: paint and live output share frames. |
| `attach-first-paint-0.1.16.hex` | Same capture on **hc 0.1.16** (80x24, `sh`): starts with `18` (CAN) before `ESC [ ! p`, as SPEC section 13 predicted. Produced by `../tools/capture_first_paint.py`. |
| `pipelined.chunks.jsonl` / `.decoded.jsonl` | `hello {framed:true}` line and a framed `events.subscribe` frame sent in ONE write: the core accepts the pipelined frame. (In this run the two replies came in separate reads; they can also come in one.) |
| `viewer-a/-b/-observer.decoded.jsonl` | Three connections attached to one terminal: A (80x24), B (120x40), observer (50x10, `observe:true`). Resulting `size` sequence on the event stream is in `flow-control.json` → `multi_viewer_size_events`: `80x24` (A attaches) → `120x40` (B attaches) → `80x24` (A types) → `120x40` (B FOCUS 1) → observer RESIZE ignored → `80x24` (B detaches) → `200x120` (A detaches, headless). |
| `events-missed.frames.jsonl` | Frame list (no payloads) of an event channel that returned no CREDIT while 2600 `terminal.label` calls with 2 KB labels ran: exactly 262144 bytes arrive, then nothing; after a CREDIT of 1 GiB the backlog, a `missed`, and newer events arrive. DATA payloads are at most 16384 bytes and split JSON lines. |
| `events-missed.excerpt.jsonl` | Decoded lines around the `missed` notification (long strings shortened): events seq 155, 156, then `missed {seq: 2134, terminals: [...]}`, then events 2135, 2136. Events 157–2134 were dropped. |
| `herdr-app-layout.json` | **Synthetic, not captured.** A `doc.get herdr-app` reply (`{value, version}`) written by hand from SPEC §9.1, the HerdrKit `Layout.swift` reference and the field names in the installed Herdr.app binary; checked against **hc 0.1.16** (the core stores it opaquely). Two spaces: split `row` → `stack` with an `active` chain ending on a terminal, a `mod` pane, a `column` without `active`, an empty block, a mod-only tab, `size` as `weight` and `px`, and unknown fields. Used by the Android `HerdrAppLayoutParser` tests. |
| `exchanges.json` | Every request/response of the plain and framed sessions keyed by scenario name (easy to load in unit tests). |
| `flow-control.json` | Measured numbers: paint 267 B; bytes received with no CREDIT = **262144** (paint included); max stream DATA payload seen; bytes after crediting; >4 MiB behind → repaint (`ESC[!p` present, far fewer than 6 MB delivered); multi-viewer sizes; `exit 3` CLOSE payload `{"exit_code":3,"finished_ms":…,"reason":"exited","signal":null}`; lazy subscriber `missed` seq numbers. |

## Regenerating

`attach-first-paint-0.1.16.hex` is reproducible: run `python3 protocol/tools/capture_first_paint.py protocol/fixtures/attach-first-paint-0.1.16.hex`
(Python 3 stdlib only; uses an isolated `flocket-test-*` session and stops it afterwards). The other fixtures:

Scripts (not committed) lived in the research scratchpad: a Python 3 driver that spawns
`hc connect --session <isolated>` with pipes, logs every read/write as hex, and a decoder that
re-parses the logs into `*.decoded.jsonl`. Always use an isolated `--session`; never the default one.
