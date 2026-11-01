# Security and backup defaults

Flocket stores SSH credentials and pinned host keys, so the defaults are strict. This is the current configuration, not a full threat model; see the design doc's Security section for the model.

| Configuration | Behavior |
|---|---|
| App manifest | `allowBackup="false"`; one exported launcher activity; no deep links |
| Permissions | `INTERNET` and `ACCESS_NETWORK_STATE` only |
| Network security XML | Cleartext HTTP denied everywhere (Flocket speaks SSH over raw sockets, not HTTP) |
| Backup XML | Cloud backup and device transfer exclude `sharedpref`; databases and DataStore are excluded by `allowBackup="false"` |
| Device keys | Generated on device, encrypted with an AES-GCM key in Android Keystore (`:core:security`) |
| Host trust | Host key verified before any credential is sent; a changed key is a hard stop |

## Rules

- Never log secrets, key material or terminal bytes.
- Passwords are not stored by default.
- Keep exported components minimal; adding one needs a review of its intent handling.
- Real signing keys never live in the repository; see [Releasing](RELEASING.md).
- Review any new SDK's data collection before adding it, and record it in `THIRD_PARTY_NOTICES.md`.
