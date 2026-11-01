# Localization

Flocket currently ships English default resources only. Resource-backed strings make translation possible; they do not mean translations exist.

- Keep default strings in the module that owns the UI (`app`, `feature/*/impl`, `core/ui`, `core/designsystem`). Add `res/values-<language>/strings.xml` beside them.
- Resolve user-facing text from resources in Compose, never from exception messages. `core:ui` maps each `AppError` to a string resource and an action; screens do not format errors.
- Use positional format arguments (`%1$s`) instead of concatenating translated fragments.
- Format dates, times and numbers for the device locale.
- Terminal content is never translated or reformatted.
- When adding a language, check long text and font scale 1.5 on phone and tablet widths. Keep the English screenshot references deterministic and add locale cases separately.
