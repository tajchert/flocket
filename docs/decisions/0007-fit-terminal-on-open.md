# ADR-0007: Opening a terminal fits it to the phone

Status: accepted (2026-11-01)

## Context
herdr-core sizes a terminal to its most recently active viewer, and goes back to the
headless size (200×120 by default) when no viewer is attached ([SPEC §7.5](../../protocol/SPEC.md)).
The SPEC recommends that a phone attach passively and only take the size on the first
keystroke or an explicit "fit". That way a desktop user watching the same terminal is
never disturbed.

In practice, a terminal opened on the phone is usually not open anywhere else, so it sits at
the headless size. Shown passively, it is scaled down to the phone's width and looks like a
zoomed-out desktop. It fills the screen only after the user types something. The protocol
does not report how many viewers a terminal has, so the app cannot tell an idle terminal
from one someone is watching.

## Decision
Every terminal open is the **size owner**. The screen attaches with `AttachMode.Owner`
at the viewport's cols × rows. This applies to new terminals, existing ones, tab and pane
switches, and process-death restores.

- After a dropped link the session still re-attaches with `resumed:true`, which is passive.
  Once the link is back and the terminal is visible, the controller takes the size again.
- If another viewer takes the size while the screen is open (the desktop user types), the
  phone goes passive and shows "Fit to screen" until the user types or taps it.
- `AttachMode.Passive` and `Observe` remain available in `core:session` and `core:terminal`.

## Consequences
- Opening a terminal on the phone resizes it for a desktop viewer too. The desktop snaps
  back as soon as its user types there.
- `TerminalRoute.startedHere` is removed, because every open behaves the same way.
