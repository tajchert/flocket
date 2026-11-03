# ADR-0010: Desktop mods: share data, rebuild features natively

Status: accepted (2026-11-03)

## Context
Most of the desktop app's (herdr-app) interface is built from **mods**: the spaces sidebar,
the Agents list, the tab bar, menus, dialogs, toasts, the Files and Changes panels. A mod is
a JavaScript program in the desktop's `~/.config/herdr-app/mods/` folder. Herdr's own fork
of gpui-kit draws the GPUI elements it describes. Mods use about 50 app functions
(`herdr.d.ts`). Many of them belong to a desktop window: `window()`, `placement()`, overlays,
`keys()`, `layouts()`, `pickFolder`. Mods can also run programs (`exec`), read and write files
and use the network. Each Herdr version rewrites the type files, so the API follows the
desktop's releases.

Users expect Flocket to match the desktop: an agent pinned on the desktop is pinned on the
phone, and the Agents list orders and groups agents the same way. Running the desktop's mods
on a phone would take an embedded JS engine, a Compose renderer for gpui-kit's element and
style model, phone meanings for desktop-only calls, and a trust model for code that arrives
from another machine and can run programs. That runtime would have to chase every Herdr
release. Most shipped mods, and their window layout, have no place on a phone anyway.

Clients already share state without sharing code. herdr-core carries terminal labels and
documents to every client, local and federated ([SPEC §6.4](../../protocol/SPEC.md), §9).
Herdr's mod docs describe that state as client-neutral: "pin it on one and it's pinned
everywhere" (herdr-app `docs/labels.md`).

## Decision
Flocket does not run, load, download or interpret desktop mods. There is no JavaScript
runtime in the app, and no plugin or user-customisation mechanism either.

- **Share data, not code.** State that should be the same on every client travels only
  through herdr-core: labels (`herdr.seen`, `herdr.pinned`, `herdr.space`, `herdr.tab`) and
  documents (`herdr-app`). Flocket reads and writes it with the same meaning the desktop
  gives it, and nothing more.
- **Rebuild features natively.** When Flocket needs a feature a desktop mod provides, it is
  reimplemented in each platform's own code (Kotlin and Compose, Swift and SwiftUI;
  [ADR-0004](0004-native-per-platform-shared-fixtures.md)). The mod's source (herdr-app
  `defaults/mods/<name>/main.js`) is the reference for behaviour: ordering, grouping, status
  rules, what each action changes. The phone keeps its own layout and interactions.
  Code adapted from herdr-app (Apache-2.0) keeps its header and is listed in the notices
  ([ADR-0002](0002-license.md)).
- **The wire contract comes first.** A label or document a rebuilt feature reads or writes is
  documented in `protocol/SPEC.md` (with a fixture where the shape matters) before either
  platform uses it.
- **Writes follow the product rules.** They are capability-gated (for example
  `terminal.label`), at most once (rule 3), keep machine prefixes (rule 5) and are disabled
  while the host is stale (rule 7). Flocket writes the core state directly, never through a
  mod command: `terminal.pin` is a desktop app command, and Flocket writes `herdr.pinned`
  itself. Changing the `herdr-app` document beyond renames still needs its own ADR
  ([ADR-0008](0008-rename-in-desktop-layout.md)).
- **Desktop-only state stays on the desktop.** Things a mod keeps in the desktop's app home
  (`shared.json`, `data/`, `view.json`, focus, zoom, settled tabs) are not visible to the
  core. Flocket does not reach into them over SSH. If such a feature should be shared, it
  first needs to move into the core upstream.

## Consequences
- Pinning and "seen" are the model already in place: Flocket follows the desktop's labels,
  and its Agents list mirrors the desktop's `agents` mod.
- A user's own desktop mods and layouts don't appear on the phone. Only the state they keep
  in herdr-core is shared.
- Parity is ongoing work. A new or changed desktop mod feature only reaches the phone when
  someone rebuilds it on each platform. Each one is tracked as a normal feature, against the
  mod version it follows.
- If a desktop feature keeps its state only on the desktop, Flocket can't match it until
  that state moves into the core.
- No mod code runs on the phone, so there is no runtime to update with Herdr, no trust model
  for remote code, and no way around the product rules.
