# Android agent setup

Use the shortest feedback loop that answers the question: compile and test the changed module, inspect a rendered UI, then exercise the installed app. The Gradle wrapper is the canonical build entry point; Android CLI is optional workstation tooling that CI does not need.

Requirements: JDK 21, Android SDK 37 with build tools. `local.properties` (not committed) points at the SDK.

## Android CLI (optional)

Install it from the [official overview](https://developer.android.com/tools/agents/android-cli), then:

```bash
android --version
android init          # installs the official android-cli skill for detected agents
android docs search "<Android API question>"
```

Read the installed skill before device work and check `android <command> --help`, because flags change between versions.

### Render Compose previews without a device

```bash
android preview list ':app@debug' --all --format=json
android preview render ':app@debug' --preview=<fully.qualified.PreviewFunction> --output=build/agent-feedback/preview.png
```

Previews are for inspection. The screenshot test suite (see [Screenshot testing](SCREENSHOT_TESTING.md)) remains the reproducible visual regression gate.

### Install, observe, interact

```bash
./gradlew :app:installDebug      # applicationId com.mtajchert.flocket.debug
android run --help
android layout --help
```

Use an emulator only when the task needs one, save screenshots and assertions under `build/agent-feedback/`, and use the debug demo host or an isolated `hc` session (`flocket-test-<random>`) for terminals; never the developer's real herdr session. Timing and memory claims require a physical device.

Report blocked tools explicitly instead of silently skipping a check.
