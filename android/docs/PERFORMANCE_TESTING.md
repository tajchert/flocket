# Performance testing and baseline profiles

The `:benchmark` module (a `com.android.test` module targeting `:app`) contains:

- `StartupBenchmark`: cold-start timing with and without the baseline profile.
- `BaselineProfileGenerator`: collects a startup profile by launching the app.

Both need a device or emulator and are never part of `scripts/check.sh`. Timing claims require a physical device.

## Commands

```bash
# Startup benchmark (use a physical device, release-like build)
./gradlew :benchmark:connectedNonMinifiedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.mtajchert.flocket.benchmark.StartupBenchmark

# Regenerate the baseline profile (writes app/src/main/generated/baselineProfiles)
./gradlew :app:generateBaselineProfile
```

The baseline profile is committed, and `automaticGenerationDuringBuild` is off, so normal builds never need a device. Update `PACKAGE_NAME` in `StartupBenchmark.kt` if the application ID changes. As screens appear, add profile journeys for the common paths (host list, terminal open) and keep measurements separate from profile collection.
