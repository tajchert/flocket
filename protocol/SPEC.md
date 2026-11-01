# herdr-core (`hc`) client protocol — implementer spec

Target: native Android (Kotlin) and later iOS clients that talk to **herdr-core** over
`ssh host hc connect`. This document is meant to be sufficient on its own; you should not need to read Rust.

- **Protocol generation:** 1 (the API is marked preview).
- **Verified against:** installed `hc 0.1.16` (schema in `schema/hc-0.1.16.openrpc.json`; first paint in `fixtures/attach-first-paint-0.1.16.hex`; FACTS behavior). The other wire captures in `fixtures/` were taken from `hc 0.1.15` (history; schema `schema/hc-0.1.15.openrpc.json`).
- **Source read:** `herdrdev/herdr-core` @ `e07c2a1bedd62cf4787ee026ee23ee79c7d95491` (tag `v0.1.16`, released 2026-10-31).
  The OpenRPC schema is byte-identical between 0.1.15 and 0.1.16 apart from `info.version` (confirmed by diffing the two `hc schema` snapshots). §13 lists the behavior differences.
- **Cross-checked:** `zzulanas/herdr-ios@a030501` (HerdrKit) and `eliasstravik/shepherdr@b420dd0` (server/core).

Evidence tags used below:
**[W]** observed on the wire (fixture exists; 0.1.15 unless a row says 0.1.16) · **[S]** read in the e07c2a1 source ·
**[D]** stated in the official docs (`schema/README.md`, `docs/*.md`) · **[I]** inferred, not verified.
Untagged statements are [S]+[W].

---

## 1. Transport

| Item | Rule |
|---|---|
| Command | `hc connect [--session NAME]` (binary is `herdr-core`; `hc` is normally a symlink to it). `--session` is equivalent to env `HC_SESSION=NAME`. Default session name is `default`. |
| Remote | Run it over an SSH **exec** channel, **no PTY**. `ssh -T box hc connect`. A PTY would echo, translate CR/LF and corrupt binary frames. |
| stdout | Protocol bytes only. `hc connect` copies socket→stdout unbuffered in 64 KiB reads and stdin→socket untouched; it never adds text. [S] |
| stderr | Diagnostics only (e.g. core failed to start). Empty in every capture. Collect it bounded (e.g. last 8 KiB) for error messages. [W] |
| Banners | Anything the remote login prints to stdout (MOTD in `.bashrc`, etc.) breaks the protocol. The core docs say: "check that the remote login prints no extra text to stdout". Detect: first byte of a reply must be `{`. [D] |
| Auto-start | If no core is running for that session, `hc connect` starts one, then connects. [S] |
| Lifetime | Closing stdin ends `hc connect` cleanly: it half-closes the socket, the core closes the connection, stdout hits EOF, process exits. Closing the connection **only detaches**: terminals and programs keep running on the host. [S][W] |
| PATH | Non-interactive SSH shells often lack `~/.local/bin`. Resolve the executable explicitly (configured path, then `command -v herdr-core`, then `command -v hc`, then `~/.local/bin/herdr-core`). [I] |
| Endpoint | The core itself listens on a Unix socket `<runtime>/herdr-core/<session>.sock` (`$XDG_RUNTIME_DIR`, else `/run/user/<uid>`, else the state dir `~/.local/state/herdr-core/<session>/`), or a named pipe on Windows. `HC_SOCKET` overrides. You only need this if you bypass `hc connect`. [D] |
| Security | No API authentication: anyone who can run `hc connect` as that user has full shell-equivalent control. SSH is the trust boundary. Sessions are not security boundaries. [D] |

One `hc connect` process = one core connection. You can open several (e.g. one for control, one per heavy stream), but one framed connection carrying everything is the intended design.

---

## 2. Plain mode (NDJSON JSON-RPC 2.0)

Every connection starts in **plain mode**: one JSON-RPC 2.0 message per line, UTF-8, terminated by `\n`.

```
→ {"jsonrpc":"2.0","id":1,"method":"terminal.list","params":{}}
← {"jsonrpc":"2.0","id":1,"result":{"terminals":[...],"hidden_finished":0}}
```

Rules [S][W]:
- `params` is always an **object** (by-name). Missing or `null` `params` is treated as `{}`.
- `id` may be any JSON value; it is echoed verbatim. A request **without** an `id` member is a notification: it is executed and gets **no reply**. (Never omit `id` on a mutating call you need to confirm.)
- Plain mode handles one request at a time, in order (the next line is read only after the previous reply was written).
- Unparseable line → `{"jsonrpc":"2.0","id":null,"error":{"code":-32700,...}}`. Blank lines are ignored.
- JSON-RPC batches (arrays) are not supported → `invalid_request` [I: from code path].
- `hello` is **not** required in plain mode; every method except `terminal.attach` works without it.
- After a plain-mode `events.subscribe` reply the connection carries **only notifications**; any further request line is read and silently ignored (no reply) [W]. Use framed mode in the app.
- Reply member order is alphabetical (`error`/`id`/`jsonrpc`/`result`); do not depend on order.

### 2.1 `hello`

Params (all optional):

| Field | Type | Meaning |
|---|---|---|
| `generation` | u32 | Protocol generation the client speaks. Send `1`. If present and ≠ 1 → error `unsupported_generation`. If omitted, no check. |
| `framed` | bool, default false | Switch this connection to framed mode **after** the reply is written. |
| `core` | `{id,name}` | Only for core-to-core peering. Clients must not send it. |
| `calls_you` | string | Peering only. Do not send. |

Result `HelloResult` (all fields always present) [W]:

```json
{"core":{"id":"86bd470d4c557443479c94e20b614001","name":"tajchertminim6"},
 "features":["framed","attach","events","status","shell"],
 "framed":true,"generation":1,
 "methods":["hello","server.status", "... 32 names ..."],
 "version":"0.1.15"}
```

| Field | Type | Meaning |
|---|---|---|
| `generation` | u32 | Always 1 today. |
| `version` | string | Semver of the core binary (`0.1.15`). |
| `core.id` | string (32 hex) | Permanent random id of this core's state dir. Survives restarts and updates; changes only when state is wiped or the disk is cloned onto a different machine id. |
| `core.name` | string | Display name, defaults to the cleaned short hostname (lowercase, `[a-z0-9-_.]`, ≤32). |
| `framed` | bool | Echo of the requested `framed`. If true, the very next byte after this line's `\n` is a frame header. |
| `methods` | string[] | Every method name this core serves. |
| `features` | string[] | Coarse capabilities: `framed`, `attach`, `events`, `status`, `shell`. |

Error example [W]:
`{"error":{"code":-32000,"data":{"hint":"this core speaks generation 1","kind":"unsupported_generation"},"message":"protocol generation 2 not supported"},"id":2,"jsonrpc":"2.0"}`

### 2.2 Compatibility policy (what the app must do)

1. Send `hello {"generation":1,"framed":true}` as the first line.
2. Refuse if the call errors with `unsupported_generation`, or `result.generation != 1`.
3. Refuse if `result.framed != true`.
4. Refuse if any method the app needs is missing from `result.methods` (required set, §4). Gate optional UI per method (e.g. hide "Run template" if `templates.list` is absent).
5. Require `features ⊇ {framed, attach, events, status}`.
6. **Do not** compare `version` for equality or reject newer patch/minor versions. Flocket enforces a *minimum* version of **0.1.16** (semver compare of major.minor.patch, ignoring pre-release/build suffixes; newer always accepted; an empty or unparsable version is accepted) and shows `version` in diagnostics.
7. Accept unknown fields everywhere, unknown event `type`s (ignore them), unknown status words, unknown `reason`s, unknown error `kind`s (fall back to `message`). Keep raw JSON of terminal records so unknown fields survive.
8. The official evolution rule: the API only grows within a generation (no removed method/param/result field, no new required input, no input accepting less, no answer gaining a new kind of value). A real break bumps the generation. [D]

