# Workspace: rename, priority sort, desktop tab names, agent icons

Status: draft for review, 2026-11-01. Platform: Android.

## Why

The terminal list on the phone should read like the desktop app (herdr-app), so a glance on
either screen tells you the same thing:

- what needs you first (the desktop's **Priority** order),
- which agent runs where (the agent's **icon**),
- and spaces and tabs called **by the same names**, which you can change from the phone and see
  change on the desktop.

Success: the user opens Flocket and, without reading the status pills, sees the agent that needs
them at the top, recognises each agent by its icon, and the space/tab names match the desktop
window, including names they set on the phone.

## Goals

1. **Rename tabs and spaces** from the phone. The new name is written to the desktop's layout, so
   the desktop window shows it too.
2. **Sort** the terminal list by **Priority** by default (like the desktop's Agents panel), with
   **Grouped by space** as the second option.
3. **Unnamed tabs** show `1`, `2`… like the desktop (today: `Tab 1`, `Tab 2`).
4. **Agent icons**: each terminal running a known agent shows that agent's icon, in the list and
   in the terminal screen's tab strip.

## Non-goals

- Any other structural edit of the desktop layout (move, split, close, reorder, create spaces or
  tabs).
- Auto-generated descriptive tab names (the user chose desktop-style numbers; rename covers the
  rest).
- Renaming tabs or spaces that exist only in Flocket (see "Which spaces and tabs can be renamed").
- Sort modes beyond the two named here.

## Anti-goals (would make this a failure even if it "works")

- Flocket damages or simplifies the desktop's layout: a rename must change exactly one `name`
  string and keep the meaning of everything else in the document, including fields Flocket does
  not understand. (Bytes cannot be kept anyway: the core itself stores JSON with sorted keys and
  normalised numbers, e.g. `1.50` → `1.5`, verified with hc 0.1.16. Large integers survive.)
- A rename silently lost, or applied twice, or applied to the wrong space/tab.
- A wrong logo on a terminal. When unsure, show the plain terminal tile.
- Sorting that makes rows jump while the user is reading. Order changes only when a status
  changes.

## Background (verified 2026-11-01)

- The desktop stores spaces/tabs/panes in the core document `herdr-app` (SPEC §9.1). Its built-in
  docs (Herdr.app 0.1.11): *"Connected screens share that layout and live terminals … On
  simultaneous edits the newest accepted document wins (no merging)."* The desktop expects other
  writers and follows `doc` events. Its own rename commands are `tab.rename {id}` and
  `space.rename {id}`; *"renaming to "" goes back"* to the automatic name.
- Desktop naming: an unnamed space is called after where its first terminal is now (already done,
  commit `2329a3f`); an unnamed tab *"is called by its place (1, 2, …)"*.
- Desktop Agents panel sorts: `priority` (default) and `grouped` ("Grouped by space"). Priority
  rank: blocked 4 > done 3 > working 2 > idle 1, `error` counts as blocked. Sections:
  **Needs you**, **Done**, **Working**, **Idle**. In priority mode a row names its space, since
  there are no space headers.
- Desktop agent catalog (inside Herdr.app, Apache-2.0): ~25 entries of the shape
  `"claude": {"name":"Claude Code","icon":"icons/claude.svg","run":["claude"],"match":["claude-code"],"tint":false,"tile":"#1c1d21","color":"#ffffff"}`.
  The icons are **LobeHub icons** (https://github.com/lobehub/lobe-icons, MIT). Both licences fit
  ADR-0002.
- [W] hc 0.1.16, two isolated sessions, machine added with `--via "sh -c"`: `doc.set` with
  `on: "rohan"` writes that machine's own `herdr-app` (read back directly on its session), and a
  stale `version` there returns `conflict` like a local write. So renaming spaces/tabs of a
  federated machine works the same way as local ones.
- Flocket today: `protocol/SPEC.md` §9.1 says Flocket treats `herdr-app` as **read-only**.
  `core:protocol` already has `CoreConnection.docGet` / `docSet`
  (`android/core/protocol/src/main/kotlin/com/mtajchert/flocket/core/protocol/CoreCalls.kt`).
  The list already has an "Attention first" toggle (default on) that sorts spaces/rows by
  `AgentState.urgency` (blocked/error > working > done = idle), held in memory only
  (`feature/terminals/impl/.../TerminalsLogic.kt`, `TerminalsViewModel.kt`).

## Design

### 1. Rename (tab and space), written to the desktop layout

**Decided by the user:** rename edits the desktop's layout (both screens show the new name).
Spaces are renamable as well as tabs.

**Rule change.** `herdr-app` stops being read-only for Flocket, narrowly: Flocket may change the
`name` of one existing space or tab. Record this in a new ADR
(`docs/decisions/0008-rename-in-desktop-layout.md`) and update SPEC §9.1 first (working rule:
protocol changes start in `protocol/`).

**Write path** (in `core:session`, exposed on `CoreSession` as `AppResult`):

1. `doc.get {name:"herdr-app"}` (with `on: machine` for a federated machine's space).
2. Work on the raw JSON tree (`JsonObject`), not the flattened `DesktopLayout`. Find the space by
   `id`; for a tab, the tab by `id` inside that space. Replace only its `name` string. Keep every
   other field, unknown ones included (their meaning, not their bytes; see Anti-goals).
3. `doc.set {name, value, version}` with the version from step 1.
4. `conflict` → go back to step 1 and apply the same change to the fresh document. Bounded
   (e.g. 3 tries), then fail with a clear error. This is not a replay: a conflicted write was not
   applied.
5. Space or tab id no longer in the document → fail as "gone" (the desktop removed it); no write.
6. Connection drops after sending `doc.set` and before the reply → **unknown outcome** (product
   rule 3): do not resend; re-read the document when connected and show what it now says.
7. The UI updates from the normal `doc` event / refetch path, not from the local edit.

**Errors** (snackbar, the UI keeps showing the current name):
- conflict still after the retries: "The desktop changed this layout at the same time. Try again."
- gone: "This tab (space) was closed on the desktop."
- `unavailable`, `needs_update`, `machine_unknown` or disconnected: "Can't rename now: <host or
  machine> isn't reachable." (and the action is disabled while stale, as above).
- unknown outcome: re-read automatically on reconnect, then: "Connection dropped while renaming;
  showing the name the desktop has now." Nothing is kept across process death: the next connect
  re-reads the document anyway.

A name is trimmed. Saving an empty name sets `""`, which returns that space/tab to its automatic
name, like the desktop.

**Model change.** `WorkspaceSpace` and `WorkspaceTab` (`core:model/Workspace.kt`) do not say
today whether they exist in the document: tabs have only a key, and label tabs and the implicit
"all" tab look like document tabs. Add the document id (null when the space/tab is not in a
document) to both, set by `buildWorkspace` (`WorkspaceGrouping.kt`). A label tab whose
`herdr.tab` id matches a document tab already merges into that tab (shared `byId`), so it is the
document tab. Federated spaces carry `machine`, which selects `on`.

**Which spaces and tabs can be renamed:** only those that exist in a desktop layout (local or a
federated machine's):
`SpaceSource.Desktop` spaces and their tabs that come from the document. Spaces from labels only
or start folders (`SpaceSource.Labels`, `SpaceSource.Folder`) and the trailing implicit "all"
tab do not exist on the desktop, so they show no rename action. Rename is also unavailable while
the host is stale or disconnected (product rule 7), or while that machine's layout is unavailable.

**Where in the UI:**
- Terminal list, Grouped by space: long-press the space header or a tab label → rename dialog.
- Terminal list, Priority: the row's overflow menu has "Rename tab…" and "Rename space…".
- Terminal screen tab strip: long-press a tab → rename dialog.
- Dialog: text field prefilled with the current given name (empty if unnamed, with the automatic
  name as placeholder), Save / Cancel. Errors (conflict exhausted, gone, unknown outcome) show as a
  snackbar with the reason.

### 2. Sort: Priority (default) and Grouped by space

**Decided by the user:** Priority is the default, like the desktop (mockup option A).

Replace the "Attention first" toggle with a sort choice of two modes:

- **Priority** (default). Within each host (and each federated machine, as today), rows are in
  sections, in this order: **Needs you** (blocked, error), **Done**, **Working**, **Idle**. No
  space or tab headers; each row shows its place as `space · tab` (e.g. `flocket · 2`). Empty
  sections are hidden.
- **Grouped by space**: today's machine → space → tab view, with spaces, tabs and rows ordered by
  the desktop rank (blocked/error > done > working > idle > none), stable otherwise.

Change the rank to match the desktop: **done ranks above working** (a finished turn is news).
Keep `AgentState.urgency`'s meaning for `hc`'s picker if other code relies on it; add a separate
desktop rank if needed.

### 3. Unnamed tabs show their number

`workspace_tab_fallback` becomes `%1$d` in both
`feature/terminals/impl/src/main/res/values/strings.xml` and
`feature/terminal/impl/src/main/res/values/strings.xml` (and any translations). The tab strip and
list then read `1`, `2`, … like the desktop. `Space %1$d` stays.

### 4. Agent icons

- **Catalog**: a pure Kotlin catalog (`core:model`) adapted from the desktop's agent catalog:
  id, display name, `run` (program names), `match` (self-reported app names), icon id, tint flag
  and colours. Apache-2.0 attribution in `android/THIRD_PARTY_NOTICES.md`.
- **Icons**: the matching LobeHub icons from upstream (pinned version), converted to Android
  vector drawables, plus a generic terminal icon. MIT notice in `android/THIRD_PARTY_NOTICES.md`.
- **Matching**: the terminal's reported app (`status.app`) against each entry's `match` and `id`;
  else the basename of `argv[0]` against `run`. Do not use the foreground process name for
  matching: Claude Code's process name is its version (seen: `2.1.316`). No match → the generic
  terminal tile. A wrong logo is worse than no logo. Both inputs already exist
  (`AgentStatus.Reported.app`, `TerminalRecord.argv` in `core:model/Terminal.kt`), so no protocol
  work is needed.
- **Look**: a small rounded tile in the catalog's tile colour (`#1c1d21`) with the icon; `tint`
  icons are drawn in the catalog colour, others keep their own colours. The tile goes at the
  start of each list row and on each tab in the terminal screen's tab strip (a tab with several
  panes shows the icon of its active pane).

## Builder's calls (decide in the plan, keep consistent)

- Exact JSON handling that keeps unknown fields (kotlinx `JsonObject` copy-on-write is enough).
- Retry count for conflicts.
- Where the sort choice is persisted (app-level preference in `core:datastore` is the default
  assumption; per-host override not needed). No migration: "Attention first" was never persisted.
- Order of rows inside a Priority section: **core order** (stable, so rows only move when their
  own status changes section). Decided, not left open.
- Where finished terminals go in Priority when "show finished" is on (suggested: a last
  **Finished** section), and terminals with no status (suggested: a last **No status** section,
  before Finished).
- Screenshot test updates: new references for the changed list, tab strip and dialog.

## Verification

- Unit tests: rename JSON edit, round-tripped over `protocol/fixtures/herdr-app-layout.json`
  (unknown fields kept, only one name changed, missing id → gone,
  conflict → re-read and re-apply, empty name → `""`), priority sectioning and rank, icon
  matching (reported app, argv fallback, unknown → generic, version-named foreground ignored).
- Real `hc` test on an isolated session (`flocket-test-*`): `doc.set` a synthetic `herdr-app`
  document, rename through `CoreSession`, read back; conflict path with a concurrent `doc.set`.
- Manual check by the user on their real desktop window: rename a tab and a space from the phone,
  watch the desktop update; rename on the desktop, watch the phone update.
