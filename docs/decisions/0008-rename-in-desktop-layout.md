# ADR-0008: Rename spaces and tabs in the desktop layout

Status: accepted (2026-11-01)

## Context
The desktop app (herdr-app) keeps its spaces, tabs and panes in the core document `herdr-app`
([SPEC §9.1](../../protocol/SPEC.md)). Each machine has its own copy, read and written with `on`.
Until now Flocket treated that document as read-only, so the names the user saw on the phone
could only be set on the desktop.

Users want to rename tabs and spaces from the phone and see the new name on the desktop too.
The desktop expects other writers: it follows `doc` events, and "on simultaneous edits the
newest accepted document wins (no merging)" (its built-in docs). Its own `tab.rename` and
`space.rename` set the `name` field, and renaming to `""` goes back to the automatic name.
`doc.set` is compare-and-set on the document version, locally and with `on` for a federated
machine (verified with hc 0.1.16).

The document has no published schema and belongs to the desktop. Damaging it, or simplifying
fields Flocket does not understand, would break the user's desktop window.

## Decision
Flocket may write `herdr-app` for exactly one kind of change: setting the `name` string of one
existing space or tab. Nothing else in the document is ever moved, added, removed or changed.
The write path is the one in [SPEC §9.1](../../protocol/SPEC.md); in short:

- Read the document, edit the raw JSON tree (not the parsed layout), find the space by `id`
  and the tab by `id` inside it, replace only its `name`, and keep every other field, unknown
  ones included. The new name is trimmed; an empty name is written as `""`.
- Write it back with `doc.set` and the version that was read. On `conflict`, read again and
  apply the same change by id, at most 3 attempts in total. A space or tab that is no longer
  there fails as gone, without a write.
- A `doc.set` cut off by a dropped link is never resent (product rule 3): the outcome is
  unknown and the document is re-read.
- A federated machine's space or tab is read and written with `on: <machine>` (product rule 5).
  No rename while the host is stale (product rule 7).
- The screen shows the new name from the normal `doc` event and refetch, not from the local edit.

On Android this is `CoreSession.renameInLayout(LayoutRef, name)` in `core:session`. Design:
[workspace rename, priority sort, desktop tab names, agent icons](../design/2026-11-01-workspace-rename-sort-icons.md).

## Consequences
- Names set on the phone and on the desktop are the same names, on both screens.
- Spaces and tabs that exist only in Flocket (from labels or start folders, and the implicit
  "all" tab) cannot be renamed: they are not in any desktop document.
- The document's meaning is kept, not its bytes: the core itself stores JSON with sorted keys
  and normalised numbers (`1.50` → `1.5`). Tests compare JSON values.
- If the desktop edits the same document in the same moment, the rename may fail after its
  retries, and the user tries again. It is never applied twice or to the wrong space or tab.
- Any other edit of the desktop layout (move, split, close, reorder, create) needs a new ADR.