Do **not** use `rpc.discover`/`info.version` equality (shepherdr does; see §14).

---

## 3. Framed mode

### 3.1 Switching

- The client sends `hello` with `framed:true` as a plain line. The core writes the plain reply line, then switches **both directions** to frames.
- Client parsing: read bytes until the first `\n`; parse that line as the hello reply; if `result.framed == true`, every remaining byte in the buffer (and everything after) is framed data. **One read may contain the reply line plus one or more frames** — keep the remainder, do not discard it.
- The client may pipeline its first frames in the same write as the hello line; the core buffers them correctly [W: `pipelined.*`]. Only do this if you will tear the connection down on a hello error (if hello fails, the core stays in plain mode and would try to parse your frame bytes as a JSON line).
- If hello errors, the connection stays in plain mode.

### 3.2 Frame layout (both directions)

```
offset  size  field
0       4     channel   u32 little-endian
4       1     kind      u8
5       4     length    u32 little-endian  (payload bytes, 0..16 777 216)
9       len   payload
```

Header is exactly 9 bytes. Example: `02000000 03 04000000 6400 1e00` = RESIZE on channel 2, 100 cols × 30 rows [W].

| kind | Name | Direction | Payload |
|---|---|---|---|
| 0 | DATA | both | Channel 0: exactly one JSON-RPC message (UTF-8 JSON, no trailing newline). Stream channels: raw bytes (terminal output core→client; keystrokes client→core; event JSON lines core→client). |
| 1 | CREDIT | both (client→core is what matters) | u32 LE: number of payload bytes the receiver consumed on that channel. Must be exactly 4 bytes or it is ignored. |
| 2 | CLOSE | both | Client→core: empty; ends the stream. Core→client: empty (echo of your CLOSE, or reason unknown) or a JSON object reason (§7.7). |
| 3 | RESIZE | client→core | u16 LE cols, u16 LE rows (exactly 4 bytes or ignored). |
| 4 | FOCUS | client→core | 1 byte: `0x01` gained focus, anything else (incl. empty) = lost. |
| 5 | FACTS | client→core | JSON `Display` object (§6.6). Invalid JSON is ignored silently. |

Unknown kinds are ignored by the core; the client should ignore them too. Kinds 1, 3, 4, 5 on channel 0 are ignored by the core.

### 3.3 Limits

| Constant | Value | Effect |
|---|---|---|
| `MAX_PAYLOAD` | 16 MiB = 16 777 216 | A header announcing more is a protocol error: the core drops the connection. The client must do the same (treat as corrupt stream, reconnect). |
| Core→client stream DATA chunk | ≤ 16 384 bytes per frame | Observed 16 384 on event channels. Frame boundaries carry **no meaning** (they split UTF-8, escape sequences, and JSON lines). |
| `WINDOW` | 262 144 (256 KiB) | Initial credit of every stream channel (attach **and** events). |
| `MAX_PENDING` | 4 MiB = 4 194 304 | Per-stream backlog in the core. Exceeded → attach stream gets a repaint; event stream gets `missed`. |
| `MAX_CONTROL` | 64 MiB | Unsent channel-0 replies queued for a client that stopped reading. Exceeded → the core disconnects you. |
| Client buffers | your choice | Recommended: max 16 MiB + 9 per frame; cap the total undecoded buffer (e.g. 32 MiB) and cap per-stream unconsumed bytes (credit does this for you if you credit only on consumption). |

### 3.4 Channels

- **Channel 0:** JSON-RPC requests (client→core) and responses (core→client). Requests run **concurrently** in framed mode: replies can come back in any order — match strictly by `id`. A slow call (`terminal.kill`, `terminal.send`, `terminal.wait`) never blocks others. Core-initiated messages on channel 0 do not occur for normal clients (only peer cores receive calls) [S]; ignore any message with a `method`.
- **Stream channels (≥1):** allocated by the core per connection from a counter starting at 1, shared by attach streams and event subscriptions, never reused within a connection [S][W]. The channel number arrives in the result of `terminal.attach` (`channel`) or `events.subscribe` (`channel`).
- **Scheduling:** the core sends all queued channel-0 frames first, then round-robins stream channels, each limited by its own credit. A stalled stream never blocks replies or other streams.
- **Early frames:** register a stream's handler *before* you process anything after its reply, and **buffer DATA/CLOSE that arrive on an unknown channel** until the reply that names it is processed. Locally the core queues the reply before the first paint and the reply arrives first [W], but for remote (`machine/tN`) attaches the relayed paint can be queued before the reply [S]. HerdrKit and `hc attach` both keep such an "early frames" buffer.
- **Orphan streams:** if a reply with a `channel` arrives for a request you no longer wait for (timeout/cancel), send CLOSE on that channel immediately so the core drops the viewer/subscription.
- **After you send CLOSE:** discard further DATA on that channel until the core's empty CLOSE echo arrives; then forget the channel.

### 3.5 Flow control (CREDIT)

- Core→client stream bytes are credit-limited: the core never has more than `credit` unacknowledged payload bytes in flight per stream. Credit starts at 262 144 and grows by every CREDIT you send (saturating add; over-crediting is allowed — `CREDIT 1 GiB` works [W]).
- If you never return credit you receive exactly 262 144 bytes and then nothing more on that channel [W: first paint counts toward it].
- Return credit for bytes you have **consumed** (handed to the terminal emulator / parsed into events), not merely received. Simplest correct policy: after processing a DATA frame of `n` bytes, send `CREDIT n`. Batching is fine (e.g. send when accumulated ≥ 32 KiB or after each processing pass); never let unreturned credit sit at the full window while idle.
- **Event channels need credit too.** HerdrKit additionally sends a one-off `CREDIT 1048576` right after subscribing to enlarge the window [S: herdr-ios].
- Client→core input is **not** credit-limited by the protocol (the core never sends CREDIT). Bound your own outgoing queue: cap each DATA frame (e.g. ≤ 64 KiB; shepherdr rejects > 256 KiB) and the total queued input; surface "input not delivered" rather than buffering unbounded paste.
- Channel 0 replies are not credit-limited but are capped by `MAX_CONTROL` (keep reading!).

---

## 4. Methods

All methods are by-name; unknown params are ignored (no strict decoding) [S]. Types: `u16/u32/u64` = JSON integer; `f64` seconds = JSON number ≥0; `Labels` = JSON object of string→string. The full machine-readable schema is `schema/hc-0.1.15.openrpc.json` (`rpc.discover` returns the same).

**Required by the app:** `hello`, `events.subscribe`, `terminal.list`, `terminal.get`, `terminal.attach`, `doc.get`.
**Optional (capability-gate):** `server.status`, `terminal.run`, `terminal.kill`, `terminal.rm`, `terminal.label`, `terminal.send`, `terminal.keys`, `terminal.interrupt`, `terminal.read`, `templates.list`, `machine.list`, `doc.set`, `terminal.keep`, `terminal.put`.

Terminal targets (`terminal` param): an id address `t7`, a name `reviewer`, or `machine/t7` / `machine/name` for a terminal on an added machine (§6.1).

### 4.1 Server / discovery

| Method | Params | Result |
|---|---|---|
| `server.status` | `{}` | `{core:{id,name}, version, pid:u32, uptime_ms:u64, holder:HolderInfo\|null, holders:HolderInfo[], live:usize, finished:usize, installed_by:string\|null}`; `HolderInfo = {pid, instance, current:bool, terminals}` [W] |
| `templates.list` | `{}` | `{templates:[{name, description, command, error?}]}` (`error` set when the template file is unusable) |
| `machine.list` | `{}` | `{machines: MachineView[]}` (§10) |
| `rpc.discover` | `{}` | OpenRPC document |

