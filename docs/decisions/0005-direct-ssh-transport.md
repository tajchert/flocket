# ADR-0005: Direct SSH exec transport, with explicit host trust

Status: accepted (2026-11-01)

## Decision
The foreground connection path is: app → SSH exec channel (no PTY, via SSHJ) →
`<hc> connect` → core.

- SSH provides encryption, host trust and account authentication.
- There is no HTTP gateway and no relay.
- The host fingerprint is verified before any credential is sent, and the first contact
  needs explicit user confirmation.
- Device keys are Ed25519, wrapped by Android Keystore. Importing a key and password
  auth are also supported.

## Consequences
- The protocol module depends only on a `ByteTransport` interface.
- Push notifications for a suspended app, a relay and Tailscale are later, optional
  additions. None of them is required for foreground use.
