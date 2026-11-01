# ADR-0002: Apache-2.0 license

Status: accepted (2026-11-01)

## Decision
The repository is licensed under **Apache-2.0** (`/LICENSE`). Attribution for borrowed
or bundled code goes in `/NOTICE` and in `THIRD_PARTY_NOTICES.md` inside each platform
folder.

## Why
herdr-core and most of the reference implementations we adapt (HerdrKit, Herdroid,
luiscleto/shepherdr) are Apache-2.0. Others are MIT (eliasstravik/shepherdr, Ghostty),
which is compatible. Apache-2.0 includes a patent grant, and companies contributing to
the project are comfortable with it.

## Rules
- Code adapted from a reference must keep its copyright header, and the source must be
  added to the notices file.
- Never copy code from AGPL or GPL sources (for example 0cv, mohamed-essam, tuan3w).
  Reading them for ideas is fine.
- Bundled fonts and native libraries must have an entry in `THIRD_PARTY_NOTICES.md`.