`server.stop` / `server.restart` exist; **the app must never call them** (stop kills every program).

### 4.2 `terminal.list`

Params: `all?: bool = false` (include finished), `labels?: Labels` (AND-match exact values).
Result: `{terminals: Terminal[], hidden_finished: usize, machines?: MachineView[]}`.
- `terminals`: this machine's terminals, then each **connected** machine's (`rohan/t7`). Each machine gets 3 s; a slow/unreachable one is skipped silently and appears only in `machines` with its state.
- `machines` is **omitted** when no machine is added [W].
- Prefer the `events.subscribe` snapshot for the app's state; use `terminal.list` for one-shot refreshes.

### 4.3 `terminal.get`

Params: `terminal: string` (required), `caller?: string` (do not send). Result: `Terminal` (live or finished). Errors: `not_found`, `machine_unknown`.

### 4.4 `terminal.run`

Starts a program in a new terminal. Returns once it runs.

| Param | Type | Notes |
|---|---|---|
| `argv` | string[] | Program + args, run without a shell. If `argv[0]` names a template, the template's command runs and the rest of argv (joined by spaces) becomes the first message. Empty and no `line`: the machine's own shell (macOS: login shell; 0.1.16: `shell = [...]` config). |
| `line` | string | Shell line instead of argv (`sh -c` / `%COMSPEC% /d /s /c`). Not both. |
| `name` | string | Unique among **live** terminals; name rules §6.1. |
| `cwd` | string | Default via API: the **core's** cwd (with `on`: remote home). Always send it. |
| `on` | string | Machine name: run there; result address `machine/tN`. |
| `env` | Labels | Added to core env. Clients should pass terminal identity: `TERM` (e.g. `xterm-256color`), `COLORTERM=truecolor`, optionally `TERM_PROGRAM`/`TERM_PROGRAM_VERSION` (fixed at start; becomes `identity`). |
| `labels` | Labels | Empty values are dropped. Keys must be non-empty and contain no `=`. |
| `keep` | bool | Replace a lost program with a shell (§6.5). |
| `parent`, `no_parent`, `no_notify`, `outlive_parent`, `rm` | | Agent-orchestration options; app normally sends none (no `caller` → no parent). `rm`: delete the record right after exit. |
| `wait`, `timeout` | bool, f64 | Block until not working and return output. **Do not use from the app** (long-blocking call). |

Result: the full `Terminal` record, flattened, plus optional `first_message: {sent, started?, reason?, turn?}` (templates), `wait`, `output`.
Errors: `invalid_params`, `name_taken`, `spawn_failed`, `unavailable` (no holder / core stopping), `invalid_template`, `not_found`, `machine_unknown`, `config`.
Initial size is the headless size (default **200×120**), not the phone's — attach right after to set your size.

### 4.5 `terminal.attach` (framed only)

