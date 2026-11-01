# Flocket

**Your herd of terminals, in your pocket.** Flocket is a native mobile client for
[herdr-core](https://github.com/herdrdev/herdr-core) (`hc`), which keeps programs and coding
agents alive in terminals on your machines. Flocket lets you see every terminal and what its
agent is doing, open a real terminal, type into it, and leave again without disturbing
anything on the host.

> Status: early development (milestone 1). Android comes first, then iOS.
> Flocket is an independent project and is not affiliated with herdr.

## Highlights

- **Native apps**: Kotlin and Jetpack Compose on Android; Swift and SwiftUI on iOS later.
- **Direct and private**: connects over SSH (`ssh host hc connect`), with no relay, cloud
  account or gateway. Host keys are verified before any credential is sent, and device keys
  are kept in the platform keystore.
- **Real terminals**: powered by [libghostty](https://github.com/ghostty-org/ghostty), with
  flow control, so a busy agent never freezes the app.
- **Every machine at once**: multiple hosts, plus herdr-core's federated machines
  (`rohan/t7`), each with its own connection state.
- **Built for every screen**: phones, foldables and tablets, with list-detail layouts and
  hardware-keyboard support.
- **Safe by design**: terminal input is sent at most once, never replayed after a dropped
  connection, and a "maybe sent" outcome is shown as unknown rather than hidden.

## Repository

| Path | Contents |
|---|---|
| [`android/`](android/) | Android app: [README](android/README.md) · [agent guide](android/AGENTS.md) |
| [`protocol/`](protocol/) | herdr-core [protocol spec](protocol/SPEC.md), schema snapshots and wire fixtures shared by every platform |
| [`docs/decisions/`](docs/decisions/) | Architecture Decision Records |
| [`docs/design/`](docs/design/) | Milestone design documents |
| `ios/` | Future iOS app |

## Getting started

You need a machine running herdr-core that you can reach over SSH. To build the Android
app, see [android/README.md](android/README.md).

## Contributing

Start with [AGENTS.md](AGENTS.md). It sets the shared rules for humans and AI agents alike.
Architecture-wide changes need an ADR in [`docs/decisions/`](docs/decisions/).

## License

[Apache-2.0](LICENSE). Third-party notices are in [NOTICE](NOTICE) and in each platform's
`THIRD_PARTY_NOTICES.md`.
