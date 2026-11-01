# ADR-0006: Short foreground service keeps an open terminal connected briefly in the background

Status: accepted (2026-11-01)

## Context
On Android 15 and later, the system blocks an app's network about 5 s after it leaves the
screen (`blocked=APP_BACKGROUND`), and it destroys open sockets. We observed this in the
first end-to-end journey. Without a service, a screen lock or a quick switch to another app
drops the SSH link, and the user waits 1–2 s for a reconnect on return.

## Decision
While at least one terminal screen is attached, leaving the app starts a
**`shortService` foreground service** (Android 14+, at most about 3 minutes, no Play
Store declaration). It shows a small "Flocket connected" notification and keeps the
sessions' SSH links alive.

- When the user returns within the window, the terminal resumes instantly, with no
  reconnect.
- When the window ends (the service's `onTimeout`, or the grace period), sessions detach
  cleanly. The service stops, and the next foreground reconnects passively with a fresh
  paint.
- With no terminal open (only the list), the app detaches when it is backgrounded, with
  no service.
- The service never keeps the app alive indefinitely. A persistent, always-on connection
  (for live "agent needs you" alerts) belongs to the notifications milestone and needs
  its own ADR.

## Consequences
- Settings → Connection → "Keep connected in background" (on by default) is an app-level
  opt-out: when off, leaving the app detaches at once, with no service or notification, and
  the notification permission is never requested.
- The notification permission (`POST_NOTIFICATIONS`, Android 13+) is requested
  contextually. Without it the service still runs; only the notification is hidden.
- The service starts while the app is still in the foreground state, when the terminal
  screen stops, so that background start restrictions don't apply.
- Behaviour is verified in `android/docs/journeys/E2E_SMOKE.md` (lock/unlock, app switch
  under 3 minutes and over 3 minutes).