| Param | Type | Notes |
|---|---|---|
| `terminal` | string | required |
| `cols`, `rows` | u16 | **required** by the schema. Your viewport in cells. 0 means "don't change size" [S]. |
| `observe` | bool = false | Watch only: input, RESIZE and FOCUS from this viewer are ignored; never sets size. |
| `history` | usize = 0 | Scrollback lines to include in the first paint (main screen only). |
| `display` | Display = {} | Your colors/cell size (§6.6). |
| `resumed` | bool = false | "Back after a dropped line": the viewer is not active (doesn't take size ownership) until it types/resizes/focuses. Use it for automatic reattach after reconnect. |
| `caller` | string | Do not send. |

Result: `{channel: u32, terminal: Terminal}` (record already reflects your size if you became the size owner).
Errors: `not_found`, `machine_unknown`, `finished` (program ended; read it with `terminal.read`), `unavailable` (kept terminal waiting for its new shell, or initial spawn pending), `needs_framed` (plain connection), `refused` (attaching a terminal to itself; only with `caller`), `denied` (peer cores only).
Lifecycle: §7.

### 4.6 `terminal.kill`

Params `{terminal}`. Hangup, then terminate, then kill; returns the observed final record (`state:"finished"`, `reason:"killed"`, `signal` e.g. 1) [W]. May take seconds. On a finished terminal it returns the record unchanged. Errors: `not_found`, `machine_unknown`.

### 4.7 `terminal.rm`

Params `{terminal}`. Removes a **finished** record. Result `{removed:"t1"}`. Error `live` if still running (kill first). Finished records are also auto-removed after 24 h (later if a live parent hasn't seen them), max 500 kept.

### 4.8 `terminal.label`

Params `{terminal, labels: Labels}` (both required). Merges: `key=value` sets, `key:""` removes. Result: updated `Terminal`. Emits a `labels` event. Errors: `not_found`, `machine_unknown`, `invalid_params` (bad key).

### 4.9 `terminal.send`

Params: `terminal`, `text` (required); `force?: bool` (type even when `blocked`), `if?: "idle"|"working"` (only type if status equals), `wait?`, `timeout?` (don't use wait from the app), `caller?` (don't send).
Behavior: pastes `text` then presses the program's submit key, **after nobody has typed for ~3 s** (keys typed meanwhile are held and delivered after). The call can therefore block for seconds — use a long timeout and show "queued".
Result: `{status: string|null, turn: u64, started: bool|null, wait?}` — `status` = word when text went in; `started` = went `working` within a few seconds (null if the program doesn't report status) [W: `{"started":false,"status":"idle","turn":1}`].
Errors: `not_found`, `machine_unknown`, `finished`, `unavailable`, `blocked` (program is blocked; resend with `force` only after showing the user the prompt), `status_mismatch` (with `if`), `invalid_params`, `config`.

### 4.10 `terminal.keys`

Params `{terminal, keys: string[]}`. Key names: `a`..`z`, `0`..`9`, punctuation (`` ` \ [ ] , = - . ' ; / ``), `backslash`, `backspace`, `enter`/`return`, `space`, `tab`, `delete`/`del`, `end`, `home`, `insert`, `pagedown`/`pgdn`, `pageup`/`pgup`, `up`, `down`, `left`, `right`, `esc`/`escape`, `f1`..`f12`; modifiers joined with `+`: `shift`, `ctrl`/`control`, `alt`/`meta`/`opt`, `super`/`cmd` (e.g. `ctrl+c`, `shift+tab`). Case-insensitive. Encoded for the program's current keyboard modes (kitty, app-cursor, ...). Same ~3 s quiet rule as send. Result `{status, turn}`. Errors as send minus blocked/status_mismatch, plus `invalid_params` (unknown key).

### 4.11 `terminal.interrupt`

Params `{terminal, timeout?: f64 = 10}`. Presses the stop key (`esc` if the program reports status, else `ctrl+c`; templates may override), watches up to `timeout`. Never escalates. Result `{outcome: "stopped"|"exited"|"still_working"|"unknown", key: "esc"|"ctrl+c"|...}` [W: `{"key":"esc","outcome":"stopped"}`].

### 4.12 `terminal.read`

Params `{terminal, lines?: usize = 0}`. Result `{text: string, complete: bool, how: "screen"|"history"|"stitched", why?: "working"}`. `lines` = history lines above the screen (finished terminals: max 1000). For full-screen apps with `lines>0` the core scrolls the app to stitch history (`how:"stitched"`), unless it is working (`how:"screen", why:"working"`). Plain text, no escapes. Use for previews/notifications, not rendering.

### 4.13 `terminal.keep`

Params `{terminal, keep: bool}` (CLI default true). Only live terminals. Result `Terminal`; emits `keep`. Turning keep off on a terminal currently waiting for its new shell finishes it.

### 4.14 `events.subscribe`

Params (all optional; omitted = unfiltered): `terminal?: string` (one terminal), `children_of?: string` (children of that terminal), `labels?: Labels` (AND exact match).
Result: `{seq: u64, terminals: Terminal[], channel: u32|null}` — `channel` is set in framed mode (events arrive there as JSON lines, with credit), null in plain mode (the connection becomes notification-only). Errors: `not_found`, `machine_unknown`.
Unsubscribe: send CLOSE on the channel (core echoes empty CLOSE) [W]. Details §8.

### 4.15 `doc.get` / `doc.set`

`doc.get {name, on?}` → `{value: any|null, version: u64}`; never-written name → `{value:null, version:0}` [W].
`doc.set {name, value, version, on?}` → `{version: u64}` (new version = old+1). Errors: `invalid_params` (bad name, > 1 MiB), `conflict`, `internal` (could not persist), `machine_unknown`, `unavailable`, `needs_update` (remote core too old). Details §9.

### 4.16 Other methods (not needed by the app)

`terminal.wait`, `terminal.put` (upload ≤ 8 MB base64 file beside a terminal; returns `{path, bytes}`; useful later for image paste), `machine.exec`, `file.read/write/edit`, `machine.add/connect/rename/rm`, `server.stop/restart`. `$/cancelRequest {id}` (no reply) only aborts a running `machine.exec`.

---

## 5. Errors

Envelope: `{"jsonrpc":"2.0","id":<id>,"error":{"code":<int>,"message":<string>,"data":{"kind":<string>,"hint":<string>}}}`.
Switch on `data.kind` (stable); show `message` (+ `hint` when non-empty). `code` is coarse.

| code | kind | Meaning / client action |
|---|---|---|
| -32700 | `parse_error` | Malformed JSON line/frame (`id:null`). Client bug. |
| -32600 | `invalid_request` | No `method`. Client bug. |
| -32601 | `unknown_method` | Method absent (older core): capability-gate. |
| -32602 | `invalid_params` | Bad/missing param, bad name, oversize doc, unknown key name. |
| -32603 | `internal` | Core failure (e.g. doc persist failed). Retry later. |
| -32000 | `not_found` | No such terminal (or removed). Drop it from the UI. |
| -32000 | `machine_unknown` | Address prefix is not an added machine. |
| -32000 | `unsupported_generation` | `hello` generation mismatch: incompatible core. |
| -32000 | `needs_framed` | `terminal.attach` on a plain connection. |
| -32000 | `finished` | Terminal's program ended (attach/send/keys/put). |
| -32000 | `unavailable` | Temporarily impossible: kept terminal "lost, waiting for its new shell" (retry after `restarted`), spawn pending, no holder, core stopping, remote line down. |
| -32000 | `live` | `terminal.rm` on a running terminal. |
| -32000 | `blocked` | `terminal.send` refused: program reported `blocked`. |
| -32000 | `status_mismatch` | `terminal.send` `if` not satisfied. |
| -32000 | `name_taken` | `terminal.run` name in use by a live terminal. |
| -32000 | `spawn_failed`, `invalid_template`, `config` | Run failures / bad config.toml on host. |
| -32000 | `conflict` | `doc.set` version stale. Message: `document "x" is at version N, not M`. Reread. |
| -32000 | `needs_update` | Remote machine's core too old for the call. |
| -32000 | `refused`, `denied` | Self-attach (with `caller`) / peer-core restrictions. Not expected for the app. |

CLI exit-code mapping (useful for SSH fallbacks only): usage 2, not_found 3, machine_unknown 4, unavailable 5, invalid_params 6, needs_target 7, config 8, blocked 9, status_mismatch 10, finished 11, cancelled 130, other 1.

---

## 6. Terminal model

### 6.1 Addresses and names

- **Local id address:** `t` + a non-zero digit + base-30 symbols from `0123456789bcdfghjkmnpqrstvwxyz` (no vowels, no `l`). Sequence: `t1..t9, t10..t19, t1b..t1z, t20, ..., t9z, t100, ...` (`t20` is the 40th terminal, `t1b` the 20th). **Never parse as decimal; treat as opaque strings.** Ids are never reused by a core.
- **Remote:** `machine/t7` where `machine` is *this core's* name for an added machine (it can change on `machine.rename`: the core then emits `removed` + `created` under the new prefix). Preserve the full string; it is the identity within this core connection. Use it verbatim in every call.
- A name (`reviewer`) also resolves: to the live terminal holding it, else the most recent finished one with it. Prefer addresses in the app.
- `<core.id>/t7` and `<core.name>/t7` resolve to local `t7`.
- **Name rules** (terminal names, machine names, session names, **doc names**): 1–32 chars of `a-z 0-9 - _ .`, first char a letter or digit, and not id-shaped (`t7`, `t1b` are rejected; `t0`, `t3po`, `tmp` are fine).
- App-level identity: `(endpoint/core.id, address)`. `core.id` is stable across core restarts.

### 6.2 `Terminal` record (public fields)

Required in schema: `address, labels, argv, cwd, state, created_ms, screen, cols, rows`. Everything else may be absent or null — default it.

| Field | Type | Meaning |
|---|---|---|
| `address` | string | `t7` / `rohan/t7` |
| `name` | string\|null | |
| `labels` | {string:string} | Free-form, never interpreted by core (§6.4). |
| `argv` | string[] | What was started (`["sh"]`, `["sh","-c","sleep 1; exit 3"]` for `line`). |
| `cwd` | string | Start folder. For the *current* folder use `foreground.cwd`. |
| `state` | `"live"`\|`"finished"` | Record state. A kept terminal waiting for a new shell is still `live` (see `pid`/`reason`). |
| `pid` | u32\|null | Program pid. null while lost/pending. **Not cleared on finish** (kill result still shows it) [W]. |
| `exit_code` | i32\|null | |
| `signal` | i32\|null | |
| `reason` | string\|null | Why it ended / is pending: `exited`, `killed`, `server_stop`, `host_shutdown`, `holder_lost`, `stopped_by_restart`; on live kept records `signaled` (and other loss reasons) while waiting; `spawn_pending` while the initial spawn is unconfirmed. Unknown values possible. |
| `created_ms` / `finished_ms` | u64 / u64\|null | Epoch ms. |
| `screen` | `"exact"`\|`"inexact"` | inexact after a core crash (older history may be missing). |
| `cols`, `rows` | u16 | Current PTY size. |
| `identity` | string | `TERM_PROGRAM TERM_PROGRAM_VERSION` at start (e.g. `ghostty`). |
| `status` | Status\|null | Root OSC 7501 record (§6.3). null = never reported, cleared, or exited. |
| `status_ms` | u64\|null | When the status *word* last changed. **Not carried by `status` events**. |
| `records` | {id: Status} | Non-root 7501 records (omitted when empty). |
| `turn` | u64 | +1 each time the root status leaves `working`. |
| `reported` | bool | Program ever sent a 7501 report. |
| `title` | string | OSC 0/2 window title. |
| `parent` | string\|null | Terminal that started it (`t3`, or `desktop/t3` from a dialing core). |
| `keep` | bool | §6.5. |
| `template` | string\|null | Template that started it. |
| `foreground` | Foreground\|absent | `{pid:i32, name, argv:string[], cwd}` of the foreground process group leader, polled ~2×/s; absent/null once finished or lost. On Windows: the first process. |
| `notify`, `die_with_parent`, `rm` | bool | Orchestration flags. |
| `submit`, `stop`, `paste_gap_ms` | | Template overrides; usually absent. |

### 6.3 Status (OSC 7501, Program Status Protocol)

- Status is **only** what the program reports via `ESC ] 7501 ; key=value:key=value ESC \`. The core never infers it from the screen. A program that doesn't speak 7501 has `status: null`, `reported: false` → show **unknown / no status**, not "idle". Process liveness (`state`, `pid`, `foreground`) is a separate axis.
- `Status = {state, kind?, progress?, app?, title?, msg?}`:
  - `state`: `idle`, `working`, `done`, `blocked`, `error` (the parser accepts these plus `clear`; treat any other word as unknown).
  - `kind`: `permission`, `question`, `auth` — only with `blocked`, when said.
  - `progress`: 0..100 — only with `working`/`blocked`, when said.
  - `app`: program's name for itself (e.g. `claude`); `title`, `msg`: free text (sent base64 on the OSC wire, plain in JSON). Empty strings are omitted.
- `clear` with no id clears root + all records (`status: null`); with an id removes that record and its `id/…` children.
- `records` holds up to 255 additional records keyed by id (programs reporting on several things).
- Status is cleared (a `status` event with `status:null`) just before `finished`.
- `turn` increments whenever the root state leaves `working` for any reason; the `turn` event carries the word it ended on.
- Display label for "agent" (what `hc ls` shows): `status.app` if present, else basename of `argv[0]`.
- Urgency ordering used by `hc`'s picker: `blocked`/`error` > `working` > `done`/`idle` > none.

### 6.4 Labels and the `where` convention

Labels are opaque `key=value` strings. Conventions observed (not enforced by core):
- `where`: human-readable place, e.g. `"herdr-core / review"`; `hc ls` shows it instead of the folder [S: cli/output.rs].
- `herdr.space`, `herdr.tab`: desktop app (herdr-app) grouping ids, matching ids in the `herdr-app` document [S: herdr-ios; I for semantics].
- For our own grouping, use a namespaced prefix (e.g. `flocket.group`) and/or our own document (§9). Never remove labels we don't own.

### 6.5 Keep / loss / restart

- `keep: true` (via `run {keep}` or `terminal.keep`): on **loss** (holder died, core stop/restart, host shutdown, death by signal without exit code) the record stays `live`, `pid:null`, `reason` set, `foreground` dropped, and a `lost` event fires. After a grace delay the core starts **this machine's shell** in the last observed folder (then start folder, then home) — the original command is **never** re-run — and emits `restarted` with the full new record. Backoff on repeated failures: 1.5, 3, 6, 12, 30, then 60 s forever; 10 s of uptime resets it.
- An exit **with a code** and an explicit kill are always final (`finished`).
- During recovery, `send/keys/interrupt/attach` return `unavailable` ("lost, waiting for its new shell"). Loss closes attach streams with the loss reason; reattach after `restarted`.
- UI: a restarted kept terminal is a **new shell**, not the resumed coding task.

### 6.6 Display facts (`Display`)

`{fg?: "#rrggbb", bg?: "#rrggbb", cursor?: "#rrggbb", cell?: [widthPx, heightPx]}`. Used by the core to answer the program's color/cell-size queries (OSC 10/11/12, CSI 16 t) on the viewer's behalf. Send in `terminal.attach.display` and as FACTS when the theme or font changes.

---

## 7. Attach stream lifecycle

### 7.1 Sequence

```
→ ch0 DATA {"id":5,"method":"terminal.attach","params":{"terminal":"t1","cols":80,"rows":24,"display":{...}}}
← ch0 DATA {"id":5,"result":{"channel":2,"terminal":{...,"cols":80,"rows":24}}}
← ch2 DATA <first paint ...>              (may arrive in the same read; may precede the reply for remote terminals)
← ch1 DATA {"method":"event",...,"type":"size","data":{"cols":80,"rows":24}}   (if size changed)
→ ch2 CREDIT n                            (as you consume)
→ ch2 DATA "echo hi\r"                    (keystrokes, raw bytes)
← ch2 DATA "echo hi\r\nhi\r\n$ "         (raw program output, arbitrary chunking)
→ ch2 RESIZE 100x30 / FOCUS 1 / FACTS {...}
→ ch2 CLOSE ""                            (detach)        ← ch2 CLOSE "" (echo)
   or
← ch2 CLOSE {"exit_code":3,"finished_ms":…,"reason":"exited","signal":null}   (program ended)
```

### 7.2 First paint

The first DATA bytes reproduce the current screen for a fresh VT emulator of your size:
- 0.1.16: starts with `0x18` (CAN, cancels any half-parsed sequence) [W 0.1.16: `fixtures/attach-first-paint-0.1.16.hex`]. 0.1.15: starts directly with `ESC [ ! p` (DECSTR soft reset) [W].
- Then mode resets (`ESC[?2026l`, mouse/focus/bracketed-paste off, `ESC[?1049l`, kitty keys reset `ESC[=0;1u`, ...), `ESC[H ESC[2J`, tab stops, optional `history` scrollback lines, screen content, cursor, SGR, modes the program set, the title.
- Then `ESC[?9001h` or `ESC[?9001l` (win32-input-mode requested or not; ConPTY on Windows requests it — a viewer that honors 9001 must then send keys in that encoding itself; the core never re-encodes keys).
- Then the latest OSC 7501 report for the root and each record (`ESC]7501;state=…:id=…:app=…:title=<b64>:msg=<b64>ESC\`).
- 0.1.16 may append a "continuation" (bytes needed to resume a sequence the program left half-written) [S].
- Live output follows immediately, **possibly in the same DATA frame** [W: `attach-first-paint.hex` ends with a prompt redraw].
- Feed everything to the emulator as one byte stream. Treat OSC 7501 in the stream as status hints for the UI only if you like; authoritative status comes from events.

### 7.3 Live output

Raw PTY output minus terminal queries the core already answered (DA, color queries, XTVERSION, ...). The emulator must not reply to queries itself [I: the core answers them; double replies would be typed into the program]. Output to the **same terminal** goes to every viewer.

**Repaint:** if a viewer falls more than 4 MiB behind (unreturned credit + backlog), its backlog is dropped and a fresh first paint (history 0) is queued instead [W: DECSTR seen mid-stream]. Remote streams also get a fresh paint after the local core reconnects to the machine. Your emulator must therefore accept a full repaint at any point (it begins with CAN/DECSTR and clears the screen).

### 7.4 Input

DATA on the stream channel = bytes typed into the PTY, exactly as a terminal would send them (UTF-8 text, `\r` for Enter, `\x03` Ctrl-C, CSI arrows honoring DECCKM/kitty modes from the stream). The core passes them through a "human input" gate (they also delay `terminal.send` from agents by ~3 s). Observers' input is ignored. Input sent while a remote machine's line is down is lost, not queued.

### 7.5 Size ownership (multiple viewers)

- Each viewer has a size (from `attach.cols/rows` and later RESIZE). The **most recently active non-observe viewer** sets the terminal's size and display facts. "Active" = attached (unless `resumed:true`), typed (DATA), resized (RESIZE), or FOCUS gained. In 0.1.15 also FACTS; in 0.1.16 FACTS does **not** make you active [W 0.1.16: `RealCoreIntegrationTest`, passive viewer FACTS leaves 80x24] (it merges fields and applies only if you are already the leader).
- When the leader detaches, the next most recently active viewer's size applies; with no viewers the terminal goes back to **headless size (default 200×120)** [W].
- Size changes emit `size` events and SIGWINCH the program (agent TUIs redraw).
- Measured sequence [W]: A attach 80×24 → B attach 120×40 (B wins) → A types (80×24) → B FOCUS 1 (120×40) → observer RESIZE (ignored) → B detaches (80×24) → A detaches (200×120).
- RESIZE with 0 cols or rows makes you active but does not change the size; never send 0.
- **App policy (recommended):** attaching from the phone resizes a terminal someone else is using on the desktop. Default to `observe:true` (or `resumed:true` + no RESIZE) when the terminal already has a different size and the user hasn't interacted; take ownership (send RESIZE + FOCUS 1) on first keystroke or an explicit "fit to phone". Send FOCUS 0 when the app backgrounds. Expect the desktop to snap back when the user types there. (Flocket deliberately takes the size on open instead: [ADR-0007](../docs/decisions/0007-fit-terminal-on-open.md).)

### 7.6 FOCUS

`FOCUS 1` makes the viewer active (size owner) and, if the program enabled focus reporting (DECSET 1004), the core sends it `ESC[I`; `FOCUS 0` sends `ESC[O` (does not change ownership). Observers are ignored.

### 7.7 CLOSE semantics

| Situation | What you see |
|---|---|
| You send CLOSE (detach) | Core drops your viewer and anything queued for it, re-applies size ownership, replies with an **empty** CLOSE. The program keeps running. |
| Program exits / is killed | After all queued output is delivered (**requires credit**), CLOSE with JSON = the `finished` data: `{"exit_code":int\|null,"finished_ms":u64,"reason":"exited"\|"killed"\|…,"signal":int\|null}` [W]. |
| Kept terminal lost | CLOSE with the `lost` data (`reason` e.g. `holder_lost`, `signaled`, `finished_ms:null`); record stays `live`. Wait for `restarted`, then attach again. |
| Server stop | CLOSE with `reason:"server_stop"`, then the connection ends [I]. |
| Remote machine forgotten / remote re-attach failed | CLOSE with **empty** payload from the core [S]. Treat as "stream ended, unknown reason": refresh the record, offer reattach. |
| Connection lost | No CLOSE. Every stream on that connection is gone; the program keeps running. Reconnect and reattach (with `resumed:true`). |

Detach ≠ kill: only `terminal.kill` ends the program.

---

## 8. Events

### 8.1 Subscription and bootstrap

```
→ events.subscribe {}            ← {"seq":17,"terminals":[...],"channel":1}
← ch1 DATA "{"jsonrpc":"2.0","method":"event","params":{...}}\n{...}\n" (JSON lines, split arbitrarily across frames)
```

- The result is an atomic snapshot: `terminals` matching the filter (local first, then mirrored remote ones) as of `seq`. Every event with `seq > snapshot.seq` follows, in order, on the channel.
- The event channel is a byte stream of `\n`-terminated JSON-RPC notifications. Reassemble lines across DATA frames (a 16 KiB frame boundary can cut a line) [W]. Return CREDIT for consumed bytes.
- **Subscribe first, then read documents** (§9) — never the other way round.
- Use **one unfiltered subscription** for app state. Filtered subscriptions (terminal/children_of/labels) never receive `doc` events.

### 8.2 Notifications

```json
{"jsonrpc":"2.0","method":"event","params":{"seq":42,"type":"status","terminal":"t7","data":{...}}}
{"jsonrpc":"2.0","method":"event","params":{"seq":43,"type":"doc","data":{"name":"herdr-app","version":2}}}
{"jsonrpc":"2.0","method":"event","params":{"seq":44,"type":"doc","machine":"rohan","data":{"name":"herdr-app","version":5}}}
{"jsonrpc":"2.0","method":"heartbeat","params":{"seq":42}}
{"jsonrpc":"2.0","method":"missed","params":{"seq":57,"terminals":[...]}}
```

| `type` | `data` | Apply to the record at `terminal` |
|---|---|---|
| `created` | full `Terminal` | **Upsert** (a remote machine reconnect re-sends changed terminals as `created`). |
| `restarted` | full `Terminal` | Replace. Kept terminal got a new shell: reattach if you were viewing. |
| `lost` | `{reason, exit_code, signal, finished_ms:null}` | Keep `state:"live"`; set these; `pid=null`; remove `foreground`. |
| `finished` | `{reason, exit_code, signal, finished_ms}` | `state="finished"`; set these; remove `foreground`. (Preceded by a `status` event clearing status.) |
| `removed` | `{}` | Delete. |
| `keep` | `{keep}` | Set `keep`. |
| `status` | `{status: Status\|null, records: {..}, reported: bool}` | Replace all three. Note: no `status_ms` — stamp locally if you sort by recency. |
| `turn` | `{turn, state}` | Set `turn`. `state` = status word the turn ended on (program's word, not liveness). |
| `title` | `{title}` | Set `title`. |
| `labels` | `{labels, record?}` | Replace `labels`. If `record` present: the terminal **newly matches your filter** → add it. With a label filter, a terminal that stops matching still gets this event, then leaves your view: drop it if it no longer matches your filter. |
| `size` | `{cols, rows}` | Set both. |
| `foreground` | `{pid, name, argv, cwd}` \| null | Set/clear `foreground` (frequent: every program start/stop in the shell). |
| `doc` | `{name, version}` (+ `params.machine` for remote) | No `terminal`. Refetch that document (§9). |
| other | — | Ignore (additive future types). |

Events for remote terminals carry `terminal:"rohan/t7"` and arrive in the same stream, same order, same filters.

### 8.3 Sequence semantics

- `seq` is **one global counter per core process**, +1 per event, across all subscribers. It restarts with each core process (e.g. `hc update`, reboot). It is not comparable across connections to different core processes.
- Unfiltered subscriber: you see every event, so `seq` is contiguous (`prev+1`) between `missed` notifications [S: the filter matches every record and doc].
- Filtered subscriber: gaps are **normal** (events for other terminals consume numbers). Never treat a gap as loss on a filtered stream.
- Drop any event with `seq <= lastAppliedSeq` (duplicates around snapshots/missed).
- Recommended unfiltered handling of an unexpected gap (`seq > last+1` without `missed`): treat as a bug/desync → resubscribe or reconnect and re-bootstrap. (Should not happen.) [I]

### 8.4 `heartbeat`

`{seq}` = current global seq, sent every 15 s when your queue is empty (framed: only if nothing is queued for you). Use it as liveness: no bytes at all on the connection for ~45 s ⇒ assume the SSH channel is dead and reconnect [I: threshold]. On an unfiltered stream heartbeat `seq` should equal your last applied seq; a higher value without events means you missed some → resubscribe [I].

### 8.5 `missed`

Sent instead of a backlog when you fell behind (framed: > 4 MiB unconsumed on the event channel; plain: > 1024 queued events). `params = {seq, terminals}` is a **fresh snapshot** for your filter. On `missed`:
1. Replace your terminal map with `terminals`; set `lastAppliedSeq = params.seq`.
2. Discard every following event with `seq <= params.seq` (some old ones may still be in the pipe).
3. **Refetch all watched documents** (snapshots contain no documents).
4. Re-validate attached streams (a terminal may have finished meanwhile; its CLOSE still arrives on the attach channel).

[W] Lazy subscriber received events ≤156, then `missed {seq:2134}`, then 2135, 2136 … (`fixtures/events-missed.excerpt.jsonl`).

`missed` is also sent (to unfiltered subscribers, with the local seq) when a remote machine's event subscription is (re)established — including its first connection — or when the remote stream itself reported `missed`. Its terminal snapshot is already refreshed; you must refetch documents on all machines.

### 8.6 Reconnect

Sequence numbers, channels and stream state are per connection. After any reconnect: new connection epoch → `hello` → `events.subscribe {}` (replace state from snapshot) → refetch documents → reattach visible terminals with `resumed:true`. Discard callbacks from older epochs.

---

## 9. Documents

- Opaque JSON values stored by the core under a **name** (same rules as terminal names: `[a-z0-9-_.]{1,32}`, alnum first, not id-shaped). Bad name → `invalid_params` [W].
- Each has a `version: u64`: 0 = never written; each successful `doc.set` → +1. Writing `null` deletes the value but **keeps the version** (`doc.get` → `{value:null, version:2}` [W]).
- `doc.set` is compare-and-set: succeeds only if `version` equals the current version; else `conflict` with message `document "x" is at version N, not M` [W]. On conflict: `doc.get`, merge your change into the new value (preserving unknown fields), retry with the new version. Never blindly overwrite.
- Value size ≤ 1 MiB (1 048 576 bytes of compact serialized JSON) → else `invalid_params`.
- Persisted atomically in the core state (`docs.json`), survive core restart and update, lost when state is wiped. A corrupt file is renamed `docs.json.bad` and the core starts with **no documents** (versions back to 0) — handle a version going backwards after reconnect by trusting the fresh read.
- `on: "rohan"` reads/writes the document on an added machine (each machine has its own documents).
- Change notification: unfiltered subscribers get `{"type":"doc","data":{"name","version"},"seq"}` (+ `"machine":"rohan"` for remote). No value: call `doc.get` (with `on` for remote). Your own `doc.set` also produces an event.
- Refetch watched documents after: subscribing, reconnecting, `missed`, a remote machine (re)connect (signalled by `missed`), and every relevant `doc` event.
- API-only (no CLI verb).

### 9.1 `herdr-app` document (read-only for us)

The desktop app (herdr-app) stores its spaces/tabs/pane layout in the document named **`herdr-app`**. Core never interprets it. Shape as used by HerdrKit (unofficial, [I]): `{"spaces":[{"id":"sp…","name":"","folder":"/path","look":{...}?,"tabs":[{"id":"tb…","name":"","root":<node>}]}], ...}` where a node is `{"id", "children":[node…]?, "size":{"weight":0.5}?, "active":"<child id>"?, "title"?, "content":{"terminal":"t7"} | {"mod":"…","props":{…}}}`; terminals also carry labels `herdr.space`/`herdr.tab`. **Our app must treat it as read-only** (import grouping hints at most), tolerate its absence (`{value:null, version:0}` [W]), unknown fields and shape changes. Store our own grouping in labels and/or our own document, e.g. `flocket` (preserve unknown fields, CAS on version).

Further details from the Herdr.app 0.x bundle (field names and its built-in docs, [I]; synthetic example in `fixtures/herdr-app-layout.json`):
- Each machine keeps its own spaces in its own core's `herdr-app` document (read remote ones with `on`). Federated terminals never appear in the local document.
- [W] (hc 0.1.16, machine added with `hc machine add --via "sh -c" --session <other>`): `doc.get {name:"herdr-app", on:"rohan"}` returns rohan's own document, and a `doc.set` made on rohan reaches the local subscriber as a `doc` event with `"machine":"rohan"`.
- A node may carry `"type"`: `"row"`, `"column"`, `"stack"` or `"block"`. `size` is `{"weight": n}` or `{"px": n}`. `active` names the shown child of a `stack`. Content kinds seen besides `terminal` and `mod`: `agent`, `context`, `tab` (treat all unknown kinds as non-terminal panes).
- Focus, zoom and the selected space/tab are per screen (the desktop's `view.json`), not in the document: the `active` chain is the best available hint for the focused pane.
- Unnamed spaces are shown after their first terminal's live folder (repo, else folder, `~` at home); unnamed tabs by their 1-based place. A terminal with labels `herdr.space=<id>`/`herdr.tab=<id>` is adopted into that space/tab (a new tab when `herdr.tab` is missing).

---

## 10. Machines (federation)

- The local core dials added machines over SSH itself and merges their terminals and events. The app talks only to its own core; remote terminals appear as `name/tN` everywhere (list, snapshot, events, attach, send, read, kill, label, rm, get).
- `MachineView` (`machine.list`, `terminal.list.machines`): `{name, target, via:bool, temporary:bool, session:string|null, id, host, version, state, since_ms?, why?}`. `state`: `connecting`, `connected`, `needs_login`, `needs_update`, `unreachable` (`since_ms` = not connected since; `why` for unreachable). `temporary` = short-lived sandbox (show apart). `version` = remote hc version when last seen.
- When a machine's line drops, its mirrored terminals stay in your state (stale) until it returns [I: mirrors are removed only on `machine.rm`]. Show them as stale using `machine.list` state; writes will fail with `unavailable`. On return: changed terminals arrive as `created` (upsert), vanished ones as `removed`, then a `missed` (refetch docs).
- `machine.rm` → `removed` for all its terminals (they keep running there). `machine.rename` → `removed` + `created` under the new prefix: re-key UI state.
- [W] hc 0.1.16, unfiltered subscriber, machine added with `--via "sh -c"` (events carry no machine-specific type or field besides the address prefix):
  - `machine add`: `created` for each of its terminals (`rohan/t1`), then `missed` (snapshot already includes them).
  - `machine.rename rohan edoras`: `removed rohan/t1` + `created edoras/t1` and **no `missed`**. A renamed machine with no terminals produces no event at all.
  - `machine.rm`: `removed` for each of its terminals and **no `missed`**.
  - Remote server stopped (`hc server stop` there): `finished` (`reason:"server_stop"`) for its terminals, then `missed` once the core re-dialled (immediately here; `machine.list` showed `connected` throughout).
  - So clients re-read `machine.list` on `missed` and also when a terminal of an unlisted machine appears or a listed machine's last terminal is removed.
- Do not add a separate per-machine SSH connection unless the user configured that host directly; a host may appear both directly and as a machine of another core (dedupe by `core.id` from `hello` vs `MachineView.id` [I]).

---

## 11. Reference client algorithm (summary)

```
connect(endpoint):
  epoch += 1
  start SSH exec "<hc> connect [--session S]" (no PTY); bounded stderr capture
  write  {"jsonrpc":"2.0","id":0,"method":"hello","params":{"generation":1,"framed":true}}\n
  read until \n → reply; check §2.2; remainder of buffer → frame decoder
  sub = call("events.subscribe", {}) → state = snapshot, lastSeq = sub.seq, evCh = sub.channel
  send CREDIT(evCh, 1 MiB)            # optional bigger window
  docs = { name → doc.get(name) for watched names }   # after subscribing
  reattach visible terminals (resumed:true)

on frame(ch, kind, payload):
  ch==0, DATA  → JSON; if has "id" → complete pending call; else ignore
  ch==evCh, DATA → append to line buffer; for each full line → handleNotification; CREDIT(evCh, len) after processing
  ch==attachCh, DATA → emulator.feed(payload); CREDIT(ch, len) after feeding
  ch==any, CLOSE → stream ended (payload JSON reason or empty)
  unknown ch → stash until its reply arrives (bounded); CLOSE on unknown ch → stash too

handleNotification(n):
  heartbeat → liveness
  missed    → state = n.terminals; lastSeq = n.seq; refetch docs
  event     → if seq <= lastSeq: drop; apply per §8.2; lastSeq = seq
```

Timeouts: reads (`get`, `list`, `doc.get`) ~15 s and retryable; `send`/`keys` ≥ 30 s and **not** auto-retried (if the connection breaks mid-call the outcome is unknown: refresh `turn`/screen and ask the user); `kill` ≥ 30 s; `attach` 15 s (on timeout CLOSE the channel if the reply arrives late).

---

## 12. Gotchas (read this list before writing code)

1. **No exact-version checks.** Accept any `version` with `generation == 1` and the needed methods/features. shepherdr's `version === '0.1.14'` and schema-version equality reject every newer core.
2. **Hello reply + frames in one read.** After the hello line, the rest of the buffer is framed data. Never `readLine()` past it with a buffered reader you then throw away.
3. **DATA frames are arbitrary slices.** Terminal output frames split UTF-8 and escape sequences; event frames split JSON lines (observed 16 384-byte frames cutting lines). Never decode a frame on its own.
4. **Event channels are credit-limited.** If you don't send CREDIT on the event channel you silently stop receiving events after 256 KiB and later get `missed`.
5. **Program-exit CLOSE needs credit.** The final CLOSE is sent only after queued output; an uncredited stream never reports exit.
6. **Early stream frames.** DATA/CLOSE can precede the attach reply (remote relays). Buffer frames for unknown channels; register before releasing.
7. **Replies are out of order** in framed mode (calls run concurrently). Match by `id`.
8. **Filtered subscriptions have gaps and no doc events.** Don't apply an unfiltered "seq must be prev+1" rule there; use one unfiltered subscription for app state.
9. **`seq` resets per core process.** Never persist it across connections; re-bootstrap on every reconnect.
10. **Snapshots contain no documents** and doc changes during disconnects are not replayed: subscribe first, then `doc.get`; refetch on `missed`/reconnect/remote reconnect.
11. **`created` can be an update** (remote reconnect re-sends changed terminals). Upsert.
12. **Status missing = unknown.** `status:null`/`reported:false` is not idle. A `status` event with `status:null` precedes `finished`. Status `state` words are program-provided; tolerate unknown words.
13. **`status_ms` isn't in `status` events.** Stamp locally or refetch with `terminal.get`.
14. **Liveness ≠ status.** `state:"live"` with `pid:null` and a `reason` = kept terminal waiting for a new shell (`unavailable` for input). A `restarted` terminal is a fresh shell, not the old task.
15. **Addresses are base-30, not decimal** (`t1b`, `t20`, `t9z`, `t100`), and may be remote (`rohan/t7`). Opaque strings; never strip the machine prefix; machine prefixes change on rename.
16. **Detach resizes.** Attaching sets the terminal to your size (you become the active viewer); when the last viewer leaves it returns to the headless 200×120. Opening the phone can disturb a desktop user — use `observe`/`resumed` until the user interacts.
17. **`terminal.send` / `keys` block ~3 s after human typing** and wait for quiet. Don't use short timeouts; don't auto-retry (duplicate prompts to an agent are harmful). `blocked` refusals need a user decision before `force`.
18. **Plain-mode subscribe hijacks the connection** (later requests get no reply). Only use framed mode in the app.
19. **`~` is not expanded** in `terminal.run` `cwd` (`"~"` ⇒ `spawn_failed: cannot start "sh"` [W 0.1.15]). Clients expand it with the host's `$HOME`; for `on: <machine>` omit `cwd` to get that machine's home.  Flocket works around it in `CoreSession.run` by expanding a leading `~` via `machine.exec` (`printf %s "$HOME"`, cached per core and machine).
   **`terminal.run` without `cwd` runs in the core's cwd** (where the core was started), not home. Always pass `cwd`; pass `TERM`/`COLORTERM` in `env`.
20. **Never call `server.stop`/`server.restart`** from the app; `hc connect` auto-starts a core if none runs (so "connect" may start one).
21. **Notifications without `id` get no reply** — always set `id` on requests.
22. **`pid` is not cleared on finish**; check `state`.
23. **Frame length > 16 MiB = corrupt stream.** Drop the connection; don't allocate.
24. **Don't let the reply queue grow:** a client that stops reading channel 0 for long (64 MiB of replies) is disconnected; a plain subscriber blocked > 30 s is dropped.
25. **Repaint can arrive mid-stream** (fell > 4 MiB behind, or remote line resumed). The emulator must accept it.
26. **Win32-input-mode:** first paint carries `ESC[?9001h/l`. Ignore 9001 in the mobile emulator (don't switch key encoding) unless you implement it end-to-end; `hc attach` strips it.
27. **Banner text on SSH stdout** breaks hello parsing; report "remote shell prints to stdout" when the first byte isn't `{`.
28. **Doc version can go backwards** if the host's `docs.json` was corrupt and reset; trust the fresh read after reconnect.
29. **Labels with empty values are deleted**, not stored; label keys can't contain `=`.
30. **FACTS steals size ownership in 0.1.15** (not in 0.1.16). Don't send FACTS from a passive/observing phone viewer on 0.1.15 unless you accept the resize.

---

## 13. 0.1.15 (installed) vs e07c2a1 / 0.1.16 (source)

| Area | 0.1.15 | 0.1.16 | Client impact |
|---|---|---|---|
| OpenRPC schema | — | identical except `info.version` [W: diff of `hc schema` 0.1.15 vs 0.1.16] | None. |
| First paint / repaint | Starts `ESC[!p`… | Prefixed by `0x18` (CAN) [W]; may append a "continuation" for a half-written sequence; a viewer that attached mid-sequence is "recovering" and gets a repaint once the program reaches a clean boundary | Emulator must tolerate CAN and mid-stream repaints (it must anyway). |
| FACTS | Replaces the whole `Display` and makes the viewer active (can take size ownership) | [W] Does not make the viewer active (size unchanged after FACTS from a passive viewer); merges only provided fields; applies only if you are the leader; re-answers color queries so programs see theme changes | Send full `Display` objects to work on both. |
| `terminal.run` no argv | Machine's own shell (macOS login shell) | `shell = [...]` in host config.toml overrides it | None. |
| Holder reconnection | — | Core reconnects to its holder after falling behind instead of freezing terminals | Fewer `holder_lost` losses. |
| Docs README | Says FACTS counts as activity | Same README, but 0.1.16 code no longer does | Trust code: §7.5. |
| CLOSE reason | README shows `{"reason","exit_code","signal"}` | Actual payload also includes `finished_ms` (both versions [W]/[S]) | Parse leniently. |
| `MachineView.state` | doc comment lists 4 states | code also emits `needs_update` | Handle unknown states. |

---

## 14. Notes on reference clients

- **zzulanas/herdr-ios (HerdrKit):** framing, early-frame buffering, orphan-reply CLOSE, per-frame CREDIT on both attach and event channels (+1 MiB boost on subscribe), unfiltered subscribe — all consistent with this spec. Its version policy (generation check + minimum version) is the right shape. Its default command (`herdr-core connect` with a `~/.local/bin` fallback) is a good PATH strategy. Its `herdr-app` layout parser is the best available reference for that (unofficial) document.
- **eliasstravik/shepherdr:** framing and reducer match this spec, but (a) exact `version === '0.1.14'` and `rpc.discover` `info.version` equality — replace with §2.2; (b) its comment "the server sends the reply before the first paint" holds only for local terminals — keep an early-frame buffer; (c) its strict `seq === prev+1` check is correct only because it subscribes unfiltered; (d) it bounds input frames at 256 KiB and pending calls at 256 — sensible limits to copy.

## Appendix A — constants

| Name | Value | Where |
|---|---|---|
| Generation | 1 | `hello` |
| Frame header | 9 bytes | wire |
| Max frame payload | 16 777 216 | wire |
| Stream initial credit | 262 144 | wire (attach + events) |
| Max stream backlog | 4 194 304 | wire (→ repaint / missed) |
| Max queued replies | 67 108 864 | wire (→ disconnect) |
| Core stream DATA chunk | ≤ 16 384 | wire |
| Heartbeat | 15 s | events (`HC_HEARTBEAT_MS` on the host) |
| Plain event queue | 1024 events | events |
| Plain writer stall limit | 30 s | server |
| Human-typing quiet before send/keys | 3 s (`HC_QUIET_MS`) | input |
| Interrupt watch default | 10 s | terminal.interrupt |
| Wait timeout default | 180 s | terminal.wait / send.wait |
| Remote list timeout | 3 s per machine | terminal.list |
| Headless size | 200×120 (`headless = "COLSxROWS"`) | config |
| Document max | 1 048 576 bytes | doc.set |
| Name rule | `[a-z0-9][a-z0-9._-]{0,31}`, not `t[1-9][0-9b-z…]*` | names, docs |
| Finished retention | 24 h, max 500 records | registry |
| Keep restart backoff | 1.5, 3, 6, 12, 30, 60 s | kept |
| Read history cap (finished) | 1000 lines | terminal.read |
